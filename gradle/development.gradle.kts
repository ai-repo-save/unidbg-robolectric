import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.jvm.application.tasks.CreateStartScripts

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun directoryIdentity(directory: File): List<Map<String, Any>> = directory.walkTopDown().filter { it.isFile }
    .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.map { file -> mapOf(
        "path" to file.relativeTo(directory).invariantSeparatorsPath, "sha256" to sha256(file), "bytes" to file.length()
    ) }.toList()

fun requireDirectoryIdentity(directory: File, expected: List<Map<*, *>>) {
    check(directory.isDirectory) { "Core directory is missing: $directory" }
    fun indexed(entries: List<Map<*, *>>) = entries.associate {
        it["path"] to Pair(it["sha256"], (it["bytes"] as Number).toLong())
    }
    check(indexed(directoryIdentity(directory)) == indexed(expected)) { "Core directory identity mismatch: $directory" }
}

val identityProperty = providers.gradleProperty("developmentBuildIdentity")
if (identityProperty.isPresent) {
    val identityFile = file(identityProperty.get()).canonicalFile
    val identity = JsonSlurper().parse(identityFile) as Map<*, *>
    check(identity["schema_version"] == 1 && identity["kind"] == "build" && identity["published"] == false) {
        "Development mode requires an unpublished schema-1 build identity"
    }
    val inputHash = sha256(identityFile)
    fun runtimeSourceSnapshot(): Map<String, String> = providers.exec {
        commandLine("git", "ls-files", "--cached", "--others", "--exclude-standard")
    }.standardOutput.asText.get().lineSequence().filter { it.isNotBlank() && rootProject.file(it).isFile }
        .associateWith { sha256(rootProject.file(it)) }
    val runtimeSource = runtimeSourceSnapshot()
    val runtimeCommit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
    val runtimeDirty = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().isNotBlank()
    val developmentRoot = layout.projectDirectory.dir("build/development/${inputHash.take(16)}")
    allprojects { layout.buildDirectory.set(if (this == rootProject) developmentRoot else developmentRoot.dir(name)) }
    val coreNames = setOf("unidbg-api", "unidbg-android", "unidbg-unicorn2")
    val components = identity["components"] as List<Map<*, *>>
    check(components.map { it["artifact_id"] }.toSet() == coreNames && components.size == 3) { "Exactly three core components are required" }
    check(components.all { it["group_id"] == "io.github.ai-repo-save.unidbg" }) { "Unexpected core group" }
    val corePaths = components.map { file(it["path"] as String).canonicalFile }
    val inputPaths = (identity["classpath"] as List<String>).map { file(it).canonicalFile }
    check(inputPaths.take(3) == corePaths) { "Core components must lead the declared classpath" }
    val pathIdentities = identity["classpath_identity"] as List<Map<*, *>>
    check(pathIdentities.map { file(it["path"] as String).canonicalFile } == inputPaths) { "Classpath identities must match their declared order" }
    val consumerIdentity = identity["consumer_classpath_file"] as Map<*, *>
    val consumerFile = file(consumerIdentity["path"] as String).canonicalFile
    check(sha256(consumerFile) == consumerIdentity["sha256"]) { "Producer consumer classpath identity mismatch" }
    val producerConsumer = consumerFile.readText().trim().split(File.pathSeparator).map { file(it).canonicalFile }.toSet()
    check(inputPaths.containsAll(producerConsumer)) { "Producer consumer classpath is outside the identity" }

    data class DeclaredDependency(val file: File, val coordinate: String, val repository: File, val pom: File)
    fun declaredDependency(jar: File): DeclaredDependency {
        val pom = jar.resolveSibling(jar.nameWithoutExtension + ".pom")
        check(pom.isFile) { "Dependency requires its formal adjacent Maven POM: $jar" }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
        fun text(element: org.w3c.dom.Node, name: String): String? {
            val nodes = element.childNodes
            return (0 until nodes.length).map { nodes.item(it) }.firstOrNull { it.nodeName == name }?.textContent?.trim()
        }
        val project = document.documentElement
        val parent = (0 until project.childNodes.length).map { project.childNodes.item(it) }.firstOrNull { it.nodeName == "parent" }
        val group = text(project, "groupId") ?: parent?.let { text(it, "groupId") }
        val artifact = text(project, "artifactId")!!
        val version = text(project, "version") ?: parent?.let { text(it, "version") }
        check(group != null && version != null && !group.contains('$') && !version.contains('$')) { "POM requires concrete coordinates: $pom" }
        val suffix = group.replace('.', '/') + "/$artifact/$version/$artifact-$version.jar"
        check(jar.path.replace(File.separatorChar, '/').endsWith("/$suffix")) { "JAR is not in the POM-declared Maven layout: $jar" }
        val repository = File(jar.path.dropLast(suffix.length)).canonicalFile
        return DeclaredDependency(jar, "$group:$artifact:$version", repository, pom)
    }
    val declared = inputPaths.drop(3).filter { it !in producerConsumer }.map(::declaredDependency)
    val parserExclusion = "net.dongliu:apk-parser:2.6.10"
    val selected = declared.filter { it.coordinate != parserExclusion }
    val excluded = declared.filter { it.coordinate == parserExclusion }
    check(excluded.size == 1) { "Expected the explicitly declared original APK parser dependency" }

    val validate = tasks.register("validateDevelopmentInput") {
        inputs.file(identityFile)
        inputs.file(consumerFile)
        inputs.files(inputPaths)
        doLast {
            check(sha256(identityFile) == inputHash) { "Build identity changed during configuration" }
            check(runtimeSourceSnapshot() == runtimeSource) { "Runtime source changed before compilation" }
            check(sha256(consumerFile) == consumerIdentity["sha256"]) { "Producer consumer classpath changed" }
            for ((path, expected) in inputPaths.zip(pathIdentities)) {
                when (expected["kind"]) {
                    "file" -> check(path.isFile && sha256(path) == expected["sha256"]) { "Build dependency identity mismatch: $path" }
                    "directory" -> requireDirectoryIdentity(path, expected["files"] as List<Map<*, *>>)
                    else -> error("Unsupported path identity kind: ${expected["kind"]}")
                }
            }
            logger.lifecycle("Validated unpublished core identity {}", inputHash)
        }
    }
    allprojects {
        configurations.configureEach { coreNames.forEach { exclude(mapOf("group" to "io.github.ai-repo-save.unidbg", "module" to it)) } }
        for ((repository, entries) in selected.groupBy { it.repository }) repositories.maven {
            url = repository.toURI()
            content { entries.forEach { val gav = it.coordinate.split(':'); includeVersion(gav[0], gav[1], gav[2]) } }
        }
        extensions.getByType<SourceSetContainer>().configureEach {
            compileClasspath += files(corePaths)
            runtimeClasspath += files(corePaths)
        }
        tasks.withType<JavaCompile>().configureEach { dependsOn(validate) }
    }
    gradle.taskGraph.whenReady {
        val forbidden = allTasks.filter { it.name.startsWith("publish") || it.name in setOf("releaseLocal", "installDist", "distZip", "distTar", "prepareRuntime", "jniIntegration", "apkStartupIntegration") }
        check(forbidden.isEmpty()) { "Development mode cannot run release/distribution tasks: ${forbidden.map { it.path }}" }
    }
    selected.forEach { project(":spi").dependencies.add("api", it.coordinate) }

    gradle.projectsEvaluated {
        val worker = project(":worker")
        val workerJar = worker.tasks.named<Jar>("jar")
        val runtime = worker.configurations.getByName("runtimeClasspath")
        val sdk = worker.configurations.getByName("androidSdk")
        val jvmArguments = worker.extensions.getByType<JavaApplication>().applicationDefaultJvmArgs.toList()
        val installation = developmentRoot.dir("install/unidbg-worker")
        fun runtimeInputs(): List<File> = (listOf(workerJar.get().archiveFile.get().asFile) + corePaths + runtime.files).distinct()
        fun relativePaths(): List<String> = runtimeInputs().mapIndexed { index, path ->
            if (index == 0) path.name
            else if (path in corePaths) "classes/${components[corePaths.indexOf(path)]["artifact_id"]}/"
            else "dependencies/${index.toString().padStart(4, '0')}-${path.name}"
        }
        workerJar.configure {
            dependsOn(validate)
            inputs.files(runtime)
            doFirst { manifest.attributes["Class-Path"] = relativePaths().drop(1).joinToString(" ") }
        }
        val scripts = tasks.register<CreateStartScripts>("developmentStartScripts") {
            dependsOn(workerJar)
            mainClass.set("io.github.aireposave.unidbg.runtime.worker.WorkerMain")
            applicationName = "unidbg-worker"
            classpath = files(workerJar.flatMap { it.archiveFile })
            defaultJvmOpts = jvmArguments
            outputDir = developmentRoot.dir("scripts").asFile
        }
        val install = tasks.register("developmentInstall") {
            group = "distribution"
            description = "Install an isolated unpublished worker from an explicit bottom-layer build identity."
            dependsOn(validate, workerJar, scripts, ":spi:jar", ":client:jar")
            inputs.files(runtime, sdk)
            inputs.property("runtimeSourceFiles", runtimeSource)
            inputs.property("runtimeSourceCommit", runtimeCommit)
            inputs.property("runtimeSourceDirty", runtimeDirty)
            outputs.dir(installation)
            doLast {
                check(runtimeSourceSnapshot() == runtimeSource) { "Runtime source changed during compilation" }
                val root = installation.asFile
                delete(root)
                root.mkdirs()
                val paths = relativePaths()
                for ((source, relative) in runtimeInputs().zip(paths)) {
                    val destination = root.resolve("lib/$relative")
                    if (source.isDirectory) copy { from(source); into(destination) }
                    else { destination.parentFile.mkdirs(); source.copyTo(destination) }
                }
                copy { from(scripts.get().outputDir); into(root.resolve("bin")) }
                root.resolve("bin/unidbg-worker").setExecutable(true)
                copy { from(sdk); into(root.resolve("sdk")) }
                copy { from(rootProject.file("LICENSE")); into(root) }
                root.resolve("jvm-args.txt").writeText(jvmArguments.joinToString("\n", postfix = "\n"))
                val resolved = runtime.incoming.artifacts.artifacts.mapNotNull {
                    val id = it.id.componentIdentifier as? ModuleComponentIdentifier
                    id?.let { id -> mapOf("coordinate" to "${id.group}:${id.module}:${id.version}", "source" to it.file.path, "sha256" to sha256(it.file)) }
                }
                val selections = selected.map { declared ->
                    val actual = resolved.filter { (it["coordinate"] as String).substringBeforeLast(':') == declared.coordinate.substringBeforeLast(':') }
                    check(actual.isNotEmpty()) { "Declared bottom dependency is absent from the runtime: ${declared.coordinate}" }
                    mapOf(
                        "declaredCoordinate" to declared.coordinate, "declaredSource" to declared.file.path,
                        "declaredSha256" to sha256(declared.file), "selected" to actual,
                        "sameCoordinate" to actual.all { it["coordinate"] == declared.coordinate },
                        "sameBytes" to actual.all { it["sha256"] == sha256(declared.file) }
                    )
                }
                val jars = runtimeInputs().filter { it.isFile }
                val slf4jProviders = jars.flatMap { jar -> ZipFile(jar).use { zip ->
                    zip.getEntry("META-INF/services/org.slf4j.spi.SLF4JServiceProvider")?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().use { reader -> reader.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() } }
                    } ?: emptyList()
                } }
                check(slf4jProviders == listOf("ch.qos.logback.classic.spi.LogbackServiceProvider")) { "Expected the unique runtime Logback provider: $slf4jProviders" }
                val parserSources = jars.filter { jar -> ZipFile(jar).use { it.getEntry("net/dongliu/apk/parser/ApkFile.class") != null } }
                check(parserSources.size == 1 && resolved.any { it["coordinate"] == "io.github.ai-repo-save.apk-parser:apk-parser:2.6.11" && it["source"] == parserSources.single().path }) { "APK parser must have one fork-defined class source" }
                val binaries = runtimeInputs().zip(paths).map { (source, relative) ->
                    val coreIndex = corePaths.indexOf(source)
                    val files = if (source.isDirectory) pathIdentities[coreIndex]["files"] as List<Map<*, *>> else null
                    if (files != null) requireDirectoryIdentity(root.resolve("lib/$relative"), files)
                    mapOf(
                        "source" to source.path, "installed" to "lib/$relative", "kind" to if (source.isDirectory) "directory" else "file",
                        "sha256" to if (source.isFile) sha256(source) else null, "files" to files,
                        "component" to coreIndex.takeIf { it >= 0 }?.let { components[it]["artifact_id"] }
                    )
                }
                val spiJar = project(":spi").tasks.named<Jar>("jar").get().archiveFile.get().asFile
                val spiInputs = listOf(spiJar) + corePaths + project(":spi").configurations.getByName("runtimeClasspath").files
                val spiClasspath = spiInputs.distinct().map { source ->
                    binaries.single { it["source"] == source.path }
                }
                val manifest = mapOf(
                    "version" to version.toString(), "sourceDeclaredVersion" to version.toString(), "development" to true, "published" to false,
                    "sourceCommit" to runtimeCommit, "sourceDirty" to runtimeDirty,
                    "runtimeSourceFiles" to runtimeSource,
                    "bottomLayerIdentity" to identity, "bottomLayerIdentitySha256" to inputHash,
                    "producerConsumerExclusions" to producerConsumer.map { it.path },
                    "dependencyExclusions" to excluded.map { mapOf("coordinate" to it.coordinate, "source" to it.file.path, "reason" to "Runtime SPI declares fork apk-parser 2.6.11") },
                    "declaredBottomDependencies" to selected.map { mapOf("coordinate" to it.coordinate, "source" to it.file.path, "pom" to it.pom.path, "sha256" to sha256(it.file)) },
                    "resolvedDependencies" to resolved, "bottomDependencySelections" to selections,
                    "binaries" to binaries, "spiClasspath" to spiClasspath,
                    "orderedClasspath" to paths.map { "lib/$it" },
                    "jvmClasspath" to listOf("lib/${workerJar.get().archiveFile.get().asFile.name}"), "slf4jProviders" to slf4jProviders,
                    "apkParserClassSource" to "lib/${paths[runtimeInputs().indexOf(parserSources.single())]}",
                    "apkParserResolvedSource" to parserSources.single().path,
                    "sdk" to mapOf("coordinate" to "org.robolectric:android-all-instrumented:11-robolectric-6757853-i7", "file" to "sdk/${sdk.singleFile.name}")
                )
                root.resolve("runtime-manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
                logger.lifecycle("Installed unpublished development runtime at {}", root)
            }
        }
        tasks.register<Test>("developmentJniIntegration") {
            group = "verification"
            description = "Explicit single lifecycle/JNI integration against the unpublished development worker."
            dependsOn(install)
            val source = project.extensions.getByType<SourceSetContainer>().getByName("integration")
            testClassesDirs = source.output.classesDirs
            classpath = source.runtimeClasspath
            useJUnit()
            filter { includeTestsMatching("org.example.unidbg.RuntimeIntegrationTest.independentWorkersAndNativeSideEffects") }
            outputs.upToDateWhen { false }
            val run = java.time.Instant.now().toString().replace(':', '-')
            reports.junitXml.outputLocation.set(developmentRoot.dir("evidence/$run/xml"))
            reports.html.outputLocation.set(developmentRoot.dir("evidence/$run/html"))
            systemProperty("integration.runId", run)
            systemProperty("integration.runtime", installation.asFile.absolutePath)
            systemProperty("integration.evidenceDir", developmentRoot.dir("evidence/$run/artifacts").asFile.absolutePath)
            jvmArgs(jvmArguments)
            doFirst { systemProperty("integration.fixtureClasspath", source.output.asPath) }
            testLogging { events("passed", "failed"); showExceptions = true; showStackTraces = true }
        }
    }
}

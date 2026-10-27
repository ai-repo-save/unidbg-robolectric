import javax.xml.parsers.DocumentBuilderFactory

val coreClasspath = providers.gradleProperty("developmentCoreClasspath")
if (coreClasspath.isPresent) {
    fun readClasspath(path: String) = file(path).readText().trim().split(File.pathSeparator).map { file(it) }
    val inputPaths = readClasspath(coreClasspath.get())
    val corePaths = inputPaths.filter { it.isDirectory }
    val coreNames = setOf("unidbg-api", "unidbg-android", "unidbg-unicorn2")
    val consumerPaths = providers.gradleProperty("developmentConsumerClasspath").orNull
        ?.let(::readClasspath)?.toSet() ?: emptySet()

    data class DeclaredDependency(val coordinate: String, val repository: File)
    fun declaredDependency(jar: File): DeclaredDependency {
        val pom = jar.resolveSibling(jar.nameWithoutExtension + ".pom")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
        fun text(element: org.w3c.dom.Node, name: String): String? {
            val nodes = element.childNodes
            return (0 until nodes.length).map { nodes.item(it) }
                .firstOrNull { it.nodeName == name }?.textContent?.trim()
        }
        val project = document.documentElement
        val parent = (0 until project.childNodes.length).map { project.childNodes.item(it) }
            .firstOrNull { it.nodeName == "parent" }
        val group = text(project, "groupId") ?: text(parent!!, "groupId")!!
        val artifact = text(project, "artifactId")!!
        val version = text(project, "version") ?: text(parent!!, "version")!!
        val suffix = group.replace('.', '/') + "/$artifact/$version/$artifact-$version.jar"
        return DeclaredDependency("$group:$artifact:$version", File(jar.path.dropLast(suffix.length)))
    }
    val declared = inputPaths.filter { it !in corePaths && it !in consumerPaths }
        .map(::declaredDependency).filter { it.coordinate != "net.dongliu:apk-parser:2.6.10" }

    allprojects {
        layout.buildDirectory.set(layout.projectDirectory.dir("build/development"))
        configurations.configureEach {
            coreNames.forEach { exclude(mapOf("group" to "io.github.ai-repo-save.unidbg", "module" to it)) }
        }
        for ((repository, entries) in declared.groupBy { it.repository }) repositories.maven {
            url = repository.toURI()
            content {
                entries.forEach {
                    val gav = it.coordinate.split(':')
                    includeVersion(gav[0], gav[1], gav[2])
                }
            }
        }
    }
    val coreJars = corePaths.mapIndexed { index, path ->
        tasks.register<Jar>("developmentCoreJar$index") {
            from(path)
            archiveFileName.set("development-core-$index.jar")
            destinationDirectory.set(layout.buildDirectory.dir("core"))
        }
    }
    project(":spi").dependencies.add("api", files(coreJars.map { it.flatMap { jar -> jar.archiveFile } }))
    declared.forEach { project(":spi").dependencies.add("api", it.coordinate) }
    dependencies.add("integrationImplementation", files(coreJars.map { it.flatMap { jar -> jar.archiveFile } }))

    val install = tasks.register("developmentInstall") {
        group = "distribution"
        description = "Install the worker with the selected development core classes."
        dependsOn(":worker:installDist")
    }
    tasks.register<Test>("developmentJniIntegration") {
        group = "verification"
        description = "Explicit lifecycle/JNI integration against the development worker."
        dependsOn(install)
        val source = project.extensions.getByType<SourceSetContainer>().getByName("integration")
        testClassesDirs = source.output.classesDirs
        classpath = source.runtimeClasspath
        useJUnit()
        filter { includeTestsMatching("org.example.unidbg.RuntimeIntegrationTest.independentWorkersAndNativeSideEffects") }
        outputs.upToDateWhen { false }
        val run = java.time.Instant.now().toString().replace(':', '-')
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("evidence/$run/xml"))
        reports.html.outputLocation.set(layout.buildDirectory.dir("evidence/$run/html"))
        systemProperty("integration.runId", run)
        systemProperty("integration.runtime", project(":worker").layout.buildDirectory.dir("install/unidbg-worker").get().asFile.absolutePath)
        systemProperty("integration.evidenceDir", layout.buildDirectory.dir("evidence/$run/artifacts").get().asFile.absolutePath)
        jvmArgs(project(":worker").extensions.getByType<JavaApplication>().applicationDefaultJvmArgs)
        doFirst { systemProperty("integration.fixtureClasspath", source.output.asPath) }
        testLogging { events("passed", "failed"); showExceptions = true; showStackTraces = true }
    }
}

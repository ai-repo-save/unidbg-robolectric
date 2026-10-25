import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.HexFormat

val androidSdk = configurations.create("androidSdk") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(androidSdk.name, "org.robolectric:android-all-instrumented:11-robolectric-6757853-i7") }

fun digest(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    return HexFormat.of().formatHex(hash.digest())
}

val releaseGroup = group.toString()
val releaseVersion = version.toString()
val jvmArguments = extensions.getByType<org.gradle.api.plugins.JavaApplication>().applicationDefaultJvmArgs.toList()
val licenseFile = rootProject.file("LICENSE")
val prepared = layout.buildDirectory.dir("prepared-runtime")
val sourceCommit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
val sourceDirty = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.map { it.isNotBlank() }
val prepareRuntime = tasks.register("prepareRuntime") {
    dependsOn(tasks.named("jar"), ":client:jar", ":spi:jar", "startScripts")
    inputs.files(androidSdk, configurations.named("runtimeClasspath"), tasks.named<Jar>("jar").flatMap { it.archiveFile }, tasks.named("startScripts"))
    inputs.file(rootProject.file("LICENSE"))
    inputs.property("sourceCommit", sourceCommit)
    inputs.property("sourceDirty", sourceDirty)
    outputs.dir(prepared)
    doLast {
        val root = prepared.get().asFile
        root.mkdirs()
        val sdk = androidSdk.singleFile
        val provenance = configurations.getByName("runtimeClasspath").incoming.artifacts.artifacts
            .sortedBy { it.file.name }
            .map {
                val component = it.id.componentIdentifier
                val coordinate = if (component is org.gradle.api.artifacts.component.ProjectComponentIdentifier)
                    "$releaseGroup:unidbg-robolectric-${component.projectName}:$releaseVersion" else component.displayName
                mapOf("component" to coordinate, "file" to "lib/${it.file.name}", "sha256" to digest(it.file))
            }
        val worker = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        root.resolve("jvm-args.txt").writeText(jvmArguments.joinToString("\n", postfix = "\n"))
        copy { from(licenseFile); into(root) }
        val scripts = tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts").get()
        val sdkEntry = mapOf("coordinate" to "org.robolectric:android-all-instrumented:11-robolectric-6757853-i7", "file" to "sdk/${sdk.name}", "sha256" to digest(sdk))
        val workerEntry = mapOf("file" to "lib/${worker.name}", "sha256" to digest(worker))
        val executionFiles = listOf(
            mapOf("file" to "jvm-args.txt", "sha256" to digest(root.resolve("jvm-args.txt"))),
            mapOf("file" to "LICENSE", "sha256" to digest(root.resolve("LICENSE"))),
            mapOf("file" to "bin/${scripts.unixScript.name}", "sha256" to digest(scripts.unixScript)),
            mapOf("file" to "bin/${scripts.windowsScript.name}", "sha256" to digest(scripts.windowsScript))
        )
        val manifest = mapOf(
            "version" to releaseVersion, "sourceRepository" to "https://github.com/ai-repo-save/unidbg-robolectric",
            "sourceCommit" to sourceCommit.get(), "sourceDirty" to sourceDirty.get(),
            "java" to 21, "androidSdk" to 30, "robolectric" to "io.github.ai-repo-save.robolectric:robolectric:1.0.0", "robolectricSupport" to "4.17",
            "unidbg" to "io.github.ai-repo-save.unidbg:0.10.0",
            "sdk" to sdkEntry, "worker" to workerEntry, "dependencies" to provenance,
            "files" to (provenance + listOf(sdkEntry, workerEntry) + executionFiles)
        )
        root.resolve("runtime-manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))
        root.resolve("runtime-manifest.json.sha256").writeText(digest(root.resolve("runtime-manifest.json")) + "  runtime-manifest.json\n")
    }
}

configure<org.gradle.api.distribution.DistributionContainer> {
    named("main") {
        distributionBaseName.set("unidbg-worker")
        contents {
            from(prepared)
            from(androidSdk) { into("sdk") }
        }
    }
}
tasks.named("installDist") { dependsOn(prepareRuntime) }
tasks.named("distZip") { dependsOn(prepareRuntime) }
tasks.named("distTar") { dependsOn(prepareRuntime) }
tasks.register<Exec>("verifyRuntime") {
    dependsOn("installDist")
    val installation = layout.buildDirectory.dir("install/unidbg-worker").get().asFile
    commandLine(installation.resolve("bin/unidbg-worker").absolutePath, "--verify-installation", installation.absolutePath)
}

configure<PublishingExtension> {
    publications.named<MavenPublication>("library") {
        artifact(tasks.named<Zip>("distZip")) { classifier = "runtime" }
    }
}

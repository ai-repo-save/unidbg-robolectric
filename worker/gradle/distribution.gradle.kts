import groovy.json.JsonOutput

val androidSdk = configurations.create("androidSdk") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(androidSdk.name, "org.robolectric:android-all-instrumented:11-robolectric-6757853-i7") }

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
    inputs.file(licenseFile)
    inputs.property("sourceCommit", sourceCommit)
    inputs.property("sourceDirty", sourceDirty)
    outputs.dir(prepared)
    doLast {
        val root = prepared.get().asFile
        root.mkdirs()
        val sdk = androidSdk.singleFile
        val coordinates = configurations.getByName("runtimeClasspath").incoming.artifacts.artifacts.map {
            val component = it.id.componentIdentifier
            if (component is org.gradle.api.artifacts.component.ProjectComponentIdentifier)
                "$releaseGroup:unidbg-robolectric-${component.projectName}:$releaseVersion" else component.displayName
        }.distinct().sorted()
        root.resolve("jvm-args.txt").writeText(jvmArguments.joinToString("\n", postfix = "\n"))
        copy { from(licenseFile); into(root) }
        val manifest = mapOf(
            "version" to releaseVersion, "sourceRepository" to "https://github.com/ai-repo-save/unidbg-robolectric",
            "sourceCommit" to sourceCommit.get(), "sourceDirty" to sourceDirty.get(),
            "java" to 21, "androidSdk" to 30, "robolectric" to "io.github.ai-repo-save.robolectric:robolectric:1.1.0", "robolectricSupport" to "4.17",
            "unidbg" to "io.github.ai-repo-save.unidbg:0.12.0",
            "sdk" to mapOf("coordinate" to "org.robolectric:android-all-instrumented:11-robolectric-6757853-i7", "file" to "sdk/${sdk.name}"),
            "worker" to "$releaseGroup:unidbg-robolectric-worker:$releaseVersion", "dependencies" to coordinates
        )
        root.resolve("runtime-manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))
    }
}

configure<org.gradle.api.distribution.DistributionContainer> {
    named("main") {
        distributionBaseName.set("unidbg-worker")
        contents {
            from(prepared) { include("runtime-manifest.json", "jvm-args.txt", "LICENSE") }
            from(androidSdk) { into("sdk") }
        }
    }
}
tasks.named("installDist") { dependsOn(prepareRuntime) }
tasks.named("distZip") { dependsOn(prepareRuntime) }
tasks.named("distTar") { dependsOn(prepareRuntime) }

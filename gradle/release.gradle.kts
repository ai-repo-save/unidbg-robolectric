import groovy.json.JsonOutput

val releaseVersion = version.toString()
val validateRelease = tasks.register("validateRelease") {
    doLast {
        val status = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get()
        check(status.isBlank()) { "Formal release requires a clean source checkout" }
        check(providers.gradleProperty("unidbgRepository").isPresent) { "Pass -PunidbgRepository=<stable fork Maven repository>" }
        check(providers.gradleProperty("robolectricRepository").isPresent) { "Pass -ProbolectricRepository=<stable core/framework Maven repository>" }
        val unidbgRepository = file(providers.gradleProperty("unidbgRepository").get())
        check(unidbgRepository.resolve("release-manifest.json").isFile) { "Stable unidbg 0.13.0 release receipt is missing" }
        for (artifact in listOf("unidbg-api", "unidbg-android", "unidbg-unicorn2")) {
            for (extension in listOf("pom", "jar")) {
                check(unidbgRepository.resolve("io/github/ai-repo-save/unidbg/$artifact/0.13.0/$artifact-0.13.0.$extension").isFile) {
                    "Stable unidbg 0.13.0 repository is incomplete: $artifact ($extension)"
                }
            }
        }
        val robolectricRepository = file(providers.gradleProperty("robolectricRepository").get())
        for (artifact in listOf("robolectric", "shadows-framework")) {
            for (extension in listOf("pom", "module", "jar", "provenance.json")) {
                check(robolectricRepository.resolve("io/github/ai-repo-save/robolectric/$artifact/1.2.0/$artifact-1.2.0.$extension").isFile) {
                    "Stable Robolectric 1.2.0 repository/provenance is incomplete: $artifact ($extension)"
                }
            }
        }
    }
}
val stageUnidbg = tasks.register<Copy>("stageUnidbgRelease") {
    mustRunAfter(validateRelease)
    from(providers.gradleProperty("unidbgRepository"))
    into(layout.buildDirectory.dir("repository"))
}
val stageRobolectric = tasks.register<Copy>("stageRobolectricRelease") {
    mustRunAfter(validateRelease)
    from(providers.gradleProperty("robolectricRepository")) { include("io/github/ai-repo-save/robolectric/**") }
    into(layout.buildDirectory.dir("repository"))
}
subprojects {
    tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach { mustRunAfter(validateRelease) }
}
val writeReleaseManifest = tasks.register("writeReleaseManifest") {
    dependsOn(stageUnidbg, stageRobolectric, ":client:publishAllPublicationsToReleaseRepository",
        ":spi:publishAllPublicationsToReleaseRepository", ":worker:publishAllPublicationsToReleaseRepository")
    doLast {
        val repository = layout.buildDirectory.dir("repository").get().asFile
        val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        repository.resolve("runtime-release-manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "version" to releaseVersion, "sourceRepository" to "https://github.com/ai-repo-save/unidbg-robolectric",
            "sourceCommit" to commit,
            "coordinates" to listOf("client", "spi", "worker").map { "io.github.ai-repo-save:unidbg-robolectric-$it:$releaseVersion" },
            "dependencyCoordinates" to listOf("io.github.ai-repo-save.unidbg:*:0.13.0",
                "io.github.ai-repo-save.robolectric:robolectric:1.2.0",
                "io.github.ai-repo-save.robolectric:shadows-framework:1.2.0")
        ))))
    }
}
tasks.register("releaseLocal") {
    group = "publishing"
    description = "Publish the clean source release with its original stable fork artifacts."
    dependsOn(validateRelease, writeReleaseManifest, ":worker:installDist")
}

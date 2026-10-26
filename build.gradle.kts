import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipFile
import java.time.Instant

plugins { java }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

allprojects {
    group = "io.github.ai-repo-save"
    version = "1.2.1"
    repositories {
        providers.gradleProperty("unidbgRepository").orNull?.let { repository ->
            maven { url = uri(repository); content { includeGroup("io.github.ai-repo-save.unidbg") } }
        }
        providers.gradleProperty("robolectricRepository").orNull?.let { repository ->
            maven { url = uri(repository); content { includeGroup("io.github.ai-repo-save.robolectric") } }
        }
        mavenLocal {
            content {
                includeGroup("io.github.ai-repo-save.unidbg")
                includeGroup("io.github.ai-repo-save.robolectric")
                includeVersion("com.github.zhkl0228", "unicorn", "1.0.15")
                includeVersion("com.github.zhkl0228", "capstone", "3.1.8")
                includeVersion("com.github.zhkl0228", "keystone", "0.9.7")
                includeVersion("com.github.zhkl0228", "demumble", "1.0.4")
                includeVersion("org.robolectric", "android-all-instrumented", "11-robolectric-6757853-i7")
            }
        }
        mavenCentral()
        google { content { includeGroupByRegex("androidx\\..*"); includeModule("com.android.tools.build", "apksig") } }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
        withSourcesJar()
        withJavadocJar()
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "unidbg-robolectric-${project.name}"
            pom {
                name = artifactId
                description = "Isolated unidbg / Robolectric runtime"
                url = "https://github.com/ai-repo-save/unidbg-robolectric"
                scm {
                    url = "https://github.com/ai-repo-save/unidbg-robolectric"
                    connection = "scm:git:https://github.com/ai-repo-save/unidbg-robolectric.git"
                    developerConnection = "scm:git:ssh://git@github.com/ai-repo-save/unidbg-robolectric.git"
                    tag = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
                }
                licenses { license { name = "Apache License, Version 2.0"; url = "https://www.apache.org/licenses/LICENSE-2.0" } }
            }
        }
        repositories { maven { name = "release"; url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI() } }
    }
}

// The Java plugin consumes jars; AndroidX publishes its test infrastructure as AARs.
abstract class AarClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        ZipFile(inputArtifact.get().asFile).use { archive ->
            archive.getInputStream(archive.getEntry("classes.jar")).use { input ->
                outputs.file(inputArtifact.get().asFile.nameWithoutExtension + ".jar").outputStream().use { input.copyTo(it) }
            }
        }
    }
}

allprojects {
    dependencies.registerTransform(AarClasses::class) {
        from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "aar")
        to.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
    }
    configurations.matching { it.name.endsWith("Classpath") }.configureEach {
        attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
    }
}

val integration = sourceSets.create("integration")
configurations.named(integration.compileClasspathConfigurationName) {
    attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
}
configurations.named(integration.runtimeClasspathConfigurationName) {
    attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
}

dependencies {
    add(integration.implementationConfigurationName, "junit:junit:4.13.2")
    add(integration.implementationConfigurationName, project(":client"))
    add(integration.implementationConfigurationName, project(":spi"))
    add(integration.implementationConfigurationName, "io.github.ai-repo-save.robolectric:robolectric:1.2.0")
    add(integration.implementationConfigurationName, "org.robolectric:android-all:11-robolectric-6757853")
    add(integration.implementationConfigurationName, "io.github.ai-repo-save.unidbg:unidbg-android:0.13.1")
    add(integration.implementationConfigurationName, "io.github.ai-repo-save.unidbg:unidbg-unicorn2:0.13.1")
    add(integration.implementationConfigurationName, "org.slf4j:slf4j-api:2.0.20")
    add(integration.runtimeOnlyConfigurationName, "ch.qos.logback:logback-classic:1.5.18")
    add(integration.runtimeOnlyConfigurationName, "net.logstash.logback:logstash-logback-encoder:8.1")
}

tasks.wrapper {
    gradleVersion = "9.5.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
}

val runId = Instant.now().toString().replace(":", "-")
tasks.register<Test>("jniIntegration") {
    description = "Explicit runtime distribution integration: JNI, independent workers, native IO, failure and cancellation."
    dependsOn(":worker:installDist")
    group = "verification"
    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath
    useJUnit()
    filter { includeTestsMatching("org.example.unidbg.RuntimeIntegrationTest") }
    outputs.upToDateWhen { false }
    reports.junitXml.outputLocation = layout.buildDirectory.dir("evidence/$runId/xml")
    reports.html.outputLocation = layout.buildDirectory.dir("evidence/$runId/html")
    systemProperty("integration.runId", runId)
    systemProperty("integration.runtime", project(":worker").layout.buildDirectory.dir("install/unidbg-worker").get().asFile.absolutePath)
    doFirst { systemProperty("integration.fixtureClasspath", integration.runtimeClasspath.asPath) }
    systemProperty("integration.evidenceDir", layout.buildDirectory.dir("evidence/$runId/artifacts").get().asFile.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("robolectric.dependency.proxy.host", "127.0.0.1")
    systemProperty("robolectric.dependency.proxy.port", "7891")
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
    )
    testLogging { events("passed", "failed", "standardOut", "standardError"); showExceptions = true; showStackTraces = true }
}

apply(from = "gradle/release.gradle.kts")

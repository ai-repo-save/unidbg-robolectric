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
    version = "1.0.0"
    repositories {
        providers.gradleProperty("unidbgRepository").orNull?.let { repository ->
            maven { url = uri(repository); content { includeGroup("io.github.ai-repo-save.unidbg") } }
        }
        mavenLocal {
            content {
                includeGroup("io.github.ai-repo-save.unidbg")
                includeVersion("com.github.zhkl0228", "unicorn", "1.0.15")
                includeVersion("com.github.zhkl0228", "capstone", "3.1.8")
                includeVersion("com.github.zhkl0228", "keystone", "0.9.7")
                includeVersion("com.github.zhkl0228", "demumble", "1.0.4")
            }
        }
        mavenCentral()
        google { content { includeGroupByRegex("androidx\\..*") } }
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
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "unidbg-robolectric-${project.name}"
            pom {
                name = artifactId
                description = "Isolated unidbg / Robolectric runtime"
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
                outputs.file("classes.jar").outputStream().use { input.copyTo(it) }
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
    add(integration.implementationConfigurationName, "org.robolectric:robolectric:4.17")
    add(integration.implementationConfigurationName, "org.robolectric:android-all:11-robolectric-6757853")
    add(integration.implementationConfigurationName, "io.github.ai-repo-save.unidbg:unidbg-android:0.9.10")
    add(integration.implementationConfigurationName, "io.github.ai-repo-save.unidbg:unidbg-unicorn2:0.9.10")
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
    description = "Explicit ARM64 JNI / Robolectric integration, including two isolated sessions."
    group = "verification"
    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath
    useJUnit()
    filter { includeTestsMatching("org.example.unidbg.RobolectricJniIntegrationTest") }
    outputs.upToDateWhen { false }
    reports.junitXml.outputLocation = layout.buildDirectory.dir("evidence/$runId/xml")
    reports.html.outputLocation = layout.buildDirectory.dir("evidence/$runId/html")
    systemProperty("integration.runId", runId)
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

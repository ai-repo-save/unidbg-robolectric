plugins { `java-library`; `maven-publish`; application }

dependencies {
    api(project(":spi"))
    implementation(project(":client"))
    implementation("org.robolectric:android-all:11-robolectric-6757853")
    implementation("io.github.ai-repo-save.robolectric:robolectric:1.3.0")
    implementation("junit:junit:4.13.2")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("com.android.tools.build:apksig:9.4.1")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")
}

application {
    mainClass = "io.github.aireposave.unidbg.runtime.worker.WorkerMain"
    applicationName = "unidbg-worker"
    applicationDefaultJvmArgs = listOf(
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
}

val compileDeviceEgl = tasks.register<Exec>("compileDeviceEgl") {
    val source = layout.projectDirectory.file("src/main/native/device_egl.c")
    val output = layout.buildDirectory.file("generated/resources/device-egl/native/linux-x86_64/libdevice-egl.so")
    inputs.file(source)
    outputs.file(output)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine(
        "gcc", "-shared", "-fPIC", "-O2",
        "-o", output.get().asFile.absolutePath,
        source.asFile.absolutePath,
    )
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(compileDeviceEgl)
    from(layout.buildDirectory.dir("generated/resources/device-egl"))
}

apply(from = "gradle/distribution.gradle.kts")

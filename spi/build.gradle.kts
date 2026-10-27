plugins { `java-library`; `maven-publish` }

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    api("io.github.ai-repo-save.unidbg:unidbg-android:0.13.1") {
        exclude(group = "net.dongliu", module = "apk-parser")
    }
    api("io.github.ai-repo-save.apk-parser:apk-parser:2.6.11")
    api("io.github.ai-repo-save.unidbg:unidbg-unicorn2:0.13.1")
    compileOnlyApi("org.robolectric:android-all:11-robolectric-6757853")
}

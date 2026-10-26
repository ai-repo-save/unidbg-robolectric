plugins { `java-library`; `maven-publish` }

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    api("io.github.ai-repo-save.unidbg:unidbg-android:0.10.1")
    api("io.github.ai-repo-save.unidbg:unidbg-unicorn2:0.10.1")
    compileOnlyApi("org.robolectric:android-all:11-robolectric-6757853")
}

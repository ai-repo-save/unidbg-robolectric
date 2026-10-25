plugins { `java-library`; `maven-publish` }

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.slf4j:slf4j-api:2.0.20")
}

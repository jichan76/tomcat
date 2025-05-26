plugins {
    java
    war
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    providedCompile("javax.servlet:javax.servlet-api:4.0.1")
    implementation("com.google.code.gson:gson:2.8.9")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named<War>("war") {
    archiveFileName.set("tomcat.war")
}
// Terminal -> ./gradlew clean war
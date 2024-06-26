plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    application
    idea
}

group = "robaertschi"
version = "1.0-SNAPSHOT"

idea {
    module.isDownloadSources = true
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.fabricmc.net")
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.fabricmc:tiny-remapper:0.10.3")
    implementation("net.fabricmc:mapping-io:0.6.1")
    implementation ("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("ch.qos.logback:logback-core:1.5.6")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.vineflower:vineflower:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    //implementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}



application {
    mainClass = "robaertschi.remapmc.MainKt"
}
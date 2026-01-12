plugins {
    kotlin("jvm") version "2.2.10"
    application
    kotlin("plugin.serialization") version "2.0.20"
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // Ktor 3.3.0
    implementation("io.ktor:ktor-server-core-jvm:3.3.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.3.0")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.0")

    implementation("io.ktor:ktor-client-core-jvm:3.3.0")
    implementation("io.ktor:ktor-client-cio-jvm:3.3.0")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:3.3.0")
    implementation("io.ktor:ktor-client-logging-jvm:3.3.0")


    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Telegram Bot
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")
}

application {
    mainClass.set("MainKt")
}

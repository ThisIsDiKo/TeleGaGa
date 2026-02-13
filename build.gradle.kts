plugins {
    kotlin("jvm") version "2.2.10"
    application
    kotlin("plugin.serialization") version "2.2.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
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

    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")

    // Koin Dependency Injection
    implementation("io.insert-koin:koin-core:3.5.3")
}

application {
    mainClass.set("ru.dikoresearch.MainKt")
}

// Task for running CLI chatbot
tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Run CLI chatbot with RAG"
    mainClass.set("ru.dikoresearch.MainCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

kotlin {
    jvmToolchain(17)
}

// Shadow plugin configuration for fat JAR
tasks {
    shadowJar {
        archiveBaseName.set("telegaga-bot")
        archiveVersion.set("1.0.0")
        archiveClassifier.set("")

        // Merge service files (important for Ktor, Koin)
        mergeServiceFiles()

        manifest {
            attributes["Main-Class"] = "ru.dikoresearch.MainKt"
        }
    }

    // Make build depend on shadowJar
    build {
        dependsOn(shadowJar)
    }
}
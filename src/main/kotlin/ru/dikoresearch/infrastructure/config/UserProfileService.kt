package ru.dikoresearch.infrastructure.config

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Загружает персональный профиль пользователя из user_profile.properties.
 * Профиль автоматически встраивается в системный промпт, давая LLM контекст
 * о личности, привычках, целях и предпочтениях пользователя.
 */
class UserProfileService private constructor(
    val name: String,
    val occupation: String,
    val language: String,
    val timezone: String,
    val interests: String,
    val habits: String,
    val communicationStyle: String,
    val currentProjects: String,
    val goals: String,
    val additionalContext: String
) {
    /**
     * Возвращает true если профиль заполнен (есть хоть одно непустое поле кроме языка)
     */
    val isConfigured: Boolean
        get() = listOf(name, occupation, interests, habits, currentProjects, goals, additionalContext)
            .any { it.isNotBlank() }

    /**
     * Форматирует профиль в текстовый блок для вставки в системный промпт
     */
    fun toSystemPromptBlock(): String {
        if (!isConfigured) return ""

        val parts = mutableListOf<String>()
        if (name.isNotBlank()) parts.add("Name: $name")
        if (occupation.isNotBlank()) parts.add("Occupation: $occupation")
        if (currentProjects.isNotBlank()) parts.add("Current projects: $currentProjects")
        if (interests.isNotBlank()) parts.add("Interests: $interests")
        if (habits.isNotBlank()) parts.add("Daily habits: $habits")
        if (goals.isNotBlank()) parts.add("Goals: $goals")
        if (communicationStyle.isNotBlank()) parts.add("Communication style preference: $communicationStyle")
        if (additionalContext.isNotBlank()) parts.add("Additional context: $additionalContext")

        return """
            |USER PROFILE:
            |${parts.joinToString("\n|")}
        """.trimMargin()
    }

    override fun toString(): String = """
        |UserProfileService:
        |  name: ${name.ifBlank { "(not set)" }}
        |  occupation: ${occupation.ifBlank { "(not set)" }}
        |  language: $language
        |  timezone: $timezone
        |  interests: ${interests.ifBlank { "(not set)" }}
        |  habits: ${habits.ifBlank { "(not set)" }}
        |  communicationStyle: ${communicationStyle.ifBlank { "(not set)" }}
        |  currentProjects: ${currentProjects.ifBlank { "(not set)" }}
        |  goals: ${goals.ifBlank { "(not set)" }}
        |  additionalContext: ${additionalContext.ifBlank { "(not set)" }}
        |  isConfigured: $isConfigured
    """.trimMargin()

    companion object {
        private const val PROFILE_FILE = "user_profile.properties"
        private const val TEMPLATE_RESOURCE = "user_profile.properties.template"

        fun load(): UserProfileService {
            val properties = loadProperties()
            return UserProfileService(
                name = properties.getProperty("user.name", "").trim(),
                occupation = properties.getProperty("user.occupation", "").trim(),
                language = properties.getProperty("user.language", "Russian").trim(),
                timezone = properties.getProperty("user.timezone", "Europe/Moscow").trim(),
                interests = properties.getProperty("user.interests", "").trim(),
                habits = properties.getProperty("user.habits", "").trim(),
                communicationStyle = properties.getProperty("user.communication_style", "").trim(),
                currentProjects = properties.getProperty("user.current_projects", "").trim(),
                goals = properties.getProperty("user.goals", "").trim(),
                additionalContext = properties.getProperty("user.additional_context", "").trim()
            )
        }

        private fun loadProperties(): Properties {
            val externalFile = File(PROFILE_FILE)
            if (externalFile.exists()) {
                println("✅ Loading user profile from: $PROFILE_FILE")
                val properties = Properties()
                InputStreamReader(externalFile.inputStream(), StandardCharsets.UTF_8).use { properties.load(it) }
                return properties
            }

            // No profile file — extract template and return empty profile
            extractTemplate()
            println("⚠️ No user_profile.properties found, using empty profile (template created)")
            return Properties()
        }

        private fun extractTemplate() {
            val templateFile = File(PROFILE_FILE)
            if (templateFile.exists()) return

            val templateStream = UserProfileService::class.java.classLoader
                .getResourceAsStream(TEMPLATE_RESOURCE)

            if (templateStream != null) {
                templateFile.outputStream().use { output ->
                    templateStream.use { input -> input.copyTo(output) }
                }
                println("📄 Created user_profile.properties from template — please fill it in!")
            }
        }
    }
}

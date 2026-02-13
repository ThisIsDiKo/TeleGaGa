package ru.dikoresearch.domain.valueobjects

import kotlinx.serialization.Serializable

/**
 * Value object for system prompt text
 * Ensures non-blank prompts
 */
@Serializable
@JvmInline
value class SystemPrompt(val value: String) {
    init {
        require(value.isNotBlank()) {
            "System prompt cannot be blank"
        }
    }

    override fun toString(): String = value
}

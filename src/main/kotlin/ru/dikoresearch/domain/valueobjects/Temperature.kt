package ru.dikoresearch.domain.valueobjects

import kotlinx.serialization.Serializable

/**
 * Value object for LLM temperature parameter
 * Enforces valid range [0.0, 1.0]
 */
@Serializable
@JvmInline
value class Temperature(val value: Float) {
    init {
        require(value in 0.0f..1.0f) {
            "Temperature must be between 0.0 and 1.0, got $value"
        }
    }

    companion object {
        val DEFAULT = Temperature(0.87f)
        val LOW = Temperature(0.3f)
        val MEDIUM = Temperature(0.5f)
        val HIGH = Temperature(0.9f)
    }

    override fun toString(): String = value.toString()
}

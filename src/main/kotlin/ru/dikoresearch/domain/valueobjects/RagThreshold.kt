package ru.dikoresearch.domain.valueobjects

import kotlinx.serialization.Serializable

/**
 * Value object for RAG relevance threshold
 * Enforces valid range [0.0, 1.0] for cosine similarity filtering
 */
@Serializable
@JvmInline
value class RagThreshold(val value: Float) {
    init {
        require(value in 0.0f..1.0f) {
            "RAG threshold must be between 0.0 and 1.0, got $value"
        }
    }

    companion object {
        val LOW = RagThreshold(0.3f)
        val MEDIUM = RagThreshold(0.5f)
        val HIGH = RagThreshold(0.7f)
        val VERY_HIGH = RagThreshold(0.8f)
    }

    override fun toString(): String = value.toString()
}

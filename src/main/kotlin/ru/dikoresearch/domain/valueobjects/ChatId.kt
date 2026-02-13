package ru.dikoresearch.domain.valueobjects

import kotlinx.serialization.Serializable

/**
 * Value object for Telegram chat identifier
 * Type-safe wrapper around Long
 */
@Serializable
@JvmInline
value class ChatId(val value: Long) {
    override fun toString(): String = value.toString()
}

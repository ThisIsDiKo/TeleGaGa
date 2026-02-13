package ru.dikoresearch.domain.valueobjects

import kotlinx.serialization.Serializable

/**
 * Value object for reminder time in HH:mm format
 * Validates time format
 */
@Serializable
@JvmInline
value class ReminderTime(val value: String) {
    init {
        require(value.matches(Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$"))) {
            "ReminderTime must be in HH:mm format (00:00 - 23:59), got '$value'"
        }
    }

    companion object {
        val MORNING = ReminderTime("09:00")
        val AFTERNOON = ReminderTime("14:00")
        val EVENING = ReminderTime("18:00")
    }

    override fun toString(): String = value
}

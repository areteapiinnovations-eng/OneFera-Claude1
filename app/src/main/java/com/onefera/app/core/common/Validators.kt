package com.onefera.app.core.common

import java.time.LocalDate
import java.time.Period

/** Input rules shared by sign-up, sign-in and profile editing. Pure Kotlin, unit-tested. */
object Validators {
    const val MIN_AGE = 13
    const val ADULT_AGE = 18
    const val BIO_MAX = 160
    const val NAME_MAX = 40
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 24
    const val PASSWORD_MIN = 8

    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val usernameRegex = Regex("^[a-z0-9](?:[a-z0-9_]|\\.(?!\\.))*[a-z0-9_]$")

    fun email(value: String): String? = when {
        value.isBlank() -> "Enter your email"
        !emailRegex.matches(value.trim()) -> "That email doesn't look right"
        else -> null
    }

    fun password(value: String): String? = when {
        value.length < PASSWORD_MIN -> "Use at least $PASSWORD_MIN characters"
        value.none { it.isLetter() } || value.none { it.isDigit() } -> "Mix letters and numbers"
        else -> null
    }

    fun displayName(value: String): String? = when {
        value.isBlank() -> "Tell us what to call you"
        value.trim().length < 2 -> "A bit longer, please"
        value.trim().length > NAME_MAX -> "Keep it under $NAME_MAX characters"
        else -> null
    }

    /** Usernames are lowercase letters, digits, "_" and single dots, 3–24 characters. */
    fun username(value: String): String? = when {
        value.length < USERNAME_MIN -> "At least $USERNAME_MIN characters"
        value.length > USERNAME_MAX -> "Max $USERNAME_MAX characters"
        !usernameRegex.matches(value) -> "Use a–z, 0–9, _ and single dots (no dot at the end)"
        else -> null
    }

    fun normalizeUsername(raw: String): String = raw.trim().removePrefix("@").lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }

    fun ageOn(birthDate: LocalDate, today: LocalDate = LocalDate.now()): Int = Period.between(birthDate, today).years

    fun birthDate(birthDate: LocalDate?, today: LocalDate = LocalDate.now()): String? = when {
        birthDate == null -> "Add your birthday"
        birthDate.isAfter(today) -> "That date is in the future"
        ageOn(birthDate, today) < MIN_AGE -> "You need to be at least $MIN_AGE to join OneFera"
        else -> null
    }

    fun passwordStrength(value: String): PasswordStrength {
        if (value.isEmpty()) return PasswordStrength.None
        var score = 0
        if (value.length >= PASSWORD_MIN) score++
        if (value.length >= 12) score++
        if (value.any { it.isLowerCase() } && value.any { it.isUpperCase() }) score++
        if (value.any { it.isDigit() }) score++
        if (value.any { !it.isLetterOrDigit() }) score++
        return when {
            score <= 1 -> PasswordStrength.Weak
            score <= 3 -> PasswordStrength.Okay
            else -> PasswordStrength.Strong
        }
    }
}

enum class PasswordStrength(val label: String, val fraction: Float) {
    None("", 0f),
    Weak("Weak", 0.33f),
    Okay("Okay", 0.66f),
    Strong("Strong 💪", 1f),
}

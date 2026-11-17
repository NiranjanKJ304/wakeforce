package com.wakeforce.app.math

/**
 * Addition difficulty: how many numbers per question and which values they use.
 *  - EASY: 2 two-digit numbers (10-99), e.g. 30 + 70
 *  - MEDIUM: 2 or 3 numbers from the user's min-max range (default 10-99), e.g. 22 + 15 + 13
 *  - HARD: 3 two-digit numbers (10-99), e.g. 45 + 32 + 18
 */
enum class Difficulty(val terms: IntRange, val label: String, val description: String) {
    EASY(2..2, "Easy", "2 two-digit numbers (10–99)"),
    MEDIUM(2..3, "Medium", "2 or 3 numbers from your range"),
    HARD(3..3, "Hard", "3 two-digit numbers (10–99)"),
    ;

    /** Easy and Hard always use two-digit numbers; Medium uses the configurable range. */
    val usesCustomRange: Boolean get() = this == MEDIUM
}

data class MathSettings(
    val questionCount: Int = 3,
    val minValue: Int = 10,
    val maxValue: Int = 99,
    val difficulty: Difficulty = Difficulty.EASY,
) {
    /** Human-readable problem, or null when the settings are usable. */
    fun validationError(): String? = when {
        questionCount !in MIN_QUESTIONS..MAX_QUESTIONS ->
            "Number of questions must be between $MIN_QUESTIONS and $MAX_QUESTIONS"
        minValue < LOWEST || maxValue > HIGHEST ->
            "Numbers must be between $LOWEST and $HIGHEST"
        minValue >= maxValue -> "Minimum must be lower than maximum"
        else -> null
    }

    /** Settings that are always safe to generate from (falls back to defaults if invalid). */
    fun sanitized(): MathSettings =
        if (validationError() == null) this else MathSettings(difficulty = difficulty)

    /** Values each number is drawn from for the current difficulty. */
    val valueRange: IntRange
        get() = if (difficulty.usesCustomRange) minValue..maxValue else TWO_DIGIT

    companion object {
        const val MIN_QUESTIONS = 1
        const val MAX_QUESTIONS = 10
        const val LOWEST = 0
        const val HIGHEST = 9999
        val TWO_DIGIT = 10..99
    }
}

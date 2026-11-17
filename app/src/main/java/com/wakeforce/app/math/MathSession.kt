package com.wakeforce.app.math

import kotlin.random.Random

/**
 * Immutable state of one math dismissal attempt. Pure logic (unit-tested): the alarm can
 * only be stopped when [solved] is true, which requires every question answered correctly
 * in order.
 */
data class MathSession(
    val settings: MathSettings,
    val questions: List<AdditionQuestion>,
    val index: Int = 0,
    val message: String? = null,
) {
    val solved: Boolean get() = questions.isNotEmpty() && index >= questions.size
    val current: AdditionQuestion? get() = questions.getOrNull(index)

    /** Evaluates [input] for the current question and returns the next state. */
    fun submit(input: String, random: Random = Random.Default): MathSession {
        val question = current ?: return this
        return when (AdditionChallenge.check(question, input)) {
            AnswerResult.EMPTY -> copy(message = "Enter an answer first")
            AnswerResult.NOT_A_NUMBER -> copy(message = "Numbers only")
            // A wrong answer replaces the question, so it can't be found by trial and error.
            // The new one also never has the old answer, the wrong value just typed, or a
            // neighbour's answer, so no earlier or repeated answer can pass.
            AnswerResult.WRONG -> copy(
                questions = questions.toMutableList().also {
                    val avoidAnswers = setOfNotNull(
                        question.answer,
                        input.trim().toIntOrNull(),
                        questions.getOrNull(index - 1)?.answer,
                        questions.getOrNull(index + 1)?.answer,
                    )
                    it[index] = AdditionChallenge.nextQuestion(settings, random, avoid = it, avoidAnswers = avoidAnswers)
                },
                message = "Incorrect. Try this one.",
            )
            AnswerResult.CORRECT -> copy(index = index + 1, message = null)
        }
    }

    companion object {
        fun start(settings: MathSettings, random: Random = Random.Default): MathSession {
            val safe = settings.sanitized()
            return MathSession(safe, AdditionChallenge.generate(safe, random))
        }

        /** Backup challenge when QR can't be scanned: twice as many questions (3..10). */
        fun fallback(settings: MathSettings, random: Random = Random.Default): MathSession {
            val safe = settings.sanitized()
            return start(
                safe.copy(questionCount = (safe.questionCount * 2).coerceIn(3, MathSettings.MAX_QUESTIONS)),
                random,
            )
        }
    }
}

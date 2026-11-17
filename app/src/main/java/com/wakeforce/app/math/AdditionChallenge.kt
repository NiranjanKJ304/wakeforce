package com.wakeforce.app.math

import kotlin.random.Random

/** Addition only. The largest possible answer is 3 x 9999 = 29,997, far below Int overflow. */
data class AdditionQuestion(val operands: List<Int>) {
    val answer: Int get() = operands.sum()
    val text: String get() = operands.joinToString(" + ") + " = ?"
}

enum class AnswerResult { EMPTY, NOT_A_NUMBER, WRONG, CORRECT }

object AdditionChallenge {

    /**
     * A fresh set of questions; called once per ringing session. Consecutive questions never
     * share an answer, so the previous answer can't simply be typed again.
     */
    fun generate(settings: MathSettings, random: Random = Random.Default): List<AdditionQuestion> {
        val s = settings.sanitized()
        val questions = ArrayList<AdditionQuestion>(s.questionCount)
        repeat(s.questionCount) {
            val previousAnswer = questions.lastOrNull()?.answer
            questions += nextQuestion(s, random, avoid = questions, avoidAnswers = setOfNotNull(previousAnswer))
        }
        return questions
    }

    /**
     * One random question, avoiding exact repeats of [avoid] and any answer in [avoidAnswers]
     * when the range allows it.
     */
    fun nextQuestion(
        settings: MathSettings,
        random: Random = Random.Default,
        avoid: Collection<AdditionQuestion> = emptyList(),
        avoidAnswers: Set<Int> = emptySet(),
    ): AdditionQuestion {
        val s = settings.sanitized()
        val range = s.valueRange
        var question: AdditionQuestion
        var attempts = 0
        do {
            val terms = random.nextInt(s.difficulty.terms.first, s.difficulty.terms.last + 1)
            question = AdditionQuestion(List(terms) { random.nextInt(range.first, range.last + 1) })
            attempts++
        } while ((question in avoid || question.answer in avoidAnswers) && attempts < MAX_ATTEMPTS)
        return question
    }

    fun check(question: AdditionQuestion, input: String): AnswerResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return AnswerResult.EMPTY
        // toIntOrNull returns null on overflow too, so a huge input can't crash or wrap around.
        val value = trimmed.toIntOrNull() ?: return AnswerResult.NOT_A_NUMBER
        return if (value == question.answer) AnswerResult.CORRECT else AnswerResult.WRONG
    }

    private const val MAX_ATTEMPTS = 50
}

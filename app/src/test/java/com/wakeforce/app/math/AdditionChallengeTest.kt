package com.wakeforce.app.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AdditionChallengeTest {

    @Test
    fun generatesRequestedCountWithinRange() {
        val settings = MathSettings(questionCount = 5, minValue = 10, maxValue = 99, difficulty = Difficulty.MEDIUM)
        val questions = AdditionChallenge.generate(settings, Random(42))
        assertEquals(5, questions.size)
        questions.forEach { q ->
            // Requirement change (functional audit): MEDIUM = 2 or 3 numbers (was always 3).
            assertTrue(q.operands.size in 2..3)
            assertTrue(q.operands.all { it in 10..99 })
            assertEquals(q.operands.sum(), q.answer)
        }
    }

    @Test
    fun questionsAreDifferentPerSession() {
        val settings = MathSettings(questionCount = 3, minValue = 10, maxValue = 999)
        val a = AdditionChallenge.generate(settings, Random(1))
        val b = AdditionChallenge.generate(settings, Random(2))
        assertTrue(a != b)
    }

    @Test
    fun rejectsEmptyAndWrongAnswers() {
        val q = AdditionQuestion(listOf(15, 24))
        assertEquals(AnswerResult.EMPTY, AdditionChallenge.check(q, ""))
        assertEquals(AnswerResult.EMPTY, AdditionChallenge.check(q, "   "))
        assertEquals(AnswerResult.NOT_A_NUMBER, AdditionChallenge.check(q, "3x"))
        assertEquals(AnswerResult.WRONG, AdditionChallenge.check(q, "38"))
        assertEquals(AnswerResult.CORRECT, AdditionChallenge.check(q, "39"))
        assertEquals("15 + 24 = ?", q.text)
    }

    @Test
    fun validation() {
        assertNull(MathSettings().validationError())
        assertNotNull(MathSettings(questionCount = 0).validationError())
        assertNotNull(MathSettings(questionCount = 11).validationError())
        assertNotNull(MathSettings(minValue = 50, maxValue = 50).validationError())
        assertNotNull(MathSettings(minValue = -1).validationError())
        assertNotNull(MathSettings(maxValue = 10_000).validationError())
    }

    @Test
    fun invalidSettingsFallBackToSafeDefaults() {
        val questions = AdditionChallenge.generate(MathSettings(questionCount = 99, minValue = 9, maxValue = 3))
        assertEquals(MathSettings().questionCount, questions.size)
    }
}

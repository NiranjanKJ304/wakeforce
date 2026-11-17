package com.wakeforce.app.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Functional audit: addition-only questions per difficulty, answers, boundaries, overflow. */
class MathDifficultyTest {

    private fun sample(settings: MathSettings, seeds: IntRange = 1..200) =
        seeds.flatMap { AdditionChallenge.generate(settings.copy(questionCount = 5), Random(it)) }

    @Test
    fun easyIsTwoTwoDigitNumbers() {
        val qs = sample(MathSettings(difficulty = Difficulty.EASY))
        assertTrue(qs.all { it.operands.size == 2 })
        assertTrue(qs.all { q -> q.operands.all { it in 10..99 } })
    }

    @Test
    fun easyStaysTwoDigitEvenIfACustomRangeIsStored() {
        val qs = sample(MathSettings(minValue = 500, maxValue = 900, difficulty = Difficulty.EASY))
        assertTrue(qs.all { q -> q.operands.all { it in 10..99 } })
    }

    @Test
    fun mediumIsTwoOrThreeNumbersFromTheCustomRange() {
        val qs = sample(MathSettings(minValue = 20, maxValue = 50, difficulty = Difficulty.MEDIUM))
        assertTrue(qs.all { it.operands.size in 2..3 })
        assertTrue(qs.any { it.operands.size == 2 })
        assertTrue(qs.any { it.operands.size == 3 })
        assertTrue(qs.all { q -> q.operands.all { it in 20..50 } })
    }

    @Test
    fun hardIsThreeTwoDigitNumbers() {
        val qs = sample(MathSettings(minValue = 0, maxValue = 9999, difficulty = Difficulty.HARD))
        assertTrue(qs.all { it.operands.size == 3 })
        assertTrue(qs.all { q -> q.operands.all { it in 10..99 } })
    }

    @Test
    fun additionOnlyAndAnswerIsTheSum() {
        Difficulty.entries.forEach { d ->
            sample(MathSettings(difficulty = d), 1..20).forEach { q ->
                assertEquals(q.operands.sum(), q.answer)
                assertTrue(q.text.matches(Regex("""\d+( \+ \d+)+ = \?""")))
            }
        }
    }

    @Test
    fun consecutiveQuestionsNeverShareAnAnswer() {
        Difficulty.entries.forEach { d ->
            (1..300).forEach { seed ->
                val qs = AdditionChallenge.generate(MathSettings(questionCount = 10, difficulty = d), Random(seed))
                qs.zipWithNext().forEach { (a, b) -> assertNotEquals("seed $seed $d", a.answer, b.answer) }
            }
        }
    }

    @Test
    fun previousAnswerCannotBeReusedToPassTheNextQuestion() {
        var s = MathSession.start(MathSettings(questionCount = 5), Random(11))
        repeat(4) {
            val previous = s.current!!.answer
            s = s.submit(previous.toString())
            assertFalse(s.solved)
            val reuse = s.submit(previous.toString(), Random(it))
            assertEquals("reusing the previous answer must not advance", s.index, reuse.index)
        }
    }

    @Test
    fun wrongAnswerReplacementNeverHasTheTypedOrOldAnswer() {
        (1..300).forEach { seed ->
            val s = MathSession.start(MathSettings(questionCount = 3), Random(seed))
            val old = s.current!!.answer
            val typed = old + 1
            val after = s.submit(typed.toString(), Random(seed + 1000))
            assertNotEquals(old, after.current!!.answer)
            assertNotEquals(typed, after.current!!.answer)
            after.questions.zipWithNext().forEach { (a, b) -> assertNotEquals(a.answer, b.answer) }
        }
    }

    @Test
    fun boundaryValuesAndOverflow() {
        val max = AdditionQuestion(listOf(9999, 9999, 9999))
        assertEquals(29_997, max.answer)
        assertEquals(AnswerResult.CORRECT, AdditionChallenge.check(max, "29997"))
        assertEquals(AnswerResult.CORRECT, AdditionChallenge.check(AdditionQuestion(listOf(0, 0)), "0"))
        // Larger than Int: rejected, not wrapped around or crashing.
        assertEquals(AnswerResult.NOT_A_NUMBER, AdditionChallenge.check(max, "99999999999999999999"))
        assertEquals(AnswerResult.NOT_A_NUMBER, AdditionChallenge.check(max, "2147483648"))
        assertEquals(AnswerResult.WRONG, AdditionChallenge.check(max, "-29997"))
        // Widest Medium range at its limits.
        sample(MathSettings(minValue = 0, maxValue = 9999, difficulty = Difficulty.MEDIUM), 1..50).forEach { q ->
            assertTrue(q.answer in 0..29_997)
        }
    }

    @Test
    fun questionCountIsConfigurableAndNewEverySession() {
        (1..10).forEach { n -> assertEquals(n, AdditionChallenge.generate(MathSettings(questionCount = n)).size) }
        val a = MathSession.start(MathSettings(questionCount = 5), Random(1)).questions
        val b = MathSession.start(MathSettings(questionCount = 5), Random(2)).questions
        assertNotEquals(a, b)
    }
}

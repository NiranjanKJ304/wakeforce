package com.wakeforce.app.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MathSessionTest {

    private val settings = MathSettings(questionCount = 3, minValue = 10, maxValue = 99)

    private fun solveAll(session: MathSession): MathSession {
        var s = session
        repeat(50) {
            if (s.solved) return s
            s = s.submit(s.current!!.answer.toString())
        }
        error("not solved")
    }

    @Test
    fun solvedOnlyAfterEveryQuestionIsCorrect() {
        var s = MathSession.start(settings, Random(7))
        assertFalse(s.solved)
        s = s.submit(s.current!!.answer.toString())
        s = s.submit(s.current!!.answer.toString())
        assertFalse(s.solved)
        assertEquals(2, s.index)
        s = s.submit(s.current!!.answer.toString())
        assertTrue(s.solved)
    }

    @Test
    fun emptyAnswerIsRejectedWithoutProgress() {
        val s = MathSession.start(settings, Random(1))
        val after = s.submit("")
        assertEquals(0, after.index)
        assertEquals(s.questions, after.questions)
        assertNotNull(after.message)
        assertFalse(after.solved)
    }

    @Test
    fun garbageInputIsRejected() {
        val s = MathSession.start(settings, Random(1)).submit("12a")
        assertEquals(0, s.index)
        assertFalse(s.solved)
    }

    @Test
    fun wrongAnswerDoesNotAdvanceAndReplacesQuestion() {
        val s = MathSession.start(settings, Random(3))
        val wrong = (s.current!!.answer + 1).toString()
        val after = s.submit(wrong, Random(99))
        assertEquals(0, after.index)
        assertFalse(after.solved)
        assertNotEquals(s.current, after.current)
        assertEquals("Incorrect. Try this one.", after.message)
    }

    @Test
    fun repeatedWrongAnswersNeverSolve() {
        var s = MathSession.start(settings, Random(5))
        repeat(200) { s = s.submit("0") } // min operand is 10, so 0 is always wrong
        assertFalse(s.solved)
        assertEquals(0, s.index)
    }

    @Test
    fun noQuestionsMeansNotSolved() {
        val empty = MathSession(settings, emptyList())
        assertFalse(empty.solved)
        assertEquals(empty, empty.submit("42"))
    }

    @Test
    fun eachSessionGetsNewQuestions() {
        assertNotEquals(MathSession.start(settings, Random(1)).questions, MathSession.start(settings, Random(2)).questions)
    }

    @Test
    fun fallbackIsLongerAndStillRequiresAllAnswers() {
        val fallback = MathSession.fallback(settings, Random(4))
        assertEquals(6, fallback.questions.size)
        assertTrue(solveAll(fallback).solved)
        assertEquals(10, MathSession.fallback(settings.copy(questionCount = 8)).questions.size)
        assertEquals(3, MathSession.fallback(settings.copy(questionCount = 1)).questions.size)
    }
}

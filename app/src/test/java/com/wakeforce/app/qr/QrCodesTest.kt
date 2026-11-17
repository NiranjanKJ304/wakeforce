package com.wakeforce.app.qr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodesTest {

    @Test
    fun payloadsAreUniqueAndTagged() {
        val a = QrCodes.newPayload()
        val b = QrCodes.newPayload()
        assertNotEquals(a, b)
        assertTrue(QrCodes.isWakeForceCode(a))
    }

    @Test
    fun onlyTheRegisteredCodeMatches() {
        val registered = QrCodes.newPayload()
        assertTrue(QrCodes.matches(registered, registered))
        assertTrue(QrCodes.matches(" $registered\n", registered))
        assertFalse(QrCodes.matches(QrCodes.newPayload(), registered))
        assertFalse(QrCodes.matches("https://example.com", registered))
        assertFalse(QrCodes.matches("", registered))
        assertFalse(QrCodes.matches(registered, null))
        assertFalse(QrCodes.matches(null, registered))
    }
}

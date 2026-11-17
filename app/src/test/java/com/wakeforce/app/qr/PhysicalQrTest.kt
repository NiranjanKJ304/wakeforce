package com.wakeforce.app.qr

import com.wakeforce.app.qr.QrRegistry.RegisterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Functional audit: registering an existing physical QR code by scanning it, and payload matching. */
class PhysicalQrTest {

    private val sticker = "https://example.com/my-bathroom-sticker"

    private fun registered(vararg payloads: String) = payloads.fold(emptyList<com.wakeforce.app.data.RegisteredQr>()) { acc, p ->
        (QrRegistry.registerScanned(acc, p, "Code ${acc.size + 1}") as RegisterResult.Registered).codes
    }

    @Test
    fun anyReadablePayloadCanBeRegistered() {
        val codes = registered(sticker)
        assertEquals(sticker, codes.single().payload)
        assertEquals("Code 1", codes.single().label)
        assertTrue(QrRegistry.isValidForAlarm(codes))
        assertEquals(codes[0], QrRegistry.match(codes, sticker))
    }

    @Test
    fun payloadIsNormalizedSoTrailingWhitespaceStillMatches() {
        // Physical codes / decoders often add a trailing newline; stored and scanned forms must agree.
        val codes = registered("  WIFI:S:home;T:WPA;;\n")
        assertEquals("WIFI:S:home;T:WPA;;", codes.single().payload)
        assertTrue(QrCodes.matches("WIFI:S:home;T:WPA;;\r\n", codes.single().payload))
        assertTrue(QrCodes.matches("WIFI:S:home;T:WPA;;", "WIFI:S:home;T:WPA;;\n")) // legacy raw storage too
    }

    @Test
    fun emptyBlankAndOversizedPayloadsAreRejected() {
        assertEquals(RegisterResult.Invalid, QrRegistry.registerScanned(emptyList(), "", "Code 1"))
        assertEquals(RegisterResult.Invalid, QrRegistry.registerScanned(emptyList(), "   \n\t", "Code 1"))
        assertEquals(RegisterResult.Invalid, QrRegistry.registerScanned(emptyList(), null, "Code 1"))
        assertEquals(RegisterResult.Invalid,
            QrRegistry.registerScanned(emptyList(), "x".repeat(QrCodes.MAX_PAYLOAD_LENGTH + 1), "Code 1"))
        assertTrue(QrRegistry.registerScanned(emptyList(), "x".repeat(QrCodes.MAX_PAYLOAD_LENGTH), "Code 1")
            is RegisterResult.Registered)
    }

    @Test
    fun sameCodeCannotBeRegisteredTwiceAndMaxIsThree() {
        val one = registered(sticker)
        assertEquals(RegisterResult.Duplicate("Code 1"), QrRegistry.registerScanned(one, "$sticker\n", "Code 2"))
        val three = registered("A", "B", "C")
        assertEquals(RegisterResult.Full, QrRegistry.registerScanned(three, "D", "Code 4"))
    }

    @Test
    fun replacementKeepsSlotAndNameAndOldCodeStopsMatching() {
        val codes = registered("old-sticker", "other").let { QrRegistry.rename(it, it[0].id, "Bathroom") }
        val target = codes[0]
        val r = QrRegistry.replaceScanned(codes, target.id, "new-sticker") as RegisterResult.Registered
        assertEquals(target.id, r.codes[0].id)
        assertEquals(target.label, r.codes[0].label)
        assertEquals("new-sticker", r.codes[0].payload)
        assertNull(QrRegistry.match(r.codes, "old-sticker"))
    }

    @Test
    fun failedReplacementChangesNothing() {
        val codes = registered("old-sticker", "other")
        assertEquals(RegisterResult.Invalid, QrRegistry.replaceScanned(codes, codes[0].id, "  "))
        assertEquals(RegisterResult.Duplicate("Code 2"), QrRegistry.replaceScanned(codes, codes[0].id, "other"))
        assertEquals(RegisterResult.Mismatch, QrRegistry.replaceScanned(codes, "nope", "new"))
        // Caller keeps `codes` unchanged on any non-Registered result.
        assertEquals("old-sticker", codes[0].payload)
    }

    @Test
    fun matchingUsesThePayloadNotTheImage() {
        val codes = registered(sticker)
        assertFalse(QrCodes.matches("https://example.com/my-bathroom-sticker2", codes[0].payload))
        assertFalse(QrCodes.matches("HTTPS://EXAMPLE.COM/MY-BATHROOM-STICKER", codes[0].payload))
        assertFalse(QrCodes.matches("", codes[0].payload))
        assertFalse(QrCodes.matches(null, codes[0].payload))
        assertNull(QrRegistry.match(codes, "random unregistered text"))
    }
}

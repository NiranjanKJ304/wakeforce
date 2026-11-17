package com.wakeforce.app.qr

import com.wakeforce.app.data.RegisteredQr
import com.wakeforce.app.qr.QrRegistry.RegisterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrRegistryTest {

    private fun register(codes: List<RegisteredQr>, label: String): List<RegisteredQr> {
        val pending = QrRegistry.newPending(codes, label)
        return (QrRegistry.confirm(codes, pending, pending.payload) as RegisterResult.Registered).codes
    }

    @Test
    fun registerTwoCodes() {
        val codes = register(register(emptyList(), "Code 1"), "Code 2")
        assertEquals(listOf("Code 1", "Code 2"), codes.map { it.label })
        assertTrue(QrRegistry.isValidForAlarm(codes))
        assertNotEquals(codes[0].payload, codes[1].payload)
    }

    @Test
    fun registerThreeCodesButNotFour() {
        val three = register(register(register(emptyList(), "A"), "B"), "C")
        assertTrue(QrRegistry.isValidForAlarm(three))
        val fourth = QrRegistry.newPending(three, "D")
        assertEquals(RegisterResult.Full, QrRegistry.confirm(three, fourth, fourth.payload))
    }

    @Test
    fun oneCodeIsEnoughButNoneIsNot() {
        // Requirement change (functional audit): QR mode needs the registered QR, i.e. 1-3 codes (was 2-3).
        assertTrue(QrRegistry.isValidForAlarm(register(emptyList(), "A")))
        assertFalse(QrRegistry.isValidForAlarm(emptyList()))
    }

    @Test
    fun scanningAnAlreadyRegisteredCodeIsRejectedAsDuplicate() {
        val one = register(emptyList(), "Bathroom")
        val pending = QrRegistry.newPending(one, "Code 2")
        val result = QrRegistry.confirm(one, pending, one[0].payload)
        assertEquals(RegisterResult.Duplicate("Bathroom"), result)
    }

    @Test
    fun duplicatePayloadsAreNeverValid() {
        val a = RegisteredQr("a", "A", "WAKEFORCE1:same")
        assertFalse(QrRegistry.isValidForAlarm(listOf(a, a.copy(id = "b"))))
    }

    @Test
    fun anyOtherCodeIsAMismatch() {
        val pending = QrRegistry.newPending(emptyList(), "Code 1")
        assertEquals(RegisterResult.Mismatch, QrRegistry.confirm(emptyList(), pending, QrCodes.newPayload()))
        assertEquals(RegisterResult.Mismatch, QrRegistry.confirm(emptyList(), pending, "hello"))
    }

    @Test
    fun replaceKeepsSlotAndLabelButInvalidatesOldCode() {
        val codes = register(register(emptyList(), "Bathroom"), "Kitchen")
        val replacement = QrRegistry.newPending(codes, "Kitchen")
        val result = QrRegistry.confirm(codes, replacement, replacement.payload, replaceId = codes[1].id)
        val updated = (result as RegisterResult.Registered).codes
        assertEquals(codes[1].id, updated[1].id)
        assertEquals("Kitchen", updated[1].label)
        assertEquals(replacement.payload, updated[1].payload)
        assertNull(QrRegistry.match(updated, codes[1].payload)) // old printout no longer works
    }

    @Test
    fun renameAndRemove() {
        val codes = register(register(emptyList(), "A"), "B")
        val renamed = QrRegistry.rename(codes, codes[0].id, "  Front door  ")
        assertEquals("Front door", renamed[0].label)
        assertEquals(codes, QrRegistry.rename(codes, codes[0].id, "   ")) // blank names ignored
        assertEquals(QrRegistry.MAX_LABEL_LENGTH, QrRegistry.rename(codes, codes[0].id, "x".repeat(99))[0].label.length)
        val removed = QrRegistry.remove(codes, codes[0].id)
        assertEquals(listOf("B"), removed.map { it.label })
        assertTrue(QrRegistry.isValidForAlarm(removed))
        val none = QrRegistry.remove(removed, removed[0].id)
        assertFalse(QrRegistry.isValidForAlarm(none)) // saving a QR alarm would now be blocked
    }

    @Test
    fun matchFindsOnlyRegisteredCodes() {
        val codes = register(register(emptyList(), "A"), "B")
        assertEquals(codes[1], QrRegistry.match(codes, " ${codes[1].payload}\n"))
        assertNull(QrRegistry.match(codes, QrCodes.newPayload()))
        assertNull(QrRegistry.match(emptyList(), codes[0].payload))
    }

    @Test
    fun pendingCodesAreUnique() {
        val codes = register(register(emptyList(), "A"), "B")
        repeat(50) {
            val p = QrRegistry.newPending(codes, "C")
            assertTrue(codes.none { it.id == p.id || it.payload == p.payload })
        }
    }
}

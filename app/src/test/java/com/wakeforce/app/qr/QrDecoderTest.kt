package com.wakeforce.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Exercises the real offline scan path: generated code -> camera-like Y plane -> decode -> validate. */
class QrDecoderTest {

    /** Builds a Y plane like CameraX delivers: optional row padding, last row unpadded. */
    private fun luminancePlane(payload: String, size: Int, padding: Int): Triple<ByteBuffer, Int, Int> {
        val m = QrCodes.encodeMatrix(payload, size)
        val stride = m.width + padding
        val bytes = ByteArray(stride * (m.height - 1) + m.width) { 0x7F }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            bytes[y * stride + x] = if (m[x, y]) 0x10 else 0xF0.toByte()
        }
        return Triple(ByteBuffer.wrap(bytes), m.width, stride)
    }

    @Test
    fun decodesPackedPlane() {
        val payload = QrCodes.newPayload()
        val (buf, w, stride) = luminancePlane(payload, 400, padding = 0)
        assertEquals(payload, QrDecoder().decode(buf, w, w, stride))
    }

    @Test
    fun decodesPaddedRowStride() {
        val payload = QrCodes.newPayload()
        val (buf, w, stride) = luminancePlane(payload, 360, padding = 64)
        assertEquals(payload, QrDecoder().decode(buf, w, w, stride))
    }

    @Test
    fun decoderIsReusableAcrossFrames() {
        val decoder = QrDecoder()
        val a = QrCodes.newPayload()
        val b = QrCodes.newPayload()
        val (bufA, wA, sA) = luminancePlane(a, 400, 0)
        val (bufB, wB, sB) = luminancePlane(b, 300, 32)
        assertEquals(a, decoder.decode(bufA, wA, wA, sA))
        assertEquals(b, decoder.decode(bufB, wB, wB, sB))
        assertEquals(a, decoder.decode(bufA, wA, wA, sA))
    }

    @Test
    fun blankFrameDecodesToNothing() {
        val w = 320
        assertNull(QrDecoder().decode(ByteBuffer.wrap(ByteArray(w * w) { 0x60 }), w, w, w))
    }

    @Test
    fun scannedCodeOnlyDismissesWhenItIsTheRegisteredOne() {
        val registered = QrCodes.newPayload()
        val other = QrCodes.newPayload()
        val (buf, w, stride) = luminancePlane(other, 400, 0)
        val scanned = QrDecoder().decode(buf, w, w, stride)
        assertEquals(other, scanned)
        assertFalse(QrCodes.matches(scanned, registered))
        assertTrue(QrCodes.isWakeForceCode(scanned)) // -> "old WakeForce code" message
    }
}

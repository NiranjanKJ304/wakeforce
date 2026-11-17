package com.wakeforce.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * QR payload helpers. A registered code is either an existing physical QR (any readable
 * content) or a code the app generated (random 128-bit token). Validation is a local,
 * constant-time comparison of the *payload text*; no network is involved.
 */
object QrCodes {
    private const val PREFIX = "WAKEFORCE1:"

    /** Longest payload accepted for registration (real-world QR content is far shorter). */
    const val MAX_PAYLOAD_LENGTH = 2048

    fun newPayload(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isWakeForceCode(text: String?): Boolean = text?.trim()?.startsWith(PREFIX) == true

    /**
     * Canonical form used for storing *and* comparing: surrounding whitespace removed (camera
     * decoders and printed codes often add a trailing newline). Null if empty or too long.
     */
    fun normalize(payload: String?): String? =
        payload?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_PAYLOAD_LENGTH }

    fun matches(scanned: String?, registered: String?): Boolean {
        val a = normalize(scanned) ?: return false
        val b = normalize(registered) ?: return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    fun encodeMatrix(payload: String, sizePx: Int): BitMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, // tolerates a scuffed printout
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** Black-on-white QR bitmap with a quiet zone, suitable for display, saving and printing. */
    fun render(payload: String, sizePx: Int = 768): Bitmap {
        val matrix = encodeMatrix(payload, sizePx)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return createBitmap(w, h).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}

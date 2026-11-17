package com.wakeforce.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/**
 * Decodes a QR code from a camera luminance (Y) plane. Pure JVM code, unit-tested with
 * padded row strides like real camera buffers. Not thread-safe: use one instance per thread.
 */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    private var luminance = ByteArray(0)

    /** Returns the decoded text, or null if no QR code is readable in this frame. */
    fun decode(plane: ByteBuffer, width: Int, height: Int, rowStride: Int): String? {
        val buffer = plane.duplicate().apply { rewind() }
        val size = width * height
        if (luminance.size != size) luminance = ByteArray(size)
        if (rowStride == width && buffer.remaining() >= size) {
            buffer.get(luminance, 0, size)
        } else {
            // Rows are padded (rowStride > width); the last row may be shorter than rowStride.
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(luminance, row * width, width)
            }
        }
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}

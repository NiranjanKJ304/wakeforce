package com.wakeforce.app.qr

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Offline QR decoder for CameraX frames. Uses only the Y (luminance) plane, decodes at
 * most ~7 frames/second to keep CPU and battery use low, and debounces repeated results.
 * QR detection is rotation-invariant, so frames are not rotated.
 */
class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val decoder = QrDecoder()
    private var lastFrameAt = 0L
    private var lastText: String? = null
    private var lastReportAt = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < MIN_FRAME_INTERVAL_MS) return
            lastFrameAt = now
            val plane = it.planes[0]
            val text = decoder.decode(plane.buffer, it.width, it.height, plane.rowStride) ?: return
            if (text != lastText || now - lastReportAt > REPEAT_REPORT_MS) {
                lastText = text
                lastReportAt = now
                onDecoded(text)
            }
        }
    }

    private companion object {
        const val MIN_FRAME_INTERVAL_MS = 140L
        const val REPEAT_REPORT_MS = 1_500L
    }
}

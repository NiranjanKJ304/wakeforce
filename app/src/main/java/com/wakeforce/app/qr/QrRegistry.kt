package com.wakeforce.app.qr

import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.RegisteredQr
import java.security.SecureRandom

/**
 * Pure rules for an alarm's 1-3 registered QR codes. A code is registered either by scanning
 * an existing physical QR (its payload is stored) or by generating one in the app (random
 * 128-bit payload) and scanning the printed copy back. Matching is local and constant-time
 * on the normalized payload.
 */
object QrRegistry {

    private val random = SecureRandom()

    sealed interface RegisterResult {
        data class Registered(val codes: List<RegisteredQr>) : RegisterResult
        /** The scanned code is a different code from the one being registered. */
        data object Mismatch : RegisterResult
        /** The scanned code is already registered for this alarm (label of the existing one). */
        data class Duplicate(val existingLabel: String) : RegisterResult
        data object Full : RegisterResult
        /** Empty, whitespace-only or oversized content. */
        data object Invalid : RegisterResult
    }

    /**
     * Registers an existing physical QR code by storing its scanned payload (normalized).
     * Rejects empty/oversized content, a code already registered to this alarm, and a 4th code.
     */
    fun registerScanned(codes: List<RegisteredQr>, scanned: String?, label: String): RegisterResult {
        val payload = QrCodes.normalize(scanned) ?: return RegisterResult.Invalid
        match(codes, payload)?.let { return RegisterResult.Duplicate(it.label) }
        if (codes.size >= Alarm.MAX_QR_CODES) return RegisterResult.Full
        var id: String
        do id = newId() while (codes.any { it.id == id })
        return RegisterResult.Registered(codes + RegisteredQr(id, label, payload))
    }

    fun newId(): String = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** A new code waiting to be printed and scanned. Never collides with [existing]. */
    fun newPending(existing: List<RegisteredQr>, label: String): RegisteredQr {
        var code: RegisteredQr
        do {
            code = RegisteredQr(newId(), label, QrCodes.newPayload())
        } while (existing.any { it.id == code.id || it.payload == code.payload })
        return code
    }

    /**
     * Registers [pending] (or replaces the code [replaceId] with it) after the user scanned
     * [scanned]. Rejects a code that is already registered and anything that isn't [pending].
     */
    fun confirm(
        codes: List<RegisteredQr>,
        pending: RegisteredQr,
        scanned: String,
        replaceId: String? = null,
    ): RegisterResult {
        val others = codes.filter { it.id != replaceId }
        match(others, scanned)?.let { return RegisterResult.Duplicate(it.label) }
        if (!QrCodes.matches(scanned, pending.payload)) return RegisterResult.Mismatch
        if (others.any { it.payload == pending.payload }) return RegisterResult.Duplicate(pending.label)
        if (replaceId == null) {
            if (codes.size >= Alarm.MAX_QR_CODES) return RegisterResult.Full
            return RegisterResult.Registered(codes + pending)
        }
        val index = codes.indexOfFirst { it.id == replaceId }
        if (index < 0) return RegisterResult.Mismatch
        return RegisterResult.Registered(
            codes.toMutableList().also { it[index] = pending.copy(id = replaceId, label = codes[index].label) },
        )
    }

    /**
     * Replaces code [replaceId] with a newly scanned physical code, keeping its slot and name.
     * Nothing changes unless the scan is valid, so cancelling midway can't lose the old code.
     */
    fun replaceScanned(codes: List<RegisteredQr>, replaceId: String, scanned: String?): RegisterResult {
        val index = codes.indexOfFirst { it.id == replaceId }
        if (index < 0) return RegisterResult.Mismatch
        val payload = QrCodes.normalize(scanned) ?: return RegisterResult.Invalid
        match(codes, payload)?.let { return RegisterResult.Duplicate(it.label) }
        return RegisterResult.Registered(codes.toMutableList().also { it[index] = codes[index].copy(payload = payload) })
    }

    fun rename(codes: List<RegisteredQr>, id: String, label: String): List<RegisteredQr> {
        val clean = label.trim().take(MAX_LABEL_LENGTH)
        if (clean.isEmpty()) return codes
        return codes.map { if (it.id == id) it.copy(label = clean) else it }
    }

    /** Removing is always allowed while editing; saving requires [isValidForAlarm]. */
    fun remove(codes: List<RegisteredQr>, id: String): List<RegisteredQr> = codes.filterNot { it.id == id }

    fun isValidForAlarm(codes: List<RegisteredQr>): Boolean =
        codes.size in Alarm.MIN_QR_CODES..Alarm.MAX_QR_CODES &&
            codes.all { QrCodes.normalize(it.payload) != null } &&
            codes.mapNotNull { QrCodes.normalize(it.payload) }.toSet().size == codes.size &&
            codes.map { it.id }.toSet().size == codes.size

    /** The registered code equal to [scanned], or null. Checks every code (constant-time each). */
    fun match(codes: List<RegisteredQr>, scanned: String): RegisteredQr? {
        var found: RegisteredQr? = null
        for (code in codes) if (QrCodes.matches(scanned, code.payload) && found == null) found = code
        return found
    }

    const val MAX_LABEL_LENGTH = 24
}

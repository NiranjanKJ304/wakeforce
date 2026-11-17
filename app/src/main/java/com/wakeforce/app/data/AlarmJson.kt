package com.wakeforce.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/**
 * Minimal codec built on org.json (part of Android) so we need neither a database
 * nor a serialization library for a handful of alarms. Every new field has a default,
 * so data written by older versions decodes unchanged.
 */
internal object AlarmJson {

    fun encode(alarms: List<Alarm>): String {
        val array = JSONArray()
        for (a in alarms) {
            val days = JSONArray()
            a.repeatDays.sorted().forEach { days.put(it.value) }
            array.put(
                JSONObject()
                    .put("id", a.id)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("enabled", a.enabled)
                    .put("days", days)
                    .put("vibrate", a.vibrate)
                    .put("volume", a.volumePercent)
                    .put("mode", a.mode.name)
                    .put("qr", encodeQr(a.qrCodes))
                    .put("preAlarm", a.preAlarmEnabled)
                    .put("preAlarmMin", a.preAlarmMinutes)
                    .put("tempAccess", a.tempAccessEnabled)
                    .put("tempAccessVol", a.tempAccessVolumePercent)
                    .apply {
                        a.soundUri?.let { put("sound", it) }
                        a.armedAt?.let { put("armedAt", it) }
                        a.occurrence?.let { put("occurrence", it) }
                        a.skippedOccurrence?.let { put("skipped", it) }
                        a.confirmedOccurrence?.let { put("confirmed", it) }
                        a.lastCompletedOccurrence?.let { put("completed", it) }
                    },
            )
        }
        return array.toString()
    }

    /** Never throws: corrupt entries are skipped rather than losing every alarm. */
    fun decode(json: String?): List<Alarm> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val result = ArrayList<Alarm>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            try {
                val days = o.optJSONArray("days")
                val repeatDays = buildSet {
                    if (days != null) {
                        for (d in 0 until days.length()) {
                            val v = days.optInt(d)
                            if (v in 1..7) add(DayOfWeek.of(v))
                        }
                    }
                }
                val preMinutes = o.optInt("preAlarmMin", 30)
                val qrCodes = decodeQr(o.optJSONArray("qr"))
                // v1 stored QR/MATH; 2.0 stored no mode (every alarm needed math + QR).
                val mode = ChallengeMode.entries.firstOrNull { it.name == o.optString("mode") }
                    ?: if (qrCodes.isNotEmpty()) ChallengeMode.MATH_AND_QR else ChallengeMode.MATH
                result += Alarm(
                    id = o.getInt("id"),
                    hour = o.getInt("hour").coerceIn(0, 23),
                    minute = o.getInt("minute").coerceIn(0, 59),
                    enabled = o.optBoolean("enabled", true),
                    repeatDays = repeatDays,
                    vibrate = o.optBoolean("vibrate", true),
                    volumePercent = o.optInt("volume", 100).coerceIn(10, 100),
                    soundUri = o.optStringOrNull("sound"),
                    mode = mode,
                    qrCodes = qrCodes,
                    preAlarmEnabled = o.optBoolean("preAlarm", false),
                    preAlarmMinutes = if (preMinutes in Alarm.PRE_ALARM_OPTIONS) preMinutes else 30,
                    tempAccessEnabled = o.optBoolean("tempAccess", true),
                    tempAccessVolumePercent = o.optInt("tempAccessVol", 0).coerceIn(0, Alarm.MAX_TEMP_ACCESS_VOLUME),
                    armedAt = if (o.has("armedAt")) o.getLong("armedAt") else null,
                    occurrence = o.optStringOrNull("occurrence"),
                    skippedOccurrence = o.optStringOrNull("skipped"),
                    confirmedOccurrence = o.optStringOrNull("confirmed"),
                    lastCompletedOccurrence = o.optStringOrNull("completed"),
                )
            } catch (e: Exception) {
                // Skip malformed entry.
            }
        }
        return result
    }

    fun encodeQr(codes: List<RegisteredQr>): JSONArray = JSONArray().apply {
        codes.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("payload", it.payload)) }
    }

    /** Drops malformed and duplicate entries and keeps at most [Alarm.MAX_QR_CODES]. */
    fun decodeQr(array: JSONArray?): List<RegisteredQr> {
        if (array == null) return emptyList()
        val out = ArrayList<RegisteredQr>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optStringOrNull("id") ?: continue
            val payload = o.optStringOrNull("payload") ?: continue
            if (out.any { it.id == id || it.payload == payload }) continue
            out += RegisteredQr(id, o.optStringOrNull("label") ?: "Code ${out.size + 1}", payload)
            if (out.size == Alarm.MAX_QR_CODES) break
        }
        return out
    }

    fun encodeQrString(codes: List<RegisteredQr>): String = encodeQr(codes).toString()

    fun decodeQrString(json: String?): List<RegisteredQr> =
        if (json.isNullOrBlank()) emptyList() else runCatching { decodeQr(JSONArray(json)) }.getOrDefault(emptyList())

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotBlank() } else null
}

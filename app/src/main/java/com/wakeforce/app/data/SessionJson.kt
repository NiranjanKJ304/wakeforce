package com.wakeforce.app.data

import com.wakeforce.app.alarm.AlarmSession
import com.wakeforce.app.alarm.QueuedFire
import com.wakeforce.app.alarm.SessionSound
import com.wakeforce.app.alarm.SessionState
import com.wakeforce.app.alarm.WakeStats
import com.wakeforce.app.math.AdditionQuestion
import com.wakeforce.app.math.Difficulty
import com.wakeforce.app.math.MathSession
import com.wakeforce.app.math.MathSettings
import org.json.JSONArray
import org.json.JSONObject

/** org.json codec for the persisted ringing session and wake-up statistics. Never throws on decode. */
internal object SessionJson {

    fun encode(s: AlarmSession): String = JSONObject()
        .put("alarmId", s.alarmId)
        .put("occurrence", s.occurrence)
        .put("state", s.state.name)
        .put("startedAt", s.startedAt)
        .put("mode", s.mode.name)
        .put("math", JSONObject()
            .put("count", s.math.settings.questionCount)
            .put("min", s.math.settings.minValue)
            .put("max", s.math.settings.maxValue)
            .put("difficulty", s.math.settings.difficulty.name)
            .put("index", s.math.index)
            .put("questions", JSONArray().apply {
                s.math.questions.forEach { q -> put(JSONArray().apply { q.operands.forEach(::put) }) }
            }))
        .put("qr", AlarmJson.encodeQr(s.qrCodes))
        .put("scanned", JSONArray().apply { s.scannedQrIds.forEach(::put) })
        .put("accessUsed", s.accessWindowsUsed)
        .put("sound", JSONObject()
            .put("volume", s.sound.volumePercent)
            .put("vibrate", s.sound.vibrate)
            .put("tempAccess", s.sound.tempAccessEnabled)
            .put("tempAccessVol", s.sound.tempAccessVolumePercent)
            .apply { s.sound.soundUri?.let { put("uri", it) } })
        .put("queued", JSONArray().apply {
            s.queued.forEach { put(JSONObject().put("alarmId", it.alarmId).put("occurrence", it.occurrence)) }
        })
        .apply {
            s.accessEndsAt?.let { put("accessEndsAt", it) }
            s.resumeState?.let { put("resumeState", it.name) }
            s.completedAt?.let { put("completedAt", it) }
        }
        .toString()

    fun decode(json: String?): AlarmSession? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val m = o.getJSONObject("math")
            val questions = m.getJSONArray("questions").let { arr ->
                (0 until arr.length()).map { i ->
                    val ops = arr.getJSONArray(i)
                    AdditionQuestion((0 until ops.length()).map { ops.getInt(it) })
                }
            }
            val settings = MathSettings(
                m.getInt("count"), m.getInt("min"), m.getInt("max"),
                Difficulty.entries.firstOrNull { it.name == m.optString("difficulty") } ?: Difficulty.EASY,
            ).sanitized()
            val snd = o.getJSONObject("sound")
            val scanned = o.getJSONArray("scanned").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val queued = o.optJSONArray("queued")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { QueuedFire(it.getInt("alarmId"), it.getString("occurrence")) }
                }
            }.orEmpty()
            val qrCodes = AlarmJson.decodeQr(o.getJSONArray("qr"))
            AlarmSession(
                alarmId = o.getInt("alarmId"),
                occurrence = o.getString("occurrence"),
                state = SessionState.valueOf(o.getString("state")),
                startedAt = o.getLong("startedAt"),
                // Sessions saved by 2.0 have no mode: they were math + QR (or math only without codes).
                mode = ChallengeMode.entries.firstOrNull { it.name == o.optString("mode") }
                    ?: if (qrCodes.isEmpty()) ChallengeMode.MATH else ChallengeMode.MATH_AND_QR,
                math = MathSession(settings, questions, m.getInt("index").coerceIn(0, questions.size)),
                qrCodes = qrCodes,
                scannedQrIds = scanned,
                accessEndsAt = if (o.has("accessEndsAt")) o.getLong("accessEndsAt") else null,
                accessWindowsUsed = o.optInt("accessUsed", 0),
                resumeState = o.optString("resumeState").takeIf { it.isNotEmpty() }?.let { SessionState.valueOf(it) },
                sound = SessionSound(
                    snd.optInt("volume", 100),
                    if (snd.has("uri")) snd.getString("uri") else null,
                    snd.optBoolean("vibrate", true),
                    snd.optBoolean("tempAccess", true),
                    snd.optInt("tempAccessVol", 0),
                ),
                queued = queued,
                completedAt = if (o.has("completedAt")) o.getLong("completedAt") else null,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun encodeStats(s: WakeStats): String = JSONObject()
        .put("count", s.completedCount)
        .apply {
            s.lastDismissedAt?.let { put("last", it) }
            s.lastDurationMs?.let { put("duration", it) }
        }.toString()

    fun decodeStats(json: String?): WakeStats {
        if (json.isNullOrBlank()) return WakeStats()
        return runCatching {
            val o = JSONObject(json)
            WakeStats(
                o.optInt("count", 0),
                if (o.has("last")) o.getLong("last") else null,
                if (o.has("duration")) o.getLong("duration") else null,
            )
        }.getOrDefault(WakeStats())
    }
}

# WakeForce

A lightweight, offline alarm clock for Android 8.0+ (API 26, targets API 37).
To stop an alarm you must complete **the challenge chosen for that alarm**: scan its registered QR code,
or solve addition questions (or, if you pick the stricter option, both).

No internet permission, no accounts, no analytics, no backups. Data stays on the device.

## Features

| Feature | Behavior |
|---|---|
| Alarms | Time, repeat days, on/off, tone, volume, vibration. Duplicates (same time and days) are blocked. |
| Challenge per alarm | **QR scan**: scan the alarm's registered QR code(s). **Math addition**: answer every question. **Math + QR**: an optional stricter mode that needs both, in that order. The alarm keeps ringing until the selected challenge is complete. No dismiss, swipe or snooze. |
| QR codes | 1–3 per alarm. **Scan any QR code you own** (a sticker, a printed code) to register it; its content is stored on the phone. No QR code? Generate one and print it. All registered codes must be scanned. Codes can be renamed, replaced and removed. Unknown, empty and other alarms' codes are rejected. |
| Math | Addition only. **Easy**: 2 two-digit numbers. **Medium**: 2–3 numbers from your min–max range. **Hard**: 3 two-digit numbers. 1–10 questions; new questions every time; consecutive answers always differ. |
| Pre-alarm confirmation | Optional, 10 min to 2 h before the alarm: "Do you need your 5:00 AM alarm?" **YES** keeps it. **NO** skips only that occurrence (later days still ring; a one-time alarm is switched off but kept). **No answer = the alarm rings as planned.** |
| Temporary access | While ringing: 5 minutes of normal phone use, up to 2 times per alarm. The alarm drops to a configured level (0–50 %, 0 = muted) and vibration stops; progress is kept. Full alarm resumes automatically, or earlier if you return. |
| Wake-up stats | Successful dismissals are recorded (count, last time, how long it took) and shown on the success and home screens. |

## Build

Requirements: JDK 17+ and the Android SDK (platform 37). Set `sdk.dir` in `local.properties`.

```
gradlew testDebugUnitTest      # 167 JVM + Robolectric tests
gradlew assembleDebug          # <build>/outputs/apk/debug/app-debug.apk
gradlew assembleRelease        # minified, ~2.4 MB: <build>/outputs/apk/release/app-release.apk
```

`<build>` is `app/build` by default. In a cloud-synced folder (OneDrive locks files and breaks
Gradle), add `wakeforce.buildRoot=C\:/path/outside/sync` to `local.properties`; output then goes
to `<buildRoot>/app`. The release build is signed with the local **debug key** so it can be
sideloaded; configure your own keystore before distributing it.

## How it works

| Concern | Approach |
|---|---|
| Authority | `AlarmController` is the only code that changes alarm state, AlarmManager registrations or ringing sessions. Everything runs behind one process-wide lock and re-reads storage. Pure decision logic is in `AlarmPlanner` and `SessionMachine` (unit-tested). |
| Occurrences | Each scheduled ring has an id `<alarmId>@<local date-time>` (e.g. `5@2026-11-16T06:30`). Pre-alarm answers, skips and sessions apply only to that occurrence, so an old prompt or broadcast can't affect a later alarm. Local time keeps the id stable across time-zone and DST changes. |
| Session state machine | QR mode: `RINGING_QR → COMPLETED`. Math mode: `RINGING_MATH → COMPLETED`. Math + QR: `RINGING_MATH → RINGING_QR → COMPLETED`. `TEMPORARY_ACCESS` can sit in between (and `MISSED` and `RECOVERING` for interruptions). Illegal transitions are rejected (e.g. COMPLETED never rings again; access can never complete a session). Occurrence states `SCHEDULED`, `PRE_ALARM_PENDING`, `PRE_ALARM_CONFIRMED` and `PRE_ALARM_SKIPPED` are derived from stored data. |
| Persistence | The session (progress, scanned codes, access end time, snapshot of the alarm's codes and sound) is stored in DataStore, so it survives the screen closing, process death and reboot. Editing or deleting an alarm while it rings doesn't change its challenge. |
| Idle cost | Between events nothing runs: no service, no polling, no WorkManager, no wake locks. Every callback is a one-shot AlarmManager registration: main ring (`setAlarmClock`), pre-alarm (`setExactAndAllowWhileIdle`), access end and post-reboot recovery (`setAlarmClock`). |
| Ringing | `AlarmService` is a foreground service (`mediaPlayback`) that exists only while a session is active. It follows the stored session: full alarm, reduced level during access, stop on completion. Camera, audio, vibration and wake lock are released immediately afterwards. |
| Recovery | `BootReceiver` re-arms alarms on boot (before first unlock too), app update, `TIME_SET`, `TIMEZONE_CHANGED` and exact-alarm permission changes. An alarm missed during a ≤30-min reboot rings right after. A session interrupted by a reboot resumes with its progress if it started within 2 h; otherwise it's marked MISSED. |
| Migration | v1 data is migrated once, atomically (`SchemaMigration`). No alarm is lost. A v1 QR-mode alarm stays in QR mode with the old printed code as "Code 1". |

## Android limitations (by design, not bugs)

- **Force-stop and power-off.** Android cancels an app's alarms when it is force-stopped, and nothing runs while the phone is off. A force-stop from Settings also stops a ringing alarm. WakeForce doesn't use device-admin, Accessibility or any other way to stop you controlling your own phone.
- **Volume.** WakeForce sets the alarm stream volume while ringing and restores it afterwards, but the user can still change or mute it with system controls. That isn't blocked.
- **Full-screen screens are not guaranteed.** Android 14+ requires the "full-screen notifications" permission. When the phone is unlocked and in use, Android shows a heads-up notification instead of taking over the screen. This applies to the alarm, the pre-alarm and the return from temporary access. Tap the notification to open the screen.
- **Pre-alarm timing.** It uses an exact-while-idle alarm, which Android may delay slightly in deep Doze. The main alarm itself uses `setAlarmClock` and is not delayed.
- **QR codes are matched by content.** Any QR code you register works, but a common one (for example a product label you own several of) is easier to get around than a unique one. For a unique code, use the app's generate-and-print option. A code shown on the same phone can't be scanned, so a generated code must be printed or shown on another screen.
- **No QR backup.** In QR mode the registered code must be scanned. If the camera fails or its permission is revoked, use temporary access to fix it in Settings; otherwise the only way out is Android's force-stop. A QR alarm with no registered code (old data only) and the rare case where app storage can't be read fall back to math, so an alarm can always be stopped.
- **Manufacturer battery savers** (Xiaomi, Huawei, OnePlus, Samsung and others) may still interfere. Set WakeForce's battery usage to *Unrestricted*; the home screen links to the setting.

## Permissions

`CAMERA` (asked only when scanning), `POST_NOTIFICATIONS`, `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` (API ≤32),
`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `USE_FULL_SCREEN_INTENT`,
`WAKE_LOCK`, `VIBRATE`. Nothing else: no internet, location, contacts, microphone, accessibility or device admin.
The home screen lists any special permission you still need to enable by hand.

See [HOW_IT_WORKS.md](HOW_IT_WORKS.md) for a walkthrough of how the app works, and
[AUDIT.md](AUDIT.md) for the reliability audits and the physical-device test checklist.

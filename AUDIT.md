# WakeForce audits

- [Functional audit: pre-alarm, QR scanner, math alarm (2026-11-17)](#functional-audit-pre-alarm-qr-scanner-math-alarm-2026-11-17)
- [2.0 review: pre-alarm, multi-QR, dual challenge, temporary access (2026-11-16)](#wakeforce-20-review-2026-11-16)
- [1.0 post-build audit (2026-11-06)](#wakeforce-post-build-audit-2026-11-06)

## Functional audit: pre-alarm, QR scanner, math alarm (2026-11-17)

**Method:** each requirement was traced from the UI through the controller, storage and AlarmManager to the ringing service and dismissal. Every fix was then pinned with automated tests, and the tests were mutation-checked.
**Not done:** testing on a physical device or emulator (none connected). Nothing below claims verified device behavior.

### A. Functionality status

| # | Requirement | Before | Now |
|---|---|---|---|
| 1 | Pre-alarm at the right time (5:00 alarm, 1 h → 4:00) | WORKING | WORKING (tested) |
| 1 | YES keeps the alarm; NO skips one occurrence; no answer = rings | WORKING | WORKING (tested) |
| 1 | One-time alarm NO, then switched back on | PARTIALLY WORKING | **FIXED** (bug 5) |
| 1 | No duplicate prompts; editing the time/interval moves the prompt | PARTIALLY WORKING | **FIXED** (bugs 6, 7) |
| 1 | Reboot/process recovery keeps the confirmation state | PARTIALLY WORKING | **FIXED** (bug 8) |
| 1 | Prompt text "Do you need your 5:00 AM alarm?" | PARTIALLY WORKING | **FIXED** |
| 2 | One selected challenge per alarm (QR **or** Math), saved per alarm | NOT WORKING | **FIXED** (bug 1) |
| 2 | No dismiss, swipe-to-dismiss or snooze button | WORKING | WORKING |
| 3 | Register by scanning an existing physical QR | NOT WORKING | **FIXED** (bug 2) |
| 3 | Reject random, other-alarm, empty and unreadable codes; match on payload | PARTIALLY WORKING | **FIXED** (bug 3) |
| 3 | Registration persists across restarts and reboots | WORKING | WORKING (tested) |
| 3 | Camera released after scanning / leaving the scanner | WORKING | WORKING (by code review; device check below) |
| 4 | Easy / Medium / Hard as specified | NOT WORKING | **FIXED** (bug 4) |
| 4 | Previous answer can't be reused as a valid answer | NOT WORKING | **FIXED** (bug 9) |
| 4 | Random, new per session, empty/wrong rejected, progress shown, count configurable, saved | WORKING | WORKING (tested) |
| 4 | Boundaries and overflow | WORKING | WORKING (tested) |
| 5 | Rings until the challenge is done; no 5-minute auto-dismiss | WORKING | WORKING (tested with the real service) |
| 5 | Back / closing the screen / other apps don't dismiss | WORKING | WORKING (by code review; device check below) |
| 5 | Audio stops immediately on success; never plays twice | WORKING | WORKING (tested with the real service) |
| 6 | Occurrence and alarm isolation (edit, delete, skip, reboot, time zone, duplicates, several alarms) | PARTIALLY WORKING | **FIXED** (bug 10) |
| 7 | Battery and memory: no idle service, polling or network; camera and service only when needed | WORKING | WORKING (by code review) |

### B. Bugs found (root causes)

1. **No per-alarm challenge selection.** 2.0 hard-wired math + QR for every alarm. This brief asks for exactly one selected challenge.
2. **Physical QR codes couldn't be registered.** The editor only supported app-generated codes, which had to be printed and scanned back.
3. **Matching normalized only one side.** `QrCodes.matches` trimmed the scanned text but compared it with the stored payload as-is. A physical code whose content ends in a newline (common) would register fine but never match at alarm time.
4. **Difficulty didn't match the spec.** Medium always added 3 numbers (spec: 2 or 3) and Hard added 4 (spec: 3 two-digit numbers).
5. **Re-enabling an alarm kept its old skip.** After NO on a one-time 5:00 alarm, switching it back on at 4:30 armed tomorrow, not today, because `skippedOccurrence` survived.
6. **A stale prompt stayed on screen.** After the alarm time was edited, disabled or deleted, the old prompt remained visible; answering it was safely ignored, but it was confusing.
7. **Prompt registrations could collide.** The reboot re-show and the normal prompt shared one PendingIntent, so a second boot sync could cancel the re-show.
8. **Reboot dropped an unanswered prompt.** Notifications are cleared at boot, and the due prompt was never shown again (the alarm still rang).
9. **A previous answer could pass again.** Consecutive questions could share an answer, and a replacement question could repeat the old or the typed answer.
10. **A second prompt could be lost.** `PreAlarmActivity` is `singleTask` without `onNewIntent`, so a prompt for another alarm arriving while one was open was never shown.

### C. Fixes (files)

| File | Change |
|---|---|
| `data/Alarm.kt`, `data/AlarmJson.kt` | `ChallengeMode` (QR, MATH, MATH_AND_QR) per alarm, persisted. Old data decodes safely: v1 keeps its mode; 2.0 data → Math + QR. QR modes need 1–3 codes. |
| `alarm/SessionMachine.kt`, `data/SessionJson.kt` | The session snapshots the mode. QR starts at the scanner (no math); Math completes after the questions; Math + QR keeps the 2.0 order. A QR alarm with no code falls back to math so it can always be stopped. |
| `qr/QrCodes.kt` | `normalize()` is used for storing and comparing; empty or oversized content is rejected; UTF-8 encoding hint. |
| `qr/QrRegistry.kt` | `registerScanned` (physical code) and `replaceScanned` (swap in place only after a valid scan; cancelling keeps the old code). |
| `math/MathSettings.kt`, `math/AdditionChallenge.kt`, `math/MathSession.kt` | Easy 2×(10–99); Medium 2–3 numbers from the custom range; Hard 3×(10–99). Consecutive answers differ; a replacement avoids the old, typed and neighbouring answers. |
| `alarm/AlarmController.kt`, `alarm/AlarmScheduler.kt` | Re-enable clears skip/confirm; prompts are withdrawn on occurrence change, disable and delete; an unanswered prompt is re-shown once after reboot via its own registration. |
| `service/AudioPolicy.kt`, `service/AlarmService.kt` | Pure audio rule (no time-based stop); the service follows it; test-only counters for playback starts and audio mode. |
| `ui/PreAlarmActivity.kt`, `service/AlarmNotifications.kt` | "Do you need your 5:00 AM alarm?"; challenge text per mode; `onNewIntent`. |
| `ui/screens/EditAlarmScreen.kt` | Mode selector (QR scan / Math addition / Math + QR). Choosing QR with no code opens the camera. Save checks requirements per mode. |
| `ui/screens/QrSetupScreen.kt` | "Scan an existing QR code" (primary) plus "Generate one to print"; save/print only for generated codes. |
| `ui/screens/MathConfigScreen.kt`, `HomeScreen.kt`, `AlarmScreen.kt` | Range fields for Medium only; mode-aware summaries and step labels. |

### D. Test results

| | |
|---|---|
| Previous test count | 124 |
| New test count | **167** (43 added: `MathDifficultyTest` 10, `PhysicalQrTest` 7, `ChallengeModeTest` 10, `FunctionalAuditIntegrationTest` 14, `AlarmServiceLifecycleTest` 2) |
| Passed / failed | **167 / 0** |
| Android lint | No code issues (only "newer Gradle 9.9.1 / Robolectric 4.18 available") |

**Changed previous tests** (requirement changes, not weakening):
- `AdditionChallengeTest`: Medium now asserts 2–3 numbers.
- `QrRegistryTest`: 1 code is now valid, and 0 is still invalid.
- 2.0 session fixtures now set the explicit Math + QR mode they were written for.
- Unbounded helper loops are now bounded, so a logic error fails the test instead of hanging the run.

**Mutation check:** six bugs from this audit were re-injected at once: payload normalization, repeated answers, Hard = 4 numbers, the 5-minute window stopping the alarm, audio restarting on every update, and the skip surviving re-enable. All six were caught (9 failing tests across 6 suites).

The 20 required test groups map to:
- Pre-alarm (1–5): `PreAlarmTest`, `FunctionalAuditIntegrationTest`.
- QR (6–10): `PhysicalQrTest`, `QrRegistryTest`, `ChallengeModeTest`, `FunctionalAuditIntegrationTest`.
- Math (11–15): `MathDifficultyTest`, `AdditionChallengeTest`, `MathSessionTest`.
- Dismissal and audio (16–17): `ChallengeModeTest`, `AlarmServiceLifecycleTest`.
- Recovery, duplicates and isolation (18–20): `FunctionalAuditIntegrationTest`, `SessionIntegrationTest`, `AlarmSchedulingIntegrationTest`.

### E. Remaining device tests

- [ ] 5:00 alarm with a 1 h pre-alarm, phone locked at 4:00: the full-screen prompt reads "Do you need your 5:00 AM alarm?". Repeat with the phone in use (heads-up notification).
- [ ] YES → rings at 5:00. NO (asks to unlock first) → no ring at 5:00; rings tomorrow at 5:00. Ignore → rings at 5:00.
- [ ] Reboot at 4:30 without answering: the prompt reappears about 5 s after boot; the alarm still rings at 5:00.
- [ ] QR mode: register a real sticker or product QR (camera opens when you pick QR). Close the app and reboot: still registered. At ring time a different QR is rejected and the registered one stops the alarm instantly.
- [ ] Camera indicator turns off after registering, after cancelling the scanner, and after a successful dismissal.
- [ ] Math mode Easy / Medium / Hard questions look as specified; wrong and empty answers keep ringing.
- [ ] Ring for 10+ minutes with the screen off: no auto-dismiss at 5 minutes; Back, Home and the recent-apps switcher don't stop it; swiping the notification doesn't stop it.
- [ ] A second alarm's prompt arriving while the first prompt is open replaces it.
- [ ] Overnight battery stats show no WakeForce activity between prompts and alarms.

### F. Final build

Release APK **2,506,194 bytes (2.39 MB)** at `C:\Users\FBS\AppData\Local\WakeForceBuild\app\outputs\apk\release\app-release.apk`. The size matches 2.0 to the byte because APK entries are padded to fixed boundaries; the build was confirmed to contain this round's code. Permissions are unchanged (10; no internet, location, contacts, microphone, accessibility or device admin).

---

## WakeForce 2.0 review (2026-11-16)

**Scope:** extension of the audited 1.0 code base. All 1.0 fixes are kept: the controller lock, storage-level duplicate check, catch-up after reboot, the time-zone extra, degraded ringing, the playback-error fallback and in-process dismissal.
**Not done:** physical-device or emulator testing (none connected). Device behavior below is **unverified**.

### Results

| | 1.0 | 2.0 |
|---|---|---|
| Automated tests | 59 | **124, all passing** (99 JVM + 25 Robolectric) |
| Android lint | No issues | No code issues (only "newer Gradle/Robolectric available"; versions kept on purpose) |
| Release APK | 2,456,078 B | **2,506,194 B (2.39 MB)**, +49 KB |
| New dependencies | | **None** (Robolectric and org.json were already test-only) |
| Release permissions | 10 | **Same 10**, re-verified with `aapt2`; no internet, network, location, contacts, microphone, accessibility or device admin |

**Mutation check:** three bugs were injected into the new logic: a QR code counting twice, the access window being restartable, and stale pre-alarm answers being applied. The first run caught 2 of 3. The stale-answer bug slipped through because the test only used a *past* occurrence. A *future, non-armed* occurrence case was added, and the re-run caught it in both the unit and integration suites.

### What changed

| Area | Implementation |
|---|---|
| Occurrence identity | `Occurrence(key = local date-time, at = instant)`. The key is stored on the alarm (`occurrence`, `skippedOccurrence`, `confirmedOccurrence`, `lastCompletedOccurrence`) and carried in every AlarmManager intent. |
| Pre-alarm | Separate per-alarm registration (`ACTION_PRE_ALARM`, exact-while-idle) at occurrence − N min, armed only if still ahead and not already confirmed. Validated again on delivery (`preAlarmToShow`). The notification times out at alarm time. `PreAlarmActivity` (not exported) asks for unlock before **NO**. `AlarmPlanner.planConfirm` rejects answers for any occurrence other than the armed one, past occurrences, disabled or ringing alarms. |
| Skip | Stores `skippedOccurrence`; `nextOccurrence` jumps over it, so app-start, boot, time-change and catch-up syncs can never re-arm it, and a late broadcast for it is ignored. One-time alarm: switched off, still saved. Editing the schedule clears stale skips and confirmations. |
| Multi-QR | `QrRegistry`: generate → scan-back confirmation → registered. Rejects mismatches, duplicates and a 4th code; rename/replace/remove. Saving requires 2–3 codes (`isValidForAlarm`). Matching is constant-time against the session's snapshot of codes. |
| Dual challenge | `SessionMachine`: QR input is refused until math is solved; COMPLETED only after every code has been scanned once. |
| Temporary access | `startAccess`: only from RINGING_*, max 2 windows, no restart or extension, persisted `accessEndsAt`. One `ACCESS_END` setAlarmClock callback plus a free in-process handler backup. During the window the service pauses or quiets audio, stops vibration and **releases its wake lock**. |
| Session persistence | `active_session` and `wake_stats` in DataStore. The service and alarm screen render from it, so progress survives the Activity, process death (`START_REDELIVER_INTENT` → resume) and reboot (`recoverAfterBoot` → `ACTION_RECOVER`). |
| Concurrency | Same-occurrence repeat → resume; a different alarm while ringing → queued (once) and rung afterwards with its own codes; completion is atomic with stats. All under the controller lock. |
| Migration | DataStore `DataMigration` (schema 1 → 2), atomic and idempotent. A v1 QR alarm keeps the old code as "Code 1"; the global secret is deleted. |

### Behavior changes from 1.0 (by request)
- **Removed:** the QR → math fallbacks (3-minute "lost code" option, and the camera-blocked backup). Both challenges are now always required. The only remaining math-only path is degraded mode when storage can't be read.
- **Legacy alarms** that have fewer than 2 codes keep ringing (math + any existing codes) so an upgrade never silences them. The home screen flags them, and they can't be saved again until 2–3 codes are registered.

### Battery and memory review
- **Idle (no alarm):** unchanged from 1.0: nothing running. Each enabled alarm adds at most one extra AlarmManager registration (its pre-alarm).
- **Pre-alarm:** one wake-up and one notification; no service, no camera.
- **Ringing:** as 1.0, plus one small DataStore write per answer or scan.
- **Temporary access:** the wake lock is released; if muted, the player is paused. The app's only CPU need is the single AlarmManager callback at the end; the countdown screen ticks once per second only while visible.
- **Memory:** session JSON is a few KB. QR thumbnails (384 px) exist only on the QR editor; print/save renders at 1024 px on demand. The camera is bound only while a scanner is on screen and released on dispose (1.0 behavior kept).

### Test inventory (2.0 additions)

| Suite | Tests | Covers |
|---|---|---|
| `PreAlarmTest` | 12 | time calculation for all 8 options and across midnight, 2 h cap, YES keeps, NO skips one, repeating schedule preserved, one-time skip, ignored = rings, stale answers (past, future non-armed, ringing, disabled, already skipped), derived states, time-zone-stable skip |
| `SessionMachineTest` | 21 | math first, neither challenge alone, all codes required, no double count, wrong or other-alarm codes rejected, 5-min window, no input or completion during access, progress kept, no restart/extension/early auto-end, window limit, per-alarm disable, terminal states, recovery age |
| `QrRegistryTest` | 10 | register 2 and 3, no 4th, duplicates, mismatches, replace invalidates the old code, rename/remove, matching, uniqueness |
| `MigrationAndSessionJsonTest` | 5 | v1 → v2 without loss, fresh install, idempotent, session round-trip with progress, corrupt data |
| `SessionIntegrationTest` (Robolectric) | 16 | pre-alarm/main registrations, YES/NO/ignored, stale answers, full completion + stats, repeated broadcasts, persisted progress (fresh controller), unrelated occurrence, edit/delete during a ring, queued second alarm, access timer (single, not reset, early delivery ignored), reboot recovery, stale session → MISSED, time-zone change mid-session, editing another alarm |
| Updated 1.0 suites | | `AlarmPlannerTest` now also asserts occurrence keys (catch-up keeps its original key); `AlarmJsonTest` covers every 2.0 field and sanitizing |

**Process death** is tested by re-reading the persisted session with fresh objects. A real process kill plus `START_REDELIVER_INTENT` needs a device.

### Manual device checklist (2.0)

Do this in addition to the 1.0 checklist below. Use one stock Pixel on Android 14+, one aggressive-OEM phone, and one phone on Android 8 or 9.

**Multi-QR setup**
- [ ] Register 2 codes by printing them (or showing the saved PNG on another screen) and scanning each back. A 3rd is optional.
- [ ] Scanning code 1 while registering code 2 → "Already registered". Scanning a random QR → "different code".
- [ ] Rename, replace (the old printout then fails at ring time) and remove. Save is blocked below 2 codes.

**Dual challenge**
- [ ] The alarm shows math first; scanning a valid code during math does nothing.
- [ ] After math, scan the codes in any order; a repeated code says "Already scanned"; a wrong code is rejected.
- [ ] Sound and vibration stop immediately after the last code, the success screen shows, and the home screen shows "Last wake-up".

**Pre-alarm**
- [ ] Alarm in 15 min with a 10-min confirmation: the prompt appears 10 min before, full-screen when locked and heads-up when in use.
- [ ] YES → toast; the alarm rings on time. NO on a locked phone → asks to unlock → skipped; the next day is still scheduled (`adb shell dumpsys alarm`).
- [ ] Ignore the prompt: the alarm rings on time and the prompt disappears at alarm time.
- [ ] One-time alarm + NO → shown as off but still in the list.

**Temporary access**
- [ ] Start it during math: the screen closes, the alarm is muted or quiet, vibration stops, and the phone is usable (the lock screen is unchanged).
- [ ] Reopen WakeForce during the window: no redirect to the challenge and the countdown is not reset; the notification shows the resume time.
- [ ] At 5:00 the alarm resumes at full volume and the challenge returns, with math progress or scanned codes kept. Also test with the screen off for the whole window (wake-up by AlarmManager).
- [ ] A 3rd request is refused; "Return to the challenge now" ends the window early.

**Recovery**
- [ ] Power off mid-ring, power on within 2 h without unlocking: the alarm resumes about 5 s after boot with its progress.
- [ ] Kill the process (`adb shell am kill com.wakeforce.app` while ringing, or via developer options): ringing resumes and no second session starts.
- [ ] Upgrade a phone running 1.0 with a QR alarm: the alarm is kept, shows "Math + 1 QR", the old printed code still works, and the alarm is flagged to add one more code.
- [ ] Change the time zone between the pre-alarm and the alarm: the alarm rings at the same local time.

**Battery**
- [ ] Overnight with 2 alarms and pre-alarms: no WakeForce wake locks except at the prompt and ring times (`dumpsys batterystats`).

---

# WakeForce: post-build audit (2026-11-06)

**Scope:** full source review across 10 areas, fixes for confirmed defects, and new automated tests.
**Method:** code inspection, JVM unit tests, Robolectric integration tests (Android framework on the JVM), Android lint, and `aapt2` inspection of the release APK.
**Not done:** testing on a physical device or emulator. None was available, so nothing in this report claims verified device behavior. See [Manual device checklist](#manual-device-checklist).

## Results

| | Before audit | After audit |
|---|---|---|
| Unit + integration tests | 16 | **59, all passing** (50 JVM unit tests + 9 Robolectric integration tests) |
| Android lint | No issues | No issues |
| Release APK | 2,456,118 B | **2,456,078 B (2.34 MB)**, R8-minified |
| Debug APK | 23.5 MB | 17.6 MB |
| Release permissions | No internet, network or location | Unchanged (re-verified with `aapt2`) |

**Mutation check:** I re-introduced two fixed bugs (no duplicate check; re-arming without the +1 minute guard). Six tests failed, in both the unit and integration suites, so the tests catch real regressions.

## Issues found and fixed

| # | Area | Severity | Issue | Fix |
|---|---|---|---|---|
| 1 | Recovery | **High** | A one-time alarm whose time passed while the phone was off or the app was force-stopped was silently moved to the next day, so it rang a day late. | `armedAt` is now stored. Recovery detects the miss and switches the alarm off (`AlarmPlanner.planSync`). |
| 2 | Recovery | **High** | An alarm due while the phone restarted (e.g. a 06:59 OS update) was lost; repeating alarms skipped to the next day. | On the first sync after a boot (deduplicated with `Settings.Global.BOOT_COUNT`), an alarm missed by 30 min or less rings 5 s later. A pending catch-up survives the second boot broadcast. |
| 3 | Scheduling | **High** | Race: app-start sync, boot sync, post-ring rescheduling and user edits all wrote `AlarmManager` and storage without coordination. A stale sync could re-arm an old time over a fresh edit. | New `AlarmController`: every state and registration change runs under one process-wide `Mutex` and re-reads the latest stored alarm. |
| 4 | Delivery | **High** | The next occurrence of a repeating alarm was armed inside the ringing service. If the service couldn't start (FGS start refused), the alarm was never re-armed. | Re-arming moved to `AlarmReceiver` (`goAsync`), independent of the service. |
| 5 | Duplicates | Medium | The duplicate check was UI-only, so a double-tap on Save could store two identical alarms. | The check runs inside the DataStore transaction (`AlarmPlanner.upsert`). The second save is rejected with a toast. |
| 6 | Time zone | Medium | On `TIMEZONE_CHANGED`, a running process might still have the old default zone cached, so alarms could be computed in the wrong zone. | The new zone is read from the broadcast's `time-zone` extra. |
| 7 | Service | Medium | An exception while loading the alarm (storage I/O) crashed the ringing service, so the alarm could stay silent. | On a storage error, the alarm rings with safe defaults (default tone, vibration, math). It stops only if the alarm was genuinely deleted. |
| 8 | Audio | Medium | A `MediaPlayer` error during playback stopped the sound silently. | `OnErrorListener` switches to the built-in repeating tone. |
| 9 | Dismissal | Medium | Dismissal relied on `startService(DISMISS)`, which Android can refuse or delay, leaving the alarm ringing after the challenge was solved. | Dismissal is an in-process signal to the running service; the intent is only a fallback, wrapped in try/catch. |
| 10 | QR | Medium | With camera permission missing at ring time (revoked, or auto-reset for unused apps), the user was stuck for 3 minutes. The permission dialog may not be usable over the lock screen. | Missing permission now counts as "camera blocked", so the longer math backup is offered immediately. The editor also warns and asks for camera permission when a QR alarm is set up. |
| 11 | Lock screen | Low | In the fallback path, the service was started from `onCreate`, before the activity was visible, so the start could be refused. `onNewIntent` didn't apply the new intent. | Moved to `onResume`, runs once per launch, guarded; `setIntent()` added. |
| 12 | Notifications | Low | The normal and fallback full-screen PendingIntents shared one identity (extras are ignored), so they overwrote each other's extras. | Distinct request codes. |
| 13 | Challenge | Low | Scanning or the math backup could run before settings and the registered QR had loaded (a few ms), so a scan could be wrongly rejected or a short challenge used. | `loaded` gate in `AlarmChallengeViewModel`. Math logic extracted into the pure, tested `MathSession`. |
| 14 | Camera | Low | The analyzer stayed attached to `ImageAnalysis` after dispose. | `clearAnalyzer()` before `unbindAll()`. |
| 15 | Build | Low | OneDrive locks Gradle output mid-build ("Unable to delete directory"). | Optional `wakeforce.buildRoot` in `local.properties` puts build output outside the synced folder. |
| 16 | Lint | Info | `Intent.EXTRA_TIMEZONE` is public only from API 30 (min SDK is 26). | Literal `"time-zone"` key, which is sent on all versions. |

## Area-by-area audit

1. **Alarm delivery (AlarmManager).** `setAlarmClock` (exact, Doze-exempt) is used when exact alarms are allowed; otherwise the inexact `setAndAllowWhileIdle`, with the checklist warning the user. One PendingIntent per alarm id; ids are never reused. Fixes #3, #4.
2. **Full-screen alarm on a locked device.** `showWhenLocked` and `turnScreenOn` are set (window flags on API 26), plus `FLAG_KEEP_SCREEN_ON` while ringing. The notification is high-importance and alarm category with a full-screen intent. The in-app keypad avoids depending on the system keyboard over the lock screen. Back is consumed (predictive back included). Fixes #11, #12. Requires a device to verify.
3. **Foreground service and audio.** The service exists only while ringing, with type `mediaPlayback` (allowed from an alarm-clock broadcast). It uses the alarm stream, transient audio focus, a temporary volume override restored on dismiss, a wake lock with timeout, and redelivery if killed. All releases are idempotent. Fixes #7, #8, #9.
4. **QR scanning and camera cleanup.** Bound to the lifecycle only while a scanner is on screen. Analysis is throttled to about 7 fps, Y plane only, newest frame only. Decoding is tested with padded row strides like real camera buffers. Fixes #10, #14.
5. **Math validation and dismissal security.** The alarm stops only when every question is answered correctly. Empty, invalid and wrong answers never advance, and a wrong answer replaces the question. 200 wrong attempts never solve it (tested). The service and the alarm receiver are not exported, and the only path to dismiss is the in-app challenge.
6. **Duplicate prevention.** Duplicates are blocked at the storage level; registrations are idempotent (re-syncs don't add registrations, tested); two alarms firing in the same minute share one ringing session. Fix #5.
7. **Reboot, update, time zone and clock change.** Data lives in device-protected storage, so recovery works before the first unlock. Covered broadcasts: `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` and the exact-alarm permission change. DST gaps and overlaps are tested. Fixes #1, #2, #6.
8. **Exact alarm and notification permissions.** `USE_EXACT_ALARM` on API 33+; `SCHEDULE_EXACT_ALARM` (maxSdk 32) with a re-sync when it's granted. Notifications are requested on first launch. The checklist covers notifications (including a disabled channel), exact alarms and full-screen intent (API 34+). Camera permission is now covered in the alarm editor.
9. **Battery and memory.** Nothing runs between alarms. The home clock updates once per minute and only while visible. Wake locks are held only while ringing. Camera frames are throttled and the luminance buffer is reused. The two QR bitmaps (768 px for display, 1024 px for print/save) exist only on the QR screen. No leaks found: the static ringing state holds plain data only.
10. **APK permissions and privacy.** The final manifest has no INTERNET, network-state or location permission, `allowBackup=false`, and backup/device-transfer exclusion rules. Exported components are the launcher, `BootReceiver` (system-protected broadcasts; it only re-arms alarms) and AndroidX `ProfileInstallerReceiver` (protected by the `DUMP` permission).

## Known limitations (not bugs)
- Alarms cannot ring after the app is **force-stopped** (Android cancels its alarms) or while the **phone is off**. A one-time alarm missed that way is switched off when the app next runs, not rung late.
- Catch-up after a reboot covers misses of up to 30 minutes; longer outages skip to the next occurrence.
- If the user **revokes the camera permission in advance**, QR mode falls back to the longer math challenge. This trade-off avoids anyone being locked out by an alarm they can't stop.
- Behavior specific to Android 17 / API 37 couldn't be checked against a device.

## Test inventory

| Suite | Tests | Covers |
|---|---|---|
| `AlarmPlannerTest` | 18 | duplicate prevention, sync/recovery decisions, catch-up after reboot, time-zone/clock change, recurrence, DST no-double-ring |
| `AlarmSchedulingIntegrationTest` (Robolectric) | 9 | real `AlarmManager` registrations: `setAlarmClock` used, no duplicate registrations, disable/delete, reboot restore via `BootReceiver`, post-fire re-arm, time-zone intent, PendingIntent target |
| `AlarmTimeCalculatorTest` | 9 | next trigger, weekly repeat, DST gap |
| `MathSessionTest` | 8 | dismissal only after all correct answers, empty/garbage/wrong rejected, wrong answer replaces question, backup length |
| `AdditionChallengeTest` | 5 | ranges, validation, sanitizing |
| `QrDecoderTest` | 5 | offline encode → camera-like Y plane (padded strides) → decode; wrong code rejected |
| `AlarmJsonTest` | 3 | storage round-trip (recovery source), corrupt-data tolerance |
| `QrCodesTest` | 2 | token uniqueness, constant-time match rules |

## Manual device checklist

Run on at least one Pixel (stock Android 14+) and one aggressive-OEM phone (Xiaomi, Samsung, OnePlus), including one on Android 8/9.

**Delivery**
- [ ] Alarm 2 min ahead, screen locked and off: screen turns on and the challenge shows over the lock screen.
- [ ] Same, with the app swiped from Recents: still rings.
- [ ] Same, with the screen unlocked and another app in front: a heads-up notification appears; tapping it opens the challenge.
- [ ] Doze: `adb shell dumpsys deviceidle force-idle`, then wait for the alarm: rings on time.
- [ ] `adb shell dumpsys alarm | grep -A3 com.wakeforce`: exactly one registration per enabled alarm.

**Lock screen and dismissal**
- [ ] Back, Home and the power button don't stop the sound. After power off/on, the challenge is still on the lock screen.
- [ ] Math: wrong and empty answers are rejected; only all correct answers stop the alarm; the sound stops immediately.
- [ ] QR: a random QR or an old WakeForce code is rejected; the registered code stops the alarm; the camera indicator turns off afterwards.
- [ ] QR with camera permission revoked in Settings: the backup is offered immediately.

**Recovery**
- [ ] Reboot without unlocking: the next alarm still rings.
- [ ] Set an alarm for 3 min ahead, power off immediately, power on 5 min later: the alarm rings about 5 s after boot.
- [ ] One-time alarm, phone off past its time for more than 30 min: it shows as off in the list and doesn't ring the next day.
- [ ] Change the time zone in Settings: the "Next alarm" local time is unchanged and the alarm rings at that local time.
- [ ] Force-stop in App info, then reopen the app: alarms are re-armed (check with `dumpsys alarm`).
- [ ] Install an update APK over the existing app: alarms still armed.

**Permissions and battery**
- [ ] Android 13+: deny notifications: the checklist card appears; allow: it disappears.
- [ ] Android 14+: revoke full-screen intent: the card appears.
- [ ] Android 12/12L: revoke "Alarms & reminders", re-grant: alarms re-armed.
- [ ] Overnight battery stats: no WakeForce wake locks or background activity between alarms.
- [ ] Ringing for 10+ minutes with the screen off: continuous sound and vibration.

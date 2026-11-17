# How WakeForce works

This document explains what happens inside WakeForce, from creating an alarm to the moment it
stops ringing, and why it is built the way it is. Part 1 describes the app as a user sees it.
Part 2 explains the internals, with pointers to the code.

**Contents**

- [Part 1: The app from the user's side](#part-1-the-app-from-the-users-side)
  - [1. The big idea](#1-the-big-idea)
  - [2. Setting up an alarm](#2-setting-up-an-alarm)
  - [3. What happens before the alarm (pre-alarm)](#3-what-happens-before-the-alarm-pre-alarm)
  - [4. What happens when the alarm rings](#4-what-happens-when-the-alarm-rings)
  - [5. Temporary access](#5-temporary-access)
- [Part 2: Under the hood](#part-2-under-the-hood)
  - [6. The building blocks](#6-the-building-blocks)
  - [7. The life of one alarm, step by step](#7-the-life-of-one-alarm-step-by-step)
  - [8. Occurrences: why every ring has an ID](#8-occurrences-why-every-ring-has-an-id)
  - [9. The ringing session and its state machine](#9-the-ringing-session-and-its-state-machine)
  - [10. How QR codes work](#10-how-qr-codes-work)
  - [11. How the math challenge works](#11-how-the-math-challenge-works)
  - [12. Staying correct when things go wrong](#12-staying-correct-when-things-go-wrong)
  - [13. Where data is stored](#13-where-data-is-stored)
  - [14. Battery: why WakeForce costs almost nothing when idle](#14-battery-why-wakeforce-costs-almost-nothing-when-idle)
  - [15. Security and privacy](#15-security-and-privacy)
  - [16. What Android does not allow](#16-what-android-does-not-allow)
  - [17. How it is tested](#17-how-it-is-tested)
  - [18. Code map](#18-code-map)

---

# Part 1: The app from the user's side

## 1. The big idea

Most alarm apps can be silenced with one tap while you are half asleep. WakeForce can't.
Each alarm has **one challenge**, chosen when you set it up:

| Challenge | To stop the alarm | Why it works |
|---|---|---|
| **QR scan** | Scan the QR code registered to that alarm | The code is somewhere else (e.g. a sticker in the bathroom), so you have to get up |
| **Math addition** | Answer every addition question correctly | Mental effort wakes your brain |
| **Math + QR** *(optional, stricter)* | Math first, then the QR code | Both of the above |

The alarm keeps ringing until the chosen challenge is complete. There is no dismiss button,
no swipe-to-dismiss and no snooze, and the Back button does nothing on the alarm screen.

## 2. Setting up an alarm

The alarm editor has four sections.

| Section | What you set |
|---|---|
| **A. Basic alarm** | Time, repeat days (or "once"), on/off, alarm tone, volume, vibration |
| **B. Stop the alarm by** | QR scan, Math addition, or Math + QR. Math settings are shared by all alarms; QR codes belong to this alarm |
| **C. Pre-alarm** | Optional "Do you need your 5:00 AM alarm?" prompt, 10 minutes to 2 hours before |
| **D. Temporary access** | Whether you may pause for 5 minutes while it rings, and how quiet it gets meanwhile |

**Registering a QR code.** When you choose QR scan, the camera opens:

```
 choose "QR scan"  ──►  camera opens  ──►  point it at any QR code you own
                                           (a sticker, a code on a box, a printed code)
                                                        │
         registered to this alarm  ◄──  its content is saved on the phone
```

- Any readable QR code works. What's saved is the code's *content*, not a photo of it.
- No QR code at hand? Tap **"No QR code? Generate one to print"**: the app makes a unique code,
  you print it (or show the image on another screen), and scan the copy to register it.
- You can register up to 3 codes; then every one must be scanned. You can **rename** codes
  (e.g. "Bathroom"), **replace** one (the old code stops working; if you cancel, nothing
  changes) or **remove** one. A QR alarm can't be saved without at least one code.

## 3. What happens before the alarm (pre-alarm)

If you turned on the pre-alarm, WakeForce asks, for example at 4:00 for a 5:00 alarm with a
1-hour pre-alarm:

> **Do you need your 5:00 AM alarm?**
> 5:00 · Challenge: QR scan
> **[ YES, I NEED IT ]**  **[ NO, I DON'T NEED IT ]**

| Your answer | Result |
|---|---|
| **YES** | Nothing changes; the alarm rings at 5:00 as planned. |
| **NO** | Only *this* 5:00 is skipped. A repeating alarm still rings on the following days. A one-time alarm is switched off, but stays in your list. On a locked phone you must unlock first, so someone else can't skip your alarm. |
| **No answer** | The alarm **still rings**. Ignoring the question never cancels anything. |

Changing the alarm's time (or the pre-alarm interval) moves the prompt with it. If the phone
restarts while a prompt is waiting, it is shown again after boot. Switching a skipped alarm
off and on again cancels the skip.

## 4. What happens when the alarm rings

```
  5:00 ── screen turns on, alarm sound + vibration (if enabled)
   │
   ├── QR scan mode ───────► camera opens: "Scan your QR code"
   │                          wrong / random / other alarm's code → rejected, keeps ringing
   │                          registered code → done
   │
   ├── Math mode ──────────► "Question 1 of 3:  30 + 70 = ?"
   │                          empty or wrong answer → rejected, a new question replaces it
   │                          all correct → done
   │
   └── Math + QR mode ─────► math first, then the QR code
                                         │
                                         ▼
  "You're up!"   sound and vibration stop immediately, wake-up recorded
```

- There's no time limit: the alarm rings until the challenge is complete. The 5-minute
  temporary access (below) only quiets it; it never stops it.
- With several registered codes, each must be scanned and counts once.
- Codes from another alarm, replaced codes, random and empty QR codes are rejected.

## 5. Temporary access

Sometimes you need your phone while the alarm is ringing (to check a message, or to fix the
camera permission). The **"Temporary access – 5 minutes"** button:

- quiets the alarm to the level you chose (0 % = silent) and stops vibration;
- closes the alarm screen so you can use the phone normally;
- keeps your progress (solved questions, scanned codes);
- brings the alarm back at full volume after 5 minutes, or earlier if you tap "Return to the challenge now".

You get at most **2 windows per alarm**. A window can't be restarted or extended, and reopening
the app doesn't reset the countdown.

---

# Part 2: Under the hood

## 6. The building blocks

```
                         ┌────────────────────────────┐
     User taps ────────► │  UI (Jetpack Compose)      │  MainActivity, AlarmActivity,
                         │  screens + ViewModels      │  PreAlarmActivity
                         └─────────────┬──────────────┘
                                       │ every change goes through ▼
                         ┌────────────────────────────┐
  System events ───────► │  AlarmController           │  the ONLY place that changes
  (receivers)            │  (one process-wide lock)   │  alarms, schedules and sessions
                         └──┬──────────┬───────────┬──┘
                            │          │           │
              decisions ◄───┘          │           └───► storage
       ┌──────────────────────┐        │        ┌──────────────────────┐
       │ AlarmPlanner         │        │        │ DataStore (one file) │
       │ SessionMachine       │        │        │ alarms, session,     │
       │ (pure logic, tested) │        │        │ stats, math settings │
       └──────────────────────┘        ▼        └──────────────────────┘
                         ┌────────────────────────────┐
                         │  AlarmScheduler            │──► Android AlarmManager
                         └────────────────────────────┘
                                       │ at the right moment
                                       ▼
                         ┌────────────────────────────┐
                         │  AlarmReceiver             │──► AlarmService (rings)
                         │  BootReceiver              │──► re-arms everything
                         └────────────────────────────┘
```

Four design rules explain most of the code:

1. **One authority.** Only `AlarmController` changes alarm state. Each operation takes a lock and
   re-reads storage first, so two events (say, a reboot and a user edit) can never overwrite each other.
2. **Decisions are pure functions.** "What should be scheduled next?" and "is this scan valid?"
   live in `AlarmPlanner` and `SessionMachine`. They have no Android code, so they are easy to test.
3. **Storage is the truth.** The ringing service and alarm screen don't remember progress
   themselves. They display what is stored, so nothing is lost if either is destroyed.
4. **Nothing runs between alarms.** Every future event is a one-time AlarmManager wake-up.

## 7. The life of one alarm, step by step

Example: a daily 6:30 alarm with a 30-minute pre-alarm, saved on Sunday evening.

**Saving (Sunday 22:00)**

1. The editor calls `MainViewModel.saveAlarm` → `AlarmController.save`.
2. The repository stores it. Inside the same storage transaction it rejects an exact duplicate
   (same time and days), so a double-tap can't create two.
3. `AlarmTimeCalculator.nextOccurrence` computes the next ring: **Monday 06:30**.
4. `AlarmScheduler` registers two wake-ups with Android:

| Registration | Android API | When | Why this API |
|---|---|---|---|
| Main ring (`FIRE`) | `setAlarmClock` | Mon 06:30 | Exact, works in deep sleep (Doze), shows as the system's "next alarm" |
| Pre-alarm (`PRE_ALARM`) | `setExactAndAllowWhileIdle` | Mon 06:00 | Exact enough, and doesn't replace the real alarm as the "next alarm" |

5. The alarm records `armedAt` (the instant registered) and `occurrence = "2026-11-16T06:30"`.

**Overnight:** nothing runs. The phone sleeps.

**Monday 06:00, pre-alarm**

6. Android wakes `AlarmReceiver` with `PRE_ALARM`.
7. `AlarmController.preAlarmToShow` re-checks that the prompt is still valid: the alarm is on,
   this is still the armed occurrence, it hasn't been skipped or confirmed, and the time is still
   ahead. Only then is a notification shown, which opens `PreAlarmActivity`.
8. The notification removes itself at 06:30 if you never answer.

**Monday 06:30, main ring**

9. Android wakes `AlarmReceiver` with `FIRE` (alarm id and occurrence in the intent). It:
   - starts `AlarmService` immediately, which is allowed because alarm-clock broadcasts are exempt from background limits;
   - asks the controller to re-arm the alarm for **Tuesday 06:30**. This happens in the receiver,
     so the next day is scheduled even if the service fails to start.
10. `AlarmService` becomes a foreground service and calls `AlarmController.startSession`, which
    creates a **session** (see section 9). The session is saved to storage.
11. The service starts the tone, volume and vibration, and opens `AlarmActivity` over the lock screen.
12. Each answer or scan goes `AlarmActivity` → ViewModel → `AlarmController.submitMath` /
    `submitQr` → `SessionMachine` → saved.
13. When the session reaches **COMPLETED**, the controller records the wake-up statistics. The
    service, which is watching the stored session, stops the sound and vibration, restores your
    volume and shuts itself down.

## 8. Occurrences: why every ring has an ID

An alarm is a rule ("daily at 6:30"). An **occurrence** is one concrete ring ("Monday 16 Nov at 6:30").
Each occurrence gets a key from its **local date and time**:

```
occurrence key:   2026-11-16T06:30
session id:       5@2026-11-16T06:30      (alarm 5, that occurrence)
```

Every answer, skip, scan and broadcast carries this key, and the controller ignores anything
whose key doesn't match. That prevents real problems:

| Without occurrence IDs | With them |
|---|---|
| A "NO" tapped on yesterday's forgotten notification skips today's alarm | Rejected: wrong occurrence |
| A duplicate broadcast starts a second ringing session | Recognised as the same occurrence and resumed |
| A completed alarm rings again from a late broadcast | Ignored: that occurrence is already completed |
| A skipped 6:30 is re-armed by a reboot at 6:10 | `nextOccurrence` jumps over the skipped key |

The key uses **local** time on purpose. If you fly from India to London, "Monday 06:30" still
means 06:30 on your watch, and a skip still applies to the right morning.

## 9. The ringing session and its state machine

A **session** is the record of one ringing occurrence. It stores:

- the current state;
- the math questions and how many are solved;
- the QR codes already scanned;
- the temporary-access end time and windows used;
- a **snapshot** of the alarm's challenge mode, QR codes and sound settings from the moment it
  started ringing.

The snapshot means editing or deleting the alarm while it rings changes nothing: switching a
ringing QR alarm to Math mode, for example, does not let math stop it.

Where a session starts depends on its mode: **QR** starts at `RINGING_QR`, **Math** and
**Math + QR** start at `RINGING_MATH`. In Math mode the snapshot contains no QR codes, so
answering the last question completes the session.

```
                         ┌──────────────────┐
     alarm fires ──────► │   RINGING_MATH   │◄─────────┐
                         └───┬──────────┬───┘          │ window ends
                all math     │          │ temporary    │ (or user returns)
                correct      │          ▼ access       │
                             │   ┌──────────────────┐  │
                             │   │ TEMPORARY_ACCESS │──┘
                             │   └──────────────────┘──┐
                             ▼          ▲              │
                         ┌──────────────┴───┐          │
                         │    RINGING_QR    │◄─────────┘
                         └────────┬─────────┘
                    every code    │
                    scanned once  ▼
                         ┌──────────────────┐
                         │    COMPLETED     │   final: can never ring again
                         └──────────────────┘

   Interrupted by power-off for more than 2 h ──► MISSED (final)
```

`SessionMachine.canTransition` lists every allowed move and rejects all others. For example:

- In Math + QR mode, math alone can't complete the session: after the last question it moves to
  RINGING_QR, and only scanning finishes it.
- QR input is refused while math is still pending, and math input is refused in QR mode.
- TEMPORARY_ACCESS → COMPLETED is blocked, so a pause can never count as waking up.
- Nothing can leave COMPLETED or MISSED.

Before an occurrence rings, its state is **worked out from stored data** rather than kept separately:
`SCHEDULED` → `PRE_ALARM_PENDING` (prompt time reached) → `PRE_ALARM_CONFIRMED` or `PRE_ALARM_SKIPPED`.

**Two alarms at the same time.** If alarm B fires while A is ringing, B is put in a queue (once,
even if its broadcast repeats). When A is completed, B starts ringing with *its own* codes, so
A's codes can never stop B.

## 10. How QR codes work

**What is stored.** A QR code is just text (a web address, a product number, Wi-Fi details…).
When you register a code, WakeForce stores that **text**, the code's payload, for that alarm.
Two kinds of code can be registered:

| Kind | Payload | Notes |
|---|---|---|
| An existing physical code you scan | Whatever text it contains | Quickest. A common code (one you own several of) is easier to get around. |
| A code generated by the app | `WAKEFORCE1:` + 128 random bits | Unique to you; printed with high error correction (level H), so a creased printout still scans. |

Before storing, the text is **normalized**: leading and trailing whitespace is removed, because
many printed codes and camera decoders add a trailing newline. Empty content and content over
2,048 characters are rejected. The same normalization is applied at alarm time, so stored and
scanned text always compare like for like.

**Checking a scan.** The scanned payload is compared with the payloads stored for *that alarm
only*, entirely on the phone; nothing is ever looked up online. A successful camera read is not
enough by itself: only an exact payload match counts. The comparison is constant-time, a
standard security habit that avoids leaking information through timing.

**Scanning efficiently** (`QrScannerView`, `QrAnalyzer`, `QrDecoder`):

- The camera (CameraX) is switched on only while a scanner is visible, and released as soon as it disappears.
- Only the brightness part of each frame is used, at most about 7 frames per second, and only the newest frame is kept.
- Decoding uses ZXing, a small offline Java library. There's no Google Play Services and nothing is downloaded.
- No camera images are ever saved.

## 11. How the math challenge works

- **Addition only.** Settings are shared by all alarms: the number of questions (1–10) and a difficulty:

  | Difficulty | Numbers per question | Values | Example |
  |---|---|---|---|
  | Easy | 2 | 10–99 | 30 + 70 = ? |
  | Medium | 2 or 3 (random) | your min–max range (default 10–99) | 22 + 15 + 13 = ? |
  | Hard | 3 | 10–99 | 45 + 32 + 18 = ? |

- Each session gets fresh random questions, and **two questions in a row never have the same
  answer**, so typing the previous answer again can't pass the next question.
- The biggest possible answer is 29,997 (3 × 9,999), far below the integer limit. An absurdly
  long input is rejected as "not a number" rather than overflowing.
- Answers are typed on a large built-in number pad. The system keyboard isn't used because it can
  be unreliable over the lock screen.
- An empty answer is rejected. A wrong answer **replaces the question** (never with one whose
  answer is the old answer or the number you just typed), so you can't find the answer by
  trying numbers one after another.
- Progress is saved after every answer.

## 12. Staying correct when things go wrong

| Situation | What WakeForce does |
|---|---|
| **App is killed while ringing** | Android restarts the service and redelivers the alarm. The stored session is found and **resumed** with its progress, never duplicated. |
| **Phone reboots** | `BootReceiver` runs at boot, even before the first unlock (data is in storage that's readable before unlock), and re-arms every alarm. |
| **Alarm time passed during a short reboot** (e.g. an update at 6:29) | If the phone is back within 30 minutes, the alarm rings about 5 seconds after boot. This happens once per boot. |
| **Phone off much longer** | A missed one-time alarm is switched off, not moved silently to tomorrow. A repeating alarm moves to its next day. |
| **Reboot in the middle of ringing** | If it started within the last 2 hours, the session resumes with its progress. Otherwise it's marked MISSED. |
| **Temporary access ends while the phone sleeps** | A scheduled wake-up (`ACCESS_END`) brings the alarm back; it also re-arms after a reboot. |
| **Time zone or clock changed** | Every alarm is re-armed at the same local time. The new zone is read from the system message itself, so a running app can't use a stale zone. |
| **App updated** | Alarms are re-armed. Data from 1.0 is migrated once (see section 13). |
| **Ringing service can't start** | A full-screen notification appears instead. Opening it starts the service from the screen, which Android always permits. |
| **Storage can't be read** (very rare) | The alarm rings anyway with default settings and a longer math challenge, so it can always be stopped. |
| **Tone file unreadable** | It falls back to the default alarm tone, then to a built-in beeping tone. |

## 13. Where data is stored

Everything lives in **one small DataStore file** (`wakeforce.preferences_pb`) in the phone's
*device-protected* storage. That's the area Android allows apps to read before you unlock after
a reboot, which is what makes recovery at boot possible.

| Key | Contents |
|---|---|
| `alarms` | All alarms as JSON: settings, QR codes, and occurrence bookkeeping (armed, skipped, confirmed, last completed) |
| `active_session` | The current or last ringing session (progress, snapshot, access timer) |
| `wake_stats` | Wake-ups completed, last dismissal time, how long it took |
| `math_*` | Math settings |
| `next_alarm_id`, `last_catch_up_boot`, `schema_version` | Internal counters |

There is no database. The JSON is written with Android's built-in `org.json`, and every field has
a default, so older data always loads.

**Upgrading from 1.0.** On first launch of 2.0, `SchemaMigration` runs once, in a single
transaction, before anything else reads the data. Every alarm is kept. A 1.0 alarm that used QR
mode gets the old printed code as its "Code 1", and the old shared secret is deleted.

## 14. Battery: why WakeForce costs almost nothing when idle

| Moment | What is running |
|---|---|
| Between alarms | **Nothing.** No service, no timers, no polling, no network. Android holds a few scheduled wake-ups. |
| Pre-alarm | One wake-up, one notification. |
| Ringing | A foreground service plays audio and holds a wake lock. This is the only time the app actively runs. |
| Temporary access | Audio is paused or quieted, vibration stops and **the wake lock is released**. The phone may sleep until the single scheduled wake-up at the end. |
| After the challenge | Everything is released immediately: camera, audio, vibration, wake lock, service. |

On screen, the clock updates once per minute (not per second) and only while visible. The
temporary-access countdown ticks once per second only while it is on screen.

## 15. Security and privacy

- **No internet permission at all**, plus no location, contacts, microphone, accessibility or
  device-admin permissions. The final APK is checked for this on every release build.
- No accounts, analytics, ads or cloud backup. Android backup and device-to-device transfer are
  turned off for the app.
- The ringing service, the alarm screens and the alarm receiver are **not exported**, so other
  apps can't stop an alarm or send a fake "NO".
- QR secrets are never written to logs.
- Answering "NO" on a locked phone requires unlocking first.

## 16. What Android does not allow

WakeForce only uses normal, supported Android features. It does **not** try to take control of your phone, so:

- **Force-stop** (Settings → Apps → Force stop) cancels all of the app's alarms and stops a ringing one.
- **A phone that's switched off** can't ring.
- **Volume buttons and system mute still work.** WakeForce sets the alarm volume while ringing and
  restores it afterwards, but doesn't block your controls.
- **Full-screen pop-ups aren't guaranteed.** On Android 14+ you may need to allow "full-screen
  notifications". When the phone is unlocked and in use, Android shows a banner instead of taking
  over the screen; tap it to open.
- **Aggressive battery savers** on some brands (Xiaomi, Huawei, OnePlus, Samsung and others) can
  interfere. Set WakeForce's battery usage to *Unrestricted*.

The home screen shows a checklist whenever one of the required special permissions is missing.

## 17. How it is tested

- **167 automated tests**: 126 run on plain Java, and 41 use Robolectric, which runs Android's
  alarm, storage and service classes on the computer, so the real scheduling code and the real
  ringing service are exercised without a phone.
- They cover scheduling and recurrence, duplicates, the pre-alarm (YES, NO, ignored, stale,
  reboot), each challenge mode, physical and generated QR codes, math difficulty and answers,
  the audio lifecycle (starts once, never twice, stops only on success), temporary access,
  reboot, process death, time zones, migration, and edits during a ring.
- **Mutation testing:** bugs were deliberately inserted to make sure the tests notice them. Every
  injected bug has been caught; one test gap found this way was closed.
- **Not yet done:** testing on a real phone. Lock-screen behavior, sound, the camera and battery
  use need hardware. The step-by-step checklist is in [AUDIT.md](AUDIT.md#manual-device-checklist-20).

## 18. Code map

All code is under `app/src/main/java/com/wakeforce/app/`.

| Package | Files | Responsibility |
|---|---|---|
| `alarm/` | `AlarmController` | The single authority: every state and schedule change |
| | `AlarmPlanner` | Pure decisions: what to arm, catch-up, skip and confirm, duplicates |
| | `SessionMachine` | Pure ringing rules and the state machine |
| | `AlarmTimeCalculator` | Next occurrence, pre-alarm time, DST and time-zone handling |
| | `AlarmScheduler` | Thin wrapper around Android's AlarmManager |
| `data/` | `Alarm`, `AlarmRepository`, `SessionRepository` | Models and storage access |
| | `AlarmJson`, `SessionJson`, `SchemaMigration`, `WakeForceDataStore` | Storage format, 1.0 → 2.0 upgrade, the DataStore file |
| `receiver/` | `AlarmReceiver` | Receives the main ring, pre-alarm, access-end and recovery wake-ups |
| | `BootReceiver` | Reboot, update, clock and time-zone changes |
| `service/` | `AlarmService` | Plays the alarm while a session is active; follows the stored session |
| | `AlarmNotifications` | Ringing, fallback and pre-alarm notifications |
| | `AudioPolicy` | Pure rule for what the audio does in each session state (no time-based stop) |
| `qr/` | `QrCodes`, `QrRegistry`, `QrDecoder`, `QrAnalyzer` | Code generation, registration rules, offline scanning |
| `math/` | `AdditionChallenge`, `MathSession`, `MathSettings` | Question generation and answer checking |
| `settings/` | `SettingsRepository`, `SystemSettings` | Math settings; Android permission checks |
| `ui/` | `MainActivity`, `AlarmActivity`, `PreAlarmActivity`, ViewModels | Screens and their state |
| `ui/screens/`, `ui/components/` | Home, editor, QR editor, alarm and math screens; keypad, scanner, clock | Compose UI |

Tests are in `app/src/test/java/com/wakeforce/app/`. See also [README.md](README.md) (features,
build, limitations) and [AUDIT.md](AUDIT.md) (audit reports and device checklists).

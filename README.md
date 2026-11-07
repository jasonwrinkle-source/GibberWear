# GibberWear

A Wear OS utility that sends short ultrasonic messages (GGWave / Gibberlink)
from the watch. Tap the watch face and it plays an ultrasonic chirp that a
listening device (for example, a web page using GGWave) can decode.

This is the generic transport. It contains no Sexcapades content and no
profile logic. It just sends a chirp and flashes a color.

## Gestures (v1, kept in v2)

| Gesture | Color flash | Chirp (GGWave ultrasound) |
|---------|-------------|---------------------------|
| Tap | Green | `TAP` |
| Double-tap | Blue | `DBL` |
| Hold (long press) | Red | `HLD` |

Each chirp is a pre-generated GGWave ultrasonic WAV (protocol `p=4`,
ultrasound fast, ~1.07 s, 48 kHz mono) stored in `app/src/main/res/raw/`.
The three files are `chirp_tap.wav`, `chirp_doubletap.wav`, `chirp_hold.wav`.

## v2 — ratings, crown, haptics, wrist gestures

### Circular slider
The rating is an arc around the watch face (~3x the travel of a linear
slider) with the number big in the centre. Tap the ring to jump (coarse
set); rotate the crown to fine-tune (+/-1 per detent). A tap anywhere else
(centre / edge) still sends the v1 `TAP` chirp. Each tap sends exactly one
chirp.

### Crown press cycles metrics
Rotate the crown adjusts the current metric; pressing the crown cycles
which metric is being adjusted, in this order: enjoyment -> heat/arousal
-> endurance. The current metric name is shown above the number.
- Enjoyment (green) — how much you are enjoying it.
- Heat (orange) — arousal. Enjoyment and heat can diverge.
- Endurance (purple) — willingness to endure an activity for someone
  else. Most relevant for edge play and bondage.

### Metric-value chirps
When a metric value is set (ring tap or crown), the watch also sends an
encoded chirp carrying the metric letter and the value:

    <metric letter><value 0-100>

Letters: `E` = enjoyment, `H` = heat, `N` = endurance. Examples: `E70`,
`H60`, `N40`. Values are quantised to the nearest 10 before sending
(0, 10, 20, ... 100); each quantised value has a pre-generated WAV in
`res/raw` (`chirp_e0.wav` ... `chirp_e100.wav`, and the same for `h` and
`n` — 33 files). The displayed slider value stays fine-grained (0-100);
only the chirp is quantised. See `MetricChirp.kt` for the format. The
listening side decodes these in a follow-up; for now the watch just sends
them. The v1 `TAP` / `DBL` / `HLD` path is untouched.

### Haptic reminder
The watch vibrates at check-in intervals (default 5 minutes, constant
`REMINDER_INTERVAL_MS` in `MainActivity.kt`) while the app is open.
System-initiated: the sub is prompted to respond, instead of having to
remember. Two short pulses (vibrate 100 ms, pause 100 ms, vibrate
100 ms), distinct from a notification buzz. The vibration itself sends no
chirp.

### Bound-sub wrist gestures
When the sub cannot tap (bound wrists), motion sensors stand in for
fingers. Coarse only — no precise numbers, no metric cycling via
gestures. See `WristGestureDetector.kt` for the tuning constants.

| Gesture | Meaning | Chirp |
|---------|---------|-------|
| Shake (accelerometer) | "I'm okay / enjoying it" | `TAP` |
| Wrist flip / rotate (gyroscope) | "more" or "less" | `DBL` |
| Big sustained shake | "stop" | `HLD` |

A fully immobilised wrist cannot shake or flip at all, and needs a
different channel (foot, voice, or the Dom's prompt) — out of scope.

## Open it in Android Studio

1. Download and unzip this project on the Chromebook (Spin 514).
2. In Android Studio: **File → Open**, pick the `GibberWear` folder.
3. Let it sync Gradle. It will download the Gradle distribution and the
   Android SDK pieces it needs. This takes a while on first open.
4. If it asks about the Gradle wrapper or a Gradle/AGP upgrade, accept the
   recommended version and let it sync again.

## Run it on the Pixel Watch

The emulator does not run on ChromeOS, so deploy to the physical watch.

1. On the Pixel Watch: **Settings → Developer options → ADB debugging**
   and **Debug over Wi-Fi** (turn both on). Note the watch's IP address
   and port (the port is random, not always 5555).
2. In the Chromebook Linux terminal, pair and connect (ADB ships with
   Android Studio; if `adb` is not on the PATH, use the full path under
   the Android SDK `platform-tools` folder, e.g.
   `~/Android/Sdk/platform-tools/adb`):
   `adb pair <watch-IP>:<pairing-port>` (enter the pairing code),
   then `adb connect <watch-IP>:<connect-port>`.
3. In Android Studio, pick the watch in the device dropdown and press Run.
   The app installs on the watch and launches.

Tap the watch face to send a chirp. The screen flashes the gesture's color.

## If Gradle sync fails

The Gradle / plugin versions in this project were written for a current
Android Studio. If sync fails on version mismatches, use this fallback:

1. In Android Studio, create a **new Wear OS project** (Empty Activity,
   Kotlin, min SDK 30). Let the wizard generate the project.
2. Copy these files from this project into the new one, replacing the
   wizard's versions:
   - `app/src/main/java/app/gibberwear/MainActivity.kt`
   - `app/src/main/java/app/gibberwear/ChirpPlayer.kt`
   - `app/src/main/java/app/gibberwear/MetricChirp.kt`
   - `app/src/main/java/app/gibberwear/HapticReminder.kt`
   - `app/src/main/java/app/gibberwear/WristGestureDetector.kt`
   - `app/src/main/res/raw/chirp_*.wav` (all 36 files)
   - `app/src/main/res/values/strings.xml`
   - `app/src/main/AndroidManifest.xml` (keep the new project's
     `applicationId` if the wizard used a different package; the source
     files use package `app.gibberwear`)
3. Sync Gradle and run.

## What is not in this version

- No device/user ID in the chirp yet. The chirp only encodes the gesture
  (`TAP` / `DBL` / `HLD`) or a metric value (`E70` / `H60` / `N40`).
  Attributing a tap to a person (an ID) is the next step, either by
  giving the utility its own ID or by having the listening web page
  program the utility over Gibberlink.
- No listening / receiving. This version only sends. Receiving a
  programming message from a web page (over Gibberlink) is a later step.
- No launcher icon. The app uses the system default icon. Add a proper
  icon under `res/mipmap-*` later if wanted.

## Files

- `app/src/main/java/app/gibberwear/MainActivity.kt` — watch UI, circular
  slider, crown handling, haptic reminder loop, wrist gestures, color flash.
- `app/src/main/java/app/gibberwear/ChirpPlayer.kt` — plays a raw WAV chirp.
- `app/src/main/java/app/gibberwear/MetricChirp.kt` — metric-value chirp
  format (`E`/`H`/`N` + value, quantised to 10s) and WAV lookup.
- `app/src/main/java/app/gibberwear/HapticReminder.kt` — check-in prompt
  vibration (two short pulses).
- `app/src/main/java/app/gibberwear/WristGestureDetector.kt` — shake /
  flip / sustained-shake detection via accelerometer + gyroscope.
- `app/src/main/res/raw/chirp_*.wav` — pre-generated GGWave ultrasound chirps
  (3 gesture chirps + 33 metric-value chirps).
- `app/src/main/AndroidManifest.xml` — standalone Wear OS app, no phone
  companion required. Declares the VIBRATE permission (haptic reminder).
- `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`,
  `gradle.properties`, `gradle/wrapper/` — Gradle project config.
  (Gradle 8.13, AGP 8.13.0, Kotlin 2.1.20, Compose plugin 2.1.20.)

## Build notes

- Package `app.gibberwear`. compileSdk 35, minSdk 30, targetSdk 35.
- Regular Compose only (no `androidx.wear.compose` dependency).
- The Kotlin files were verified by inspection (no Android SDK in the
  build VM). Build in Android Studio on the Chromebook.
- The IDE's Gradle sync needs Java 21, not its bundled Java 25 (the
  Kotlin plugin cannot parse Java 25). Point Gradle at Java 21, e.g. via
  `org.gradle.java.home` in `gradle.properties`, or set the Gradle JDK in
  Android Studio (Settings → Build Tools → Gradle → Gradle JDK).

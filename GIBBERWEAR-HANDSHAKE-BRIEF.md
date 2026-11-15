# GibberWear Handshake & Ultrasound ACK Brief

### 1. Context & Observed Behavior
During live testing between Sexcapades Web (4.0) and GibberWear (1.0.6) on the Pixel Watch (`192.168.1.227:40691`), the watch successfully receives and decodes the outgoing setup configuration:

```text
11:55:36.239  6216  6761 D GGWave          : Decoded: CFG:eve:300
11:55:36.240  6216  8048 I GibberLinkEngine: Decoded GibberLink ultrasound message: CFG:eve:300
11:55:36.241  6216  6216 I GibberWearMain  : Incoming message: CFG:eve:300
11:55:36.242  6216  6216 I GibberWearMain  : Configured profile: key=eve, timerSec=300
11:55:36.243  6216  8048 D GibberLinkEngine: Encoding and playing ultrasound chirp: ACK:eve:CFG
11:55:36.244  6216  8048 I GibberLinkEngine: GibberLink listening stopped
11:55:37.882  6216  8048 I GibberLinkEngine: GibberLink listening started
```

**Issue**: The web app's `ggwave-wasm` decoder does not decode the return `ACK:eve:CFG` chirp because the watch's micro-speaker at 15–20 kHz (`ULTRASOUND_FAST`) produces insufficient acoustic amplitude at the current `volume(10)` setting.

---

### 2. Surgical Fix Required
**Target File**: [`app/src/main/java/app/gibberwear/GibberLinkEngine.kt`](file:///home/jason/gibberwear/app/src/main/java/app/gibberwear/GibberLinkEngine.kt) (Lines 44–49)

**Current Code**:
```kotlin
    private val codec: GGWaveCodec = GGWaveCodec.Builder()
        .sampleRate(SAMPLE_RATE)
        .protocolId(GGWaveProtocolId.ULTRASOUND_FAST)
        .volume(10)
        .build()
```

**Required Change**:
```kotlin
    private val codec: GGWaveCodec = GGWaveCodec.Builder()
        .sampleRate(SAMPLE_RATE)
        .protocolId(GGWaveProtocolId.ULTRASOUND_FAST)
        .volume(70)
        .build()
```

---

### 3. Build & IDE Guardrails (Avoiding Android Studio OOM)
1. **Headless / Command-line Build Recommended**:
   - To prevent Android Studio IDE heap crashes / Gradle daemon OOM, build and install directly via terminal or Gradle daemon with restricted max heap if needed:
     ```bash
     cd /home/jason/gibberwear
     ./gradlew assembleDebug
     ~/Android/Sdk/platform-tools/adb -s 192.168.1.227:40691 install -r app/build/outputs/apk/debug/app-debug.apk
     ```
2. **AudioTrack Lifecycle**:
   - `playEncodedMessage(message)` currently creates a short-lived `AudioTrack` with `MODE_STATIC`, plays the encoded buffer, and releases it in `finally`. Keep this cleanly scoped.
3. **Sampling Rate**:
   - Do not lower `SAMPLE_RATE` from `48000f`. GGWave `ULTRASOUND_FAST` requires $\ge 44.1\text{ kHz}$ to satisfy the Nyquist limit for ~15–20 kHz chirps.
4. **Wear OS Ambient Mode**:
   - Remind the user that Wear OS calls `MainActivity.onPause()` (which stops listening) when the screen dims or enters ambient mode. The watch display must stay active while executing the initial sync handshake.

---

### 4. Low Power Mode (BLE Fallback Integration)
To aggressively preserve watch battery, GibberWear supports a dynamically activated **Low Power Mode (LPM)**. When LPM is enabled and a BLE connection to the host device is established, the watch completely shuts down its microphone and ultrasound processing engine, relying purely on BLE GATT for check-ins and telemetry. 
*   **Enable over BLE or Ultrasound**: `LPM:1` (or `LPM:ON`)
*   **Disable**: `LPM:0` (or `LPM:OFF`)
*   **Behavior**: If `LPM=1` and `BLE_CONNECTED=true`, GibberWear executes `GibberLinkEngine.stopListening()`. If the BLE link drops, the ultrasound engine automatically respawns to guarantee delivery. The state is persisted in `SharedPreferences`.

# Google Play Store Listing: GibberWear

---

## 1. App Title & Basic Details

- **App Name:** GibberWear
- **Short Description (max 80 chars):**
  Discreet sensory & state tracking with ultrasonic and BLE touch signaling.
- **Category:** Wear OS / Tools & Utilities
- **Content Rating:** Everyone / Unrated (General utility; contains no explicit content or references)

---

## 2. Full Store Description (max 4000 chars)

GibberWear is a lightweight, responsive wearable utility designed for discreet state check-ins, sensory feedback, and zero-touch physical communication directly from your Wear OS smartwatch.

Whether you need quick non-verbal signaling or continuous rating feedback, GibberWear turns your watch face into an intuitive multi-channel communicator using high-frequency audio (ultrasonic GGWave chirps) and Bluetooth Low Energy (BLE).

### Key Features:

🎯 **Triple Ring State Tracking**
- Track 3 distinct continuous dimensions simultaneously on concentric touch-friendly arcs:
  - **Enjoyment** (Outer ring)
  - **Heat / Intensity** (Middle ring)
  - **Endurance / Capacity** (Inner ring)
- Tap any ring arc directly for quick adjustments or rotate the physical crown for fine-tuning (-100 to +100).
- Automatic focus dimming highlights your active metric while keeping all states visible at a glance.

⚡ **Instant One-Touch Signaling**
- **Tap (Center):** Sends instant confirmation pulse (Green).
- **Double-Tap (Center):** Sends quick secondary signal (Yellow).
- **Long-Press (Center):** Sends hold/stop alert (Red).

🔊 **Dual-Channel Broadcast (Ultrasound + BLE)**
- **Ultrasonic Chirps:** Uses near-inaudible audio pulses (GGWave fast ultrasound protocol) that nearby listening apps and web browsers can decode using standard microphones—no Wi-Fi pairing or internet connection required!
- **BLE Server:** Simultaneously advertises over standard Bluetooth Low Energy characteristics for real-time telemetry receivers.

⌚ **Hands-Free Wrist Motion Gestures**
When fingers or screen taps aren't an option, built-in accelerometer and gyroscope filters translate deliberate wrist movements into actions:
- **Wrist Shake:** Confirms status / check-in.
- **Wrist Twist / Flip:** Sends adjustment signals.
- **Sustained Shake:** Triggers emergency hold.

⏱️ **Discreet Haptic Prompting**
- Configurable quiet haptic pulses check in on you at regular intervals without turning heads or displaying disruptive popups.

*GibberWear operates completely offline and on-device. No telemetry, no background cloud sync, and no personal account required.*

---

## 3. Data Safety Form Responses

| Field | Declaration |
| :--- | :--- |
| **Data Collection** | **No data collected.** The app does not collect, log, or transmit any personal user information off-device to any server. |
| **Data Sharing** | **No data shared.** No data is shared with third parties or advertising brokers. |
| **Sensors & Permissions** |
| - `BLUETOOTH_ADVERTISE` / `CONNECT` | Used strictly for local device-to-device telemetry broadcast. |
| - `VIBRATE` | Used strictly for discreet local haptic pulses. |
| - Motion Sensors | Processed entirely on-device in real-time memory for wrist gestures. Not stored or logged. |

---

## 4. Play Store Screenshots Generated

High-resolution round Wear OS screenshots have been captured directly from the Jetpack Compose renderer:
1. **Overview Rings:** Tri-metric concentric arcs with dynamic color gradient.
2. **Value Adjustment:** Active dial manipulation showing real-time numeric readouts (+85).
3. **Timer Check-In:** Periodic subtle haptic reminder indicator (`✓ ⏱`).
4. **Touch Signal Flash:** Center tap feedback state.

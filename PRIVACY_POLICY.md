# Privacy Policy for GibberWear

**Last updated:** November 7, 2026

GibberWear ("we", "our", or "the app") is committed to protecting your privacy. This Privacy Policy explains our practices regarding data collection, usage, and disclosure when you use the GibberWear Wear OS application.

---

## 1. Summary: Zero Personal Data Collection

GibberWear operates **entirely offline** and on-device. 
- **We do not collect, store, transmit, or sell any personal data or personally identifiable information (PII).**
- **We do not require any user account, registration, login, or email address.**
- **The application does not request internet access permissions (`android.permission.INTERNET`) and has no ability to transmit data to any remote server or third party.**

---

## 2. Device Hardware & Permissions Used

GibberWear requests only the minimal device permissions necessary to perform its local wearable functions:

### A. Bluetooth Low Energy (BLE)
- **Permissions:** `android.permission.BLUETOOTH_ADVERTISE`, `android.permission.BLUETOOTH_CONNECT`, `android.permission.BLUETOOTH` (API ≤ 30), `android.permission.BLUETOOTH_ADMIN` (API ≤ 30).
- **Purpose:** Used strictly to broadcast local, short-range device-to-device telemetry signals to nearby receivers (e.g., paired local displays or nearby listening clients) over Bluetooth Low Energy.
- **Data Handling:** No Bluetooth metadata, device IDs, or connection histories are recorded, stored to disk, or sent to any cloud infrastructure.

### B. Vibration & Haptics
- **Permission:** `android.permission.VIBRATE`.
- **Purpose:** Used solely to pulse the physical vibration motor on your Wear OS watch for subtle interval check-in reminders and touch feedback.
- **Data Handling:** No haptic data or timer logs are stored or monitored.

### C. Motion Sensors (Accelerometer & Gyroscope)
- **Purpose:** Detects deliberate physical wrist motions (such as wrist shakes or flips) to trigger local gestures when physical screen taps are inconvenient.
- **Data Handling:** Sensor streams are processed in transient, volatile device memory in real time to calculate motion thresholds. Sensor values are discarded immediately and are never logged, recorded, or transmitted.

### D. Audio Playback (High-Frequency / Ultrasonic GGWave Chirps)
- **Purpose:** Plays pre-recorded sound files (`.wav`) via the watch speaker to transmit short acoustic tokens to nearby acoustic receivers.
- **Data Handling:** The app **does not record audio** and does not request or use the microphone (`RECORD_AUDIO`). It only plays pre-packaged audio files.

---

## 3. Third-Party Services and Analytics

- **No Analytics:** The app does not include Google Analytics, Firebase, Crashlytics, or any other telemetry libraries.
- **No Advertising:** GibberWear is completely ad-free and contains no advertising SDKs or tracking pixels.
- **No Third-Party Sharing:** Because no data is collected, no data is shared with, sold to, or disclosed to third parties.

---

## 4. Children's Privacy

GibberWear does not collect data from any user, including children under the age of 13 (or under 16 in the European Economic Area). 

---

## 5. Hosting & Public Access

This policy is maintained for compliance with Google Play Developer Program Policies. Users can access this policy at any time to verify the app's offline data architecture.

---

## 6. Changes to This Privacy Policy

If we update our application features or permissions in future versions, we will update this document accordingly with a revised "Last updated" date.

---

## 7. Contact Us

If you have questions, feedback, or concerns regarding this Privacy Policy or GibberWear, please contact:
- **Developer / Project:** GibberWear
- **Repository / Issues:** https://github.com/
- **Email:** support@gibberwear.app *(or your designated developer contact email)*

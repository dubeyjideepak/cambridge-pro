# CamBridge Pro 🎥 ⚡

> **Dedicated Hardware Bridge & Broadcast Studio for Android**  
> Connects professional 4K/HD HDMI cameras via USB-C capture cards to smartphones for **zero-latency OBS Studio RTSP bridging** and **direct bonded RTMP live streaming** (YouTube / Facebook / Instagram) with devotional Katha broadcast overlays.

---

## Key Features

- **Universal Dual-Engine Camera Pipeline:**
  - **CameraX HAL:** Pixel 6–9, Samsung Galaxy S21–S25
  - **Native libuvc / libusb Driver:** OnePlus, Xiaomi, Vivo, Oppo, Realme
  - *Internal phone cameras (front/back) are permanently locked out.*
- **Dual-Track RFC-Compliant RTSP Server:**
  - Track 0: H.264 Video (Annex-B & FU-A fragment splitting)
  - Track 1: AAC Audio at **48,000 Hz** broadcast standard
  - Single presentation-time clock epoch (`CLOCK_MONOTONIC`) — zero lip-sync drift over 10+ hour continuous streams.
- **RTMPS SSL/TLS Encryption:** Direct publishing to Facebook Live and Instagram Live on port 443.
- **Live Broadcast Overlays:** Lower-third Vyas strap, animated Sanskrit/Hindi text ticker, and Picture-in-Picture Jhanki cards.
- **In-App Over-The-Air (OTA) Auto-Updater:** Checks GitHub server and updates the APK with a single tap directly on the phone.

---

## OBS Studio Connection

1. Connect your 4K camera to the phone using an HDMI-to-USB-C capture card.
2. Tap **"START OBS STREAM"** on the phone.
3. In OBS on your computer (on the same Wi-Fi):
   - Add **Media Source** → Uncheck "Local File".
   - Input: `rtsp://<phone-ip>:8554/live`
   - Check **"Use hardware decoding where available"**.

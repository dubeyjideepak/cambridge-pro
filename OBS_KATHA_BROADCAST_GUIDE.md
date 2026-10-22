# CamBridge Pro: Live Bhagwat Katha & Event Broadcast Guide

This guide explains how to receive the wireless camera feed from the **CamBridge Android App** into **OBS Studio** on your laptop, add broadcast graphics (lower thirds, donation QR, scrolling ticker), and multi-stream to **YouTube, Facebook, and Instagram** simultaneously.

---

## 1. Connecting CamBridge to OBS Studio

1. **Connect to Same Wi-Fi:** Ensure your smartphone and laptop are both connected to the **same Wi-Fi router (5 GHz band)**.
2. **Start App:**
   - Plug the HDMI cable from your camera into the **USB-C HDMI Capture Card**.
   - Open **CamBridge Pro** on Android.
   - Tap **"START BROADCAST"**.
   - Note down the RTSP URL shown on the screen (e.g., `rtsp://192.168.1.105:8554/live`).
3. **In OBS Studio (on Laptop):**
   - In the **Sources** panel, click **`+` ➔ Media Source**.
   - Name it: `Wireless Katha Camera`.
   - **Uncheck** *"Local File"*.
   - In **Input**, paste your RTSP URL: `rtsp://192.168.1.105:8554/live`
   - In **Input Format**, type: `rtsp`
   - In **Network Buffering**, select: **1 MB** (or Low Latency).
   - Check **"Use hardware decoding when available"** (uses your laptop GPU for zero CPU load).
   - Click **OK**.
   - *Your wireless camera feed is now live in OBS!*

---

## 2. Setting Up Broadcast Graphics (Video Fusion / Strips)

For a professional Bhagwat Katha / religious event broadcast, set up these layers in OBS:

```
[ Layers in OBS (Top to Bottom) ]
1. Logo Watermark (Top Left / Right)
2. Katha Vachak Name Strap / Lower Third (Bottom)
3. Scrolling Text Ticker (Breaking news / Bhajan lyrics / Mandal info)
4. Donation / UPI QR Code Overlay (Top Right or Bottom Corner)
5. Main Video Feed (Wireless Katha Camera)
```

### A. Katha Vachak Name Strap (Lower Third)
* Create an image with transparent background (PNG format, 1920x250 px) with:
  * "परम पूज्य [व्यास जी का नाम]"
  * "श्रीमद् भागवत कथा ज्ञान यज्ञ - दिवस 1"
* In OBS: **`+` ➔ Image ➔ Select your PNG ➔ Position at bottom**.

### B. Scrolling Text Ticker (Scrolling Strip)
* In OBS: **`+` ➔ Text (GDI+ on Windows / FreeType 2 on Mac)**.
* Enter your text in Hindi / Sanskrit:
  > *"।। श्री राधे ।। कथा स्थल: श्री राम मंदिर प्रांगण | कथा समय: दोपहर 2:00 बजे से सायं 6:00 बजे तक | आरती एवं प्रसाद वितरण प्रतिदिन सायं 6:00 बजे ।।"*
* Right-click the Text source ➔ **Filters ➔ `+` ➔ Scroll**:
  * Set **Horizontal Speed** to `50` or `70`.
  * Check **"Loop"**.
  * The text will now scroll smoothly across the bottom of the screen.

### C. Live Aarti / Bhajan Picture-in-Picture (PIP)
* If you have a second camera (e.g., dedicated shot of the deity or audience):
  * Add it as a second source in OBS.
  * Resize and place it in the bottom-right corner as a small inset box.

---

## 3. Audio Synchronization (Mixer Sound + Wireless Video)

Since wireless video over Wi-Fi takes ~50–80 milliseconds, while microphone sound from a USB sound mixer card enters immediately, you must sync them in OBS:

1. In OBS, click the **Gear icon (⚙️) on your Audio Mixer ➔ Advanced Audio Properties**.
2. Find your **Microphone / Soundboard Input**.
3. Set **Sync Offset (ms)** to: **`+70 ms`** (adjust between 50ms and 100ms until lips match the spoken words).

---

## 4. Multi-Streaming to YouTube, Facebook & Instagram

To stream to all three platforms at the same time from one laptop:

### Step 1: Install "Multiple RTMP Outputs" Plugin for OBS
* Download the free plugin: [OBS Multi-RTMP Plugin (Sorayuki)](https://github.com/sorayuki/obs-multi-rtmp/releases).
* It adds a **"Multiple Output"** dock directly inside OBS Studio.

### Step 2: Configure Stream Targets
1. **Primary Target (YouTube Live in OBS Settings):**
   * Go to `Settings ➔ Stream ➔ Service: YouTube - RTMPS ➔ Connect Account` (or paste Stream Key).
2. **Facebook Live (in Multi-RTMP Dock):**
   * Click **Add New Target**.
   * Name: `Facebook Live`.
   * RTMP Server: `rtmps://live-api-s.facebook.com:443/rtmp/`
   * RTMP Key: [Your Facebook Live Stream Key].
   * Video Settings: *Get from OBS*.
3. **Instagram Live (in Multi-RTMP Dock):**
   * Use **YellowDuck** or Instagram's native Live Producer (instagram.com/live/create).
   * RTMP Server & Key from Instagram.
   * *Tip for Instagram:* You can create a vertical 1080x1920 Canvas using the free **Aitum Vertical Plugin** for OBS!

### Step 3: Go Live!
* Click **"Start Streaming"** in OBS.
* Click **"Start"** on your Facebook and Instagram targets in the Multi-RTMP dock.
* All three platforms will broadcast the synchronized, branded Bhagwat Katha feed simultaneously!

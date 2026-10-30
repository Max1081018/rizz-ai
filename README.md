# ⚡ Rizz AI — Real-Time Texting & Dating Wingman (Android)

A native Android companion that floats directly over dating and messaging apps (Tinder, Hinge, Bumble, Instagram, WhatsApp, Telegram). It takes real-time visual snapshots of your conversations, performs deep psychological subtext analysis using **Google Gemini Flash Vision**, and gives you calibrated, high-charm reply options with 1-tap clipboard copying.

---

## 🚀 Features

- **Floating Overlay Bubble (`SYSTEM_ALERT_WINDOW`)**:
  - Unobtrusive floating head that docks to the edge of your screen.
  - Expands into a sleek glassmorphic HUD card with one tap.
- **Visual Screen Capture (`MediaProjection`)**:
  - Captures the exact chat viewport in real time.
  - Automatically identifies user vs. incoming messages, speech bubbles, and emojis without fragile OCR.
- **Deep Psychological Subtext Analysis**:
  - Decodes banter, irony, shit-tests, interest levels, and emotional investment.
  - Provides a concise psychological read before suggesting lines.
- **3 Calibrated Reply Styles**:
  - 🔥 **Smooth / Flirty**: Confident charm creating attraction.
  - 😏 **Playful Tease**: Lighthearted wit and bantering pushback.
  - 🎯 **High-EQ Closer**: Effortless transition to dates or exchanging numbers.
- **One-Tap Clipboard Copy**:
  - Tap any suggestion to copy it to your clipboard with subtle haptic vibration.
- **Custom Wingman Personas**:
  - Switch between *Smooth & Flirty*, *Playful & Teasing*, *Chill & High-EQ*, and *Direct & Bold*.
- **Secure Key Storage**:
  - Free Google AI Studio API key stored using `EncryptedSharedPreferences`.

---

## 🛠️ Architecture

```
app/src/main/java/com/rizz/ai/
├── MainActivity.kt               # Setup UI, API key input, permissions & launch trigger
├── FloatingRizzService.kt        # Foreground overlay HUD with draggable bubble
├── ScreenCaptureService.kt       # MediaProjection engine capturing screen frames
├── ChatAccessibilityService.kt   # Optional accessibility listener for chat updates
├── PreferencesManager.kt         # Secure preferences for API key & chosen persona
└── model/
    └── RizzModels.kt             # Data models for Gemini Vision REST API & JSON schemas
```

---

## 📦 How to Build the APK

### Method 1: Automated Cloud Build via GitHub Actions (Recommended)
You don't need Android Studio or a high-end PC installed locally. A ready-to-use GitHub Actions workflow is included:
1. Initialize git and push this folder to your GitHub repository:
   ```bash
   cd ~/rizz-ai
   git init
   git add .
   git commit -m "Initial commit of Rizz AI"
   git remote add origin https://github.com/YOUR_USERNAME/rizz-ai.git
   git branch -M main
   git push -u origin main
   ```
2. In your GitHub repository, click on the **Actions** tab.
3. Once the **Build Rizz AI APK** workflow completes, download `rizz-ai-debug-apk` from the **Artifacts** section!
4. Install the `.apk` on your Android device.

### Method 2: Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `~/rizz-ai` folder.
3. Let Gradle sync dependencies.
4. Connect your Android phone or start an emulator.
5. Click the green **Run (▶)** button to install and launch.

---

## 🔑 Setup & Permissions

1. **Gemini API Key (Free)**:
   - Visit [Google AI Studio](https://aistudio.google.com/) to generate a free Gemini API key.
   - Enter it into the Rizz AI main screen and tap **Save Key**.
2. **Display Over Other Apps**:
   - Tap **Grant** on the permission card and toggle allow for Rizz AI.
3. **Launch**:
   - Tap **Launch Floating Wingman**. Accept the screen capture prompt.
   - Switch to WhatsApp, Instagram, Tinder, Hinge, or Bumble and tap the floating bubble anytime you need a witty response!

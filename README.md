<h1 align="center">Alert Battery Full</h1>
<p align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png" width="256" alt="AppIcon">
</p>

<p align="center">A lightweight Android application designed to alert you when your battery reaches a specific charge level to protect battery health</p>

## 🚀 Key Features

* **Master Switch:** Toggle the monitoring service ON/OFF easily.
* **Full Battery Alert:** Triggers at your custom percentage (e.g., 80% or 100%) **only while charging**.
* **Auto-Dismiss:** Dismisses notifications automatically upon unplugging.
* **Privacy & Local Persistence:** Settings are saved locally using `SharedPreferences`.

## 🛠 Advanced Customization Options

* **Visual, Audio (Beep/TTS), & Haptic Feedback:** Customizable notification channels with independent controls.
* **Respect System State:** Option to keep the app muted on Silent/Vibrate modes.
* **Background Resilience:** Includes battery optimization exclusion and `BootReceiver` for automatic restart after reboot.

## 📋 Requirements

* **Min. SDK:** Android API 26 (Android 8.0+)
* **Target SDK:** Android API 34 (Android 14)
* **Language:** Kotlin 1.9+
* **UI Toolkit:** Jetpack Compose con Material Design 3

## 📦 Installation via USB Cable (ADB)

To install the generated APK directly from your computer to your phone via cable, make sure you have **Developer Options** and **USB Debugging** enabled on your Android device, then follow these steps:

1. **Connect your device:** Plug your phone into your computer using a USB cable.
2. **Verify connection:** Open your terminal and run the following command to ensure your device is recognized:
   ```bash
   adb devices
   ```
3. **Install the APK:** Navigate to the folder where the APK was downloaded and execute:
   ```bash
   adb install AlertBatteryFull.apk
   ```
   *(Note: If you are updating an existing installation, use `adb install -r AlertBatteryFull.apk` to reinstall and keep your saved settings).*

## 👤 Personal Project Policy

This app is a **Personal Project**.

This means that this is something I created in my free time because I needed it myself, and I
decided to share it with the world.

If you like the app I'm happy to hear that! And if you have suggestions or if you find any bugs
please do let me know!

However, please be aware that you're not entitled to:

- Receiving support
- Having any bugs fixed
- Having any features added

If you would like to make the changes yourself, you're very welcome to send me a pull request.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! Feel free to check the issues page if you want to contribute.

## 📄 License

This project is licensed under the GNU General Public License v3.0 - see the LICENSE file for details.

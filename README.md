# Repo Launcher

An Android launcher combining a Lawnchair-like home screen with **Obtainium-style GitHub repo APK installation**.

Long-press the home screen → choose **Wallpaper & style**, **Widgets**, or **Settings**.  
Swipe up from anywhere on the home screen to open the app drawer with momentum physics.

## Features

- **Workspace layout** — Add apps to home screen and dock, move between them
- **App drawer** — Scrollable grid of all installed apps with search bar
- **GitHub repo installer** — Add a GitHub repo (e.g. `ImranR98/Obtainium`), browse releases, download and install APKs via Shizuku
- **Lawnchair backup import** — Import `.lawnchairbackup` files to restore home screen layout
- **Lawnchair-style long-press context menu** — Wallpaper & style, Widgets, Settings
- **Full settings** — Customize grid columns/rows, dock icon count, icon size, labels, search bar
- **Swipe gesture** — Momentum-based drawer open/close from anywhere on the home screen

## Build

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
```

**Requirements**: Java 17+, Gradle 8.11.1, Android SDK API 35+, Build Tools 35+

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or via Shizuku:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/ && pm install -r /data/local/tmp/app-debug.apk
```

## License

GPL-3.0-only — see [LICENSE](LICENSE)

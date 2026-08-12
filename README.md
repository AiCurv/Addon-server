# Addon Server

Android TV application that embeds an HTTP server (Port 7000) for Stremio addons AND an internal Telegram Bot client with interactive Inline Keyboard UI to remotely update Cloudflare cookies and User-Agent strings.

## Architecture

- **Local Stremio Server**: Foreground Service with WakeLock + WiFi lock, HTTP server on `127.0.0.1:7000`
- **Embedded Python**: Chaquopy plugin runs `addon_server.py` for HTTP routing and dynamic header injection
- **Telegram Bot**: Long-polling with InlineKeyboardMarkup for remote control
- **Hot-Reload**: Config updates via Telegram are applied instantly without app restart

## Target Device

- Skyworth SWTV-22AE-FHD (Android 11, API 30)
- MediaTek mt5867 (armeabi-v7a, 32-bit)
- 1.5GB RAM - memory-optimized

## Build

```bash
./gradlew assembleDebug
```

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Stremio Setup

Add the addon in Stremio: `http://<TV_IP>:7000/manifest.json`

## Telegram Bot Commands

- `/start` or `/menu` - Show interactive control panel
- Inline buttons: 🔄 Status | 🍪 Update Cookie | 🌐 Update UA | 📋 List Addons

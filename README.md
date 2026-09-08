<div align="center">

# LogLab

**No root · No PC · No cable — an Android app that captures your phone's logcat right on the phone**

[English](README.md) | [简体中文](README_zh-CN.md)

[![Release](https://img.shields.io/github/v/release/resooo/loglab)](https://github.com/resooo/loglab/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://github.com/resooo/loglab/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

</div>

LogLab is a log capture tool with an embedded ADB protocol implementation. Using the TLS pairing mechanism of Android Wireless Debugging, the phone becomes its own logcat terminal — no computer, no root, no cable.

## ✨ Features

- **Log capture**: combined filters by buffer (main / system / crash…), tag, process and priority, plus keyword search
- **Live tailing**: streaming `logcat -T` output with a foreground service, auto re-attach when the target process restarts (PID follow)
- **On-launch capture**: start capturing before the target app launches — never miss the startup moment
- **Crash monitoring**: keeps the app's crash reports with full stack traces; today / last-7-days filter, app icons, one-tap history replay
- **One-tap export**: export as `.log` / `.gz`, share or save anywhere
- **In-app updates**: checks GitHub Releases at most once every 24 h, shows release notes, downloads and launches the installer
- **Loopback-first connection**: connects to `127.0.0.1:<port>` learned from mDNS — switching Wi-Fi networks never breaks the connection (LAN-IP fallback kept)
- **Dual channels**: embedded ADB protocol (arm64) or HostBridge remote gateway
- **Bilingual UI**: follow system / 中文 / English

## 📲 Install

1. Grab the latest APK (arm64-v8a) from [Releases](https://github.com/resooo/loglab/releases/latest)
2. If prompted, allow "install unknown apps"
3. On first use, follow the in-app guide to pair once with Wireless Debugging (enable it in Developer options)

> Pairing note: the pairing code/port is only valid while the "Pair device with pairing code" dialog is open.
> On some ROMs (e.g. ColorOS) the code refreshes when you switch apps — use split-screen / floating window as guided in-app.

## 🔧 Build

```bash
git clone https://github.com/resooo/loglab.git
cd loglab
./gradlew assembleRelease
```

- Requires JDK 17 and Android SDK (compileSdk 35 / build-tools 34)
- The release signing keystore defaults to `app/keystore/debug.jks` (not committed). Use `assembleDebug` for local debugging
- CI (GitHub Actions) injects signing secrets: `KS_FILE`, `KS_PASS`, `KEY_ALIAS`, `KEY_PASS`

## 🚀 Release flow

```bash
# 1. Bump versionName / versionCode in app/build.gradle.kts
# 2. Commit and tag (the tag must match versionName)
git tag v1.8.0
git push origin main --tags
# 3. GitHub Actions builds the release APK and attaches it to the Release
```

The in-app "Settings → Check updates" fetches `releases/latest`, compares the tag with the local version, and downloads + installs when a newer version is found.

## ⚠️ Privacy & Disclaimer

- Logs may contain sensitive information (accounts, tokens, personal data…) — review before sharing/exporting
- The app only uses Android's official Wireless Debugging (ADB over TLS): no root, nothing uploaded
- Use at your own risk

## 📄 License

[MIT](LICENSE)

# Changelog

All notable changes to LogLab are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/), versioning follows [SemVer](https://semver.org/).

## [1.8.3] - 2026-09-08

### Fixed
- **False "services found but cannot connect" when wireless debugging is off**: after toggling wireless debugging off, adbd keeps accepting TLS handshakes for a short while ("half-dead") and the system mDNS cache keeps stale service advertisements. The startup check now tracks the "handshake OK but echo fails" (adbd half-dead) signal across the direct connect and every candidate; when it appears, the verdict is **"Wireless debugging is off" (DebugOff)** — stale mDNS advertisements no longer mask it. Users now get the "Enable" shortcut instead of a misleading diagnosis.
- Capture failure caused by a refused connection (port no longer listening) now shows a friendly "wireless debugging may be off" message instead of a raw `ECONNREFUSED` exception text.
- Version 1.8.3 (22)

## [1.8.2] - 2026-09-08

### Fixed
- **English localization gaps closed**: startup-check results and live phases ("Detecting connection" / "Not paired" / "Wireless debugging is off"…), the connect (pairing) page, the guide page, the log detail sheet ("Copy raw" / "Filter by this tag"), the capture stats line ("N lines · N ms") and the app-picker dialog are now fully localized (en/zh).
- Home status bar "Connect" button was hardcoded Chinese regardless of locale.

### Changed
- **Live tail "Proc" chip now opens the app picker directly** (same as the home screen); the manual package-name input row is gone.
- **Wireless debugging pairing now reports the device name as "LogLab"** (was "logcat-grabber"); the ADB public key comment is "LogLab@android" too. Devices already paired keep the old name until re-pairing. (Internal keystore alias `logcat_grabber_adb_rsa_v1` and the signing `keyAlias` are unchanged — renaming them would break existing keys.)
- Version 1.8.2 (21)

## [1.8.1] - 2026-09-08

### Fixed
- **`Only one --pid argument can be provided.`**: logcat accepts a single `--pid`; the command now uses only the first PID while multi-PID filtering stays on the result side (no behaviour change for single-process apps).

### Changed
- **Reset pairing now navigates to the pairing page** and disconnects the channel immediately, so the home screen shows "not paired" right away (previously it kept showing "connected" until app restart).
- **Connection page is loopback-first** (consistent with the startup check): picking a scanned device connects to `127.0.0.1:<port>` (LAN IP as fallback); after pairing, `127.0.0.1` is tried first instead of the LAN address; scanned devices are displayed as `127.0.0.1:<port>`.
- **Live tail screen is now localized** (English/Chinese), including the shared status bar (checking / connected / not connected / actions).
- **Process picker on the capture screen**: tapping "Proc" opens the app picker directly (the manual text input row is gone).
- **Shorter quick chips**: "只看错误" → "错误" (Errors), "启动抓取" → "抓启动" (On-launch).

## [1.8.0] - 2026-09-06

### Added
- **In-app update check on launch**: checks GitHub Releases at most once every 24 hours; when a new version is found, a banner shows on the home screen and jumps to the update dialog.
- **Update dialog now shows release notes**: the changelog text from GitHub Releases is rendered inside the dialog.
- **Crash list date filter**: switch between "Today" and "Last 7 days" (today by default); crash records are now kept for 7 days instead of 1 day.
- **App icons in crash list**: each crash row shows the crashing app's icon and label.
- **Language setting**: follow system / 中文 / English (per-app locale, Android 7.0+).
- **Redesigned About page**: project links, feedback email, and a "Built with 太墟 (Taixu)" credit.
- Bilingual README (English by default, with a link to the Chinese version).

### Changed
- **Wireless debugging now prefers `127.0.0.1` (loopback)**: the app and adbd run on the same phone, so the loopback address works regardless of Wi-Fi network or IP range changes. mDNS discovery is only used to learn the current port; LAN-IP fallback is kept for extreme ROM cases. Switching networks no longer breaks the connection or requires a slow re-scan.
- Home screen quick-chip order: "Errors only" → "Startup capture" → buffers → search mode.

## [1.7.3] - 2026-09-05

### Fixed
- Aligned `versionCode`/`versionName` (18 / 1.7.3) so the in-app update check no longer loops on a stale tag.

## [1.7.2] - 2026-09-05

### Changed
- **Crash page shows only today's crashes** by default: `logcat -b crash` replay is bounded with `-T <today 00:00:00.000>`; the store also filters on load/add/background so old crashes stop re-appearing.

### Fixed
- Explained `com.logcat.monitor` crashes (a third-party app missing `FOREGROUND_SERVICE_SPECIAL_USE`, unrelated to LogLab).

## [1.7.1] - 2026-09-05

### Fixed
- **Crash parsing rewrite**: Java crash stack traces are printed line-by-line by `AndroidRuntime` (each line a separate log entry); blocks are now aggregated by tag+PID so crashes no longer split into fragments.
- **Native crash app names extracted** from `in tid N (process)` / `>>> process <<<` patterns; no more "Unknown app" rows.
- Subtitle no longer shows "Java crash · Java crash" duplicates; falls back to the thread name as summary.

## [1.7.0] - 2026-09-04

### Added
- **Buffer multi-select** on capture / tail / export screens (main, system, crash, radio, events, stats).
- **Errors-only filter** quick chip.
- **Startup capture**: waits for a chosen app to launch, marks the startup point, optionally shows only post-start logs.
- **PID follow mode**: watches the target process every 2 s (with debounce) and re-attaches automatically when the process restarts.
- App picker dialog with icons, running/foreground badges and recent-apps ranking.

## [1.6.x]

- Smart startup check: direct connect + mDNS scan in parallel, ghost-cache defence with automatic re-scan, echo verification before trusting a connection, rollback on failed candidates.
- Pairing flow, dual channel (ADB / HostBridge), export, crash monitor foreground service, theming.

[1.8.1]: https://github.com/resooo/loglab/releases/tag/v1.8.1
[1.8.0]: https://github.com/resooo/loglab/releases/tag/v1.8.0
[1.7.3]: https://github.com/resooo/loglab/releases/tag/v1.7.3
[1.7.2]: https://github.com/resooo/loglab/releases/tag/v1.7.2
[1.7.1]: https://github.com/resooo/loglab/releases/tag/v1.7.1
[1.7.0]: https://github.com/resooo/loglab/releases/tag/v1.7.0

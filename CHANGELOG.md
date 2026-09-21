# Changelog

All notable changes to LogLab are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/), versioning follows [SemVer](https://semver.org/).

## [1.9.6] - 2026-09-21

### Added
- **Auto-enable wireless debugging.** Once LogLab has been paired at least once, it can switch
  "Wireless debugging" on by itself — no more digging through Developer options after a reboot or
  a system-initiated toggle-off:

  1. **Self-grant** (`SelfGrant`): after a successful connection LogLab runs
     `pm grant <itself> WRITE_SECURE_SETTINGS` over its own ADB channel. ADB shell is allowed to do
     this, which is exactly how [Shizuku](https://github.com/RikkaApps/Shizuku) obtains the
     permission; no root, no Shizuku, no PC required.
  2. **Auto-toggle** (`WirelessDebugSettings`): with the permission in hand, LogLab writes the
     three `Settings.Global` keys `adb_wifi_enabled=1`, `ADB_ENABLED=1` and
     `adb_allowed_connection_time=0` (0 = never expire), then re-discovers the port.

  First-time pairing still has to be done by hand — the 6-digit pairing code is only shown in
  system UI and cannot be read by apps.

  The grant + toggle flow is now also available from **Settings → Auto-enable wireless debugging**,
  so it can be set up deliberately rather than only being reachable as a repair action on the
  Connect screen. The row shows a Granted / Not-granted badge and runs both steps from one button.

### Changed
- **Startup checks are skipped when nothing can have changed.** The check used to run on every
  `ON_RESUME`, which in the worst case (wireless debugging off) walks through
  direct-connect → toggle the switch → first mDNS round → second mDNS round (up to 15 s) →
  port scan, i.e. 40 s+ of work for what is almost always an unchanged connection. It now skips when
  **both** the last full check was under 30 s ago **and** the channel is still connected; the
  explicit "Retry" button always bypasses the shortcut.
- **The post-reboot port wait window is 15 s instead of 6 s when LogLab enabled wireless debugging
  itself.** Measured on a OnePlus / Android 16: after writing `adb_wifi_enabled=1`, adbd needs
  roughly 8 s to restart and re-advertise (00:47:12 toggle → 00:47:20 new port visible). The default
  6 s window expired first and wasted a round.

### Build
- **APK is roughly 1.2 MB smaller.** `bcprov` ships post-quantum lookup tables (Picnic / SPHINCS)
  as `.properties` resources, which `shrinkResources` does not remove; they accounted for ~22% of
  the APK. LogLab only uses RSA for ADB authentication, so `org/bouncycastle/pqc/**` is excluded.
- Onboarding images downscaled to 480 px and re-encoded (~62 KB saved).
- Debug builds now run R8 + resource shrinking too, since the previous unminified debug APK was
  24.4 MB versus 5.2 MB for release.
- Added `scripts/prepare-build.sh`: reclaims memory held by leftover Gradle/Kotlin daemons before
  building. Release builds were intermittently hitting `R8: OutOfMemoryError` because the sandbox
  had as little as 2.4 GB free; killing stale daemons frees 1–1.5 GB. See
  `docs/BUILD_TROUBLESHOOTING.md`.

### Internal
- Foreground-service notifications extracted into `ServiceNotification`, removing ~110 lines of
  near-verbatim duplication between `LogTailService` and `CrashMonitorService`.
- Inline fully-qualified class names cleaned up in 39 places.

> **Reverted during development.** An attempt to restructure `StartupCheck` into a step pipeline,
> unify port liveness checks on a `bind()`-based probe, and change `KadbAdbBackend.probe()` from
> `connectionCheck()` to an explicit `shell("echo")` call **was reverted before release** — it
> regressed the (previously working) connection flow. `kadb.connectionCheck()` was the correct
> API all along; the change was based on sandbox-level socket experiments that do not reflect the
> third-party library's actual behaviour. All of `core/` is byte-identical to v1.9.5's connection
> logic. Lesson recorded in `docs/AUTO_WIRELESS_ADB_PLAN.md`.

## [1.9.5] - 2026-09-14

### Fixed
- **Reading crash history reported "nothing found" — the `-T` time format was wrong** (introduced in 1.9.4, fixed here). `logcat`'s `-T` accepts only `MM-DD HH:mm:ss.mmm` (**no year**). The 1.9.4 build switched it to ISO 8601 (`2026-09-14T00:00:00.000`), which `logcat` fails to parse literally and consequently filters out **every** line. Measured on a OnePlus / Android 16 with 69 lines in the crash buffer:
  | `-T` value | lines returned |
  |---|---|
  | `09-14 00:00:00.000` | ✅ 69 |
  | `2026-09-14T00:00:00.000` | ❌ 1 |
  | `09-14` | ❌ 0 |

  Both call sites (`CrashViewModel.readHistory`, `CrashMonitorService`) are back to the year-less format.
- **tombstone-format native crashes were discarded entirely.** `CrashParser` only recognised the legacy `Fatal signal 11 (SIGSEGV)` wording, but on Android 10+ debuggerd writes a different shape into the crash buffer:
  ```
  *** engrave_tombstone_ucontext ***
  signal 5 (SIGTRAP), code 1 (TRAP_BRKPT), fault addr --------
  ```
  The signal line has **no `Fatal` prefix**, so Chrome / WebView / every NDK app that crashed this way was treated as an ordinary log line and dropped — "the crash buffer has data but zero records are parsed". Added `TOMBSTONE_HEADER` / `SIGNAL_LINE` patterns, a tombstone branch in `flush()`, boundary detection for the tombstone header, and signal-name extraction for the new shape. Verified offline against a real 69-line buffer: **0 → 2 records**.
- **The list did not refresh after loading history** — records were only shown after switching the time-range tab. `filteredEvents` was a `derivedStateOf` reading `events.value`, and **`StateFlow.value` is a plain property access that does not register a Compose snapshot dependency**, so the derived state never learned that `events` had changed. `range` *is* a `mutableStateOf`, which is why switching tabs appeared to fix it. `events` is now mirrored into a real `MutableState` (`eventsSnapshot`, kept in sync from `init`), and both `filteredEvents` and `contentVersion` read that.
- **Native crashes showed as "unknown app (package not resolved)".** The tombstone process line is
  `pid: 18683, tid: 18708, name: CrRendererMain  >>> com.android.chrome:sandboxed_process0:org.chromium... <<<`
  The old pattern required `>>>` to be immediately followed by `<<<`, but the text in between contains `:` — and `[\w.$]` does not match `:`, so the match failed. The pattern is now `>>>\s*([\w.$]+)` and `Process name is <pkg>` is tried first (it is the most reliable tombstone source).

## [1.9.4] - 2026-09-14

### Fixed
- **After turning wireless debugging off and on again, the app could not find the new port** (OnePlus / Android 16, reproduced from logcat).
  - *Root cause*: the system mDNS resolver (`mdnsd` behind `NsdManager`) keeps serving **stale records** for the old ports. Logs show it repeatedly advertising `35939` / `40357` — ports that are long dead (TLS handshake still succeeds, but the adbd behind them is half-dead, so `echo` fails) — while the **real new port never appears in any advertisement**, even after waiting 60+ seconds. mDNS had become unreliable as the sole source of port discovery on this ROM.
  - *Fix*: added `PortProbe`, an active loopback port scanner used as a fallback once both mDNS rounds fail. It scans the known-port neighbourhood (±3000) first, then the full `30000–65000` range concurrently (loopback connects are ~1 ms; measured **1.6 s** for the whole range), and returns at most 8 candidates. It only tests TCP reachability — adbd identity is still confirmed by the `echo` re-verification, so ports belonging to other apps are rejected naturally.
  - *Result*: the log shows the true port found and adopted in ~1.6 s (`45311 → 44245`), fully automatic, where the previous build reported "wireless debugging is off".
- **App exit now clears the mDNS cache association.** Since Android exposes no API to flush the system mDNS cache, two measures are combined on `ON_STOP`: `stopServiceDiscovery` (which makes the resolver drop the cache entries tied to that discovery session, so the next launch queries afresh instead of reusing stale results) and a TCP liveness probe of the known ports, whose failures are recorded in a 30-minute stale list so they are tried last on the next launch even if the system still returns them.

### Fixed
- **Crash records were captured but never appeared in the list** (two independent causes):
  1. *The parser held the event back.* `logcat -b crash` has a **trailing silence** problem — after a crash is written, the stream may stay quiet for a long time (possibly forever). `CrashParser` only emits a block when the *next line* arrives or the stream ends, so a crash reproduced while the user was in another app stayed inside the parser's `current` block. The "Read history" action worked because `logcat -b crash -d` terminates and flushes — which is exactly why the record was visible there but not in the list. `CrashParser.isSegmentBoundary()` now detects the end of a block and publishes it immediately; continuation lines (bare stack frames, same-tag follow-ups) are never mistaken for a boundary.
  2. *The UI never recomposed.* Crash monitoring runs in a foreground service, so data written while the app is backgrounded had no recomposition trigger on return. Added `RefreshOnResume` (fires on `ON_RESUME` only — no polling) plus a list anchor that invalidates the `LazyColumn` keys, covering the "Last 7 days" tab where new records are inserted mid-list rather than appended.
- **Crash page app icons never rendered.** `PackageManager.getApplicationIcon()` returns the framework default icon for some packages — no exception, just the wrong drawable. Switched to `getApplicationInfo(...).loadIcon(pm)` and made "no real icon" an explicit `null` so the UI draws a placeholder instead of a fake icon. Also fixed the bitmap cache key (was the `Drawable` *instance*, which is a new object on every recomposition → bitmap rebuilt every frame).
- **Clear made no visible change on the crash page.** Regression from the list-anchor change above: item keys were built from a refresh anchor that `clear()` did not advance, so `LazyColumn` reused the stale items. `CrashViewModel.clear()` now advances the anchor and resyncs its snapshot.
- **Dark mode was unusable: the top-bar title and group headings were invisible.** The `V4` design tokens read `isSystemInDarkTheme()` (the *system* switch) while `MaterialTheme` was driven by the *app's* own dark setting. With system light + app dark, the palette returned a dark-on-dark foreground over a dark background. Tokens now read a `LocalV4Dark` CompositionLocal published by `LogLabTheme`, so the palette and the color scheme can never disagree. Four further tokens (`IconIdleResolved`, `DisabledResolved`, `DisabledTextResolved`, `SubtleResolved`) got dark variants, since their light values washed out on a dark background.
- **"Enable wireless debugging" opened the pairing page instead of the developer options.** `StartupCheck` already distinguished `DebugOff` / `NeedPairing` / `NotReachable` in the core layer, but the UI collapsed all three into a single "wireless debugging is off" card wired to the pairing screen. Added `DevSettingsLauncher` (three-step intent fallback to the developer options) and split the status card and status-dot colour per case.

### Added
- **Share diagnostics** on the crash page (⋮ menu): crash records + runtime log + device/version info in one shareable text, so "it captured but did not show" can be diagnosed without guesswork. `CrashMonitorService` also logs each parsed record with package / type / timestamp / whether it was actually stored, making de-duplication and retention-window rejections visible.

### Changed
- Crash page: the "Start/Stop monitoring" pill left the quick-action row (the bottom-right FAB owns capture start/stop; having both was redundant). The FAB reads **"开始" / "Start"**. Quick-action row is now *read history · menu · ⋮*.
- Crash page: added the app-icon placeholder (app-name initial on a primary-tinted tile) matching the process picker, and made row icons decode at 96px with the package name as cache key.
- Capture page labels: **「抓启动」**, **「选进程」** (English `Auto`, `Select app`).

## [1.9.3] - 2026-09-11

### Fixed
- **The capture-on-start toggle disappeared from the capture page**: in 1.9.2 it was rendered as a pill whose label is only drawn in the `expandable` branch of `V4RoundIconButton`. It also collided visually with the primary FAB, which was labelled "Capture" — two different buttons that looked like the same action. The toggle now shows an explicit **"Start" / "启动"** label again, and the primary FAB was renamed to **"开始" / "Start"** so the two are unambiguous.

### Changed
- **Quick-action rows reordered by usage frequency**: the most-used action now sits leftmost on each page. Capture: *start-on-launch toggle · process chip · search · filter*. Live: *pause · stop · process chip · search · keywords*. Crash: *monitor toggle · read history · menu* (read-history was promoted out of the ⋮ menu, and still remains there).
- **Crash page FAB label**: "Monitor" → **"Listen" / 「监听」**.
- **Log list no longer prints the leading timestamp**: `MM-DD HH:mm:ss.SSS` repeated on every row ate roughly half the screen width on a phone and pushed the actual message off to the right. Rows now render only `[level] Tag: message` (unparsed continuation lines — stack traces — are still shown verbatim). Nothing is lost: the detail sheet now shows the timestamp in its header, and both the copy button and the exported file still use the full original line including the timestamp.

## [1.9.2] - 2026-09-11

### Fixed
- **Crash page did not refresh after clearing**: `CrashViewModel.filteredEvents` was a plain getter (`get() = events.value.filter { ... }`), which Compose cannot register as a snapshot dependency — clearing or adding records left the stale list on screen. It is now a `derivedStateOf` over `events` / `range`, so the list recomputes and the UI repaints the moment the store changes.

### Changed
- **Live page FAB label**: the bottom-right monitor button now reads just **"Monitor"** instead of "Start monitoring".
- **Copy / clear moved next to the capture button (all three pages)**: ⧉ copy and 🗑 clear left the quick-action row and now sit as smaller 40dp circles stacked **above** the main 56dp FAB, bottom-right. They only appear once there is content to act on (log lines / crash records), so empty pages no longer show buttons that do nothing.
- **Capture page: "Capture on app start" moved out of the ⋮ menu** to the quick-action row, directly to the right of the process chip, as a toggleable pill (primary-tinted while on — matching the crash page's monitor pill).

## [1.9.1] - 2026-09-11

### Fixed
- **Live page crashed on open** (`IllegalFormatConversionException: d != java.lang.Float`): the v4 stats line for the live page used `%2$d 行/秒`, but `ratePerSecond` is a `Float` — the previous wording used `%2$.1f`. Corrected to `%2$.1f` in both locales. Every other formatter added in 1.9.0 was re-audited against its call sites and is type-correct.
- **v4 top bar overlapped the system status bar**: the host `Scaffold` runs with `contentWindowInsets = WindowInsets(0,0,0,0)` (edge-to-edge), and the old layout relied on Material3 `TopAppBar` to absorb the status-bar inset. The custom `V4TopBar` did not, so the title sat underneath the status icons. `V4TopBar` now applies `windowInsetsPadding(WindowInsets.statusBars)` itself (with an `applyStatusBarInset` escape hatch for pages that already handle it).

## [1.9.0] - 2026-09-11

### Changed — UI rebuilt to the v4 layout spec

A full layout pass across the capture, live and crash pages, following the v4 design sheet.

- **Minimal top bar**: it now carries only the title plus a **7dp status dot** (green = connected, amber = checking, red = error/off) — the dot is tappable and opens the connect page. The `?` / filter / export icon buttons that used to sit in the top bar are gone.
- **New dedicated quick-action row**: leading **process chip** (34dp pill, primary when an app is chosen, grey "Process" placeholder otherwise), then 34dp round buttons for **search ⌕ · filter ☰ · copy ⧉ · clear 🗑**, followed by a spacer that pushes **⋮ (more)** to the far right. The process chip leads because picking the process is always step one of a log session; the spacer keeps "more" away from the frequent actions to avoid mis-taps.
- **Status moved into a centered card overlay**: "Checking connection…" shows a spinner plus the live phase line; a failure shows ⚠ + "Wireless debugging is off" with [Enable] / [Retry]. The log list stays visible behind it (dimmed to 28% on failure) instead of the old always-reserved status row.
- **Floating capture FAB** (56dp, bottom-right, 14dp inset): "Capture" in primary blue, "Stop" in red while running, grey when disabled. The log area now takes nearly the full remaining height.
- **Search and filter became bottom sheets** — the page no longer keeps a permanent search field or chip row:
  - *Search sheet*: input with live filtering, match mode (**Filter (matches only)** / **Highlight (keep all, mark yellow)**), recent searches, [Cancel] [Apply].
  - *Filter sheet*: log level (V all / D / I / W and above / E), max lines kept (200 / 1000 / 5000 / 20000 / 100000), buffers (main / system / events / crash, multi-select), process & misc (app, keep capturing after start), [Reset] [Done]. All edits are staged locally and only applied on "Done".
  - *Process picker sheet*: search box plus flat rows (app name + package + ✓), [Clear selection] [Done] — replaces the old icon-list dialog.
- **⋮ overflow menu for the low-frequency items**: Export to file / Capture on app start (with On/Off state) / Buffers (with current value) / How to use.
- **Crash page**: quick-action row is now a wide "Start monitoring" pill (becomes "Stop monitoring") plus ☰ ⧉ 🗑 ⋮; records render as compact flat rows (app name + red timestamp / red "type · summary" / grey package) separated by 1dp lines; the stats line reads "Today · N records"; the FAB turns red "Stop" while monitoring. The time-range switch (Today / Last 7 days) moved into the ⋮ menu.
- **Live page**: quick-action row is process · ⌕ · ☰ · ⏸/▶ · ⏹ · ⋮; the rate moved into the stats line ("Showing 1000 lines · 12 lines/s"); the FAB reads "Stop" while running.
- **Log line colours aligned with the design sheet**: info `#1FA95E`, warn `#D98A16`, error `#E5484D` (verbose/debug unchanged), so the terminal palette matches the spec exactly.

### Fixed
- **Localization gaps closed for runtime status text**: capture/live/crash statuses (`Probing channel…`, start-up capture progress, history-read results, share/start failures) and every copy Toast are now resource-backed instead of hardcoded Chinese, so switching to English no longer leaves half the messages in Chinese.
- **Crash message colour no longer depends on Chinese keywords**: the UI used to decide red-vs-blue by `contains("失败")`, which broke in English. `CrashStore` now carries an explicit `messageIsError` flag.

### Added
- Shared v4 component set under `ui/components/`: `V4TopBar`, `V4StatusDot`, `V4QuickActionBar`, `V4ProcessChip`, `V4RoundIconButton`, `V4CaptureFab`, `V4CenterStatus` / `V4StatusCard`, `V4OverflowMenu`, `V4StatsLine`, `SearchSheet`, `FilterSheet`, `AppPickerSheet`, plus the `V4` design-token object (light/dark values for bg / surface / surface-2 / primary / line / muted).

### Removed
- Dead components after the rewrite: `HomeStatusBar` (superseded by the top-bar status dot plus the centered status card), `CfgChip` and `PackagePickerDialog` (both unreferenced). `EllipseTextField`, which used to live inside `HomeStatusBar.kt`, moved to its own file — it is still used by the connect / export / live-keyword screens.
- Version 1.9.0 (24)

## [1.8.4] - 2026-09-08

### Fixed
- **Port change after re-enabling wireless debugging is now picked up reliably**: previously the rescan-once safety net only ran when every mDNS candidate matched the archived port; a **stale cached advertisement on a different port** (ghost ads survive for hours and ports stay identical across sessions) bypassed it, so the check gave up before the real new port appeared (advertisements can lag 11s+ after re-enabling). The rescan round now runs **whenever all first-round candidates fail**, only trying ports that were not tried before — a genuine new port gets adopted automatically, a ghost ad still ends in the correct "wireless debugging is off" verdict.
- **"Wireless debugging is off — enable" prompt now actually shows on the home screen**: when a check concluded *off*, the rollback reconnect could still pass the TLS handshake (half-dead adbd), flipping the channel state back to "connected" — and the home status bar rendered the green "connected" row **before** the red failure row, hiding the prompt entirely. Failed verdicts now always disconnect the channel, and the red failure row takes priority over a (possibly stale) "connected" state.

### Changed
- **All connection addresses are now strictly `127.0.0.1`**: after several releases in production the LAN-IP fallback never fired once — every candidate, pairing, and reconnect uses the loopback address only (mDNS is used solely to discover the port; its resolved host is discarded). Failure verdicts also come faster (loopback refuses in milliseconds).
- Version 1.8.4 (23)

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

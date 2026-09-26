# Fire TV findings

## Status at diagnostic implementation

- **WORKING:** Repository inspection and independent Fire TV scaffold on `fire-tv-mvp`.
- **PARTIALLY WORKING:** Diagnostic implementation exists; build and device verification pending below.
- **NOT WORKING / NOT IMPLEMENTED:** Auto-skip and audio suppression intentionally absent until device evidence supports them.
- **NEEDS DEVICE TEST:** Installation, accessibility enablement, foreground detection, ad/Skip exposure, clickability and ancestors, ACTION_CLICK result, false positives, performance and survivability.

No physical Fire Stick behavior has been observed yet. V0 success is not established.

## Existing browser architecture

Manifest V3 loads content.js on YouTube pages and a background.js service worker. The content script watches player DOM changes, detects ad classes, remembers/restores video mute state and finds known Skip selectors. The worker temporarily attaches Chrome Debugger, requests a fresh visible click location, sends a browser input click and detaches. Chrome source files were not modified.

## Device evidence matrix

| Scenario | Foreground | Ad nodes | Skip node / ancestor | Click result | Outcome |
|---|---|---|---|---|---|
| Normal video | Untested | Untested | Untested | Not attempted | Pending |
| Skippable ad, countdown | Untested | Untested | Untested | Not attempted | Pending |
| Skip available | Untested | Untested | Untested | Not attempted | Pending |
| Non-skippable ad | Untested | Untested | Untested | Not attempted | Pending |
| Two-ad pod | Untested | Untested | Untested | Not attempted | Pending |
| Content resumes | Untested | Untested | Untested | Not attempted | Pending |

Only redacted control metadata belongs here; do not add viewing history or raw accessibility dumps.

## Build verification — 2026-09-26

- `assembleDebug lintDebug`: BUILD SUCCESSFUL (JDK 17, Gradle 8.9, AGP 8.7.3).
- Lint: zero errors, 13 warnings (English-only diagnostic UI text and simple vector banner sizing). Only the Google Play target-SDK publication rule is disabled, explicitly, for this sideload-only API 28 experiment.
- `apksigner verify --verbose`: signature verified (v2).
- `aapt dump badging`: package tv.slopoff, version 0.1-diagnostic, min/target SDK 28, TV launcher activity present; no requested network permissions.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (2,382,529 bytes).
- Gradle distribution SHA-256 verified against Gradle's published checksum and pinned in wrapper properties.
- `git diff main -- background.js content.js manifest.json`: empty.
- **WORKING:** Debug compilation, APK packaging/signature and lint checks.
- **PARTIALLY WORKING:** Diagnostic service and TV UI compile; physical runtime not yet verified.
- Device gauntlet, auto-skip viability and audio suppression remain unproven.

## First physical device connection — 2026-09-26

User approved the TV's ADB authorization prompt. `adb devices -l` then reported an authorized `device`, product/device `sheldonp`, model `AFTSSS`. `getprop ro.build.version.sdk` returned `28`. `pm list packages youtube` returned `com.amazon.firetv.youtube`, matching the diagnostic allowlist.

Commands used (session address supplied to ADB only):

```powershell
adb connect 192.168.86.30:5555
adb devices -l
adb -s 192.168.86.30:5555 get-state
adb -s 192.168.86.30:5555 install -r fire-tv/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.86.30:5555 shell pm path tv.slopoff
adb -s 192.168.86.30:5555 shell getprop ro.build.version.sdk
adb -s 192.168.86.30:5555 shell pm list packages youtube
adb -s 192.168.86.30:5555 shell am start -W -n tv.slopoff/.MainActivity
```

Observed: streamed installation returned `Success`; `pm path` confirmed the installed base.apk; activity launch returned `Status: ok` and `Activity: tv.slopoff/.MainActivity`.

- **WORKING (physical device):** ADB authorization, APK installation, package verification and activity launch.
- **NEEDS DEVICE TEST:** Accessibility enablement and service connection, UI appearance/remote navigation, and all YouTube accessibility observations. No Skip/ad signal or successful click has been observed. V0 success remains unproven.

Next physical step: select Open accessibility setup and report the options Fire OS exposes.

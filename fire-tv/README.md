# Slop Off TV — 0.1 diagnostic experiment

This is a local, Kotlin accessibility experiment for the **official YouTube app** on Fire TV Stick (3rd Gen), Fire OS 7 / Android 9 (API 28). It does not yet skip or mute ads. The Chrome extension is independent and unchanged.

## Build

Use JDK 17, Android SDK platform 35, build-tools 34.0.0, and platform-tools. Set JAVA_HOME and ANDROID_HOME to your installations. Accept the SDK licenses with `sdkmanager --licenses`.

From this directory:

```powershell
.\gradlew.bat assembleDebug lintDebug
```

On macOS/Linux: `./gradlew assembleDebug lintDebug`.

The checked-in Gradle wrapper uses Gradle 8.9; Android Gradle Plugin is 8.7.3 and Kotlin is 2.0.21. Only the Kotlin standard library is added to the Android platform APIs. Internet is needed for the first build, never by the installed app. Target/min SDK 28 intentionally match the sideloaded Fire OS 7 experiment; this is not an app-store release.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Install and enable

Replace `FIRE_TV_IP` with the device's current LAN address. No address is in the application.

```powershell
adb connect FIRE_TV_IP:5555
adb devices -l
```

If unauthorized, stop and approve **Allow USB debugging?** on the TV. Then:

```powershell
adb -s FIRE_TV_IP:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FIRE_TV_IP:5555 shell pm path tv.slopoff
adb -s FIRE_TV_IP:5555 shell am start -n tv.slopoff/.MainActivity
```

Choose **Open accessibility setup**, enable **Slop Off TV diagnostics**, and accept the system disclosure. Some Fire OS builds may not expose third-party services in that screen: report the actual screen before changing system settings. Do not overwrite the system's list of enabled accessibility services.

The UI shows the service connection, observation switch, foreground status and last candidate. Protection is explicitly marked not implemented. Buttons support D-pad focus. YouTube status describes the foreground, not whether its process is running in the background.

## Capture evidence locally

```powershell
adb -s FIRE_TV_IP:5555 shell pm list packages youtube
adb -s FIRE_TV_IP:5555 logcat -v threadtime SLOPOFF_SERVICE:I SLOPOFF_FOREGROUND:I SLOPOFF_YOUTUBE:I SLOPOFF_AD_CANDIDATE:I SLOPOFF_SKIP_CANDIDATE:I SLOPOFF_TREE:I '*:S'
```

The provisional package allowlist is `com.amazon.firetv.youtube` and `com.google.android.youtube.tv`; verify the actual installed official application on the device. A package name is not a signature verification. No interaction code exists in this diagnostic build.

To inspect YouTube's currently active tree without leaving the TV screen:

```powershell
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.DUMP -n tv.slopoff/.DiagnosticReceiver
```

The receiver requires the privileged `android.permission.DUMP` permission (normally held by ADB shell); ordinary apps cannot request dumps. Alternatively, use **Dump next YouTube tree** in the UI, return to YouTube within 30 seconds, and the next relevant event captures it. No screenshot capture is used.

JSON logs include monotonic timestamps. Foreground changes log package names only; background application text is not inspected. Routine YouTube summaries are limited to one per five seconds. Candidate details include text, description, ID, class, bounds, visibility, enabled/clickable state, click-action support, up to four parents and four children. The same candidate is logged at most once every five seconds, with at most 64 deduplication entries. These are **unverified English-language heuristics**, not reliable ad detectors. Generic Skip can be unrelated to an ad; it must never be used alone for automation.

Scans are event-triggered, coalesced at 150 ms and run on a worker thread, capped at 250 nodes, depth 20 and a 250 ms traversal budget. Individual Android IPC calls can exceed that budget. There is no idle polling, OCR, screen capture, network permission, database or volume change. Manual dumps are capped/rate-limited too; truncation or inaccessible nodes must not be interpreted as proof that no control exists. Interactive-window retrieval is enabled for follow-up investigation; this first probe traverses only the active root.

Logs stay in local logcat unless a developer saves them. Candidate context and manual dumps can contain on-screen video titles or other sensitive text; do not commit or upload raw logs or collect viewing history. Only keep minimal redacted control evidence in FINDINGS.md. Disabling diagnostics stops inspection. Force-stopping/disabling/uninstalling this app cannot leave altered volume or a stuck input because it never changes either.

## Device gauntlet and release gate

Record normal playback, skippable ad before Skip, Skip appearance, non-skippable ad, two-ad pod, and content resume. For each, record the package/window, control label/ID, visible/enabled/clickable flags and parent chain. Compare with normal UI to identify false positives. Confirm whether the tree was truncated.

Only after a stable ad-specific signal is observed should a narrowly scoped manual ACTION_CLICK experiment be added. Its return value alone is not proof of a successful skip: observe content resuming on the TV. Only then implement package-restricted, debounced auto-skip. No speculative clicking or audio suppression is included here.

After core functionality works, test reboot, YouTube restart, Slop Off restart, sleep/wake, app switching, consecutive ads, non-skippable ads, two-ad pods and a long session. Audio suppression remains separate and must restore state on all exits without permanently changing global volume.

If the active tree is insufficient: inspect alternate parents/windows first, then deterministic media/system signals, then permission-compatible lightweight visual capture only with evidence. Do not jump to OCR.

See [FINDINGS.md](FINDINGS.md) for actual build/device evidence and status.

Reference APIs: [Android accessibility services](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [Amazon installation via ADB](https://developer.amazon.com/docs/fire-tv/installing-and-running-your-app.html).

## Tested result (2026-09-26)

Installed and launched on the physical AFTSSS / API 28 Fire TV. Accessibility binding and official YouTube foreground detection work. On YouTube 25.30.r0.v283.0, captures after user-confirmed visible Skip and normal playback both exposed 16 unlabeled nodes with no click actions; a second accessibility inspector corroborated the sparse tree. There is no safe node target for auto-skip in this evidence.

Media transport capabilities differed across two ad/content pairs (55 during each ad versus 383 during subsequent playback: rewind/fast-forward/seek became available). This is only a research lead, not an ad detector or Skip-ready signal, and was read through ADB rather than by the installed app. See FINDINGS.md for limitations and outstanding tests.

## One-frame capture probe (0.2, manual experiment)

This probe tests whether normal MediaProjection consent can deliver a frame on the physical API 28 Fire Stick. It is not a visual detector and does not enable auto-skip. It adds no runtime/network/storage/audio permissions. The activity is protected by the privileged DUMP permission for explicit ADB use:

```powershell
adb -s FIRE_TV_IP:5555 shell am start -n tv.slopoff/.CaptureProbeActivity
adb -s FIRE_TV_IP:5555 logcat -v brief SLOPOFF_CAPTURE:I '*:S'
```

The TV must grant screen-capture consent. After approval, the activity moves its task to the background and waits at most 12 seconds for an active YouTube accessibility root. It then creates a 640x360 virtual display, inspects one frame in memory and releases capture. A foreground change, denial, error, timeout or activity destruction stops the experiment. A frame already in flight when foreground changes is discarded. No image is saved or transmitted. Only dimensions and aggregate brightness counts are logged. An all-black frame or visible UI pixels alone does not prove a Skip button can be captured or recognized; that requires further device evidence. The projection token is not persisted or reused.

This implementation targets the specified API 28 device and is not a production capture service for newer Android versions. Existing diagnostics remain independent.

### Pre-consented single-frame test (0.3)

To avoid a consent dialog changing focus during an ad, start this while normal YouTube is foregrounded:

```powershell
adb -s FIRE_TV_IP:5555 shell am start -f 0x10008000 -n tv.slopoff/.CaptureProbeActivity --ez armed true
```

Approve the system prompt on the television. The log reports armed_no_frames: no virtual display or frame reader exists yet. The consent grant expires in three minutes. When the user confirms a visible Skip button, capture once without opening any UI:

```powershell
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.CAPTURE_ONCE -n tv.slopoff/.DiagnosticReceiver
```

The first request is the only one accepted; capture releases after one frame or a 12-second timeout. To cancel early:

```powershell
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.STOP_CAPTURE -n tv.slopoff/.DiagnosticReceiver
```

Both broadcasts remain protected by DUMP permission. An armed grant is not an ongoing frame capture. This is still a manual experiment, not a visual detector or automatic skipping implementation.

### Offline text probe (0.4)

The armed command is unchanged, but the grant now expires after **ten minutes**. No frames are read while waiting. CAPTURE_ONCE processes one 1280x720 frame with a bundled single-threaded English Tesseract model after releasing capture. Only exact Skip-label candidates, coordinates, confidence, line counts and elapsed time are logged under SLOPOFF_TEXT; images and other recognized text are not stored or uploaded. There is no recurring screenshot/OCR loop, network permission, database or analytics backend. Only the static model is copied to private app storage. See THIRD_PARTY.md for dependencies and model checksum.

A 30-second text-processing timeout requests cancellation and ignores late results; native cancellation is cooperative, so it is not a guaranteed CPU deadline. Recognition success and confidence do not authorize clicks. The app still has no input injection or audio control. The diagnostic APK is limited to armeabi-v7a for the tested Stick. Build/lint, final manifest and device behavior must be verified before claiming this fallback works.

Version 0.5 expands the manual text probe to word-level matching and one enlarged bottom-right crop from the same frame. The 30-second cooperative recognition timeout remains shared by both passes. This does not enable automatic skipping.


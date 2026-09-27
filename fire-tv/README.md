# Slop Off TV — physical-device experiment

A local Kotlin app for official YouTube on Fire TV Stick (3rd Gen), Fire OS 7 / Android 9 (API 28). The browser extension is independent and unchanged. **Automatic protection is not active.** Version 0.6 adds an explicitly requested one-shot gesture experiment; it does not run unattended.

## Current status

| Status | Behavior |
|---|---|
| Working on the tested device | Install, accessibility binding, official YouTube foreground identification, consent-based single-frame capture and offline Skip recognition |
| Working in one controlled test | ADB tap at the recognized label skipped a paused ad; the user confirmed regular video resumed |
| Partially working | Visual recognition: both full-frame and enlarged-region passes recognized Skip at 92–93% confidence in one confirmed example, after whole-line matching failed |
| Needs device test | The app's accessibility gesture path, Sponsored marker recognition, refusal on normal content, repeated ads and non-skippable ads |
| Not implemented | Unattended ad detection/auto-skip, audio suppression, production background capture lifecycle |

Accessibility-based node clicking is not viable in the observed YouTube version: both accessibility inspectors saw 16 unlabeled nodes with no real Skip action. ADB media-session actions correlate with ad/content transitions in limited observations, but do not prove Skip readiness. See [FINDINGS.md](FINDINGS.md) for evidence and limitations.

## Build

Use JDK 17, Android SDK platform 35, build-tools 34.0.0 and platform-tools. Set JAVA_HOME and ANDROID_HOME and accept SDK licenses. From this directory:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

On macOS/Linux use `./gradlew`. Gradle 8.9, Android Gradle Plugin 8.7.3 and Kotlin 2.0.21 are pinned. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Min/target SDK 28 is intentional for sideloading on this API 28 device, not store distribution. Native ABI is limited to the tested armeabi-v7a Stick.

First build downloads dependencies; the installed app has no network permission. The bundled Tesseract recognizer and English model need no runtime download or analytics client. See [THIRD_PARTY.md](THIRD_PARTY.md).

## Install and enable

Replace FIRE_TV_IP with the current LAN address; the app contains no device IP.

```powershell
adb connect FIRE_TV_IP:5555
adb devices -l
adb -s FIRE_TV_IP:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FIRE_TV_IP:5555 shell pm path tv.slopoff
adb -s FIRE_TV_IP:5555 shell am start -n tv.slopoff/.MainActivity
```

If ADB reports unauthorized, approve **Allow USB debugging?** on the television before continuing. Enable Slop Off TV diagnostics in Accessibility. This Fire OS build hides third-party service switches; development used the user's explicitly approved ADB setup. Never overwrite an existing enabled-service list. The service now declares gesture capability for the bounded test below; the diagnostics switch also disables its input path.

## Event-driven accessibility diagnostics

```powershell
adb -s FIRE_TV_IP:5555 logcat -v brief SLOPOFF_SERVICE:I SLOPOFF_FOREGROUND:I SLOPOFF_YOUTUBE:I SLOPOFF_AD_CANDIDATE:I SLOPOFF_SKIP_CANDIDATE:I '*:S'
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.DUMP -n tv.slopoff/.DiagnosticReceiver
```

Ordinary apps cannot invoke the receiver: it requires privileged DUMP permission. The TV UI can also arm a tree dump for the next YouTube event within 30 seconds. Inspection is scoped to official YouTube package names, coalesces events, and bounds nodes/depth/traversal effort. Summaries/candidates are rate limited. Individual Android IPC calls may exceed the traversal budget. Package names are not a signature verification.

Candidate and manual-tree logs may contain on-screen text. Keep logs local, exclude raw logs from commits, and do not collect viewing history. Candidate words alone do not establish an ad or authorize automation.

## One-frame observation

```powershell
adb -s FIRE_TV_IP:5555 shell am start -f 0x10008000 -n tv.slopoff/.CaptureProbeActivity --ez armed true
```

Choose **Start now** on the TV. The grant waits for up to ten minutes without creating a frame reader or virtual display. Once the intended YouTube screen is confirmed:

```powershell
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.CAPTURE_ONCE -n tv.slopoff/.DiagnosticReceiver
adb -s FIRE_TV_IP:5555 logcat -v brief SLOPOFF_CAPTURE:I SLOPOFF_TEXT:I SLOPOFF_INPUT:I '*:S'
```

Only one request is accepted. A 1280x720 frame is processed in memory, capture stops before OCR, and two bounded passes inspect the full frame and an enlarged bottom-right region of that same frame. Only exact Skip/Sponsored tokens, positions, confidence, counts and timings are logged; images and other recognized text are not saved or uploaded. Only static model data is copied into private app storage. Capture expires after 12 seconds and recognition after a cooperative 30-second timeout; native cancellation is not a hard CPU deadline. There is no periodic screenshot or OCR loop.

Cancel an armed or processing probe with:

```powershell
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.STOP_CAPTURE -n tv.slopoff/.DiagnosticReceiver
```

The older non-armed command still captures once after consent, always in observation-only mode.

## One-shot app gesture test (0.6)

This is a manual feasibility experiment on a user-confirmed paused skippable ad, not a reliable automatic ad detector. Arm with:

```powershell
adb -s FIRE_TV_IP:5555 shell am start -f 0x10008000 -n tv.slopoff/.CaptureProbeActivity --ez armed true --ez test_skip true
```

Choose **Start now**. Confirm that the paused ad and Skip remain visible, then send the same CAPTURE_ONCE command. The app attempts at most one 50-ms gesture, only if:

- Full-frame and enlarged-region reads each find one high-confidence Skip at matching coordinates in the observed button region.
- A high-confidence Sponsored marker is present in the lower-left ad area.
- The frame is at most 7.5 seconds old, diagnostics remain enabled, the same YouTube window remains active, and no window-state event occurred since capture.
- Screen aspect ratio matches and the ten-second attempt debounce has elapsed.

No retries occur. The normal capture command never enables input. These guards reduce risk but have not established a false-positive rate; they are insufficient to claim safe unattended operation. Gesture completion only confirms Android delivered the gesture. A physical outcome check is required to prove an ad skipped.

## Remaining work

Prove app-driven input, then find a permission-compatible event trigger that avoids continuous capture/OCR. Validate normal content, live content, non-skippable ads, two-ad pods and repeated skippable ads before enabling any autonomous behavior. After that, test reboot, sleep/wake, app switching, process restarts and a long session. Audio suppression stays separate; this app never alters volume or mute state.

Reference: [Android accessibility gesture API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)).

# Slop Off TV — physical-device experiment

A local Kotlin app for official YouTube on Fire TV Stick (3rd Gen), Fire OS 7 / Android 9 (API 28). The browser extension is independent and unchanged. **Version 0.11 is experimental, not a production release.** It runs bounded automatic checks after explicit session startup and required permissions. App-driven Skip has been confirmed once; later automatic checks missed visible buttons. Version 0.11 adds contextual confirmation for weak tight-crop readings; reliable unattended skipping still needs device validation.

## Current status

| Status | Behavior |
|---|---|
| Working on the tested device | Install, accessibility binding, official YouTube foreground identification, consent-based single-frame capture and offline Skip recognition |
| Working in controlled tests | ADB targeting and then the app's own guarded accessibility gesture each skipped an ad; the user confirmed playback resumed |
| Working on the tested device | Repeated captures reuse one foreground session without another consent popup; natural ad events started bounded automatic checks |
| Partially working | Automatic skipping: visible buttons have been missed by full-frame recognition or rejected by the refinement confidence gate |
| Needs device test | Version 0.11 contextual confirmation, repeated unattended skips, normal/live-content refusal, non-skippable ads, pods and lifecycle behavior |
| Not implemented | Audio suppression, automatic recovery of screen permission after process death or reboot |

Accessibility-based node clicking is not viable in the observed YouTube version: both accessibility inspectors saw 16 unlabeled nodes with no real Skip action. ADB media-session actions correlate with ad/content transitions in limited observations, but do not prove Skip readiness. See [FINDINGS.md](FINDINGS.md) for evidence and limitations.

## Reusable session (0.7 and later)

Start from the TV app's **Start protection session (experimental)** button. Existing sessions are reused without another screen-permission prompt. For the first setup, approve Accessibility and media access, then **Start now**. The foreground service keeps the grant until Stop, revocation, a crash/process stop, or eight hours. A new grant is required after the session ends. No frame reader or virtual display exists between requests, and no pixels are stored. This API-28 implementation is not validated for newer Android projection restrictions.

Media access is Android notification-listener authorization. The implementation ignores notification contents and never reads media metadata; it uses only YouTube playback-state events and the occurrence of metadata-change events. Because this is a broader system permission, obtain explicit user approval before enabling it through development setup:

```powershell
adb -s FIRE_TV_IP:5555 shell settings get secure enabled_notification_listeners
adb -s FIRE_TV_IP:5555 shell cmd notification allow_listener tv.slopoff/tv.slopoff.MediaAccessService
```

Preserve existing listeners. To revoke only this app's access:

```powershell
adb -s FIRE_TV_IP:5555 shell cmd notification disallow_listener tv.slopoff/tv.slopoff.MediaAccessService
```

For controlled testing with the same permission grant, start a retained manual session:

```powershell
adb -s FIRE_TV_IP:5555 shell am start -f 0x10008000 -n tv.slopoff/.CaptureProbeActivity --ez armed true --ez session true --ez test_skip true
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.CAPTURE_ONCE -n tv.slopoff/.DiagnosticReceiver
```

After app input is proven on the device, turn on the event trigger without reopening the dialog:

```powershell
adb -s FIRE_TV_IP:5555 shell am broadcast -a tv.slopoff.ENABLE_AUTOMATIC -n tv.slopoff/.DiagnosticReceiver
adb -s FIRE_TV_IP:5555 logcat -v brief SLOPOFF_SESSION:I SLOPOFF_TEXT:I SLOPOFF_INPUT:I '*:S'
```

Only observed transport action values 53/55 initiate candidate checks. They are not proof of an ad. Each candidate burst permits at most six scans over 45 seconds, spaced four seconds after completion (one second for a valid borderline candidate needing temporal confirmation), with at most twelve automatic attempts per minute across all events. The ad/button visual gate remains mandatory. Normal content, app switching, missing access, disabled diagnostics and stale windows invalidate pending evidence. There is no idle screenshot/OCR timer. Missing or different transport flags, delayed Skip, unfamiliar layouts and false-positive rates need further tests. A stop action is available in the app and session notification. The system FOREGROUND_SERVICE permission is added; network/storage/audio permissions are absent.

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

Without a retained session, one request is accepted. A 1280x720 frame is processed in memory and frame delivery stops before OCR. Version 0.11 uses at most five bounded passes on that same frame: full-frame discovery, up to two regional fallbacks when needed, tight-word refinement, and a contextual confirmation fallback. Only exact Skip/Sponsored tokens, positions, confidence, counts and timings are logged; images and other recognized text are not saved or uploaded. Only static model data is copied into private app storage. Frame acquisition and recognition share a cooperative 30-second timeout; native cancellation is not a hard CPU deadline. This observation command does not start automatic checks.

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

- A full-frame or regional primary read and a separate close-up find one qualifying Skip at matching coordinates in the observed button region, with temporal confirmation for borderline close-ups.
- A high-confidence Sponsored marker is present in the lower-left ad area.
- The frame is at most 7.5 seconds old, diagnostics remain enabled, the same YouTube window remains active, and no window-state event occurred since capture.
- Screen aspect ratio matches and the ten-second attempt debounce has elapsed.

No retries occur. The normal capture command never enables input. These guards reduce risk but have not established a false-positive rate; they are insufficient to claim safe unattended operation. Gesture completion only confirms Android delivered the gesture. A physical outcome check is required to prove an ad skipped.

## Remaining work

Validate the regional recognition fix and reliable unattended skipping using the existing media-event trigger. Validate normal content, live content, non-skippable ads, two-ad pods and repeated skippable ads before a production release. Test reboot, sleep/wake, app switching, process restarts and a long session. Audio suppression stays separate; this app never alters volume or mute state.

Reference: [Android accessibility gesture API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)).

## Confidence handling (0.9)

Both the primary Skip reading and full-frame lower-left Sponsored marker must score at least 90. A matching close-up at 85 or above qualifies through the fresh-frame gate. A close-up between 80 and 85 requires another separate recent frame with the same strong ad/primary-label evidence and matching target coordinates. The prior frame must be at most twelve seconds old, at least 500 ms earlier, and belong to the same playback episode/window/window-event generation and capture dimensions. Current evidence remains limited to 7.5 seconds. Confidence is an OCR score, not a measured probability of correctness. Repeated-frame confirmation still needs normal-content and false-positive validation.

For testing, install updates first, start the retained session and approve Start now while YouTube is on its home screen, then start playback. Do not reopen the consent activity during an ad. Further tests and automatic checks reuse that grant until the session ends. APK replacement or a process/session restart requires a new grant; the app does not bypass Android's consent prompt.

## Focused discovery and consent return (0.10)

When full-frame OCR finds strong Sponsored evidence but misses Skip, a small bottom-right region is read at 3x scale. If necessary, one grayscale/inverted read of that same region is tried. Regional Skip evidence must score at least 90 and pass the same geometry, separate refinement, freshness and optional two-frame confirmation checks. The ad marker must still come from the full frame; ambiguous strong targets fail closed. At most four OCR passes use the same captured frame. Logs distinguish full-frame (0), refinement (1), region (2), and contrast-assisted region (3) readings. These are local in-memory operations; no pixels or arbitrary screen text are stored or transmitted.

After the system consent result, the setup activity consumes confirmation keys for one second before returning to YouTube. This is a guard against a remote press carrying over into the player, not proof of the cause of earlier transitions. Updates still require a fresh session grant. Prefer completing setup on YouTube's home screen before loading the test video.

## Contextual confirmation (0.11)

A strong primary reading can be followed by a poor tight-word score on a real button. Version 0.11 then rereads the bottom-right control region at 2x scale (pass 4), preserving surrounding context. This fallback must meet the existing confidence and coordinate requirements; it does not authorize a low-confidence word. Qualifying but conflicting or ambiguous confirmation readings are rejected. Borderline confirmation still needs a separate matching recent frame. There are at most five OCR passes on the same capture; freshness limits and scan budgets are unchanged.

Frame diagnostics report only aggregate sample counts for brightness and opacity. A frame whose samples are all black is discarded without OCR or input. These counts cannot confirm that a frame contains the correct screen; the visual and foreground gates remain mandatory. Empty OCR iterator entries no longer count as recognized words. Images are never saved by the app.

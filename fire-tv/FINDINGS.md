# Fire TV findings

## Current status after physical device tests — 2026-09-26

- **WORKING:** Reproducible debug build and lint, installation and launch on Fire TV AFTSSS / API 28, approved accessibility binding, official YouTube foreground identification, bounded logs and manual tree dumps.
- **PARTIALLY WORKING:** The diagnostic probe answers what this installed YouTube version exposes. Its active tree is accessible but has no useful ad/Skip controls. A media-action difference repeated across two ad/content pairs, but is not validated as an ad detector.
- **NOT WORKING / NOT IMPLEMENTED:** Node-based auto-skip has no exposed target in tested captures; automatic clicking and audio suppression remain absent. No claim that ACTION_CLICK or clean muting works.
- **NEEDS DEVICE TEST:** Repeated ad/content comparisons, non-skippable ads, countdown-to-Skip transitions, two-ad pods, live/unseekable content false positives, performance and reboot/sleep/restart survivability.

The physical diagnostic milestone is reached. The preferred hands-free skipping outcome is not achieved. Scope of evidence: official YouTube 25.30.r0.v283.0 on the tested API 28 Stick. User confirmations and captures are close in time, not frame-synchronized.

## Existing browser architecture

Manifest V3 loads content.js on YouTube pages and a background.js service worker. The content script watches player DOM changes, detects ad classes, remembers/restores video mute state and finds known Skip selectors. The worker temporarily attaches Chrome Debugger, requests a fresh visible click location, sends a browser input click and detaches. Chrome source files were not modified.

## Device evidence matrix

| Scenario | Foreground | Ad nodes | Skip node / ancestor | Click result | Outcome |
|---|---|---|---|---|---|
| Normal video | YouTube | No labels/candidates | No clickable nodes | Not attempted | 16 nodes; media actions 383 |
| Skippable ad, countdown | Untested | Untested | Untested | Not attempted | Pending |
| Skip available | YouTube | No labels/candidates | No clickable nodes/ancestors | No target; not attempted | User confirmed visible Skip; 16 nodes |
| Non-skippable ad | Untested | Untested | Untested | Not attempted | Pending |
| Two-ad pod | Untested | Untested | Untested | Not attempted | Pending |
| Content resumes | YouTube | No labels/candidates | No clickable nodes | Not attempted | User confirmed normal playback |

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

## Accessibility setup limitation — 2026-09-26

After the user opened setup, the resumed activity was `com.amazon.tv.settings.v2/.tv.accessibility.AccessibilityActivity`. A local UI hierarchy inspection showed Closed Caption, Alexa Caption, VoiceView, Text Banner, Screen Magnifier and High Contrast Text (Experimental); no Slop Off entry or scrollable node was exposed. `settings get secure enabled_accessibility_services` returned `null`; `dumpsys accessibility` showed `services:{}`. No Slop Off service-connected log was present.

An attempted ADB enablement was rejected by automatic approval review before execution because persistent accessibility access requires explicit user approval. No secure settings were changed by that rejected command. Await explicit approval before enabling `tv.slopoff/.DiagnosticService`. Preserve existing enabled-service entries and capture prior settings for reversal. Do not retry or bypass this gate without approval.

Additional read-only commands used:

```powershell
adb -s 192.168.86.30:5555 shell settings get secure enabled_accessibility_services
adb -s 192.168.86.30:5555 shell dumpsys activity activities
adb -s 192.168.86.30:5555 shell uiautomator dump /data/local/tmp/slopoff-settings.xml
adb -s 192.168.86.30:5555 pull /data/local/tmp/slopoff-settings.xml work/fire-tv-settings.xml
adb -s 192.168.86.30:5555 shell dumpsys accessibility
adb -s 192.168.86.30:5555 logcat -d -t 200 -v brief SLOPOFF_SERVICE:I SLOPOFF_FOREGROUND:I '*:S'
```

Raw settings hierarchy remains local and is not committed. YouTube observations are still untested.

## Accessibility enabled with explicit approval — 2026-09-26

User explicitly approved ADB enablement after the access scope was explained. Before changing settings, saved the local baseline: `enabled_accessibility_services=null`, `accessibility_enabled=0`. Preserved the enabled-service list and added only `tv.slopoff/.DiagnosticService`, then set `accessibility_enabled=1`.

Observed on the physical Stick:

- Enabled service list: `tv.slopoff/.DiagnosticService`.
- `dumpsys accessibility` shows Slop Off TV diagnostics bound, content-retrieval capability 1, and the three requested window/content event types.
- Touch exploration, display/navigation magnification and system autoclick all remain false.
- `SLOPOFF_SERVICE` reports `connected:true`.
- `SLOPOFF_FOREGROUND` reports `com.amazon.tv.settings.v2` with active accessibility window 13. No settings-screen text was logged by Slop Off.
- Installed version is 0.1-diagnostic, debug signing scheme v2. APK manifest has no network/audio/recording permissions; source scan finds no performAction, dispatchGesture or AudioManager operations.

Commands used:

```powershell
adb -s 192.168.86.30:5555 shell settings get secure enabled_accessibility_services
adb -s 192.168.86.30:5555 shell settings get secure accessibility_enabled
adb -s 192.168.86.30:5555 shell settings put secure enabled_accessibility_services tv.slopoff/.DiagnosticService
adb -s 192.168.86.30:5555 shell settings put secure accessibility_enabled 1
adb -s 192.168.86.30:5555 shell dumpsys accessibility
adb -s 192.168.86.30:5555 shell dumpsys package tv.slopoff
adb -s 192.168.86.30:5555 logcat -d -t 300 -v brief SLOPOFF_SERVICE:I SLOPOFF_FOREGROUND:I '*:S'
```

**WORKING (physical device):** Accessibility binding and foreground package observation for Fire TV Settings. **NEEDS DEVICE TEST:** Official YouTube foreground detection, normal playback, ad signals and Skip exposure. No automatic interaction exists and no ad has been observed yet.

Reversal: disable diagnostics using the app's observation toggle, or remove Slop Off from the current secure enabled-service list through ADB. If it is still the only enabled service, delete that list setting and restore accessibility_enabled to the saved value 0. Re-read the list first and preserve any services enabled since this test. The local prior-state snapshot is in the development workspace, outside the repository.

## Official YouTube foreground and initial tree — 2026-09-26

After the user reported opening YouTube:

- Resumed activity: `com.amazon.firetv.youtube/dev.cobalt.app.MainActivity`.
- Slop Off independently reported foreground package `com.amazon.firetv.youtube`, window 19.
- Event-driven scan: 16 nodes, zero candidates, not truncated; four content events and seven window events in the reported counters.
- Shell-triggered manual dump succeeded: 13 nodes, zero text labels, zero descriptions, two view IDs, zero clickable controls. Classes were android.view.View, android.widget.FrameLayout and android.widget.LinearLayout. Node count changed between observations; these are separate snapshots.
- The exact on-TV content state was not confirmed. Do not label this snapshot normal playback or an ad.
- Raw dump was retained only in the local work directory; only aggregate structure is recorded here.

**WORKING (physical device):** Official YouTube foreground detection and manual diagnostic dump. **NEEDS DEVICE TEST:** Playback/ad transitions and Skip controls. The sparse initial tree does not establish whether ad auto-skip is viable.

Commands used:

```powershell
adb -s 192.168.86.30:5555 shell dumpsys activity activities
adb -s 192.168.86.30:5555 logcat -d -t 1000 -v brief SLOPOFF_SERVICE:I SLOPOFF_FOREGROUND:I SLOPOFF_YOUTUBE:I SLOPOFF_AD_CANDIDATE:I SLOPOFF_SKIP_CANDIDATE:I '*:S'
adb -s 192.168.86.30:5555 shell am broadcast -a tv.slopoff.DUMP -n tv.slopoff/.DiagnosticReceiver
adb -s 192.168.86.30:5555 logcat -d -t 500 -v brief SLOPOFF_TREE:I SLOPOFF_YOUTUBE:I '*:S'
```

## User-confirmed ad and visible Skip — 2026-09-26

User reported an ad playing and then explicitly confirmed that Skip was visible. A manual dump was requested immediately after each report; confirmation and capture are not frame-synchronized. Both captures exposed 16 nodes with no text, descriptions, clickable nodes or ACTION_CLICK support, and no diagnostic candidates. The Skip-confirmed capture was not truncated (elapsed timestamp 1761454108). The only resource IDs were `android:id/content` and `com.amazon.firetv.youtube:id/action_bar_root`; both identify layout containers, not an ad or Skip control. All exposed ancestors were also non-clickable.

Alternate inspection: UI Automator independently exposed 16 unlabeled, non-clickable nodes in the same YouTube package. Accessibility window inspection reported only the active YouTube application window (id 19), with no separate ad/Skip window. No screenshots, OCR, coordinate clicks, key presses or ACTION_CLICK attempts were used.

Installed official YouTube version: `25.30.r0.v283.0`, versionCode `325302830`, targetSdk 34, minSdk 24. These findings apply to this tested installation; do not generalize them to every YouTube/Fire OS release.

Current answers:

- A: YouTube foreground identification observed successfully.
- B/C: No ad-specific or Skip accessibility label/ID exposed in the captures following user-confirmed ad and visible Skip.
- D/E: No clickable node or ancestor exposed; all 16 inspected nodes lack click-action support.
- F: ACTION_CLICK not attempted because no supported target exists. Its effectiveness is unproven.
- G: No reliable ad-vs-content signal established. Auto-skip must remain unimplemented.

Fallback A has been checked through complete active-root traversal, ancestors, system window enumeration and a second accessibility inspector. These checks found no usable Skip target. This is evidence against node-based auto-skip on this installed YouTube version, not proof that every possible accessibility configuration fails.

Fallback B preliminary read-only media-session inspection: YouTube active=true, playback state=3, speed=1.0, actions=55, custom actions empty, error=null. These fields alone do not establish an ad signal. Normal-content comparison is still required. Raw media metadata was kept local and was not committed.

Additional commands:

```powershell
adb -s 192.168.86.30:5555 shell am broadcast -a tv.slopoff.DUMP -n tv.slopoff/.DiagnosticReceiver
adb -s 192.168.86.30:5555 shell dumpsys accessibility
adb -s 192.168.86.30:5555 shell uiautomator dump /data/local/tmp/slopoff-ad-ui.xml
adb -s 192.168.86.30:5555 pull /data/local/tmp/slopoff-ad-ui.xml work/youtube-ad-uiautomator.xml
adb -s 192.168.86.30:5555 shell dumpsys package com.amazon.firetv.youtube
adb -s 192.168.86.30:5555 shell dumpsys media_session
```

Next physical step: user lets normal video resume so its tree and playback state can be compared. No audio suppression or automatic input will be added based on the evidence so far.

## Normal playback comparison — 2026-09-26

After the user confirmed normal video was playing, manual accessibility capture at elapsedMs 1761582434 again returned 16 nodes, no text/descriptions, zero clickable nodes, zero click actions and no candidates, not truncated. The only IDs remained the content and action-bar layout containers. This provides no usable accessibility distinction between the tested ad and normal playback states.

YouTube media session stayed active with playback state=3, speed=1.0 and no custom actions/errors. Supported actions changed from **55 during the ad-time inspection to 383 during confirmed normal video**. XOR=328, comprising REWIND (8), FAST_FORWARD (64), SEEK_TO (256). These are supported transport capabilities, not an explicit ad flag or a Skip-ad command. The ordinary SKIP_TO_NEXT bit was present in both snapshots and must not be confused with skipping an advertisement.

This is one promising but unvalidated fallback-B observation. Missing seek support can also occur with other content/states; test repeated skippable/non-skippable ads, live streams, transitions and normal playback before considering any detector. It gives no proven countdown/Skip-ready signal. The ADB shell's ability to read media sessions does not prove that a standalone app can: Android's getActiveSessions requires MEDIA_CONTENT_CONTROL or an enabled notification listener. Neither capability has been added or granted to this APK.

References: [PlaybackState action constants](https://developer.android.com/reference/android/media/session/PlaybackState), [MediaSessionManager access requirements](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)).

No screen-capture/OCR fallback, media command, automated click, or audio manipulation was implemented. Fallback C is deferred while the lower-cost media-state lead remains unvalidated. Diagnostic app remains enabled and observation-only; manual tree dumps are one-shot, and no continuous ADB capture was left running.

## Second user-confirmed ad — 2026-09-26

User reported an ad after being asked to start another video. Immediate read-only media-session capture again showed official YouTube active=true, playback state=3, speed=1.0, supported actions=55 and empty custom actions. This repeats the first ad-time result (55), compared with the earlier user-confirmed normal-playback result (383).

The associated manual tree at elapsedMs 1761951126 exposed 16 nodes, zero text labels/descriptions, zero click-action support and zero candidates, not truncated. No input, audio changes or screen capture were performed.

This is two user-reported ad snapshots and one normal-playback snapshot, not a validated classifier. Whether Skip is visible during this second snapshot is pending user confirmation; the result must not be treated as evidence of Skip readiness. Live/unseekable content and transition false positives remain untested. Standalone media-session access is also unverified.

User subsequently confirmed Skip was visible on the second ad. A follow-up media-session capture still returned actions=55, custom actions empty, with the same playback-state update timestamp (1761915333). Thus no additional explicit Skip-ad command or readiness flag was observed at that moment. No before-Skip countdown sample was obtained, so this does not prove that the media state never changes when Skip appears. Next comparison: normal playback after this second ad ends.

## Second post-skip comparison — 2026-09-26

After the user reported pressing Skip, the official YouTube activity remained foreground. Its media session was active, playback state=3, speed=1.0, actions=383 and custom actions empty. The event-driven accessibility summary remained 16 nodes, zero candidates, not truncated.

| Capture | User-reported state | Supported media actions | Explicit Skip-ad action |
|---|---|---:|---|
| First ad | Ad; later Skip visibly confirmed | 55 | None observed |
| First content | Normal video playing | 383 | None observed |
| Second ad | Ad; Skip visibly confirmed | 55 (also 55 on follow-up) | None observed |
| Second post-skip | User pressed Skip | 383 | None observed |

The media-action difference repeated across both pairs. This remains an unvalidated correlation, not an ad classification rule: live/unseekable playback, errors, transitions, non-skippable ads and countdown timing have not been tested. No direct evidence yet establishes standalone app access to these fields, Skip readiness or a safe activation target. No additional permissions or media controls were introduced.

Publication history: the initial push was denied because Ferxxo-pa lacked write access. After the user accepted repository access, the connected GitHub API confirmed push=true for clawdez/slop-off. The committed fire-tv-mvp branch is ready for publication as the diagnostic milestone. The diagnostic app remains observation-only, with no continuous ADB logging process running.

## Next feasibility experiment: source inspection and capture probe

Read the upstream Cobalt 25.lts.1+ implementation of CobaltA11yHelper. It defines nine 10x10 virtual cells for D-pad navigation, leaves their labels blank, and returns false for virtual-node actions. This strongly explains the nine tiny unlabeled leaf nodes observed on this YouTube installation, though upstream source is not proof of the exact installed binary. Enabling a spoken-feedback service would affect YouTube's screen-reader mode; it does not establish a real Skip node and was not attempted.

Source: https://github.com/youtube/cobalt/blob/25.lts.1%2B/starboard/android/apk/app/src/main/java/dev/cobalt/coat/CobaltA11yHelper.java

Fire TV's service registry exposes media_projection and media_session. Service presence is not proof that app capture works. Added a manual, permission-gated, one-frame MediaProjection probe (0.2) to test this empirically before implementing visual detection. Captures remain in memory; only aggregate frame statistics are logged. No screenshots are uploaded, saved to disk, or included in the repository. No input or audio changes are added. Physical capture consent/result are pending.

0.2 validation: assembleDebug and lintDebug passed; APK v2 signature verified, versionCode=2, min/target SDK 28, no requested permissions added. Physical MediaProjection functionality remains pending the consent test.

## Physical MediaProjection result — 2026-09-26

After the user returned to official YouTube and approved Start now, filtered capture logs showed two separate one-frame probe completions. The initial short logcat window missed these messages because unrelated device logs advanced quickly; an unrestricted tag-filtered read recovered them.

| Probe completion (elapsedMs) | Frame | Sampled pixels | Non-black samples | Luminance range | Image saved |
|---:|---|---:|---:|---|---|
| 1780970052 | 640x360 | 3600 | 1594 | 0–222 | No |
| 1781093813 | 640x360 | 3600 | 9 | 0–203 | No |

Both probes logged frame_received followed by complete. `dumpsys media_projection` subsequently reported null, confirming no active projection remained. The exact video/ad state at each frame was not labeled by the user; do not infer protected playback, usable video capture, or Skip readability from these statistics. The second frame was almost entirely black. This establishes that consent-based app-level frame delivery works on the physical device, not that a visual detector is feasible or reliable.

A stale/waiting launch was recovered by cancelling the host wait and starting the probe using Android-9-compatible flags `am start -f 0x10008000 -n tv.slopoff/.CaptureProbeActivity`. The first attempt with the named --activity-new-task switch failed because this device's am does not support that spelling; no capture was started by that failed command.

Next controlled test: capture a frame while the user confirms an ad's Skip button is visible, before considering visual matching. No OCR, clicks, media commands, audio changes or continuous capture were introduced. Accessibility remains connected; capture is stopped.

## Capture timing confound reported by user

A capture requested after the user reported visible Skip produced 3112 non-black samples out of 3600 (640x360, luminance 0–255) at elapsedMs 1781442221 and completed at 1781442223. MediaProjection was subsequently null. The user then reported that the ad skipped when Start now was selected. The probe does not send any input or transport action, but the cause of the transition is unknown: natural ad completion, remote event/focus behavior or another effect cannot be excluded. Do not label that frame a confirmed Skip-visible capture or claim that requesting projection skips ads.

To remove the permission-dialog/focus confound, version 0.3 adds an explicitly armed mode: request consent before an ad, hold the grant without creating a virtual display for at most three minutes, then accept one privileged CAPTURE_ONCE broadcast without opening an activity. Capture still stops after one frame or a 12-second capture timeout. STOP_CAPTURE cancels the armed grant. No images are saved or transmitted, and no input/audio automation is added. The caller must verify YouTube/ad state before and after the snapshot.

0.3 build/lint and APK signature checks passed. Armed-mode behavior still needs the physical consent/capture test.

## Armed probe expiry and OCR necessity

The three-minute grant expired at elapsedMs 1782047417 before the next user-confirmed ad. The subsequent CAPTURE_ONCE request took no frame, and MediaProjection was null. The ad again reported actions=55. This timing-only probe did not establish Skip readability.

Version 0.4 extends the manual armed window to ten minutes with no frames read until one request, captures one 1280x720 frame, then releases projection before single-threaded offline text recognition. Exact English Skip-label candidates and bounds are reported; other recognized text is discarded. OCR is introduced only after physical accessibility inspection and upstream Cobalt source established no real Skip node, and media-session flags did not establish Skip readiness. No recognition loop or auto-input is added.

An initial ML Kit build was abandoned before installation because its analytics-related dependencies conflict with this project's constraints. The selected implementation instead uses Tesseract4Android 4.9.0 and a pinned bundled English model, with no runtime model download or analytics backend. Network permissions are explicitly removed in the merged manifest. Model files are the only OCR data written to app storage. ABI is restricted to the tested Fire Stick's armeabi-v7a.

The user currently reports a paused ad with a visible Skip button, which provides a controlled screen for the next manual test. No click or playback command was issued.

0.4 device result: user reconfirmed paused ad with Skip visible after consent. One 1280x720 frame had 6073/14400 nonblack samples. Tesseract inspected 17 text lines in 3196 ms but returned no exact full-line Skip candidates. Capture completed; no input was sent. This is a recognition failure, not evidence that Skip was absent. Build and lint passed; APK signature verified and packaged permission list was empty. Installed versionCode 4 confirmed.


## 0.5 bounded word probe

The user supplied a photo showing paused YouTube with a bottom-right Skip label, adjacent icon and countdown. The 0.4 whole-line comparison could reject labels grouped with adjacent UI. Version 0.5 compares exact normalized words (Skip, Skipad, Skipads), with coordinates and confidence, in two bounded OCR passes: full frame and doubled bottom-right quadrant. Both passes use the same one captured frame; no periodic capture or input is added. A word match remains diagnostic, not proof of a clickable ad button. Device validation pending.


## 0.5 recognition and controlled ADB Skip success

At elapsedMs 1783188446, the user confirmed a paused ad with Skip visible. One 1280x720 frame produced 37 inspected words across two passes in 3523 ms. Full-frame Skip: confidence 92.03494, bounds [1096,630,1125,645]. Enlarged region: confidence 93.08898, mapped bounds [1095,630,1125,645]. No image saved. Projection was subsequently null.

The display was 1920x1080. Official YouTube remained foreground, with paused media state 2 at position 6187 and actions 53. After rechecking foreground and that paused position, a single ADB tap at (1665,956) was issued. YouTube changed to playing state 3 near position 167 with actions 383. The user explicitly confirmed that the ad skipped and regular video resumed. This proves one controlled ADB coordinate input worked; it does NOT yet prove app-level accessibility gesture delivery or unattended detection.

## 0.6 one-shot app input experiment

Adds gesture capability only for an explicitly armed shell-protected test, with matching two-pass Skip positions, lower-left Sponsored evidence, confidence/geometry/freshness guards, same YouTube window and window-event generation, and a ten-second attempt debounce. Ordinary capture is observation-only. No recurring trigger, input retries or audio control. Physical gesture validation pending.

## 0.6 timing guard and 0.7 session candidate

0.6 recognized Skip in both passes (95.0 and 90.5 confidence) and Sponsored at confidence 96.5 on a second user-confirmed paused ad. OCR took 4424 ms, but the input gate refused the frame. The old per-pixel conversion ran before the reported frame timestamp; the total frame age was not separately logged, so excessive age is the leading explanation, not a directly measured result. No app gesture was sent. A binding refresh was required after the capability change: installed service capabilities changed from 1 to 33 after re-enabling only the already-authorized component while preserving the original enabled-service list.

At the user's request to stop repeated consent interruptions, version 0.7 moves projection ownership to a foreground service with an eight-hour maximum session. It retains the grant with no active frame reader between bounded requests. Native bitmap copy replaces the per-pixel loop, with copy and total age diagnostics. A notification-listener component enables media-session event access without reading notification contents or metadata. The new access has not yet been granted. Experimental automatic checks use only the observed transport action values 53/55 as candidates, then require the visual gate and current foreground state before input. Bursts stop after six attempts or 45 seconds, with at most 12 automatic attempts per minute across media events. Normal playback, leaving YouTube and lost permissions invalidate in-flight results. This remains a release candidate requiring physical validation, not production proof.

## 0.7 retained-session device result and 0.8 refinement

The user authorized playback-event access; version 7 installation and the notification listener setting were verified. Two explicitly requested frames reused the same projection grant with no intervening consent popup. Native image copy took 61 ms then 82 ms; total frame ages at recognition were 3464 ms then 2878 ms. Both full-frame reads found Skip at confidence 95.0 (bounds [1080,630,1109,645]) and Sponsored at 95.6, but the wide enlarged quadrant produced no second Skip candidate. The visual gate correctly refused input in both tests. Projection remained active with no frame reader between requests. Automatic checks were not enabled.

Version 0.8 refines exactly one eligible full-frame Skip candidate with a tightly padded 4x word crop, instead of the broad 2x quadrant. Two matching high-confidence reads plus Sponsored evidence remain required. This targets the observed second-pass segmentation failure without weakening the input gate. App gesture outcome still needs physical proof.

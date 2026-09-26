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

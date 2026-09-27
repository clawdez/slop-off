# Slop Off 🧴

**Ad Exterminator — Get Rid of Those Ads!**

<p align="center">
  <img src="logo.png" alt="Slop Off" width="300">
</p>

A free, open-source Chrome extension that mutes YouTube ads and auto-clicks **Skip Ad** the moment it appears. Your sound comes back when the ad ends. No setup, no account, no tracking.

## How It Works

1. Detects when a YouTube ad starts playing
2. Immediately mutes the ad
3. Clicks the **Skip Ad** button as soon as it's available
4. Restores your previous volume/mute state when the ad ends

Ads without a skip button stay muted until they finish. Your volume, playback speed, and video position are never touched.

## Install

1. **Download** this repo — click the green **Code** button → **Download ZIP**, then extract it
2. Open `chrome://extensions` in Chrome
3. Enable **Developer mode** (top-right toggle)
4. Click **Load unpacked** and select the extracted folder containing `manifest.json`
5. Refresh any open YouTube tabs

That's it. No config, no toolbar icon to click. It just works.

## Update

Drop the new files into your extension folder, go to `chrome://extensions`, hit the reload icon on **Slop Off**, then refresh YouTube.

## How the Skip Click Works

YouTube ignores synthetic JavaScript clicks on the skip button. Slop Off uses Chrome's `debugger` permission to send a real browser-level click. Chrome may briefly show a debugging banner — the extension disconnects immediately after each click.

If you hit **Cancel** on that banner, auto-clicking pauses for that tab until reload. Ad muting still works.

## What It Doesn't Do

- No data collection, analytics, or external requests
- No sponsored segment skipping (SponsorBlock handles that)
- No mobile or embedded player support
- No Chrome Web Store distribution (install manually)

## Privacy

Everything runs locally. Zero network requests. Zero tracking. The code is right here — read it yourself.

## License

MIT — do whatever you want with it.

## Fire TV (experimental)

A separate Kotlin diagnostic application lives in [fire-tv/](fire-tv/README.md), targeting Fire OS 7 / Android 9. It observes accessibility signals from the official YouTube TV app to establish whether safe automatic skipping is possible. This initial diagnostic build does **not** skip or mute ads. See [device findings and current status](fire-tv/FINDINGS.md). The Chrome extension continues to work independently.

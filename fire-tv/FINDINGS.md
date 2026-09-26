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

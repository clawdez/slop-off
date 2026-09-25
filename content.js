(() => {
  "use strict";

  // Match known YouTube skip controls without relying on their display language.
  // Deliberately exclude countdown labels, ad links, and generic "Skip" buttons.
  const SKIP_BUTTONS = [
    ".ytp-skip-ad-button",
    ".ytp-ad-skip-button-modern",
    ".ytp-ad-skip-button"
  ].join(",");
  const RETRY_MS = 1000;
  const lastClicks = new WeakMap();
  const mutedVideos = new Map();
  let player = null;
  let scanQueued = false;
  let clickPending = false;

  function adIsPlaying() {
    return player?.isConnected && player.matches(".ad-showing, .ad-interrupting");
  }

  function restoreSound(video, wasMuted) {
    mutedVideos.delete(video);
    if (video.muted !== wasMuted) video.muted = wasMuted;
  }

  function syncAdSound() {
    const adPlaying = adIsPlaying();

    // Restore media when the ad ends, the player changes, or a video is removed.
    for (const [video, wasMuted] of mutedVideos) {
      if (!adPlaying || !player.contains(video)) restoreSound(video, wasMuted);
    }
    if (!adPlaying) return;

    for (const video of player.querySelectorAll("video")) {
      // Remember the state only once per ad, including videos already muted by
      // the viewer. Never change volume, playback speed, or playback position.
      if (!mutedVideos.has(video)) mutedVideos.set(video, video.muted);
      if (!video.muted) video.muted = true;
    }
  }

  function canClick(button) {
    if (!button.isConnected || button.matches(":disabled") ||
        button.closest('[hidden], [inert], [aria-hidden="true"], [aria-disabled="true"]')) {
      return false;
    }

    // Include ancestor visibility/opacity: a countdown can hide the real button.
    if (!button.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) {
      return false;
    }
    const bounds = button.getBoundingClientRect();
    return bounds.width > 0 && bounds.height > 0 &&
      getComputedStyle(button).pointerEvents !== "none";
  }

  function skipIfReady() {
    if (!adIsPlaying() || clickPending) return;

    for (const button of player.querySelectorAll(SKIP_BUTTONS)) {
      if (!canClick(button)) continue;

      const now = performance.now();
      if (now - (lastClicks.get(button) ?? -Infinity) < RETRY_MS) continue;

      // Retry a still-visible button if YouTube ignored a click while loading.
      // Store first, since a click may immediately mutate or replace the player.
      lastClicks.set(button, now);
      // YouTube rejects synthetic element.click() events. Ask Chrome to send a
      // browser input click; the worker asks us to recheck the target afterward.
      clickPending = true;
      chrome.runtime.sendMessage({ type: "skip-ad" }).catch(() => {
        // A reloaded/disabled extension clears its old script on page refresh.
      }).finally(() => { clickPending = false; });
      return;
    }
  }

  function queueScan() {
    if (scanQueued) return;
    scanQueued = true;
    setTimeout(() => {
      scanQueued = false;
      findPlayer();
      syncAdSound();
      skipIfReady();
    }, 0);
  }

  const playerObserver = new MutationObserver(queueScan);

  chrome.runtime.onMessage.addListener((message, sender, respond) => {
    if (sender.id !== chrome.runtime.id || message.type !== "locate-skip-button") return;
    findPlayer();
    if (!adIsPlaying()) { respond(null); return; }

    for (const button of player.querySelectorAll(SKIP_BUTTONS)) {
      if (!canClick(button)) continue;
      const bounds = button.getBoundingClientRect();
      const left = Math.max(0, bounds.left);
      const top = Math.max(0, bounds.top);
      const right = Math.min(innerWidth, bounds.right);
      const bottom = Math.min(innerHeight, bounds.bottom);
      if (right <= left || bottom <= top) continue;
      const x = (left + right) / 2;
      const y = (top + bottom) / 2;
      const hit = document.elementFromPoint(x, y);
      // Never click an overlay, an offscreen control, or a stale position.
      if (hit && (hit === button || button.contains(hit))) {
        respond({ x, y });
        return;
      }
    }
    respond(null);
  });

  function findPlayer() {
    const nextPlayer = document.querySelector("#movie_player") ||
      document.querySelector(".html5-video-player");
    if (nextPlayer === player) return;

    playerObserver.disconnect();
    player = nextPlayer;
    if (player) {
      playerObserver.observe(player, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: [
          "class", "style", "hidden", "disabled", "aria-disabled", "aria-hidden", "inert"
        ]
      });
    }
  }

  // YouTube changes videos without reloading the page and can replace its player.
  const discoveryObserver = new MutationObserver(() => {
    if (!player?.isConnected) queueScan();
  });
  discoveryObserver.observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener("yt-navigate-finish", queueScan);
  document.addEventListener("visibilitychange", queueScan);
  // Media events do not bubble, so listen during capture. Reapply muting if
  // YouTube resets sound while loading another ad into the same video element.
  for (const event of ["volumechange", "loadedmetadata", "play"]) {
    document.addEventListener(event, ({ target }) => {
      if (target instanceof HTMLVideoElement && player?.contains(target)) queueScan();
    }, true);
  }
  window.addEventListener("pageshow", queueScan);
  window.addEventListener("pagehide", () => {
    for (const [video, wasMuted] of mutedVideos) restoreSound(video, wasMuted);
  });

  // Covers CSS-only visibility changes and ignored clicks. Mutation observation
  // handles normal button appearances immediately; background tabs may be throttled.
  setInterval(queueScan, 500);
  queueScan();
})();

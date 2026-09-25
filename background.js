"use strict";

const pendingTabs = new Set();
const releasingTabs = new Set();
const canceledTabs = new Set();

async function clickSkipButton(tabId) {
  if (pendingTabs.has(tabId) || canceledTabs.has(tabId)) return { clicked: false };
  pendingTabs.add(tabId);
  const target = { tabId };
  let attached = false;
  try {
    await chrome.debugger.attach(target, "1.3");
    attached = true;
    // Attaching may resize the viewport. Get a fresh, visible, unobscured target
    // from our own content script after attaching, never from a web page message.
    const point = await chrome.tabs.sendMessage(tabId, { type: "locate-skip-button" }, { frameId: 0 });
    if (!point || !Number.isFinite(point.x) || !Number.isFinite(point.y) ||
        point.x < 0 || point.y < 0) return { clicked: false };

    const input = { x: point.x, y: point.y, button: "left", clickCount: 1 };
    await chrome.debugger.sendCommand(target, "Input.dispatchMouseEvent", {
      ...input, type: "mousePressed", buttons: 1
    });
    await chrome.debugger.sendCommand(target, "Input.dispatchMouseEvent", {
      ...input, type: "mouseReleased", buttons: 0
    });
    return { clicked: true };
  } catch (error) {
    // For example, another debugger or browser policy may prevent attachment.
    // Muting remains active, and the user can still click Skip ad normally.
    return { clicked: false, reason: error.message };
  } finally {
    if (attached) {
      releasingTabs.add(tabId);
      await chrome.debugger.detach(target).catch(() => {});
      releasingTabs.delete(tabId);
    }
    pendingTabs.delete(tabId);
  }
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message?.type !== "skip-ad" || sender.id !== chrome.runtime.id ||
      sender.frameId !== 0 || !Number.isInteger(sender.tab?.id)) return;
  let url;
  try { url = new URL(sender.url); } catch { return; }
  if (url.protocol !== "https:" || !["youtube.com", "www.youtube.com"].includes(url.hostname)) return;

  clickSkipButton(sender.tab.id).then(respond);
  return true;
});

// Respect Cancel on Chrome's debugging banner until that tab is reloaded.
chrome.debugger.onDetach.addListener((source, reason) => {
  if (reason === "canceled_by_user" && pendingTabs.has(source.tabId) &&
      !releasingTabs.has(source.tabId)) canceledTabs.add(source.tabId);
});
chrome.tabs.onUpdated.addListener((tabId, change) => {
  if (change.status === "loading") canceledTabs.delete(tabId);
});
chrome.tabs.onRemoved.addListener(tabId => canceledTabs.delete(tabId));

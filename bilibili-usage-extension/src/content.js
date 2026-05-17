const TICK_INTERVAL_MS = 1000;
const FLUSH_INTERVAL_MS = 1000;
const MAX_TICK_DELTA_MS = 2000;

let lastTickAt = Date.now();
let pendingMs = 0;
let pendingStartAt = 0;
let tickTimer = 0;
let flushTimer = 0;

function shouldCount() {
  return document.visibilityState === "visible";
}

function tick() {
  const now = Date.now();
  const delta = Math.min(Math.max(0, now - lastTickAt), MAX_TICK_DELTA_MS);
  lastTickAt = now;

  if (!shouldCount()) {
    flush("inactive");
    pendingStartAt = 0;
    return;
  }

  if (!pendingStartAt) {
    pendingStartAt = now - delta;
  }
  pendingMs += delta;
}

function flush(reason) {
  if (pendingMs < 250) return;

  const payload = {
    type: "bili-usage-delta",
    reason,
    url: location.href,
    host: location.hostname,
    visible: document.visibilityState === "visible",
    focused: document.hasFocus(),
    startAt: pendingStartAt || Date.now() - pendingMs,
    endAt: Date.now(),
    durationMs: Math.round(pendingMs)
  };

  chrome.runtime.sendMessage(payload).then(response => {
    if (response?.ok) {
      pendingMs = 0;
      pendingStartAt = 0;
    }
  }).catch(() => {
    // Keep pendingMs so the next flush can retry after the service worker restarts.
  });
}

function resetClock() {
  lastTickAt = Date.now();
}

function start() {
  stop();
  resetClock();
  tickTimer = window.setInterval(tick, TICK_INTERVAL_MS);
  flushTimer = window.setInterval(() => flush("interval"), FLUSH_INTERVAL_MS);
}

function stop() {
  if (tickTimer) window.clearInterval(tickTimer);
  if (flushTimer) window.clearInterval(flushTimer);
  tickTimer = 0;
  flushTimer = 0;
}

document.addEventListener("visibilitychange", () => {
  tick();
  if (document.visibilityState === "visible") {
    resetClock();
  } else {
    flush("hidden");
  }
});

window.addEventListener("focus", () => {
  resetClock();
});

window.addEventListener("blur", () => {
  tick();
  flush("blur");
});

window.addEventListener("pagehide", () => {
  tick();
  flush("pagehide");
});

start();

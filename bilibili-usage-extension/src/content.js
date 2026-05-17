/**
 * content script：在 B 站站内每秒累积「已观看的毫秒数」，
 * 满足条件时把 {startAt,endAt,durationMs,...} 发给 background。
 *
 * 关键约束：
 *   - 只在 document.visible 时累积。
 *   - flush 失败时**保留** pendingMs 等待 service worker 复活后下一次 flush，
 *     这样不会丢数据。但若 SW 一直不可用，会被 MAX_BUFFER_MS 截顶避免越积越多。
 *   - flush 时上报真实的 startAt / endAt，便于 background 把这段时间正确地落到
 *     本地时区下「日 + 小时」桶里（跨小时也会被 background 拆分）。
 */
const TICK_INTERVAL_MS = 1000;
const FLUSH_INTERVAL_MS = 5000;
const MAX_TICK_DELTA_MS = 2000;
const MAX_BUFFER_MS = 60_000; // SW 长时间不可用时，最多累计 1 分钟，超出截断防止跨小时分配错误。
const MIN_FLUSH_MS = 250;

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

  if (pendingMs > MAX_BUFFER_MS) {
    // 截顶避免 startAt 横跨多个小时
    pendingStartAt = now - MAX_BUFFER_MS;
    pendingMs = MAX_BUFFER_MS;
  }
}

function flush(reason) {
  if (pendingMs < MIN_FLUSH_MS) {
    return;
  }

  const now = Date.now();
  const payload = {
    type: "bili-usage-delta",
    reason,
    url: location.href,
    host: location.hostname,
    visible: document.visibilityState === "visible",
    focused: document.hasFocus(),
    startAt: pendingStartAt || now - pendingMs,
    endAt: now,
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

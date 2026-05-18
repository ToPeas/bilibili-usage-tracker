/**
 * content script：在 B 站 tab 可见（前台）时累积使用毫秒，每 5 秒上报 background。
 *
 * 计时语义：
 *   ✅ B 站 tab 在前台可见 → 计时
 *   ✅ B 站 tab 前台 + 打开插件 popup → 继续计时（popup 不改变 tab 的 visibilityState）
 *   ❌ 切换到其他应用（Terminal/IDE）→ tab 变 hidden → 停止
 *   ❌ 切换 Mac 桌面 → tab 变 hidden → 停止
 *   ❌ 切换到浏览器其他 tab → 当前 tab 变 hidden → 停止
 *   ❌ 页面关闭/导航离开 → pagehide → 停止
 *
 * document.visibilityState 是最可靠的判断依据，不使用 window.blur/focus
 * （blur/focus 在 popup 弹出时也会错误触发）。
 */
const TICK_INTERVAL_MS = 1000;
const FLUSH_INTERVAL_MS = 5000;
const MAX_TICK_DELTA_MS = 2000;
const MAX_BUFFER_MS = 60_000;
const MIN_FLUSH_MS = 250;

let lastTickAt = Date.now();
let pendingMs = 0;
let pendingStartAt = 0;
let tickTimer = 0;
let flushTimer = 0;

function isVisible() {
  return document.visibilityState === "visible";
}

function tick() {
  const now = Date.now();
  const delta = Math.min(Math.max(0, now - lastTickAt), MAX_TICK_DELTA_MS);
  lastTickAt = now;

  if (!isVisible()) return; // tab 不在前台，不累积

  if (!pendingStartAt) pendingStartAt = now - delta;
  pendingMs += delta;
  if (pendingMs > MAX_BUFFER_MS) {
    pendingStartAt = now - MAX_BUFFER_MS;
    pendingMs = MAX_BUFFER_MS;
  }
}

function flush(reason) {
  if (pendingMs < MIN_FLUSH_MS) return;
  const now = Date.now();
  const payload = {
    type: "bili-usage-delta",
    reason,
    url: location.href,
    host: location.hostname,
    visible: isVisible(),
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
    // SW 暂时不可用，保留 pendingMs 等待下次 flush 重试
  });
}

function resetClock() {
  lastTickAt = Date.now();
}

// tab 从后台切回前台时，重置时钟防止第一个 delta 虚高
document.addEventListener("visibilitychange", () => {
  if (isVisible()) {
    resetClock(); // 刚变可见，重置上次 tick 时间
  } else {
    flush("hidden"); // 变不可见，立即 flush 已积累的数据
    pendingStartAt = 0;
  }
});

window.addEventListener("pagehide", () => {
  flush("pagehide");
  clearInterval(tickTimer);
  clearInterval(flushTimer);
});

tickTimer  = window.setInterval(tick, TICK_INTERVAL_MS);
flushTimer = window.setInterval(() => flush("interval"), FLUSH_INTERVAL_MS);

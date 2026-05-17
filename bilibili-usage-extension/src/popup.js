/**
 * popup.js
 *
 * 主流程：
 *   1) 拉取 background `get-status` 显示今日总时长 / 当前是否在 B 站 / D1 配置；
 *   2) 拉取 `get-recent-usage`（范围由用户选择：7/30/90/180 天）画趋势柱状图，支持悬停 tooltip；
 *   3) 点击某天 → 拉取 `get-day-detail` 画 24 小时分布柱状图 + 列出各设备时长；
 *   4) 提供「立即上传 / 测试 D1 / 打开设置」三个动作。
 */

const PINK = "#FB7299";
const PINK_DEEP = "#E45378";
const PINK_SOFT = "#FFE6EE";
const GRID = "#F0E8EE";
const INK = "#1F1F23";
const INK_SOFT = "#6B6470";
const INK_MUTE = "#B6AFB9";

const DPR = window.devicePixelRatio || 1;

/** -------- DOM -------- */
const $ = id => document.getElementById(id);
const todayDurationEl = $("todayDuration");
const stateChip = $("stateChip");
const hostChip = $("hostChip");
const dbChip = $("dbChip");
const rangeTabs = $("rangeTabs");
const rangeSummary = $("rangeSummary");
const trendCanvas = $("trendChart");
const trendLegend = $("trendLegend");
const hourCanvas = $("hourChart");
const dayTitle = $("dayTitle");
const daySub = $("daySub");
const deviceList = $("deviceList");
const uploadBtn = $("uploadNow");
const testBtn = $("testD1");
const statusLine = $("uploadStatus");
const lastUploadInfo = $("lastUploadInfo");
const openOptionsBtns = [$("openOptions"), $("openOptions2")];
const installHintCard = $("installHintCard");
const installHint = $("installHint");
const toggleDebugBtn = $("toggleDebug");
const debugList = $("debugList");

let statusTimer = 0;
let debugExpanded = false;

/** -------- 状态 -------- */
const state = {
  range: 7,
  days: [],          // [{date,totalMs,latestUploadedAt,devices:[{deviceId,deviceAlias,totalMs,uploadedAt}]}]
  selectedDate: "",
  selectedDetail: null
};

/** ---------- entry ---------- */
async function bootstrap() {
  bindEvents();
  await refreshStatus();
  await refreshTrend();
  startStatusPolling();
  window.addEventListener("unload", stopStatusPolling, { once: true });
}

function startStatusPolling() {
  stopStatusPolling();
  // 每 2 秒拉一次状态，只刷今日总时长、chip、调试，不重画图表。
  statusTimer = window.setInterval(() => { refreshStatus().catch(() => {}); }, 2000);
}

function stopStatusPolling() {
  if (statusTimer) { clearInterval(statusTimer); statusTimer = 0; }
}

function bindEvents() {
  rangeTabs.addEventListener("click", async event => {
    const btn = event.target.closest("button[data-range]");
    if (!btn) return;
    state.range = Number(btn.dataset.range) || 7;
    [...rangeTabs.children].forEach(c => c.classList.toggle("active", c === btn));
    rangeSummary.textContent = labelOfRange(state.range);
    await refreshTrend();
  });
  uploadBtn.addEventListener("click", onUpload);
  testBtn.addEventListener("click", onTestD1);
  openOptionsBtns.forEach(btn => btn?.addEventListener("click", () => chrome.runtime.openOptionsPage()));
  toggleDebugBtn?.addEventListener("click", () => {
    debugExpanded = !debugExpanded;
    applyDebugVisibility();
  });
}

function applyDebugVisibility() {
  if (!debugList || !toggleDebugBtn) return;
  debugList.hidden = !debugExpanded;
  // 为了避免 .debug-list 上的 display:flex 覆盖 hidden 的 display:none，手动扎一下 style。
  debugList.style.display = debugExpanded ? "" : "none";
  toggleDebugBtn.textContent = debugExpanded ? "收起" : "展开";
}

function labelOfRange(n) {
  if (n >= 180) return "最近半年";
  if (n >= 90) return "最近 3 个月";
  if (n >= 30) return "最近 30 天";
  return "最近 7 天";
}

/** ---------- status ---------- */
async function refreshStatus() {
  const status = await sendMessage({ type: "get-status" });
  if (!status) return;
  todayDurationEl.textContent = formatDuration(status.todayTotalMs || 0);
  if (status.active) {
    stateChip.textContent = "正在记录";
    stateChip.classList.add("active");
    stateChip.classList.remove("idle");
  } else {
    stateChip.textContent = "未在记录";
    stateChip.classList.remove("active");
  }
  hostChip.textContent = status.host ? status.host : "未在 B 站";
  const debug = status.debug || {};
  // 不再依赖 chrome.idle 决定是否计时。
  // 但仅在「系统明确锁屏」时对用户提示，以避免用户以为插件坏了。
  // 看视频不动键鼠会被误判 idle，所以 idle 状态不再包装「空闲 1 分钟」提示。
  if (debug.idleState === "locked") {
    stateChip.textContent = "已锁屏";
    stateChip.classList.add("idle");
  }
  if (status.uploadLog && status.uploadLog.length) {
    const last = status.uploadLog[0];
    const when = new Date(last.time);
    lastUploadInfo.textContent = `${last.ok ? "上次成功" : "上次失败"} · ${when.toLocaleString()}${last.date ? " · " + last.date : ""}`;
  } else {
    lastUploadInfo.textContent = "尚未上传";
  }
  renderInstallHint(status);
  renderDebug(status);
}

function renderInstallHint(status) {
  if (!installHintCard) return;
  const noToday = !status.todayTotalMs;
  const debug = status.debug || {};
  const noMessage = !debug.lastMessageAt;
  const ignoredHost = debug.lastReason === "ignored host";
  // 今天还没记到一点 + content script 从来没报过 -> 提示刷新 B 站页面
  if (noToday && noMessage) {
    installHint.textContent = "今天还没收到 B 站页面的任何上报。请刷新一下 B 站标签页，让 content script 注入后再回来看。";
    installHintCard.hidden = false;
  } else if (noToday && ignoredHost) {
    installHint.textContent = "收到上报但不是 B 站域名。把活跃 tab 切到 bilibili.com/m.bilibili.com 再观察。";
    installHintCard.hidden = false;
  } else {
    installHintCard.hidden = true;
  }
}

function renderDebug(status) {
  if (!debugList) return;
  const debug = status.debug || {};
  const rows = [
    ["今日总时长", formatDuration(status.todayTotalMs || 0)],
    ["当前是否记录", status.active ? "是" : "否", status.active ? "ok" : "warn"],
    ["活跃 host", status.host || "-"],
    ["系统 idle", debug.idleState || "-", (debug.idleState && debug.idleState !== "active") ? "warn" : ""],
    ["最后一次接受", debug.lastAcceptedAt ? new Date(debug.lastAcceptedAt).toLocaleTimeString() : "-"],
    ["最后一次忽略", debug.lastIgnoredAt ? new Date(debug.lastIgnoredAt).toLocaleTimeString() : "-"],
    ["忽略原因", debug.lastReason || "-"],
    ["上报来源 host", debug.lastHost || "-"],
    ["上报 tabId", String(debug.lastTabId || "-")],
    ["上下文 host", debug.contextHost || "-"],
    ["上下文聚焦", String(debug.contextFocused === undefined ? "-" : debug.contextFocused)],
    ["页面 visible", String(debug.contentVisible === undefined ? "-" : debug.contentVisible)],
    ["页面 focused", String(debug.contentFocused === undefined ? "-" : debug.contentFocused)],
    ["上次计数 (ms)", String(debug.lastCountedMs || 0)]
  ];
  debugList.innerHTML = "";
  for (const [k, v, klass] of rows) {
    const li = document.createElement("li");
    if (klass) li.className = klass;
    li.innerHTML = `<span class="k">${escapeHtml(k)}</span><span class="v">${escapeHtml(v)}</span>`;
    debugList.appendChild(li);
  }
  // 重填后重新应用一下可见性，防止默认 hidden 被 .debug-list 的 flex 覆盖。
  applyDebugVisibility();
}

/** ---------- trend (range days) ---------- */
async function refreshTrend() {
  const response = await sendMessage({ type: "get-recent-usage", days: state.range });
  if (!response) return;
  dbChip.textContent = `D1：${response.database || "未配置"}`;
  if (!response.ok) {
    state.days = [];
    drawTrend(null, response.error || "查询失败");
    return;
  }
  state.days = response.days || [];
  // 默认选最近一个有数据的日，否则今天
  if (!state.selectedDate || !state.days.some(d => d.date === state.selectedDate)) {
    const lastWithData = [...state.days].reverse().find(d => d.totalMs > 0);
    state.selectedDate = lastWithData?.date || state.days[state.days.length - 1]?.date || "";
  }
  drawTrend();
  await refreshDayDetail();
}

/** ---------- day detail ---------- */
async function refreshDayDetail() {
  if (!state.selectedDate) {
    dayTitle.textContent = "暂无数据";
    daySub.textContent = "等待数据上传";
    deviceList.innerHTML = "";
    drawHourChart([]);
    return;
  }
  const resp = await sendMessage({ type: "get-day-detail", date: state.selectedDate });
  if (!resp || !resp.ok) {
    dayTitle.textContent = state.selectedDate;
    daySub.textContent = resp?.error || "查询失败";
    deviceList.innerHTML = "";
    drawHourChart([]);
    return;
  }
  state.selectedDetail = resp;
  dayTitle.textContent = `${resp.date} · ${formatDuration(resp.totalMs)}`;
  daySub.textContent = resp.devices.length
    ? `共 ${resp.devices.length} 台设备 · 点击柱子查看其他天`
    : "这一天暂无设备上报";
  drawHourChart(resp.hours || []);
  renderDeviceList(resp.devices || []);
}

function renderDeviceList(devices) {
  deviceList.innerHTML = "";
  if (!devices.length) {
    const li = document.createElement("li");
    li.innerHTML = `<span class="device-name" style="color:${INK_SOFT}">这一天没有设备上报</span>`;
    deviceList.appendChild(li);
    return;
  }
  const max = devices.reduce((m, d) => Math.max(m, d.totalMs), 1);
  for (const dev of devices) {
    const li = document.createElement("li");
    const ratio = Math.round((dev.totalMs / max) * 100);
    li.innerHTML = `
      <span class="device-dot"></span>
      <span class="device-name">${escapeHtml(dev.deviceAlias || dev.deviceId)}</span>
      <span class="device-meta">${ratio}%</span>
      <span class="device-time">${formatDuration(dev.totalMs)}</span>
    `;
    deviceList.appendChild(li);
  }
}

/** ---------- actions ---------- */
async function onUpload() {
  uploadBtn.disabled = true;
  statusLine.textContent = "上传中…";
  statusLine.classList.remove("ok", "error");
  try {
    const resp = await sendMessage({ type: "upload-now" });
    if (resp?.ok) {
      const r = resp.result || {};
      statusLine.textContent = `上传完成：${r.uploaded || 0} / ${r.attempted || 0} 天`;
      statusLine.classList.add("ok");
      await refreshStatus();
      await refreshTrend();
    } else {
      statusLine.textContent = resp?.error || "上传失败";
      statusLine.classList.add("error");
    }
  } catch (err) {
    statusLine.textContent = String(err?.message || err);
    statusLine.classList.add("error");
  } finally {
    uploadBtn.disabled = false;
  }
}

async function onTestD1() {
  testBtn.disabled = true;
  statusLine.textContent = "测试 D1 中…";
  statusLine.classList.remove("ok", "error");
  try {
    const resp = await sendMessage({ type: "test-d1-connection" });
    if (resp?.ok) {
      statusLine.textContent = `D1 OK · ${resp.message || ""}`;
      statusLine.classList.add("ok");
    } else {
      statusLine.textContent = `D1 失败：${resp?.error || ""}`;
      statusLine.classList.add("error");
    }
  } catch (err) {
    statusLine.textContent = String(err?.message || err);
    statusLine.classList.add("error");
  } finally {
    testBtn.disabled = false;
  }
}

/** ---------- canvas chart ---------- */
function drawTrend(_unused, errorMsg) {
  const ctx = trendCanvas.getContext("2d");
  resizeCanvas(trendCanvas, ctx);
  const w = trendCanvas.clientWidth;
  const h = trendCanvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = "#FFFFFF";
  ctx.fillRect(0, 0, w, h);

  if (errorMsg) {
    ctx.fillStyle = INK_SOFT;
    ctx.font = "12px sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(errorMsg, w / 2, h / 2);
    return;
  }
  if (!state.days.length) {
    ctx.fillStyle = INK_MUTE;
    ctx.font = "12px sans-serif";
    ctx.textAlign = "center";
    ctx.fillText("等待数据…", w / 2, h / 2);
    return;
  }

  const padding = { top: 24, right: 12, bottom: 24, left: 36 };
  const innerW = w - padding.left - padding.right;
  const innerH = h - padding.top - padding.bottom;
  const days = state.days; // 顺序：i=0 是今天最早，最后一个是最旧（getRecentUsage 返回顺序）
  // 我们想让最旧 → 最新 从左到右
  const sorted = [...days].sort((a, b) => (a.date < b.date ? -1 : 1));
  const maxMs = Math.max(60_000, sorted.reduce((m, d) => Math.max(m, d.totalMs || 0), 0));

  // 网格 + Y 轴标签
  ctx.strokeStyle = GRID;
  ctx.lineWidth = 1;
  ctx.fillStyle = INK_MUTE;
  ctx.font = "10px sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  for (let i = 0; i <= 3; i++) {
    const y = padding.top + (innerH * i) / 3;
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(padding.left + innerW, y);
    ctx.stroke();
    const value = maxMs * (1 - i / 3);
    ctx.fillText(formatDuration(value, true), padding.left - 6, y);
  }

  const slot = innerW / sorted.length;
  const barW = Math.max(2, Math.min(18, slot * 0.6));

  for (let i = 0; i < sorted.length; i++) {
    const day = sorted[i];
    const x = padding.left + slot * (i + 0.5) - barW / 2;
    const ratio = day.totalMs / maxMs;
    const barH = Math.max(2, ratio * innerH);
    const y = padding.top + innerH - barH;
    const isSelected = day.date === state.selectedDate;
    ctx.fillStyle = isSelected ? PINK_DEEP : day.totalMs > 0 ? PINK : "#F2DCE5";
    roundedRect(ctx, x, y, barW, barH, Math.min(4, barW / 2));
    ctx.fill();
  }

  // X 轴标签（只展示稀疏几个）
  ctx.fillStyle = INK_SOFT;
  ctx.textAlign = "center";
  ctx.textBaseline = "top";
  const step = Math.max(1, Math.ceil(sorted.length / 6));
  for (let i = 0; i < sorted.length; i += step) {
    const x = padding.left + slot * (i + 0.5);
    ctx.fillText(shortDateLabel(sorted[i].date), x, padding.top + innerH + 4);
  }

  // 交互：把每个柱子的命中区记下来
  trendCanvas._hitMap = { padding, innerW, innerH, slot, barW, sorted };
}

function drawHourChart(hours) {
  const ctx = hourCanvas.getContext("2d");
  resizeCanvas(hourCanvas, ctx);
  const w = hourCanvas.clientWidth;
  const h = hourCanvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = "#FFFFFF";
  ctx.fillRect(0, 0, w, h);

  const padding = { top: 14, right: 8, bottom: 22, left: 30 };
  const innerW = w - padding.left - padding.right;
  const innerH = h - padding.top - padding.bottom;
  if (!hours.length) {
    ctx.fillStyle = INK_MUTE;
    ctx.font = "12px sans-serif";
    ctx.textAlign = "center";
    ctx.fillText("尚无时段数据", w / 2, h / 2);
    return;
  }

  const maxMs = Math.max(60_000, hours.reduce((m, h) => Math.max(m, h.durationMs), 0));
  ctx.strokeStyle = GRID;
  ctx.lineWidth = 1;
  ctx.fillStyle = INK_MUTE;
  ctx.font = "10px sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  for (let i = 0; i <= 2; i++) {
    const y = padding.top + (innerH * i) / 2;
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(padding.left + innerW, y);
    ctx.stroke();
    const value = maxMs * (1 - i / 2);
    ctx.fillText(formatDuration(value, true), padding.left - 4, y);
  }

  const slot = innerW / 24;
  const barW = slot * 0.65;
  for (let i = 0; i < 24; i++) {
    const ms = hours[i]?.durationMs || 0;
    const x = padding.left + slot * i + (slot - barW) / 2;
    const ratio = ms / maxMs;
    const barH = Math.max(0.5, ratio * innerH);
    const y = padding.top + innerH - barH;
    ctx.fillStyle = ms > 0 ? PINK : "#F2DCE5";
    roundedRect(ctx, x, y, barW, barH, Math.min(3, barW / 2));
    ctx.fill();
  }

  ctx.fillStyle = INK_SOFT;
  ctx.textAlign = "center";
  ctx.textBaseline = "top";
  ctx.font = "10px sans-serif";
  const labels = [0, 6, 12, 18, 23];
  for (const i of labels) {
    const x = padding.left + slot * i + slot / 2;
    ctx.fillText(`${i}:00`, x, padding.top + innerH + 4);
  }

  hourCanvas._hitMap = { padding, innerW, innerH, slot, barW, hours };
}

trendCanvas.addEventListener("click", event => {
  const map = trendCanvas._hitMap;
  if (!map) return;
  const rect = trendCanvas.getBoundingClientRect();
  const x = event.clientX - rect.left - map.padding.left;
  if (x < 0 || x > map.innerW) return;
  const i = Math.min(map.sorted.length - 1, Math.max(0, Math.floor(x / map.slot)));
  const day = map.sorted[i];
  if (day && day.date !== state.selectedDate) {
    state.selectedDate = day.date;
    drawTrend();
    refreshDayDetail();
  }
});

trendCanvas.addEventListener("mousemove", event => {
  const map = trendCanvas._hitMap;
  if (!map) return;
  const rect = trendCanvas.getBoundingClientRect();
  const x = event.clientX - rect.left - map.padding.left;
  if (x < 0 || x > map.innerW) {
    trendCanvas.title = "";
    return;
  }
  const i = Math.min(map.sorted.length - 1, Math.max(0, Math.floor(x / map.slot)));
  const day = map.sorted[i];
  if (day) {
    trendCanvas.title = `${day.date} · ${formatDuration(day.totalMs)}`;
  }
});

hourCanvas.addEventListener("mousemove", event => {
  const map = hourCanvas._hitMap;
  if (!map) return;
  const rect = hourCanvas.getBoundingClientRect();
  const x = event.clientX - rect.left - map.padding.left;
  if (x < 0 || x > map.innerW) {
    hourCanvas.title = "";
    return;
  }
  const i = Math.min(23, Math.max(0, Math.floor(x / map.slot)));
  const ms = map.hours[i]?.durationMs || 0;
  hourCanvas.title = `${i}:00 - ${i + 1}:00 · ${formatDuration(ms)}`;
});

/** ---------- helpers ---------- */
function resizeCanvas(canvas, ctx) {
  const w = canvas.clientWidth || canvas.width;
  const h = canvas.clientHeight || canvas.height;
  if (canvas.width !== w * DPR || canvas.height !== h * DPR) {
    canvas.width = Math.floor(w * DPR);
    canvas.height = Math.floor(h * DPR);
  }
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
}

function roundedRect(ctx, x, y, w, h, r) {
  const radius = Math.max(0, Math.min(r, w / 2, h / 2));
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.lineTo(x + w - radius, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + radius);
  ctx.lineTo(x + w, y + h - radius);
  ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h);
  ctx.lineTo(x + radius, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - radius);
  ctx.lineTo(x, y + radius);
  ctx.quadraticCurveTo(x, y, x + radius, y);
  ctx.closePath();
}

function formatDuration(ms, compact) {
  ms = Math.max(0, Math.round(Number(ms) || 0));
  const totalSec = Math.floor(ms / 1000);
  const hours = Math.floor(totalSec / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  const seconds = totalSec % 60;
  if (compact) {
    if (hours >= 1) return `${hours}h${minutes ? minutes + "m" : ""}`;
    if (minutes >= 1) return `${minutes}m`;
    return `${seconds}s`;
  }
  if (hours >= 1) return `${hours} 小时 ${minutes} 分`;
  if (minutes >= 1) return `${minutes} 分 ${seconds} 秒`;
  return `${seconds} 秒`;
}

function shortDateLabel(dateStr) {
  // dateStr 形如 2026-05-17
  const parts = dateStr.split("-");
  if (parts.length !== 3) return dateStr;
  return `${parts[1]}/${parts[2]}`;
}

function escapeHtml(str) {
  return String(str || "").replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

function sendMessage(payload) {
  return new Promise(resolve => {
    try {
      chrome.runtime.sendMessage(payload, response => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        resolve(response);
      });
    } catch (e) {
      resolve({ ok: false, error: String(e?.message || e) });
    }
  });
}

bootstrap();

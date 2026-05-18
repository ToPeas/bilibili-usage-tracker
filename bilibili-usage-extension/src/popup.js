/**
 * popup.js
 *
 * 主流程：
 *   1) 拉取 background `get-status` 显示今日总时长 / 当前是否在 B 站 / D1 配置；
 *   2) 拉取 `get-recent-usage`（范围由用户选择：7/30/90/180 天）画趋势柱状图，支持悬停 tooltip；
 *   3) 点击某天 → 拉取 `get-day-detail` 列出各设备时长；
 *   4) 提供「立即上传 / 测试 D1 / 打开设置」三个动作。
 */

const PINK = "#FB7299";
const PINK_DEEP = "#E45378";
const PINK_SOFT = "#FFE6EE";
const GRID = "#F0E8EE";
const INK = "#1F1F23";
const INK_SOFT = "#6B6470";
const INK_MUTE = "#B6AFB9";

// 多设备调色板：[粉(web), 蓝(android), 绿, 橙, 紫]
const DEVICE_COLORS = [
  { line: "#FB7299", fill: "rgba(251,114,153,0.18)" },
  { line: "#23ADE5", fill: "rgba(35,173,229,0.15)" },
  { line: "#52C41A", fill: "rgba(82,196,26,0.13)" },
  { line: "#FA8C16", fill: "rgba(250,140,22,0.13)" },
  { line: "#722ED1", fill: "rgba(114,46,209,0.13)" },
];

const DPR = window.devicePixelRatio || 1;

/** -------- DOM -------- */
const $ = id => document.getElementById(id);
const todayDurationEl = $("todayDuration");
const todayAllDevicesEl = $("todayAllDevicesDuration");
const stateChip = $("stateChip");
const hostChip = $("hostChip");
const dbChip = $("dbChip");
const rangeTabs = $("rangeTabs");
const rangeSummary = $("rangeSummary");
const trendCanvas = $("trendChart");
const trendLegend = $("trendLegend");
const hourChartWrap = $("hourChartWrap");
const hourBars = $("hourBars");
const dayTitle = $("dayTitle");
const daySub = $("daySub");
const deviceList = $("deviceList");
const uploadBtn = $("uploadNow");
const testBtn = $("testD1");
const statusLine = $("uploadStatus");
const lastUploadInfo = $("lastUploadInfo");
const openOptionsBtns = [$("openOptions"), $("openOptions2")];
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
  // 打开 popup 时静默上传今天数据，让趋势和设备列表尽量展示最新值。
  sendMessage({ type: "upload-now" }).then(() => refreshTrend()).catch(() => {});
  await refreshTrend();
  startStatusPolling();
  window.addEventListener("unload", stopStatusPolling, { once: true });
}

function startStatusPolling() {
  stopStatusPolling();
  // 每 2 秒拉一次状态，只刷今日总时长、chip、调试，不重画图表。
  statusTimer = window.setInterval(() => { refreshStatus().catch(() => {}); }, 1000);
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
  renderDebug(status);
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
  const todayKey = formatDateInTimeZoneJs(new Date());
  // 把今天「全设备总计」打到 Hero 上
  try {
    const todayRow = state.days.find(d => d.date === todayKey);
    if (todayAllDevicesEl) {
      if (todayRow && todayRow.totalMs >= 0) {
        todayAllDevicesEl.textContent = formatDuration(todayRow.totalMs || 0);
      } else {
        todayAllDevicesEl.textContent = "0秒";
      }
    }
  } catch (_e) {}
  // 打开弹窗时默认 active 今天；用户手动点过其他日期后，在当前范围内保持选择。
  if (!state.selectedDate || !state.days.some(d => d.date === state.selectedDate)) {
    const todayRow = state.days.find(d => d.date === todayKey);
    state.selectedDate = todayRow?.date || todayKey;
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
    return;
  }
  const resp = await sendMessage({ type: "get-day-detail", date: state.selectedDate });
  if (!resp || !resp.ok) {
    dayTitle.textContent = state.selectedDate;
    daySub.textContent = resp?.error || "查询失败";
    deviceList.innerHTML = "";
    return;
  }
  state.selectedDetail = resp;
  dayTitle.textContent = `${resp.date} · ${formatDuration(resp.totalMs)}`;
  daySub.textContent = resp.devices.length
    ? `共 ${resp.devices.length} 台设备 · 点击趋势图查看其他天`
    : "这一天暂无设备上报";
  drawHourChart(resp.hours || [], resp.hoursByDevice || {}, resp.devices || []);
  renderDeviceList(resp.devices || [], resp.hoursByDevice || {});
}

// （24h 柱图改为纯 DOM，canvas 缓存变量已移除）

/** 按设备在列表中的序号分配颜色，每台设备独立一种颜色 */
function assignSeriesColors(deviceIds, hoursByDevice) {
  return deviceIds.map((id, idx) => {
    const info = hoursByDevice[id] || {};
    const colorIdx = idx % DEVICE_COLORS.length;
    return { id, info, colorIdx, color: DEVICE_COLORS[colorIdx] };
  });
}

/**
 * 24h 多设备分组柱状图（纯 DOM）。
 * Tooltip 为单例 div 挂在 .hour-bars 上，mouseover 时定位，避免被 overflow 裁切。
 */
function drawHourChart(hours, hoursByDevice, devices) {
  if (!hourBars) return;

  const deviceIds = Object.keys(hoursByDevice || {});
  const hasData = deviceIds.length > 0;
  if (hourChartWrap) hourChartWrap.style.display = hasData ? "" : "none";
  hourBars.innerHTML = "";
  if (!hasData) return;

  const seriesMeta = assignSeriesColors(deviceIds, hoursByDevice);
  const series = seriesMeta.map(({ id, info, colorIdx, color }) => {
    const label = info.alias || id.slice(0, 8);
    const srcLabel = info.sourceLabel || srcDisplayLabel(info.source || "");
    return { id, label, srcLabel, colorIdx,
             data: normalizeHours(info.hours || []), color };
  });

  // Y 轴固定最大值 = 1 小时，超出时撑开
  let rawMax = 0;
  for (const s of series) for (const pt of s.data) rawMax = Math.max(rawMax, pt.durationMs || 0);
  if (rawMax <= 0) { hourBars.innerHTML = `<div style="padding:16px;color:${INK_MUTE};font-size:12px;text-align:center">该天暂无时段数据</div>`; return; }
  const yMax = Math.max(3_600_000, rawMax);

  // 清除旧图例
  hourChartWrap.querySelectorAll(".hour-legend").forEach(el => el.remove());

  // 图例
  const legendEl = document.createElement("div");
  legendEl.className = "hour-legend";
  for (const s of series) {
    const item = document.createElement("div");
    item.className = "hour-legend-item";
    item.innerHTML = `<span class="hour-legend-dot" style="background:${s.color.line}"></span><span>${escapeHtml(s.srcLabel)}·${escapeHtml(s.label)}</span>`;
    legendEl.appendChild(item);
  }
  hourChartWrap.insertBefore(legendEl, hourBars);

  const BAR_H = 100; // 与 CSS .hour-col height: 100px 一致

  // 单例 tooltip div 挂在 hourBars 上，不在每个列里，避免被裁切
  const tip = document.createElement("div");
  tip.className = "hour-tip";
  tip.style.display = "none";
  hourBars.appendChild(tip);

  // 24 列
  for (let h = 0; h < 24; h++) {
    const col = document.createElement("div");
    col.className = "hour-col";
    col.dataset.hour = String(h);

    const inner = document.createElement("div");
    inner.className = "hour-col-inner";

    // 各设备色块（从下往上叠），用 px 避免 % 因无父高失效
    for (const s of series) {
      const ms = s.data[h]?.durationMs || 0;
      if (ms <= 0) continue;
      const px = Math.max(1, Math.round((ms / yMax) * BAR_H));
      const seg = document.createElement("div");
      seg.className = "hour-seg";
      seg.style.cssText = `height:${px}px;background:${s.color.line};opacity:0.85;`;
      inner.appendChild(seg);
    }
    col.appendChild(inner);

    // X 轴标签
    if (h === 0 || h === 6 || h === 12 || h === 18 || h === 23) {
      const lbl = document.createElement("span");
      lbl.className = "hour-label";
      lbl.textContent = String(h);
      col.appendChild(lbl);
    }

    // hover 事件：定位并显示单例 tooltip
    col.addEventListener("mouseenter", () => {
      const hasAny = series.some(s => (s.data[h]?.durationMs || 0) > 0);
      let html = `<div class="hour-tip-header">${h}:00 – ${h + 1}:00</div>`;
      if (!hasAny) {
        html += `<div style="font-size:10px;color:${INK_MUTE}">无记录</div>`;
      } else {
        for (const s of series) {
          const ms = s.data[h]?.durationMs || 0;
          if (ms <= 0) continue;
          html += `<div class="hour-tip-row">
            <span class="hour-tip-dot" style="background:${s.color.line}"></span>
            <span class="hour-tip-name">${escapeHtml(s.srcLabel)}·${escapeHtml(s.label)}</span>
            <span class="hour-tip-time">${formatDuration(ms)}</span>
          </div>`;
        }
      }
      tip.innerHTML = html;
      tip.style.display = "block";

      // 定位：以列中心为基准，防止超出左右边界
      const barsRect = hourBars.getBoundingClientRect();
      const colRect  = col.getBoundingClientRect();
      const colCenterX = colRect.left - barsRect.left + colRect.width / 2;
      const tipW = tip.offsetWidth || 130;
      let left = colCenterX;
      // 防止超出右边界
      if (left + tipW / 2 > barsRect.width - 4) left = barsRect.width - tipW / 2 - 4;
      // 防止超出左边界
      if (left - tipW / 2 < 4) left = tipW / 2 + 4;
      tip.style.left = left + "px";
      // 底部对齐柱子区域顶端上方 6px
      tip.style.bottom = (BAR_H + 6) + "px";
    });
    col.addEventListener("mouseleave", () => { tip.style.display = "none"; });

    hourBars.appendChild(col);
  }
}

function normalizeHours(hours) {
  const result = Array.from({ length: 24 }, (_, hour) => ({ hour, durationMs: 0 }));
  for (let i = 0; i < hours.length; i++) {
    const item = hours[i] || {};
    const hour = Number.isInteger(item.hour) ? item.hour : i;
    if (hour >= 0 && hour <= 23) result[hour].durationMs = Math.max(0, Number(item.durationMs || 0));
  }
  return result;
}

function srcDisplayLabel(src) {
  src = (src || "").toLowerCase();
  if (src === "android" || src === "app") return "Android";
  if (src === "web" || src === "browser" || src === "chrome") return "浏览器";
  return src || "未知";
}

function renderDeviceList(devices, hoursByDevice) {
  deviceList.innerHTML = "";
  if (!devices || !devices.length) {
    const li = document.createElement("li");
    li.innerHTML = `<span class="device-name" style="color:${INK_SOFT}">这一天没有设备上报</span>`;
    deviceList.appendChild(li);
    return;
  }
  // 计算每个设备的颜色索引（与 24h 图完全对应）
  const colorMap = {};
  if (hoursByDevice) {
    const metas = assignSeriesColors(Object.keys(hoursByDevice), hoursByDevice);
    for (const m of metas) colorMap[m.id] = m.color;
  }

  for (const dev of devices) {
    const li = document.createElement("li");
    const src = dev.source || "";
    const srcTag = dev.sourceLabel || srcDisplayLabel(src);
    // 从 hoursByDevice 颜色映射找，找不到就按设备序号分色
    const colorEntry = colorMap[dev.deviceId]
      || DEVICE_COLORS[i % DEVICE_COLORS.length];
    const lineColor = colorEntry.line || colorEntry;
    const fillHex = lineColor + "1a";
    li.innerHTML = `
      <span class="device-dot" style="background:${lineColor}"></span>
      <span class="device-name">${escapeHtml(dev.deviceAlias || dev.deviceId)}
        <span style="color:${lineColor};font-size:10px;padding:1px 6px;border-radius:6px;background:${fillHex};margin-left:4px;">${escapeHtml(srcTag)}</span>
      </span>
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

  const padding = { top: 28, right: 14, bottom: 26, left: 34 };
  const innerW = w - padding.left - padding.right;
  const innerH = h - padding.top - padding.bottom;
  // 从旧到新从左到右
  const sorted = [...state.days].sort((a, b) => (a.date < b.date ? -1 : 1));
  const rawMax = sorted.reduce((m, d) => Math.max(m, d.totalMs || 0), 0);
  const niceMax = niceCeilMs(Math.max(60_000, rawMax));

  // 网格 + Y 轴标签（4 档）
  ctx.strokeStyle = GRID;
  ctx.lineWidth = 1;
  ctx.fillStyle = INK_MUTE;
  ctx.font = "10px sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  for (let i = 0; i <= 4; i++) {
    const y = padding.top + (innerH * i) / 4;
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(padding.left + innerW, y);
    ctx.stroke();
    const value = niceMax * (1 - i / 4);
    ctx.fillText(axisLabelMs(value), padding.left - 6, y);
  }

  const n = sorted.length;
  const slot = innerW / Math.max(1, n);
  const xs = [];
  const ys = [];
  for (let i = 0; i < n; i++) {
    const day = sorted[i];
    const x = padding.left + slot * (i + 0.5);
    const ratio = (day.totalMs || 0) / niceMax;
    let y = padding.top + innerH - ratio * innerH;
    if (!day.totalMs) y = padding.top + innerH; // 零值贴基线
    else y = Math.min(y, padding.top + innerH - 2);
    xs.push(x);
    ys.push(y);
  }

  // 选中点背景高亮
  const selectedIdx = sorted.findIndex(d => d.date === state.selectedDate);
  if (selectedIdx >= 0) {
    ctx.fillStyle = "rgba(251,114,153,0.10)";
    const sx = xs[selectedIdx];
    ctx.fillRect(sx - slot / 2 + 2, padding.top - 4, slot - 4, innerH + 4);
  }

  // 填充面积 + 折线
  if (n >= 1) {
    const grad = ctx.createLinearGradient(0, padding.top, 0, padding.top + innerH);
    grad.addColorStop(0, "rgba(251,114,153,0.30)");
    grad.addColorStop(1, "rgba(251,114,153,0.02)");
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.moveTo(xs[0], padding.top + innerH);
    ctx.lineTo(xs[0], ys[0]);
    for (let i = 1; i < n; i++) {
      const midX = (xs[i - 1] + xs[i]) / 2;
      ctx.bezierCurveTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
    }
    ctx.lineTo(xs[n - 1], padding.top + innerH);
    ctx.closePath();
    ctx.fill();

    ctx.strokeStyle = PINK;
    ctx.lineWidth = 2;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.beginPath();
    ctx.moveTo(xs[0], ys[0]);
    for (let i = 1; i < n; i++) {
      const midX = (xs[i - 1] + xs[i]) / 2;
      ctx.bezierCurveTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
    }
    ctx.stroke();

    // 点
    for (let i = 0; i < n; i++) {
      const isSel = i === selectedIdx;
      ctx.fillStyle = "#FFFFFF";
      ctx.beginPath();
      ctx.arc(xs[i], ys[i], isSel ? 5 : (sorted[i].totalMs > 0 ? 3.5 : 2.2), 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = isSel ? PINK_DEEP : (sorted[i].totalMs > 0 ? PINK : "#E9CCD7");
      ctx.beginPath();
      ctx.arc(xs[i], ys[i], isSel ? 3.5 : (sorted[i].totalMs > 0 ? 2.2 : 1.6), 0, Math.PI * 2);
      ctx.fill();
      if (isSel) {
        ctx.strokeStyle = PINK_DEEP;
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.arc(xs[i], ys[i], 6, 0, Math.PI * 2);
        ctx.stroke();
      }
    }
  }

  // 选中点 tooltip
  if (selectedIdx >= 0) {
    const day = sorted[selectedIdx];
    const label = `${shortDateLabel(day.date)} · ${formatDuration(day.totalMs)}`;
    ctx.font = "11px sans-serif";
    const tw = ctx.measureText(label).width + 16;
    const th = 20;
    let tx = xs[selectedIdx] - tw / 2;
    tx = Math.max(padding.left, Math.min(padding.left + innerW - tw, tx));
    let ty = ys[selectedIdx] - th - 8;
    if (ty < 2) ty = 2;
    ctx.fillStyle = "#18181B";
    roundedRect(ctx, tx, ty, tw, th, 6);
    ctx.fill();
    ctx.fillStyle = "#fff";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(label, tx + tw / 2, ty + th / 2);
  }

  // X 轴标签
  ctx.fillStyle = INK_SOFT;
  ctx.font = "10px sans-serif";
  ctx.textAlign = "center";
  ctx.textBaseline = "top";
  const step = Math.max(1, Math.ceil(n / 6));
  for (let i = 0; i < n; i += step) {
    ctx.fillText(shortDateLabel(sorted[i].date), xs[i], padding.top + innerH + 4);
  }
  if (n > 0 && (n - 1) % step !== 0) {
    ctx.fillText(shortDateLabel(sorted[n - 1].date), xs[n - 1], padding.top + innerH + 4);
  }

  // 交互：点击点选中
  trendCanvas._hitMap = { padding, innerW, innerH, slot, xs, ys, sorted };
}

/**
 * Y 轴友好的最大值取整。
 * 策略：从 10s 到 24h 的一个授权步长表里选「刚好 ≥ ms 且 能被 4 整除」的那个。
 * 这样看 10 分钟时刻度是 0/2.5m/5m/7.5m/10m，看 1 小时时是 0/15m/30m/45m/1h。
 */
function niceCeilMs(ms) {
  if (ms <= 0) return 1000;
  const steps = [
    1000, 5000, 10_000, 20_000, 30_000,                       // 1s ~ 30s
    60_000, 2 * 60_000, 4 * 60_000, 5 * 60_000, 10 * 60_000,  // 1m ~ 10m
    20 * 60_000, 30 * 60_000,                                  // 20m / 30m
    60 * 60_000, 2 * 60 * 60_000, 4 * 60 * 60_000,             // 1h / 2h / 4h
    6 * 60 * 60_000, 8 * 60 * 60_000, 12 * 60 * 60_000,        // 6h / 8h / 12h
    24 * 60 * 60_000                                           // 24h
  ];
  for (const s of steps) if (ms <= s) return s;
  return Math.ceil(ms / 3600_000) * 3600_000;
}

/** Y 轴紧凑标签（0 / 30秒 / 2.5分 / 5分 / 1小时 / 1小时30分 / …） */
function axisLabelMs(ms) {
  if (ms <= 0) return "0";
  if (ms < 1000) return Math.round(ms) + "毫秒";
  if (ms < 60_000) {
    const s = ms / 1000;
    if (Number.isInteger(s)) return s + "秒";
    return s.toFixed(1) + "秒";
  }
  if (ms < 60 * 60_000) {
    const m = ms / 60_000;
    if (Number.isInteger(m)) return m + "分";
    return m.toFixed(1) + "分";
  }
  const totalMin = ms / 60_000;
  const hh = Math.floor(totalMin / 60);
  const rem = totalMin % 60;
  if (rem === 0) return hh + "小时";
  if (Number.isInteger(rem)) return hh + "小时" + rem + "分";
  return hh + "小时" + rem.toFixed(1) + "分";
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
  if (!dateStr) return "";
  const [, mm, dd] = dateStr.split("-");
  return `${Number(mm)}/${Number(dd)}`;
}

function formatDateInTimeZoneJs(date) {
  // 取本地时区 YYYY-MM-DD。background 依赖 DISPLAY_TIME_ZONE，这里兑底用本地。
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
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

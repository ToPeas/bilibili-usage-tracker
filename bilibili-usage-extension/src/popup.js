const total = document.querySelector("#total");
const active = document.querySelector("#active");
const hosts = document.querySelector("#hosts");
const uploadButton = document.querySelector("#upload-now");
const uploadStatus = document.querySelector("#upload-status");
const trackingDebug = document.querySelector("#tracking-debug");
const databaseLabel = document.querySelector("#database-label");
const recentChart = document.querySelector("#recent-chart");
const deviceDetail = document.querySelector("#device-detail");
const recentDays = document.querySelector("#recent-days");
const recentMessage = document.querySelector("#recent-message");
const openOptionsButton = document.querySelector("#open-options");
const DISPLAY_TIME_ZONE = "Asia/Shanghai";

init();
const refreshTimer = setInterval(() => {
  render();
}, 1000);

window.addEventListener("unload", () => {
  clearInterval(refreshTimer);
});

uploadButton.addEventListener("click", async () => {
  uploadButton.disabled = true;
  uploadButton.textContent = "补传中...";
  const response = await chrome.runtime.sendMessage({ type: "upload-now" });
  uploadButton.textContent = response?.ok ? "补传完成" : "补传失败";
  setTimeout(() => {
    uploadButton.textContent = "立即补传";
    uploadButton.disabled = false;
  }, 1200);
  await render();
  await renderRecentUsage();
});

openOptionsButton.addEventListener("click", async () => {
  await chrome.runtime.sendMessage({ type: "open-options" });
});

async function init() {
  await render();
  await renderRecentUsage();
}

async function render() {
  const status = await chrome.runtime.sendMessage({ type: "get-status" });
  total.textContent = formatDuration(status.todayTotalMs || 0);
  active.textContent = status.active ? `正在统计：${status.host}` : "当前未统计";
  uploadStatus.textContent = getTodayUploadText(status);
  trackingDebug.textContent = formatDebug(status.debug, status.todayTotalMs || 0);

  const rows = Object.entries(status.todayItems || {}).sort((a, b) => b[1] - a[1]);
  hosts.replaceChildren(...rows.map(([host, ms]) => {
    const item = document.createElement("li");
    const name = document.createElement("span");
    const value = document.createElement("span");
    name.textContent = host;
    value.textContent = formatDuration(ms);
    item.append(name, value);
    return item;
  }));
}

async function renderRecentUsage() {
  recentMessage.textContent = "读取中...";
  const response = await chrome.runtime.sendMessage({ type: "get-recent-usage" });
  databaseLabel.textContent = response?.database || "未配置 D1";

  if (!response?.ok) {
    recentChart.replaceChildren();
    deviceDetail.replaceChildren();
    recentDays.replaceChildren();
    recentMessage.textContent = response?.error === "missing settings" ? "填写 D1 配置后显示最近 7 天数据" : `读取失败：${response?.error || "unknown"}`;
    return;
  }

  renderChart(response.days);
  renderDeviceDetail(response.days[0]);
  recentDays.replaceChildren(...response.days.map(day => {
    const item = document.createElement("li");
    item.className = "day-row";
    item.addEventListener("mouseenter", () => renderDeviceDetail(day));
    item.addEventListener("focus", () => renderDeviceDetail(day));
    item.tabIndex = 0;

    const date = document.createElement("span");
    date.className = "day-date";
    date.textContent = formatDateLabel(day.date);

    const upload = document.createElement("span");
    upload.className = "day-upload";
    upload.textContent = day.latestUploadedAt ? `上传 ${formatDateTime(day.latestUploadedAt)}` : "暂无上传";

    const totalValue = document.createElement("span");
    totalValue.className = "day-total";
    totalValue.textContent = formatDuration(day.totalMs || 0);

    item.append(date, upload, totalValue);
    return item;
  }));
  recentMessage.textContent = "";
}

function renderChart(days) {
  const chronological = [...days].reverse();
  const maxMs = Math.max(...chronological.map(day => day.totalMs || 0), 1);
  recentChart.replaceChildren(...chronological.map(day => {
    const item = document.createElement("div");
    item.className = "chart-day";
    item.addEventListener("mouseenter", () => renderDeviceDetail(day));
    item.addEventListener("focus", () => renderDeviceDetail(day));
    item.tabIndex = 0;

    const bar = document.createElement("div");
    bar.className = "chart-bar";
    bar.style.height = `${Math.max(3, Math.round(((day.totalMs || 0) / maxMs) * 76))}px`;
    bar.title = `${day.date} · ${formatDuration(day.totalMs || 0)}`;

    const label = document.createElement("span");
    label.className = "chart-label";
    label.textContent = formatDateLabel(day.date);

    item.append(bar, label);
    return item;
  }));
}

function renderDeviceDetail(day) {
  deviceDetail.replaceChildren(buildDeviceBreakdown(day));
}

function buildDeviceBreakdown(day) {
  const box = document.createElement("div");
  const title = document.createElement("div");
  title.className = "device-title";
  title.textContent = `${day.date} · 按设备`;
  box.append(title);

  if (!day.devices.length) {
    const empty = document.createElement("div");
    empty.className = "device-meta";
    empty.textContent = "这一天没有已上传记录";
    box.append(empty);
    return box;
  }

  for (const device of day.devices) {
    const row = document.createElement("div");
    row.className = "device-row";

    const id = document.createElement("span");
    id.className = "device-id";
    id.textContent = device.deviceAlias || device.deviceId || "unknown";
    id.title = device.deviceId || "";

    const totalValue = document.createElement("span");
    totalValue.className = "day-total";
    totalValue.textContent = formatDuration(device.totalMs || 0);

    const meta = document.createElement("span");
    meta.className = "device-meta";
    meta.textContent = device.uploadedAt ? `上传 ${formatDateTime(device.uploadedAt)}` : "暂无上传时间";

    row.append(id, totalValue, meta);
    box.append(row);
  }
  return box;
}

function formatDuration(ms) {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function formatDateTime(value) {
  const date = parseD1Time(value);
  if (Number.isNaN(date.getTime())) return value;
  const parts = new Intl.DateTimeFormat("zh-CN", {
    timeZone: DISPLAY_TIME_ZONE,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${values.month}-${values.day} ${values.hour}:${values.minute}`;
}

function getTodayUploadText(status) {
  if (status.uploaded?.[status.today]) return "今日已上传";
  const latestToday = (status.uploadLog || []).find(item => item.date === status.today);
  if (latestToday?.ok) return "今日已上传";
  if (latestToday && !latestToday.ok) return "今日上传失败";
  return "今日尚未上传";
}

function formatDebug(debug, todayTotalMs) {
  if (!debug?.lastMessageAt) return "还没有收到页面计时消息。请刷新 B 站页面。";
  const ageSeconds = Math.round((Date.now() - debug.lastMessageAt) / 1000);
  const totalText = formatDuration(todayTotalMs);
  if (debug.lastReason === "accepted") {
    return `正在计时 · 今日 ${totalText} · 最近 +${((debug.lastCountedMs || 0) / 1000).toFixed(1)}s · ${ageSeconds}s 前`;
  }
  return `未计入：${debug.lastReason || "unknown"} · 今日 ${totalText} · ${ageSeconds}s 前 · active=${debug.contextHost || "-"} · visible=${debug.contentVisible}`;
}

function formatDateLabel(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || "");
  if (!match) return value;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12, 0, 0));
  const parts = new Intl.DateTimeFormat("zh-CN", {
    timeZone: DISPLAY_TIME_ZONE,
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${values.month}-${values.day}`;
}

function parseD1Time(value) {
  if (typeof value !== "string") return new Date(value);
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) {
    return new Date(`${value.replace(" ", "T")}Z`);
  }
  return new Date(value);
}

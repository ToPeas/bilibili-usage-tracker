const form = document.querySelector("#settings-form");
const message = document.querySelector("#message");
const uploadButton = document.querySelector("#upload-now");
const testD1Button = document.querySelector("#test-d1");
const logList = document.querySelector("#upload-log");

init();

async function init() {
  const { settings = {} } = await chrome.storage.local.get("settings");
  form.accountId.value = settings.accountId || "";
  form.databaseId.value = settings.databaseId || "";
  form.cloudflareApiToken.value = settings.cloudflareApiToken || "";
  form.deviceId.value = settings.deviceId || crypto.randomUUID();
  form.deviceAlias.value = settings.deviceAlias || "";
  form.appVersion.value = settings.appVersion || "1.0.0";
  await renderLog();
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  const settings = {
    accountId: form.accountId.value.trim(),
    databaseId: form.databaseId.value.trim(),
    cloudflareApiToken: form.cloudflareApiToken.value.trim(),
    deviceId: form.deviceId.value.trim(),
    deviceAlias: form.deviceAlias.value.trim(),
    appVersion: form.appVersion.value.trim() || "1.0.0"
  };
  await chrome.storage.local.set({ settings });
  await chrome.runtime.sendMessage({ type: "settings-updated" });
  message.textContent = "已保存";
});

uploadButton.addEventListener("click", async () => {
  uploadButton.disabled = true;
  message.textContent = "正在补传...";
  const response = await chrome.runtime.sendMessage({ type: "upload-now" });
  message.textContent = response?.ok ? `补传完成：${response.result.uploaded} 天` : `补传失败：${response?.error || "unknown"}`;
  await renderLog();
  uploadButton.disabled = false;
});

testD1Button.addEventListener("click", async () => {
  testD1Button.disabled = true;
  message.className = "";
  message.textContent = "正在测试 D1 读写...";
  await saveSettings();
  const response = await chrome.runtime.sendMessage({ type: "test-d1-connection" });
  if (response?.ok) {
    message.className = "status-ok";
    message.textContent = `${response.message} · ${response.database}`;
  } else {
    const phase = response?.phase === "write" ? "写入测试失败" : response?.phase === "read" ? "读取测试失败" : "测试失败";
    message.className = "status-error";
    message.textContent = `${phase}：${response?.error || "unknown"}`;
  }
  testD1Button.disabled = false;
});

async function saveSettings() {
  const settings = {
    accountId: form.accountId.value.trim(),
    databaseId: form.databaseId.value.trim(),
    cloudflareApiToken: form.cloudflareApiToken.value.trim(),
    deviceId: form.deviceId.value.trim(),
    deviceAlias: form.deviceAlias.value.trim(),
    appVersion: form.appVersion.value.trim() || "1.0.0"
  };
  await chrome.storage.local.set({ settings });
  await chrome.runtime.sendMessage({ type: "settings-updated" });
}

async function renderLog() {
  const response = await chrome.runtime.sendMessage({ type: "get-status" });
  const logs = response?.uploadLog || [];
  logList.replaceChildren(...logs.map(log => {
    const item = document.createElement("li");
    const time = new Date(log.time).toLocaleString();
    item.textContent = `${log.date} · ${log.ok ? "成功" : "失败"} · ${log.status || ""} · ${time}`;
    return item;
  }));
}

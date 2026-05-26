# Bilibili Usage Tracker

> 跨设备 B 站使用时长统计 — Chrome 插件 · Android App · HarmonyOS（实验性）

**Bilibili Usage Tracker** 让你看清自己每天在 B 站上花了多少时间。无论是在电脑浏览器刷视频，还是用手机/平板开 App，所有设备的数据都汇聚到同一个 Cloudflare D1 数据库，在趋势图和 24 小时热力图中一目了然。

---

## 截图

<table>
  <tr>
    <td align="center" width="33%">
      <b>Chrome 插件 · Popup</b><br/>
      <img src="docs/screenshot-extension.png" alt="Chrome 插件 Popup" width="240"/>
    </td>
    <td align="center" width="33%">
      <b>Android · 趋势图 + 24h 分布</b><br/>
      <img src="docs/screenshot-android-chart.png" alt="Android 趋势图" width="180"/>
    </td>
    <td align="center" width="33%">
      <b>Android · 多设备拆分</b><br/>
      <img src="docs/screenshot-android-device.png" alt="Android 设备拆分" width="180"/>
    </td>
  </tr>
</table>

---

## 功能

### 🖥️ Chrome / Edge 插件 `bilibili-usage-extension/`

- **精准计时**：只有 B 站标签页处于前台、窗口聚焦且系统未 idle 时才计时，切换标签页或最小化立即暂停。
- **按「日 + 小时」双重分桶**：本地以 `usage[date].byHour` 存储，跨午夜自动拆分；D1 端同步写入 `usage_hours` 表。
- **自动上传**：每天凌晨自动补传最近 30 天内所有未上传数据，也可在 popup 中手动「立即上传」。
- **趋势 Popup**：7 / 30 / 90 / 180 天范围可切换，折线图点击后展示该日 24 小时分布与全设备拆分。
- **设置页**：填写 Cloudflare 信息，支持测试 D1 读写连通性，展示近期上传日志。

### 📱 Android App `bilibili-usage-android/`

- 调用系统 `UsageStatsManager` 读取 B 站（含 B 站 HD、海外版）App 的实际使用时长。
- **全设备汇总**：Hero 区同时显示「本机今日」和「全设备今日总计」（从 D1 拉取）。
- **使用趋势图**：7 天 / 30 天 / 3 个月 / 半年范围切换，折线图点击查看任意一天的 24 小时分布。
- **设备拆分**：选中某天后展示当天每台设备的用量与上传时间，配色与趋势图一一对应。
- **自动上传**：每天早上 **05:00** 后台自动上传；打开 App 时也会静默上传今日数据。
- **手动同步**：「同步最近 7 天」「上传当前范围」「补传最近半年」三种手动操作。
- **并发安全**：自动上传与手动上传之间有互斥锁，不会因并发写入导致 D1 报错。

### 🌐 Cloudflare Worker `worker/` （可选）

Worker 为可选的后端中转层，适合不想把 API Token 暴露在客户端的场景。若直接用插件 / App 直连 D1，可忽略此目录，仅参考 `worker/schema.sql` 初始化表结构即可。

### ⚠️ HarmonyOS App `bilibili-usage-harmony/` — 实验性，暂不可用

代码已完成基本框架，但**华为鸿蒙目前不向三方应用开放 App 使用时长查询接口**（`BUNDLE_ACTIVE_INFO` 权限属 `system_basic` 级别，三方不可申请），无法读取 B 站的使用时长数据。待鸿蒙开放相关 API 后可继续完善。

---

## 项目结构

```
bilibili-usage-tracker/
├── bilibili-usage-extension/       # Chrome / Edge MV3 插件（v1.3.3）
│   ├── src/
│   │   ├── background.js           # Service Worker：计时调度、本地存储、D1 上传
│   │   ├── content.js              # 注入 B 站页面，精确累计可见时间
│   │   ├── popup.js / popup.html   # 弹出窗：趋势折线图 + 24h 热力图 + 设备拆分
│   │   └── options.js / options.html # 设置页：D1 配置、连接测试、上传日志
│   └── manifest.json
│
├── bilibili-usage-android/         # Android App（v1.3.9，纯 Java，无第三方依赖）
│   └── app/src/main/java/com/example/biliusage/
│       ├── MainActivity.java       # 主界面（全部纯代码构建 UI）
│       ├── UsageCollector.java     # UsageStatsManager 封装
│       ├── DailyUploadReceiver.java # 定时上传 BroadcastReceiver（每天 05:00）
│       ├── D1Client.java           # Cloudflare D1 HTTP API 客户端
│       └── SettingsStore.java      # SharedPreferences 配置存储
│
├── bilibili-usage-harmony/         # HarmonyOS App（实验性，暂不可用）
│
├── worker/                         # 可选 Cloudflare Worker 后端中转
│   ├── src/index.js
│   └── schema.sql                  # D1 建表 SQL（必须执行一次）
│
└── docs/                           # 截图与文档资源
```

---

## 快速开始

### 第一步：创建 Cloudflare D1 数据库

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com/) → **Workers & Pages → D1** → 新建数据库，命名随意（如 `bili_usage`）。
2. 进入数据库详情 → **Console** 标签页 → 粘贴并执行 `worker/schema.sql` 中的全部 SQL，完成建表。
3. 记下以下三项（后面填写到插件/App 的设置中）：

   | 配置项 | 获取位置 |
   |--------|---------|
   | **Account ID** | Dashboard 右侧边栏 |
   | **D1 Database ID** | 数据库详情页 URL 中的 UUID |
   | **API Token** | [Create Token](https://dash.cloudflare.com/profile/api-tokens)，选 `D1 Edit` 权限模板 |

> 也可以用 Wrangler CLI：
> ```bash
> npx wrangler d1 create bili_usage
> npx wrangler d1 execute bili_usage --file=./worker/schema.sql
> ```

---

### 第二步：安装 Chrome 插件

1. 打开 Chrome / Edge → 地址栏输入 `chrome://extensions/` → 开启**开发者模式**。
2. 点击「**加载已解压的扩展程序**」→ 选择仓库中的 `bilibili-usage-extension/` 目录。
3. 点击插件图标 → 右上角齿轮进入设置页，填入：
   - Cloudflare Account ID
   - D1 Database ID  
   - API Token
   - Device ID（留空则自动生成 UUID）
   - Device Alias（如 `家里的 Mac`、`公司的 Mac`）
4. 点击「**测试连接**」，返回"可读可写"即配置成功。

---

### 第三步：安装 Android App

> **前提**：Android 6.0+，需要授予「使用情况访问权限」。

**方式一：直接安装 APK**

用 Android Studio 打开 `bilibili-usage-android/` 后 Build → 安装到设备。

**方式二：Android Studio Run**

```bash
# 连接 Android 设备后
cd bilibili-usage-android
./gradlew installDebug
```

**配置步骤：**

1. 打开 App → 点击「**打开使用情况权限**」→ 找到本 App → 开启权限。
2. 下拉到「D1 连接设置」区域，填入与插件**相同**的 Account ID、Database ID、API Token，以及自定义的 Device ID / Device Alias。
3. 点击「**同步最近 7 天**」，图表刷新后即可看到来自各设备的汇总数据。

---

## D1 数据表

| 表名 | 说明 |
|------|------|
| `usage_daily` | 每日每设备汇总，主键 `(date, source, device_id)` |
| `usage_items` | 每日明细（域名 / 包名级别的细分时长） |
| `usage_hours` | 每日 24 小时分桶（`hour` 字段范围 0–23） |

同一天同一设备重复上传会覆盖旧数据，保证幂等性。

---

## 安全说明

- **API Token 仅存储在本地**（Chrome `storage.local` / Android `SharedPreferences`），不经过任何第三方服务器。
- D1 中只存储时长毫秒数，不包含任何 URL、标题或浏览内容信息。
- 建议为本项目单独创建权限最小化的 API Token（仅 `D1 Edit`，**不要**使用 Global API Key）。

---

## 版本

| 平台 | 版本 |
|------|------|
| Chrome 插件 | v1.3.3 |
| Android App | v1.3.9 (versionCode 19) |
| HarmonyOS | 实验性，暂不可用 |

---

## License

[MIT](LICENSE)

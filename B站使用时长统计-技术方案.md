# B 站每日使用时长统计 —— 三端整体技术方案

> 目标：完整统计单一 B 站账号在 **网页版 / 安卓平板 / 鸿蒙 NEXT 手机** 三端的每日使用时长，统一上报到云端，供后续汇总分析与可视化。

---

## 一、整体架构

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ 浏览器扩展    │   │ 安卓 App     │   │ 鸿蒙 NEXT App│
│ (Chrome/Edge)│   │ (Android)    │   │ (HarmonyOS)  │
└──────┬───────┘   └──────┬───────┘   └──────┬───────┘
       │ 每日 00:05       │                   │
       │ PUT JSON         │                   │
       ▼                  ▼                   ▼
              ┌──────────────────────┐
              │  Cloudflare R2       │
              │  (S3 兼容对象存储)    │
              │  bili-usage/<src>/   │
              │   <device>/<date>.json│
              └──────────┬───────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │ 汇总脚本 / Web Dashboard │
              │ (本地 Python / Worker)│
              └──────────────────────┘
```

**核心思路**：三端独立采集 → 统一格式 → 同一个 R2 桶 → 后续汇总。

---

## 二、统一上报协议

所有端共用同一套 JSON 格式与对象 Key 规则，保证后续聚合简单。

### 2.1 R2 对象 Key

```
bili-usage/{source}/{deviceId}/{yyyy-MM-dd}.json
```

- `source`：`web` / `android` / `harmony`
- `deviceId`：稳定标识（浏览器用随机 UUID 持久化；移动端用 udid 哈希）
- 一文件一天一设备，**幂等**，重传直接覆盖同 Key

### 2.2 文件内容

```json
{
  "date": "2026-05-16",
  "source": "harmony",
  "deviceId": "hash-or-uuid",
  "timezone": "Asia/Shanghai",
  "items": [
    { "bundle": "tv.danmaku.bili", "durationMs": 3120000 }
  ],
  "totalMs": 3120000,
  "reportedAt": "2026-05-17T00:05:12+08:00",
  "appVersion": "1.0.0",
  "schemaVersion": 1
}
```

- **网页端** 的 `bundle` 用域名标识：`bilibili.com` / `live.bilibili.com` / `t.bilibili.com`
- **移动端** 的 `bundle` 用包名

### 2.3 云端存储：Cloudflare R2

- Endpoint: `https://<account>.r2.cloudflarestorage.com`
- Bucket: `bili-usage`
- 客户端直接用 **AWS SigV4** 签名 PUT（自用场景）
- R2 API Token **限定** 只能 `PutObject` 到 `bili-usage/*`，最小权限
- 可选更安全方案：Cloudflare Workers 中转，鸿蒙/安卓/扩展只发 Bearer Token

> ⚠️ 不要用 R2 直接存 SQLite，对象存储不支持部分写入、无文件锁，多端并发会互相覆盖。

---

## 三、端 1：浏览器扩展（Web）

### 3.1 技术选型

| 项 | 选型 |
|---|---|
| 标准 | Manifest V3 |
| 平台 | Chrome / Edge / Brave 通用 |
| 存储 | `chrome.storage.local` |
| 定时 | `chrome.alarms` |

### 3.2 核心逻辑

**计时规则**（满足以下条件才计时）：
1. 当前激活 Tab 的 host 在白名单：`*.bilibili.com`
2. 浏览器窗口处于 focused 状态
3. 系统未 idle（`chrome.idle` 状态为 `active`）

**事件监听**：
- `chrome.tabs.onActivated`
- `chrome.tabs.onUpdated`
- `chrome.windows.onFocusChanged`
- `chrome.idle.onStateChanged`（阈值 60s）

**实现方式**：
- 维护一个状态机 `{ active: bool, host: string, startedAt: number }`
- 状态变化时累加上一段时长到 `storage.local[date][host]`
- `chrome.alarms` 注册每日 00:05 触发上报

**数据结构（local）**：
```json
{
  "2026-05-16": {
    "bilibili.com": 1820000,
    "live.bilibili.com": 600000
  },
  "uploaded": { "2026-05-16": true }
}
```

### 3.3 上报

Service Worker 内调用 `fetch` PUT 到 R2（带 SigV4 签名）。失败则下次启动重试，扫最近 30 天 `uploaded != true` 的记录补传。

### 3.4 配置页

- 输入 R2 Endpoint / Bucket / AccessKey / SecretKey
- 设备名（默认随机 UUID，可改）
- 手动触发上报、查看上报历史

---

## 四、端 2：安卓平板 App（Android）

### 4.1 技术选型

| 项 | 选型 |
|---|---|
| 语言 | Kotlin |
| min SDK | 26 (Android 8.0+) |
| 使用统计 | `UsageStatsManager` |
| 定时 | `WorkManager`（PeriodicWorkRequest） |
| 网络 | OkHttp |
| 本地存储 | Room (SQLite) |

### 4.2 权限

```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
                 tools:ignore="ProtectedPermissions" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

`PACKAGE_USAGE_STATS` 是特殊权限，需引导用户：
```kotlin
startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
```

### 4.3 数据采集

```kotlin
val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
val stats = usm.queryUsageStats(
    UsageStatsManager.INTERVAL_DAILY,
    yesterdayStartMs,
    yesterdayEndMs
)
val total = stats
    .filter { it.packageName in TARGET_PACKAGES }
    .sumOf { it.totalTimeInForeground }
```

**目标包名**：
```
tv.danmaku.bili
tv.danmaku.bilibilihd
com.bilibili.app.in
```

### 4.4 定时与补传

- `WorkManager` 注册 `PeriodicWorkRequest`，间隔 24h，约束需联网
- 每次执行：查昨日 → 写 Room → 上传 R2 → 标记 uploaded
- 开机广播 `BOOT_COMPLETED` 时检查近 30 天补传
- App 启动时也做一次补传扫描

### 4.5 本地数据库

`usage_daily` 表（同鸿蒙端，见下文，**三端共用同一张表结构**）

---

## 五、端 3：鸿蒙 NEXT App（HarmonyOS）

### 5.1 技术选型

| 项 | 选型 |
|---|---|
| 语言 | ArkTS |
| UI | ArkUI（声明式） |
| 使用统计 | `@ohos.resourceschedule.usageStatistics` |
| 定时 | `@ohos.resourceschedule.workScheduler` |
| 网络 | `@ohos.net.http` |
| 存储 | `@ohos.data.relationalStore` + `@ohos.data.preferences` |

### 5.2 权限

```json
"requestPermissions": [
  { "name": "ohos.permission.BUNDLE_ACTIVE_INFO" },
  { "name": "ohos.permission.INTERNET" },
  { "name": "ohos.permission.GET_NETWORK_INFO" },
  { "name": "ohos.permission.KEEP_BACKGROUND_RUNNING" }
]
```

`BUNDLE_ACTIVE_INFO` 为 **system_basic**，普通签名拿不到。  
自用通过 DevEco 自动签名 / 调试签名安装即可；首次启动引导用户去 **设置 → 隐私 → 应用使用记录访问权限** 授权。

### 5.3 数据采集

```ts
import bundleState from '@ohos.resourceschedule.usageStatistics';

const infos = await bundleState.queryBundleStatsInfoByInterval(
  bundleState.IntervalType.BY_DAILY,
  yesterdayStartMs,
  yesterdayEndMs
);
const total = infos
  .filter(i => TARGET_BUNDLES.includes(i.bundleName))
  .reduce((s, i) => s + i.abilityInForegroundTotalTime, 0);
```

包名同安卓端。

### 5.4 定时与补传

- `workScheduler` 注册延迟任务，触发时间 00:05，约束联网
- 流程同安卓
- 应用启动时扫描近 30 天 `uploaded = false` 补传

---

## 六、三端共用模块设计

### 6.1 本地表结构（统一）

| 字段 | 类型 | 说明 |
|---|---|---|
| date | TEXT PK | yyyy-MM-dd |
| total_ms | INTEGER | 当日合计毫秒 |
| detail_json | TEXT | items 明细 |
| uploaded | INTEGER | 0 / 1 |
| upload_time | INTEGER | 上传时间戳 |
| retry_count | INTEGER | 重试次数 |

### 6.2 SigV4 签名上传伪代码

```
PUT https://<account>.r2.cloudflarestorage.com/bili-usage/{key}
Headers:
  Host:                <account>.r2.cloudflarestorage.com
  x-amz-date:          20260517T000512Z
  x-amz-content-sha256: <sha256(body)>
  Authorization:       AWS4-HMAC-SHA256 Credential=.../auto/s3/aws4_request,
                       SignedHeaders=host;x-amz-content-sha256;x-amz-date,
                       Signature=...
Body: <json>
```

每端各自实现一份 SigV4 签名（约 150 行），或封装一个轻量 SDK 复用思路。

### 6.3 时间统一规则

- 所有端按 **设备本地时区** 切日
- 文件 `timezone` 字段记录当时时区，便于后续跨时区出行修正
- 上报时间窗：每日 00:05 ~ 00:30，错峰减少集中失败

---

## 七、汇总与展示（后期）

### 7.1 拉取脚本（Python，本地跑）

```
list_objects("bili-usage/")
for obj in objs:
    download → 解析 JSON → INSERT INTO local.sqlite
```

### 7.2 看板方案

- 简易：Python + matplotlib / streamlit 出日报、周报
- 进阶：Cloudflare Workers + D1 做在线 Dashboard
- 推送：每周邮件 / 飞书 webhook 发送周报

---

## 八、开发优先级与工时估算

| 阶段 | 内容 | 工时 |
|---|---|---|
| P0 | R2 桶 & API Token 创建，定义协议 | 0.5 d |
| P1 | 浏览器扩展 MVP（采集 + 本地存储 + 上报） | 1.5 d |
| P2 | 安卓 App MVP（UsageStats + WorkManager + 上报） | 2 d |
| P3 | 鸿蒙 App MVP（usageStatistics + workScheduler + 上报） | 3 d |
| P4 | 汇总脚本 + 看板 | 1 d |
| P5 | 三端 UI 完善、异常处理、补传机制 | 2 d |

合计约 **10 人日**。

建议落地顺序：**P1 浏览器扩展（最简单先跑通协议）→ P2 安卓 → P3 鸿蒙 → P4 汇总**。

---

## 九、风险与注意事项

| 风险 | 应对 |
|---|---|
| 鸿蒙 `BUNDLE_ACTIVE_INFO` 权限等级高 | 自用通过自签名解决，不分发 |
| 安卓 `PACKAGE_USAGE_STATS` 需手动授权 | 首启引导，定期检查权限 |
| 浏览器扩展 Service Worker 会被回收 | 状态写 `storage`，不依赖内存；用 `alarms` 唤醒 |
| 系统使用统计有延迟 | 00:05 之后再读，避开缓冲期 |
| B 站包名/域名变更 | 维护可远程更新的白名单（可放 R2 一个 config.json） |
| 多端同时使用导致重复计时 | 设计上**默认相加**；如需精确去重，按小时分桶后取 max |
| AK 泄露 | R2 Token 仅授予 `PutObject` + 路径前缀；或改用 Workers 中转 |
| 设备时区漂移 | 文件带 timezone，汇总时统一归一化 |

---

## 十、未来扩展

- 增加直播观看时长细分（B 站直播 host 单独统计）
- 跨账号支持（如果家庭共享）
- 支持导出 Apple Screen Time 风格周报
- 接入更多端：Windows 桌面客户端、iPad（受限于 iOS 沙盒，可能只能做 Safari 扩展）

---

## 附录 A：目标标识清单

**移动端包名**：
```
tv.danmaku.bili
tv.danmaku.bilibilihd
com.bilibili.app.in
```

**网页域名**：
```
www.bilibili.com
m.bilibili.com
t.bilibili.com
live.bilibili.com
space.bilibili.com
search.bilibili.com
message.bilibili.com
```

## 附录 B：R2 Token 最小权限策略

```
Permissions: Object Read & Write
Specify bucket: bili-usage
TTL: 长期（自用）
```

仅授予到具体 bucket，避免账户级风险。

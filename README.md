# Bilibili Usage Tracker

这个仓库按 `B站使用时长统计-技术方案.md` 生成了浏览器端 MVP，并把原方案里的云端存储改成插件直连 Cloudflare D1 HTTP API。

## 目录

- `bilibili-usage-extension/`：Chrome/Edge Manifest V3 插件，统计当前聚焦窗口里 B 站域名的每日使用时长，并直接写入 D1。
- `bilibili-usage-android/`：安卓端 APK 项目，通过系统 UsageStatsManager 读取 B 站 App 使用时长并上传 D1。
- `worker/schema.sql`：D1 建表 SQL。`worker/` 里的 Worker 版本可忽略，除非你之后想改回后端中转。

## 插件能力

- 只在当前激活 Tab 是 B 站域名、浏览器窗口聚焦、系统 idle 状态为 active 时计时。
- B 站页面通过 content script 每秒累计可见且聚焦的时间，每 5 秒向后台发送增量；后台再校验当前激活 tab、窗口聚焦和系统 idle 状态后入账。
- **按「日 + 小时」双重分桶**：本地存为 `usage[date]={byHost,byHour}`，跨午夜/跨小时会拆到不同桍；D1端同步入表 `usage_hours`。
- 每天 00:05 自动补传上一批未上传数据（默认滑动窗 30 天）；也可在 popup / 设置页手动「立即上传」。
- popup 展示今日总时长、趋势图（8 个可选范围：7 / 30 / 90 / 180 天）、点到某天后可看该日 24 小时分布 + 设备拆分。
- 设置页支持测试 D1 连接（读 + 写两阶段），并呈现最近上传日志。

## D1 接入

插件直接调用 Cloudflare D1 Query API：

```text
POST https://api.cloudflare.com/client/v4/accounts/{account_id}/d1/database/{database_id}/query
Authorization: Bearer <Cloudflare API Token>
```

你只需要准备三项：

- Cloudflare Account ID
- D1 Database ID
- 有 `D1 Read` 和 `D1 Write` 权限的 Cloudflare API Token

如果你愿意用 Wrangler 创建和初始化数据库，可以在 `worker/` 目录执行：

```bash
wrangler d1 create bili_usage
```

把输出的 `database_id` 填入 `worker/wrangler.toml`：

```toml
[[d1_databases]]
binding = "DB"
database_name = "bili_usage"
database_id = "你的 database_id"
```

初始化表结构：

```bash
wrangler d1 execute bili_usage --file=./schema.sql
```

如果不想本地装 Wrangler，也可以在 Cloudflare Dashboard 创建 D1 数据库，然后在 D1 控制台里执行 `worker/schema.sql` 里的 SQL。

## 插件配置

打开插件设置页，填写：

- Cloudflare Account ID：Cloudflare 账户 ID
- D1 Database ID：D1 数据库 UUID
- Cloudflare API Token：带 `D1 Read` 和 `D1 Write` 权限的 API Token
- Device ID：默认会生成 UUID，也可以手动改
- Device Alias：设备别名，例如 `MacBook`、`Orion`，展示时优先使用它

## 直接验证

你可以用 curl 模拟插件写入：

```bash
curl "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT_ID/d1/database/$DATABASE_ID/query" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT 1 AS ok"
  }'
```

## 安装插件

1. 打开 Chrome/Edge 的扩展程序页面。
2. 开启开发者模式。
3. 选择“加载已解压的扩展程序”，目录选 `bilibili-usage-extension/`。
4. 打开插件设置页，填写 D1 连接信息。

## D1 表

- `usage_daily`：每日设备汇总，主键为 `(date, source, device_id)`。
- `usage_items`：每日明细，记录域名或移动端包名的时长。
- `usage_meta`：插件测试 D1 读写时自动创建，仅保存 `_connection_test` 记录。

同一天同设备重复上传会覆盖旧数据，保持幂等。

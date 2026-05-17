# B 站使用时长 - 鸿蒙 NEXT 原生应用

## 📦 这是什么

`bilibili-usage-harmony/` 是一个 HarmonyOS NEXT (5.0) 原生 ArkTS 应用，跟仓库里另外两个端共用同一套 Cloudflare D1 数据：

| 端 | 路径 | 数据源 |
| --- | --- | --- |
| Chrome 浏览器插件 | `bilibili-usage-extension/` | `source = 'web'` |
| Android APK | `bilibili-usage-android/` | `source = 'app'` |
| **鸿蒙 NEXT 原生** | **`bilibili-usage-harmony/` (本目录)** | **`source = 'harmony'`** |

## 🛠 为什么不能在 macOS CLI 直接打包

HarmonyOS hap 包必须由华为 **DevEco Studio** 用华为账号签名打出，无法纯命令行无登录构建。
代码我已经全部生成好；你需要：

## ✅ 装出来的步骤（10 分钟搞定）

1. **下载 DevEco Studio**（macOS 版，免费）：
   https://developer.huawei.com/consumer/cn/deveco-studio/

2. 打开 DevEco → File → Open → 选择本目录 `bilibili-usage-harmony/`

3. 首次会自动 Sync 拉取依赖（hypium 等）

4. 顶部菜单 **File → Project Structure → Signing Configs**
   勾选 `Automatically generate signature`（用华为账号自动签名 调试证书）

5. 插上一台 **鸿蒙 NEXT 真机**（手机/平板 NEXT 5.0+）。USB 连接 + 在系统设置里开「开发者模式」「USB 调试」

6. 点 **Run ▶ 'entry'** —— DevEco 会自动安装到鸿蒙设备

7. 应用启动后会弹出权限询问框「应用使用记录」—— **允许**

8. 进入 ⚙ 设置页，填上你 Cloudflare 的 Account ID / Database ID / API Token（跟另两个端同一个 D1），保存

9. 回主页点「刷新」—— 应该就能看到本机鸿蒙版 B 站今天的使用时长 + 全设备总计

## 🔌 工程结构

```
bilibili-usage-harmony/
├── AppScope/
│   ├── app.json5                          # 包名、版本、图标
│   └── resources/base/element/string.json
│   └── resources/base/media/app_icon.png  # 与插件/APK 同款图标
├── entry/
│   ├── src/main/
│   │   ├── module.json5                   # 权限声明（BUNDLE_ACTIVE_INFO、INTERNET）
│   │   ├── ets/
│   │   │   ├── entryability/EntryAbility.ets
│   │   │   ├── pages/
│   │   │   │   ├── Index.ets              # 主页（Canvas 折线图：最近 N 天 + 24h，Y 轴带数值）
│   │   │   │   └── Settings.ets           # 设置（D1 凭证 + 别名）
│   │   │   ├── common/SettingsStore.ets   # preferences 持久化
│   │   │   └── services/
│   │   │       ├── UsageCollector.ets     # @ohos.resourceschedule.usageStatistics
│   │   │       └── D1Client.ets           # @kit.NetworkKit http
│   │   └── resources/                     # 字符串、颜色、profile
│   ├── build-profile.json5
│   ├── hvigorfile.ts
│   └── oh-package.json5
├── build-profile.json5
├── oh-package.json5
└── hvigorfile.ts
```

## 🧠 关键技术点

- **采集**：`@ohos.resourceschedule.usageStatistics`
  - `queryBundleStatsInfoByInterval(IntervalType.BY_DAILY, start, end)` 拿每日聚合（与系统数字健康一致）
  - `queryBundleEvents(start, end)` 拿前后台事件，回放得到 24 小时分布
- **权限**：`ohos.permission.BUNDLE_ACTIVE_INFO`
  - 在 `module.json5` 声明 + 启动时 `abilityAccessCtrl.requestPermissionsFromUser` 弹窗
- **B 站匹配**：默认匹配 `com.huawei.hmsapp.bilibili`（鸿蒙版）+ `com.bilibili.app.in` + `tv.danmaku.bili` + 包名含 `bilibili`
- **网络**：`@kit.NetworkKit` http 直连 Cloudflare D1 REST API
- **持久化**：`@ohos.data.preferences` 存 D1 凭证 + 设备别名 + UUID
- **source 标记**：上传时 `source = 'harmony'`，其它端会用绿色「鸿蒙」标签展示

## ⚠️ 已知限制 / 后续优化

1. **签名**：DevEco 自动签名是调试证书，应用启动后 60 天有效；要发布得改 release 证书
2. **后台采集**：当前只在主页 onAppear / 点「刷新」时采集；想做后台定时上报可加 [BackgroundTaskManager](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-resourceschedule-backgroundtaskmanager) 周期任务
3. **图表实现**：`Index.ets` 内 `TrendChart` / `HourChart` 已用 `Canvas + CanvasRenderingContext2D` 绘制平滑贝塞尔折线 + Y 轴刻度数值（与浏览器插件、APK 风格统一）。如需修改样式，直接改两个组件的 `draw()` 函数。
4. **B 站包名**：实际安装的鸿蒙版 B 站包名以应用市场为准，若 `BUNDLE_ACTIVE_INFO` 拿到的列表里都搜不到，去 `Index.ets` 里改 `BILI_BUNDLES` 数组

## 🔬 如果用 DevEco 报"包名权限不足"
HarmonyOS 把 `BUNDLE_ACTIVE_INFO` 标为 **system_basic** 等级 — 调试证书一般 OK；如果不行，可以：
- 在 DevEco 里把 `module.json5` 的 `requestPermissions[0].name` 改为 `ohos.permission.QUERY_BUNDLE_INFO` 退化方案（只统计前台启动次数，没有时长）
- 或申请 ACL（Access Control List）走系统权限审批

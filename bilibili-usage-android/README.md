# Bilibili Usage Android

安卓端使用系统 `UsageStatsManager` 读取“小米/Android 设置里的应用使用情况”数据，然后直接写入同一个 Cloudflare D1。

## 功能

- 读取目标包名：
  - `tv.danmaku.bili`
  - `tv.danmaku.bilibilihd`
  - `com.bilibili.app.in`
- 采集方式从 `queryUsageStats` 换成 **`queryEvents` 事件回放**，能还原「哪个时段在前台」，并拆到每天 24 个小时桍。
- 设置页填写 Cloudflare Account ID、D1 Database ID、API Token、Device ID、Device Alias。
- App 图标使用和浏览器插件一致的图标。
- 手动上传可选 7 / 30 / 90 / 180 天范围；每天 00:05 通过 `AlarmManager` 自动补传最近 180 天；开机后重新注册并补传。

## UI 1.2 改版

- 顶部 **Hero 卡**：粉色渐变 + 今日 B 站累计时长 + 权限/设备状态 chip + 当日按包名拆分明细。
- **趋势图卡**：提供 7 / 30 / 3个月 / 半年 四个范围 tab；**触摸/拖动柱条**会高亮该天并在柱顶弹出 tooltip 显示当日总时长（原生 Android 没有 hover，为了能看到每日总时长与各设备详情，这里改成更直观的点击联动；外接鼠标可通过 `onHoverEvent` 同样触发）。
- **24 小时分布卡（新）**：选中某天后从 D1 `usage_hours` 拉该日各小时总时长（多设备汇总），以 24 柱柱状图呈现「在什么时段看 B 站最多」。
- 选中某天后，下方 **「按设备拆分」卡片** 会刷新，列出该天每台设备的：别名、占比条、时长、上传时间。
- 操作区提供「刷新当前范围」「上传当前范围（含今天）」「补传最近半年」「打开使用情况权限」「测试 D1 读写」五个按钮。
- 设置卡片用统一圆角输入框，支持 Account ID / Database ID / API Token / Device ID / Device Alias。

## 小米平板权限

首次启动后点“打开使用情况权限”，找到 `Bilibili Usage` 并允许访问使用情况。

## 构建 APK

用 Android Studio 打开 `bilibili-usage-android/`，等待 Gradle 同步后执行：

```bash
./gradlew assembleDebug
```

或者直接复用 Android Studio 自带的 gradle：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
"$HOME/.gradle/wrapper/dists/gradle-8.10-bin/deqhafrv1ntovfmgh0nh3npr9/gradle-8.10/bin/gradle" \
  assembleDebug
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

# Bilibili Usage Android

安卓端使用系统 `UsageStatsManager` 读取“小米/Android 设置里的应用使用情况”数据，然后直接写入同一个 Cloudflare D1。

## 功能

- 读取目标包名：
  - `tv.danmaku.bili`
  - `tv.danmaku.bilibilihd`
  - `com.bilibili.app.in`
- 设置页填写 Cloudflare Account ID、D1 Database ID、API Token、Device ID、Device Alias。
- 云端使用 Cloudflare D1，不是 R2；当前 APK 不包含 R2 Endpoint/Bucket/AccessKey/SecretKey 配置。
- App 图标使用和浏览器插件一致的图标。
- 手动上传最近 7 天数据。
- App 内展示最近 7 天图表和按设备别名拆分的明细。
- 每天 00:05 通过 `AlarmManager` 自动上传最近 7 天未空数据。
- 开机后重新注册定时任务并补传。

## 小米平板权限

首次启动后点“打开应用使用情况权限”，找到 `Bilibili Usage` 并允许访问使用情况。

## 构建 APK

用 Android Studio 打开 `bilibili-usage-android/`，等待 Gradle 同步后执行：

```bash
./gradlew assembleDebug
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前机器没有全局 `gradle`，所以如果没有 Gradle Wrapper，请直接用 Android Studio 打开构建。

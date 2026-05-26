# output/

发布产物目录，由每次构建/打包后手动或自动复制到此处，**受 git 追踪**。

## 命名规则

| 产物类型 | 文件名格式 | 示例 |
|----------|-----------|------|
| Android APK | `bilibili-usage-tracker-v{版本}.apk` | `bilibili-usage-tracker-v1.4.0.apk` |
| Chrome 插件 | `bilibili-usage-tracker-extension-v{版本}.zip` | `bilibili-usage-tracker-extension-v1.3.3.zip` |

## 当前产物

| 文件 | 平台 | 版本 | 说明 |
|------|------|------|------|
| `bilibili-usage-tracker-v1.4.0.apk` | Android | v1.4.0 (code 20) | 新增全屏 Loading、统一 App 名称 |
| `bilibili-usage-tracker-extension-v1.3.3.zip` | Chrome/Edge | v1.3.3 | 修复计时语义，新增 7 天同步按钮逻辑同步 |

## 安装方式

### Android APK
直接通过 adb 或文件管理器安装（需开启"允许未知来源"）：
```bash
adb install bilibili-usage-tracker-v1.4.0.apk
```

### Chrome 插件
1. 打开 `chrome://extensions/` → 开启开发者模式
2. 点击「加载已解压的扩展程序」→ 解压 zip 后选择该目录
   （或直接使用仓库中的 `bilibili-usage-extension/` 源码目录）

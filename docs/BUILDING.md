# 构建与测试

1.1.0 已迁移为 Kotlin / Jetpack Compose 原生界面，继续使用现有 Java 后台。工具链为 JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.21、Android SDK 35。

仓库暂未包含 Gradle Wrapper。安装上述环境后执行：

```bash
gradle :app:assembleDebug
python3 tools/test_core.py
```

调试包输出至 `app/build/outputs/apk/debug/app-debug.apk`，使用独立包名 `dev.hazel.livealarm.preview`。正式发行使用 `gradle :app:assembleRelease`，需自行提供原发行签名后才能覆盖原正式版。

Android 模拟器或测试手机连接后执行：

```bash
gradle :app:connectedDebugAndroidTest
```

设备测试位于 `app/src/androidTest/`，覆盖原生页面及真实 Android 接口。截图保存在测试应用外部文件目录中的 `screenshots/`。CI 使用 Android 15 模拟器，并在成功后上传原生预览 APK；测试报告与截图另存为验证产物。

代码推送、PR 和手动运行都可触发 Android CI；仅 Markdown、docs、release 改动跳过自动运行。Actions 下载产物保留 14 天。

旧版 `ui-src/`、`tests/ui-regressions.cjs` 与 `tools/build_ui.cjs` 属于 1.0.x Web 界面的历史实现；1.1.0 不再运行网页编译，也不打包网页 assets。独立 SDK / ECJ 打包脚本不支持 Compose，1.1.0 应使用 Gradle 构建。

原生功能、后台边界与预览版迁移说明见 [NATIVE.md](NATIVE.md)。

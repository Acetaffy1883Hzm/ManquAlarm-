# 满区闹钟 · ManquAlarm

[![Android CI](https://github.com/Acetaffy1883Hzm/ManquAlarm-/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/Acetaffy1883Hzm/ManquAlarm-/actions/workflows/android-ci.yml)

<img src="app/src/main/assets/hazel.png" width="104" alt="满区闹钟图标">

为灰泽满 Hazel 的 B 站直播准备的安卓开播闹钟。选择想被提醒的时间、所在时区和喜欢的铃声，开启守候后检测直播状态。

**Android 8.0 及以上 · 稳定版 1.0.5 · 原生预览 1.1.0**

[下载原生预览 APK](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.1.0-native-preview/ManquAlarm-1.1.0-native-preview.apk) · [下载 1.0.5 稳定版](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.0.5/ManquAlarm-1.0.5.apk) · [正式发布页](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/tag/v1.0.5) · [备用下载目录](release/v1.0.5/) · [更新说明](CHANGELOG.md) · [使用说明](docs/USAGE.md) · [构建方法](docs/BUILDING.md) · [数据说明](docs/PRIVACY.md)

## 1.1.0 原生界面预览

已提供原生 Compose 页面、自定义主题色、系统动态颜色、AMOLED 模式、背景图片、最近任务隐藏与定时守候恢复。

[直接下载 ManquAlarm-1.1.0-native-preview.apk](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.1.0-native-preview/ManquAlarm-1.1.0-native-preview.apk) · [原生预览发布页与截图](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/tag/v1.1.0-native-preview)

已通过 36,063 项规则断言、Android 15 模拟器 7 项原生交互与服务测试、APK 编译及签名校验；[查看对应构建](https://github.com/Acetaffy1883Hzm/ManquAlarm-/actions/runs/34700682902)。真实手机整夜锁屏和实际开播仍需验证。

预览包使用独立包名 `dev.hazel.livealarm.preview`，可与旧版并排安装。正式覆盖升级需要原发行签名。详细使用方法见 [原生界面与迁移说明](docs/NATIVE.md)。

## 下载 1.0.5

普通用户点击第一项即可安装，**不需要下载源码，也不需要解压**。

| 文件 | 用途 |
| --- | --- |
| [ManquAlarm-1.0.5.apk](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.0.5/ManquAlarm-1.0.5.apk) | 安卓安装包，下载后直接安装，约 9.9 MB |
| [ManquAlarm-1.0.5.zip](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.0.5/ManquAlarm-1.0.5.zip) | 同一个 APK 的压缩包，解压后安装 |
| [ManquAlarm-1.0.5-Public-Source.zip](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.0.5/ManquAlarm-1.0.5-Public-Source.zip) | 公开源码，供查看和自行构建 |
| [SHA256SUMS](release/v1.0.5/SHA256SUMS) | 上述三个文件的 SHA-256 校验值 |

安装包保留原签名。已有相同包名、相同签名版本时可以覆盖升级。

## 能做什么

- **开播响铃**：循环使用闹钟音量通道，支持渐强、振动、自动停止和稍后提醒；轮播不会当作开播。
- **选自己的声音**：星铃、清晨、强提醒、系统闹钟铃声，或导入最多 30 MB 的本地音频。
- **自由设定时段**：最多 32 个时间段，按星期重复，支持凌晨和跨午夜；可选择严格按开播时间提醒，或补报已在直播的场次。
- **海外也能选时间**：默认跟随手机，也可搜索并固定 IANA 时区，按该地区规则处理夏令时。
- **后台守候**：前台服务与持续通知、断网重试、访问限制退避、重启恢复；无障碍恢复辅助可选。
- **及时看到设置变化**：铃声、主题、时间段等选择直接刷新，返回应用后重新读取系统授权状态。
- **本地记录与备份**：查看最近记录，导出或导入设置，主动导出通知诊断。

普通使用无需 Root、Shizuku 或电脑。它是粉丝制作的非官方应用，没有 B 站或主播的官方背书。

<img src="https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.1.0-native-preview/01-home-pink.png" width="260" alt="原生守候首页"> <img src="https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/download/v1.1.0-native-preview/02-appearance-blue.png" width="260" alt="原生自定义配色页面">

*1.1.0 原生预览版在 Android 15 模拟器中的实际截图。*

## 开始使用

1. 选择下载原生预览 APK 或 1.0.5 稳定版 APK。预览版可并排安装；从旧版迁移请参考 [迁移说明](docs/NATIVE.md)，只在使用中的一版开启守候。
2. 在“时段”中设定提醒范围与时区，在“声音”中选择铃声和音量。
3. 在“设置”中按提示允许通知、检查电池和后台设置，然后开启“守候”。
4. 先做一次声音测试，再做一次锁屏测试；开启守候后检查系统通知栏中的守候通知。

## 关于小米通知开关与保活

1.0.5 将守候通知与响铃通知分开，修正停止与恢复流程，并展示通知、通道和服务的真实状态。系统允许通知时，守候服务使用持续的前台通知；这不等于能够绕过系统限制永久运行。

已分析的一份真机报告中，通知权限被设备管理策略固定拒绝（`POLICY_FIXED`），全局授权策略为 `AUTO_DENY`。这是该报告的结论，不能推广为所有小米手机的共同原因。应用无法自行解除设备管理者设置的权限限制；遇到“策略限制”时，需要由设备管理者调整本应用的授权策略。参见 [Android 设备权限管理接口](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPermissionGrantState(android.content.ComponentName,%20java.lang.String,%20java.lang.String,%20int))。

用户明确开启“通知异常时仍响铃”后，可尝试兼容声音提醒；这不会让系统通知权限变成已授权。通知被拒绝时，前台服务通知可能只出现在系统的活动应用管理界面，通知栏不显示。参见 [Android 通知权限说明](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

## 使用边界

- 直接检测 B 站公开接口，无云端推送；提醒延迟受检测间隔、网络、接口状态和手机后台策略影响。
- 强行停止、关机、断网、系统省电限制均可能中断提醒；无障碍辅助不能保证永久保活。
- 全屏、锁屏、勿扰模式和耳机路由由系统控制。海外网络能否访问接口需以当地实际情况为准。
- 本版本已完成界面与规则测试，未覆盖所有机型、真实开播、长期锁屏和耗电场景。具体范围见 [验证记录](docs/TESTING.md)。

## 开发与反馈

[提出功能建议](https://github.com/Acetaffy1883Hzm/ManquAlarm-/issues/new?template=feature_request.yml) · [反馈问题](https://github.com/Acetaffy1883Hzm/ManquAlarm-/issues/new?template=bug_report.md) · [分支与 PR 贡献指南](CONTRIBUTING.md) · [CI 自动编译](https://github.com/Acetaffy1883Hzm/ManquAlarm-/actions/workflows/android-ci.yml)

欢迎 Fork 后创建分支并提交 PR。代码变更会自动运行 Java 规则测试并编译调试 APK，也支持在 Actions 手动编译；调试包在成功运行的 Artifacts 中提供，普通用户仍从上方下载正式版。

1.1.0 使用 Kotlin / Compose 原生界面，Java 负责检测、服务、音频和权限；旧版网页资源不再打入 APK。构建环境与测试命令见 [BUILDING](docs/BUILDING.md)。

提交问题时提供应用版本、手机型号、Android/系统版本和复现步骤。公开 Issue 优先附设置页状态截图；完整系统日志和包含系统组件 APK 的诊断包不要直接公开，详见 [数据说明](docs/PRIVACY.md)。

图标和角色素材来自项目提供者；仓库公开不代表这些素材可以任意商用，也不改变其原有权利归属。


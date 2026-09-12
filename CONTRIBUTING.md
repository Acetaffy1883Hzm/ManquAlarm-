# 参与满区闹钟

## 提建议、反馈问题

- [提出功能建议](https://github.com/Acetaffy1883Hzm/ManquAlarm-/issues/new?template=feature_request.yml)：描述使用场景和希望实现的效果。
- [反馈问题](https://github.com/Acetaffy1883Hzm/ManquAlarm-/issues/new?template=bug_report.md)：提供版本、复现步骤和相关状态。
- [查看已有讨论与待办](https://github.com/Acetaffy1883Hzm/ManquAlarm-/issues)。

## 创建分支并贡献代码

本仓库公开并允许 Fork。外部贡献者可以按下面的方式参与：

1. 点击仓库右上方的 **Fork**，在自己的账号下创建副本。
2. 在副本里创建工作分支，例如 `feature/custom-sound` 或 `fix/notification-state`。
3. 完成修改，参考 [构建与测试](docs/BUILDING.md) 在本地验证。
4. 创建 Pull Request，目标选择本仓库的 `main`，填写改动原因和测试结果。
5. 根据 CI 结果和维护者反馈修改，审核通过后合并。

已获本仓库写入权限的协作者也可以直接在原仓库创建工作分支。新功能较大时，建议先开一个功能建议 Issue 沟通范围。

## 自动编译

代码分支推送和 Pull Request 会触发 **Android CI**；仅修改 Markdown、`docs/` 或 `release/` 时跳过自动编译。维护者可在 **Actions → Android CI → Run workflow** 手动选择分支编译。

工作流会编译离线界面、运行现有 Java 规则测试，再使用 JDK 17、Gradle 8.11.1 和 Android SDK 35 生成调试 APK。成功后可从该次运行的 **Artifacts** 下载 `ManquAlarm-debug-*`，产物保留 14 天。

首次从外部 Fork 提交的 PR 如显示等待批准，需要仓库维护者在 GitHub 批准该次工作流。该 CI 只读取仓库内容，不使用发行签名私钥，也不会自动发布正式版本。

CI 调试包使用调试签名，不能直接覆盖原签名正式版。普通用户请下载 [正式 Releases](https://github.com/Acetaffy1883Hzm/ManquAlarm-/releases/latest)。

## 提交前

修改 `ui-src/app.js` 后执行 `npm run build:ui`，一并提交生成的界面脚本。测试时请写明实际验证范围；通知权限、长时间锁屏和真实开播仍需设备测试。

私人诊断与签名材料的处理遵循 [数据说明](docs/PRIVACY.md)。图标和角色素材继续保留原有权利归属。

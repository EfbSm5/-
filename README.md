# RootPilot

在已 Root 的 Android 手机上，通过截图和模型规划完成有限的界面操作。RootPilot 是本仓库的主入口，旧 Android Agent 保留为实验入口。

这是一个需要人工确认的实验项目，不是可靠的无人值守助手。命令成功不代表页面操作成功，真实应用的完整流程仍需逐项验证。

## 已具备的能力

- **手机直连模型**：默认 DeepSeek API（`https://api.deepseek.com`，模型 `deepseek-flash`），支持自定义 Base URL、模型和开发 Relay。首次保存后自动加载，不需要电脑桥接；服务端可用性和账号权限以连接测试为准。
- **保存 API 配置**：Token 使用 Android Keystore 管理的密钥加密，密文保存在私有、不参与备份的目录，支持更换、清除和测试连接。
- **截图驱动操作**：模型按步规划点击、滑动、按键、等待、应用启动和文本输入；由本地策略校验后执行。
- **通用应用启动与中文输入**：动态查询可启动应用；通过临时输入法输入 Unicode 文本，校验目标编辑框，拒绝密码框，结束后恢复原输入法。
- **人工确认**：提供 App、悬浮面板和通知入口。打开应用、文本输入和系统按键始终确认；点击/滑动在手动模式确认。
- **本地待办**：确认标题和截止时间后保存到应用私有存储，与旧 Agent 共用数据；同一任务内防重复。不创建系统闹钟或提醒。
- **任务与诊断**：中断任务需人工决定恢复；结构化日志按任务和步骤记录阶段、结果及耗时，不记录凭据或正文。

## 使用前提

- Android 15 / API 35 或更高版本，已取得 Root 权限。当前主要验收设备为小米 15，不承诺所有厂商兼容。
- 可访问配置的模型 API，并拥有有效凭据。
- 使用悬浮面板需手动开启“显示在其他应用上层”；使用中文输入需手动启用 RootPilot 输入法。

## 开始使用

1. 构建并安装 Debug APK，打开 RootPilot。
2. 在手机中填写 Token、Base URL 和模型，保存配置并测试连接。不要把 Token 放进源码或聊天。
3. 测试 Root 权限，按需要开启悬浮窗和输入法。
4. 输入非敏感任务，确认允许上传屏幕，再开始单步或自动执行；按提示核对动作并确认，随时可以停止。

授权上传后，当前屏幕截图及可启动应用名称、包名会发送至配置的 API。避免在密码、支付、聊天隐私等页面运行；自动模式不代表安全保证。

## 构建与测试

使用 Android Studio 配置与项目 AGP 兼容的 Gradle JDK 和 Android SDK 36。首次构建需要下载依赖。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。安装时显式选择已核对身份的设备：

```sh
adb -s <设备serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

读取执行日志，不清空设备日志：

```sh
adb -s <设备serial> logcat -d -v raw RootPilotTrace:I '*:S' | tail -n 300
```

以 `runId` 关联任务，`step` 从 0 开始，`elapsedMs` 为累计耗时。`stop_requested` 是停止请求，`run_end` 才是协程结束证据；日志可能轮转，不是持久化审计记录。

## 源码导航

| 路径（`app/src/main/java/com/example/agent/` 下） | 职责 |
| --- | --- |
| `rootpilot/RootPilotActivity.kt`、`rootpilot/ui/` | 配置、任务界面和悬浮面板 |
| `rootpilot/RootPilotService.kt`、`rootpilot/loop/` | 任务生命周期、规划与确认循环 |
| `rootpilot/deepseek/`、`rootpilot/screen/` | 模型请求和截图 |
| `rootpilot/action/`、`rootpilot/root/` | 动作解析、策略校验和 Root 执行 |
| `rootpilot/apps/`、`rootpilot/input/` | 应用目录与输入法连接 |
| `rootpilot/log/` | 脱敏结构化日志 |
| `agent/` | 旧 Agent 实验能力及复用的待办存储 |

## 当前限制与计划

最新能力状态、验证范围和后续候选项见 [SPEC.md](SPEC.md)；开发协作规则见 [AGENTS.md](AGENTS.md)。

目前系统设置搜索闭环仍为 **PARTIAL**：可以打开设置，但搜索栏点击后未进入搜索页，不能据此声称中文搜索完成。历史误点调查已暂缓。旧端侧 LiteRT-LM / Demo 后端未迁入 RootPilot，仍保留在实验入口。

# RootPilot 任务清单

更新日期：2026-10-09。本文件自 [SPEC.md](SPEC.md) 拆出，统一维护“下一步做什么”：功能推进方向与代码评审待办。`SPEC.md` 只保留当前状态、验收证据与未验证项；任务完成或结论变化后回写 `SPEC.md` 对应条目，并在本文件更新状态。评审编号沿用 2026-10-09 全库评审清单；#1、#3、#6、#10 已按决定移出范围，不在本文件维护。

## 功能下一步

1. 【已完成 2026-10-09】副屏非空纯文本输入的修复后独立复审（FAIL 低，无高／中缺陷）、四项离线真机与三项真实 Service 验收（重建夹具后 7/7 限定 PASS，手机安装的三个 APK 哈希与本机构建一致）均已取得结论；README／SPEC 已同步，结果、前置与两个夹具缺陷见 SPEC“入口、前置与执行结果”。未把八项平台探针计作生产验收；富文本／IME composing／撤销栈、hint 与真实内容区分、主屏并行输入及厂商兼容仍是未验证项。
2. 在平台探针已证明可行的条件下分别推进 HOME、LOCAL 键盘、尺寸／旋转及跨单步会话／任务外截图的最小产品接入与闭环验收；仍需明确各能力的生命周期和配置边界，不据探针直接扩大默认产品行为。
3. 已有固定失败类别；仅在再次真实失败时收集协议细分或 HTTP／网络类固定证据，不保存原文，不自动重放已成功的输入，不据未证实假设改预算或协议守卫。
4. 已在预置公开世界时钟页完成“启动 → 中文搜索 → 读取结果”限定验收；后续如需扩展，另行界定冷启动／其他应用范围。保留测试页自动关闭回执异常，保持历史误点诊断暂缓边界。
5. 无障碍已手动恢复，强杀后的自动重绑异常仍需单独归因。在已确认的专用范围内补真人／通知确认、Service 单独销毁恢复、输入提交中／提交后或其他副作用执行期间的进程死亡及查询中断连；不把受控 OpenApp／Type 待确认死亡或运行前手工断连当作所有生命周期场景通过。
6. 按实际风险补界面、厂商、活跃多显示与 GKD 业务共存；节点点击、跨步缓存和更多信息源仍需独立定范围，不扩展后台无人值守敏感操作。
7. 补其他 SAF 提供方及实际文件 UI；JSON 字段编辑、批量预览和文件到手机的交接仍需独立定范围，不把研究候选算成已实现功能。

## 评审待办

来源：2026-10-09 全库只读评审（主代理 + 两个只读子代理；当时 JVM 单测 662 全绿）。每条含证据与建议，实现前先核对现状（当前有副屏非空输入在途改动）。

### #2（高）androidTest 门控泛滥，无设备时跳过显示绿

- 证据：assumeTrue／自建 gate 约 210 处相关调用（112 个门名、92 个调用点、36 个文件）；VirtualDisplayCapabilityServiceInstrumentedTest.kt:50-109 一类 12 门。默认 `connectedAndroidTest` 在无真机／无服务时大面积 skip 仍显示通过。本轮实测：不给开关运行整类 `VirtualDisplayPlainTextInstrumentedTest`，四个用例全部以 assumption failure（`INSTRUMENTATION_STATUS_CODE: -4`）结束，报告仍显示 `OK (4 tests)`。
- 建议：验收脚本对 `skipped > 0` 报警而不是只看 failed；平台探针类移出默认集合或去掉 `Test` 后缀（另有 3 个 `*Probe*InstrumentedTest` 被 runner 收集、4 个 0 `@Test` 的支撑文件混在 androidTest）。
- 状态：待处理

### #4（中）androidTest 样板大量复制

- 证据：`ProcessBuilder("su","-c",...)` 直接构造 ×6（如 VirtualDisplayCapabilityInstrumentedTest.kt:866）、`private fun arguments()` ×7、`report()`／`check()` ×6+；FixtureClient 定义在 LiveExecutionInstrumentedTest.kt:182 内部却被 6 个文件跨包 import。巨型验收类 1497／893／865／826／763／752 行占 androidTest 54%。
- 建议：抽 `testsupport/`（RootShell／TestArgs／Assertions／FixtureClient）；巨型类按模式拆分。
- 状态：待处理

### #5（中）create_todo 双重解码，dueAt 只校验格式

- 证据：ActionParser.kt:56-71 把动作重组 JSON 再走旧 Agent 的 AgentPlanDecoder 双重解码；dueAt 仅正则（AgentPlanDecoder.kt:125-127）不校验日历合法性，日历非法值（如 `2026-13-45T99:99:99Z`）在 AgentLoop.kt:374 `OffsetDateTime.parse` 抛 DateTimeParseException，落 UNEXPECTED_ERROR 而非固定原因码。
- 建议：语义校验收进解析层，映射固定原因码。
- 状态：待处理

### #7（中）副屏写入终态语义 · 需产品语义确认

- 现状：实现已有两档固定文案（未提交“副屏输入目标不可用或已变化，未提交文本”／SET_TEXT 后未确认“副屏输入可能已生效，但提交或目标／服务状态未确认”），plan 被规则拒绝另有独立原因码（复审修复）。
- 待确认：“可能已生效”是否为可接受终态用语、是否需要更强的“已写入未确认”区分。
- 状态：待处理（仅剩产品语义确认）

### #8（中）多轮文件任务思考被清空

- 证据：FileAgentController.kt:152 `onUpdate(ModelStreamSnapshot(content = progress))` reasoning 默认空串；ChatController.kt:161-165 updateAssistant `it.copy(content = content, reasoning = reasoning)` 整体替换。
- 建议：进度只覆盖 content，保留 reasoning。
- 状态：待处理

### #9（中）只读工具失败复用“写入可能不完整”文案并终止任务 · 需产品语义确认

- 证据：FileAgentController.kt:115-117 catch FileStorageException 统一返回“文件操作失败，已停止；如已开始写入，结果可能不完整，请检查文件与备份”并终止整个任务。
- 建议：只读／写入失败文案分开；只读失败可作 tool result 回传模型自纠而非终止。涉及产品语义，动手前需明确。
- 状态：待处理（先确认语义）

### #11（低）busy 三套状态分散，`check(!busy)` 崩溃面

- 证据：generating／fileAgent.busy／fileWorkspaceBusy 三套状态在 RootPilotActivity.kt:96、RootPilotViewModel.kt:84/115/131/138 手工组合；FileAgentController.kt:55 `check(!busy)`。
- 建议：收敛单一 busy 来源，或改为容错返回。
- 状态：待处理

### #12（低）主线程解码整屏 PNG

- 证据：RootPilotScreen.kt:381 与 RootPilotSettingsContent.kt:224-227 主线程 `BitmapFactory.decodeByteArray` 整屏 PNG，每步一次。
- 建议：移后台解码。
- 状态：待处理

### #13（低）构建配置小项

- 证据：proguard-rules.pro 全空却被引用（release `isMinifyEnabled=false`）；markwon 硬编码 app/build.gradle.kts:78 `"io.notets.markwon:core:4.6.2"` 未进 libs.versions.toml；无 lint baseline；compileSdk 37／targetSdk 36 未注明差异原因（README 已声明刻意）。
- 建议：配置整理。
- 状态：待处理

### #14（低）无一键验证脚本

- 证据：README 的四连命令手敲；dev/ 只有 deepseek_replay.py。
- 建议：加 `dev/verify.sh`。
- 状态：待处理

### #15（低）AgentLoop.runSteps 约 350 行单函数

- 证据：AgentLoop.kt:150-527。
- 建议：下次改动顺手拆分，不单独立项。
- 状态：待处理

### #16（低）离线验收类的 finally 清理段仍吞断言消息

- 证据：第二轮独立复审指出 `app/src/androidTest/java/com/example/agent/rootpilot/VirtualDisplayPlainTextInstrumentedTest.kt:227` 的 `catch (_: Exception) { failure = "plain_edit_cleanup_unconfirmed" }` 会把 6 个清理 `check` 的断言消息吃掉，只剩固定码；同文件 :201-203 已有“过滤后拼接 detail”的写法可复用。
- 建议：复用 :201-203 的 detail 过滤拼接，保留清理失败原因。
- 状态：待处理

### 已修正（第二轮独立复审的另两项）

- README 副屏 `type` 文案：原写“只支持空框、非空一律拒绝”，与已接入生产源码的非空语义及同文件探针说明自相矛盾；本轮已改写为当前语义并标注空框／非空各自的验收状态。
- 设计取舍文档化：旧文超过 128 个 UTF-16 单元时在 `app/src/main/java/com/example/agent/rootpilot/input/VirtualDisplayTextInputPolicy.kt` 的接受判定层即被拒，沿用“目标不可用或已变化”漂移文案（即超限旧文视为不可绑定目标）；已写入 SPEC 与 README 的副屏 `type` 说明。

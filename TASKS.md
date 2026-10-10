# RootPilot 任务清单

更新日期：2026-10-10。本文件自 [SPEC.md](SPEC.md) 拆出，统一维护“下一步做什么”：功能推进方向与代码评审待办。`SPEC.md` 只保留当前状态、验收证据与未验证项；任务完成或结论变化后回写 `SPEC.md` 对应条目，并在本文件更新状态。评审编号沿用 2026-10-09 全库评审清单；#1、#3、#6、#10 已按决定移出范围，不在本文件维护。

2026-10-10 更新：#9 产品语义已确认（只读失败不终止任务、改回传模型自纠）；#7 补充“已提交未确认”的机制说明并仍待用词拍板；功能 #5 标注真机离线前置；新增「建议推进顺序」。

2026-10-10 晚更新：`dev/verify.sh`（四连命令 + `git diff --check` + 单测汇总）与 `dev/check-test-results.py`（`skipped > 0` 报警，含 `dev/expected-skips.txt` 预期跳过名单）已落地并离线自测通过，#14 完成、#2 的“报警”部分完成；平台探针类移出默认集合待真机在线确认 runner 收集行为后再动。

2026-10-10 深夜更新：#5 逐条取证后核心证据不成立（dueAt 的日历校验早在 `071432f` 就已在解码层生效，且解析失败映射为固定原因码），已补一条针对日历非法值的解析层回归测试；剩余仅为 `create_todo` 双重解码的维护性改动，并入架构三步，不再单列。随后取建议推进顺序第 3 条做完 #16（两处 finally 清理段不再吞断言消息），同条仅剩 #12 主线程解码整屏 PNG 未动。

## 建议推进顺序（2026-10-10）

1. **#2 与 #14／#13 合并做一小步**：先让验收脚本对 `skipped > 0` 报警，再把平台探针类移出默认集合，同时补 `dev/verify.sh`（四连命令 + `git diff --check` + skipped 报警）与构建配置小项。理由：本轮真机验收已实测到“4 个 assumption failure 仍显示 `OK (4 tests)`”，它直接决定“以为通过”的可信度；改动面小、当天可见效。（进度 2026-10-10：`dev/verify.sh`、`dev/check-test-results.py`、`dev/expected-skips.txt` 已落地；#13 构建配置小项与探针类移出仍待办。）
2. ~~**#5 create_todo 双重解码与 dueAt 语义校验**~~：2026-10-10 逐条取证后**核心证据不成立**（见该条），已补回归测试；剩余只有“双重解码”这一维护性改动，并入第 6 条架构三步。
3. **#16 与 #12**：均为低风险小修（清理断言消息丢失／主线程解码整屏 PNG），可顺手并入下一次改动。（进度 2026-10-10：~~#16~~ 已完成（两处 finally 清理段已不再吞断言消息，待真机复跑）；#12 仍待办。）
4. **#7、#9**：#9 语义已确认，可与 #7 一起实施（#7 只剩终态用词拍板）；两者都需补单测，改“任务是否继续”的行为要独立复审。
5. **功能 #5 真机生命周期补测**：需真机在线（2026-10-10 无线已断联），恢复连接后再约。
6. **架构三步**（[docs/architecture-notes.md](docs/architecture-notes.md)）：AppContainer → 拆 RunController／runSteps → 去 create_todo 双重解码；不单独立项，按改动顺手推进。

## 功能下一步

1. 【已完成 2026-10-09】副屏非空纯文本输入的修复后独立复审（FAIL 低，无高／中缺陷）、四项离线真机与三项真实 Service 验收（重建夹具后 7/7 限定 PASS，手机安装的三个 APK 哈希与本机构建一致）均已取得结论；README／SPEC 已同步，结果、前置与两个夹具缺陷见 SPEC“入口、前置与执行结果”。未把八项平台探针计作生产验收；富文本／IME composing／撤销栈、hint 与真实内容区分、主屏并行输入及厂商兼容仍是未验证项。
2. 在平台探针已证明可行的条件下分别推进 HOME、LOCAL 键盘、尺寸／旋转及跨单步会话／任务外截图的最小产品接入与闭环验收；仍需明确各能力的生命周期和配置边界，不据探针直接扩大默认产品行为。
3. 已有固定失败类别；仅在再次真实失败时收集协议细分或 HTTP／网络类固定证据，不保存原文，不自动重放已成功的输入，不据未证实假设改预算或协议守卫。
4. 已在预置公开世界时钟页完成“启动 → 中文搜索 → 读取结果”限定验收；后续如需扩展，另行界定冷启动／其他应用范围。保留测试页自动关闭回执异常，保持历史误点诊断暂缓边界。
5. 无障碍已手动恢复，强杀后的自动重绑异常仍需单独归因。在已确认的专用范围内补真人／通知确认、Service 单独销毁恢复、输入提交中／提交后或其他副作用执行期间的进程死亡及查询中断连；不把受控 OpenApp／Type 待确认死亡或运行前手工断连当作所有生命周期场景通过。（前置：2026-10-10 无线真机已断联，需重新开启无线调试取得新端口，或另行授权 USB 设备后再约。）
6. 按实际风险补界面、厂商、活跃多显示与 GKD 业务共存；节点点击、跨步缓存和更多信息源仍需独立定范围，不扩展后台无人值守敏感操作。
7. 补其他 SAF 提供方及实际文件 UI；JSON 字段编辑、批量预览和文件到手机的交接仍需独立定范围，不把研究候选算成已实现功能。

## 评审待办

来源：2026-10-09 全库只读评审（主代理 + 两个只读子代理；当时 JVM 单测 662 全绿）。每条含证据与建议，实现前先核对现状（副屏非空输入的改动已于 2026-10-09 提交，以当前 main 为准）。

### #2（高）androidTest 门控泛滥，无设备时跳过显示绿

- 证据：assumeTrue／自建 gate 约 210 处相关调用（112 个门名、92 个调用点、36 个文件）；VirtualDisplayCapabilityServiceInstrumentedTest.kt:50-109 一类 12 门。默认 `connectedAndroidTest` 在无真机／无服务时大面积 skip 仍显示通过。本轮实测：不给开关运行整类 `VirtualDisplayPlainTextInstrumentedTest`，四个用例全部以 assumption failure（`INSTRUMENTATION_STATUS_CODE: -4`）结束，报告仍显示 `OK (4 tests)`。
- 建议：验收脚本对 `skipped > 0` 报警而不是只看 failed；平台探针类移出默认集合或去掉 `Test` 后缀（另有 3 个 `*Probe*InstrumentedTest` 被 runner 收集、4 个 0 `@Test` 的支撑文件混在 androidTest）。
- 进度 2026-10-10：报警部分已完成——`dev/check-test-results.py` 把“名单外跳过”判为 FAIL(2)（退出码 0 通过／1 有失败／2 有未预期跳过／3 无结果或参数错误），`dev/expected-skips.txt` 只列 3 个显式 opt-in 的探针用例；离线自测 6 个场景通过，`dev/verify.sh` 也用它汇总单测。
- 注意：3 个探针类并非整类门控——`VirtualCalculatorUsageProbeInstrumentedTest` 另有 4 个非门控用例（:211/:226/:234/:248），`VirtualCalculatorEqualityEffortProbeInstrumentedTest` 另有 2 个（:231/:247），每类只有 1 个 live 用例受 `-e` 开关控制；所以“去掉 `Test` 后缀”不能整类做，只能把受门控的 live 用例拆出去，且改前要用真机确认 runner 的收集规则。
- 状态：部分完成（报警已落地；探针类移出默认集合待真机在线）

### #4（中）androidTest 样板大量复制

- 证据：`ProcessBuilder("su","-c",...)` 直接构造 ×6（如 VirtualDisplayCapabilityInstrumentedTest.kt:866）、`private fun arguments()` ×7、`report()`／`check()` ×6+；FixtureClient 定义在 LiveExecutionInstrumentedTest.kt:182 内部却被 6 个文件跨包 import。巨型验收类 1497／893／865／826／763／752 行占 androidTest 54%。
- 建议：抽 `testsupport/`（RootShell／TestArgs／Assertions／FixtureClient）；巨型类按模式拆分。
- 状态：待处理

### #5（中）create_todo 双重解码，dueAt 只校验格式

- 原证据（2026-10-09）：ActionParser.kt:56-71 把动作重组 JSON 再走旧 Agent 的 AgentPlanDecoder 双重解码；dueAt 仅正则（AgentPlanDecoder.kt:125-127）不校验日历合法性，日历非法值（如 `2026-13-45T99:99:99Z`）在 AgentLoop.kt:374 `OffsetDateTime.parse` 抛 DateTimeParseException，落 UNEXPECTED_ERROR 而非固定原因码。
- 核对结论 2026-10-10：**“只校验格式”这一半不成立**。`agent/serialization/AgentPlanDecoder.kt:107-115` 的 `requireRfc3339` 在正则（`:125-127`）之后还有 `OffsetDateTime.parse(this, RFC3339_FORMATTER)`，而 `RFC3339_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)`（`:128-129`）；该实现由 `071432f feat: add validated agent plan protocol` 引入，早于本次评审。`rootpilot/action/ActionParser.kt:67-70` 把解码失败映射成 `ActionParseResult.Failure`，`rootpilot/loop/AgentLoop.kt:324-333` 再映射成固定原因码 `TraceReason.PARSE_FAILED`（并带一次纠正重试），且 `action` 只在 `:325` 由 `ActionParseResult.Success` 赋值（另一处 `:546` 是 OpenApp），所以 `:374` 拿不到未校验的值。JDK 17 实测：`2026-13-45T99:99:99Z`／`2026-02-30T09:00:00Z`／`2026-02-29T00:00:00Z` 在 STRICT 与默认 SMART 下都抛 DateTimeParseException，而 STRICT 接受的集合是 SMART 的子集，`:374` 没有额外抛点。
- 已做：`app/src/test/java/com/example/agent/rootpilot/action/ActionParserTest.kt` 新增 `createTodo_rejectsCalendarInvalidDeadlineAtParseLayer`，对上述三个值断言解析层即 `Failure`、`kind == INVALID_PROTOCOL` 且 `message` 含 `due_at`（不钉死旧 Agent 的完整措辞，便于日后去双重解码）；`./gradlew :app:testDebugUnitTest --tests 'com.example.agent.rootpilot.action.ActionParserTest' --no-daemon` 通过，该类 9 个用例 0 失败。
- 剩余：只有“双重解码”这一维护性问题（RootPilot 动作协议依赖旧 Agent 的 decoder，超限错误文案也来自旧 Agent）。按 AGENTS.md“默认最小改动、不顺手重构旧 Agent”，并入架构三步第 3 步（[docs/architecture-notes.md](docs/architecture-notes.md)），本轮不动。
- 状态：证据不成立，已补回归测试；维护性改动并入架构三步

### #7（中）副屏写入终态语义 · 需产品语义确认

- 现状：实现已有两档固定文案（未提交“副屏输入目标不可用或已变化，未提交文本”／SET_TEXT 后未确认“副屏输入可能已生效，但提交或目标／服务状态未确认”），plan 被规则拒绝另有独立原因码（复审修复）。
- 为什么会出现“已提交但未确认”：SET_TEXT 是写给目标输入框的写操作，之后的确认依赖同一绑定上的屏幕观察（`input/VirtualDisplayTextInputPolicy.kt:95-104` 要求会话／显示 id、目标一致、前台包一致且键盘可见性可读，任一条件在 `:65` 的 3s 超时内不成立就拿不到观察结果）。此时既不能断言“没写”（写可能已落地），也不能断言“已写”（没有回读证据），所以只能报“可能已生效”；也不做二次重放，避免重复插入。
- 待确认：“可能已生效”是否为可接受终态用语、是否需要更强的“已写入未确认”区分（建议沿用主屏 `input/ImeTextInput.kt:53` 与任务恢复 `ui/TaskResultCard.kt:46` 的“可能已生效／部分操作可能已生效 … 不会自动重放”，不升级为“已写入”）。
- 状态：待处理（仅剩产品语义确认）

### #8（中）多轮文件任务思考被清空

- 证据：FileAgentController.kt:152 `onUpdate(ModelStreamSnapshot(content = progress))` reasoning 默认空串；ChatController.kt:161-165 updateAssistant `it.copy(content = content, reasoning = reasoning)` 整体替换。
- 建议：进度只覆盖 content，保留 reasoning。
- 状态：待处理

### #9（中）只读工具失败复用“写入可能不完整”文案并终止任务 · 语义已确认（2026-10-10）

- 证据：FileAgentController.kt:115-117 catch FileStorageException 统一返回“文件操作失败，已停止；如已开始写入，结果可能不完整，请检查文件与备份”并终止整个任务。
- 已确认语义：只读工具（`list_files`／`read_file`／`stat_file`／`search_files`）失败**不终止任务**，失败原因作为 tool result 回传模型自纠（可改路径或换文件继续），且不外溢“写入可能不完整”文案；写入类（`create_file`／`edit_file`，含审批拒绝）维持现状，停止任务并保留现有终态文案。
- 改法：`chat/FileAgentController.kt` 的 catch（:113-117）按工具类别分流，只读失败改走 tool result；补单测，改“任务是否继续”的行为需独立复审。
- 状态：待实施

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
- 状态：【已完成 2026-10-10】新增 `dev/verify.sh`（`git diff --check` + `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` + 单测汇总）与配套 `dev/check-test-results.py`（默认扫 `app/build/outputs/androidTest-results/connected/**/TEST-*.xml`）；本机实测 BUILD SUCCESSFUL（82 tasks，9s）、71 个单测结果文件／663 用例／0 失败。

### #15（低）AgentLoop.runSteps 约 350 行单函数

- 证据：AgentLoop.kt:150-527。
- 建议：下次改动顺手拆分，不单独立项。
- 状态：待处理

### #16（低）离线验收类的 finally 清理段仍吞断言消息

- 证据：第二轮独立复审指出 `app/src/androidTest/java/com/example/agent/rootpilot/VirtualDisplayPlainTextInstrumentedTest.kt:227` 的 `catch (_: Exception) { failure = "plain_edit_cleanup_unconfirmed" }` 会把 6 个清理 `check` 的断言消息吃掉，只剩固定码；同文件 :201-203 已有“过滤后拼接 detail”的写法可复用。
- 建议：复用 :201-203 的 detail 过滤拼接，保留清理失败原因。
- 已做 2026-10-10：`VirtualDisplayPlainTextInstrumentedTest.kt:227-231` 改为捕获消息、按 :201-203 的方式过滤拼接为 `plain_edit_cleanup_unconfirmed[_detail]`，且主体已失败时用 `主体|清理` 保留两段（原来清理段会覆盖主体原因）。同型缺陷另见 `VirtualDisplayParityInstrumentedTest.kt:365-368`（同样吞 `display_cleanup_unconfirmed` 与 `guard.environment()` 的断言消息），已一并修复；`VirtualDisplayCapabilityInstrumentedTest.kt:351/:822` 是按 evidence 标志置位的写法，不在本项。
- 未做：本轮无真机（2026-10-10 无线已断联），两个文件只过了 `assembleDebugAndroidTest` 编译与人工核对，未实跑；真机恢复后建议在验收时顺带复跑这两类。
- 状态：【已完成 2026-10-10（待真机复跑）】

### 已修正（第二轮独立复审的另两项）

- README 副屏 `type` 文案：原写“只支持空框、非空一律拒绝”，与已接入生产源码的非空语义及同文件探针说明自相矛盾；本轮已改写为当前语义并标注空框／非空各自的验收状态。
- 设计取舍文档化：旧文超过 128 个 UTF-16 单元时在 `app/src/main/java/com/example/agent/rootpilot/input/VirtualDisplayTextInputPolicy.kt` 的接受判定层即被拒，沿用“目标不可用或已变化”漂移文案（即超限旧文视为不可绑定目标）；已写入 SPEC 与 README 的副屏 `type` 说明。

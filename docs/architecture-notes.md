# RootPilot 架构笔记

本文是**只读架构评审**的产出：记录现状结构、判断依据与改进顺序，**不代表任何改进已实施**。
能力、验收证据与限制以 [SPEC.md](../SPEC.md) 为准；任务与评审待办以 [TASKS.md](../TASKS.md) 为准；本文不复制它们的编号职责，只在需要时引用编号。

评审时间：2026-10-09。方法：静态阅读全库 + 字段/依赖统计 + 实跑单测、打包与 lint（见 §6）。

---

## 1. 现状规模

| 源集 | 文件 | 行数 | @Test |
|---|---|---|---|
| `app/src/main/java`（生产） | 108 `.kt` | 14614 | — |
| `app/src/test` + `app/src/testDebug`（JVM 单测） | 71 | 12593 | 663 |
| `app/src/androidTest`（真机/离线插桩） | 57 | 15877 | 188 |
| `app/src/debug`（parity typealias 垫片） | 4 | 218 | — |
| `execution-fixture`（独立 application，签名权限隔离） | 4 `.java` | 546 | — |

单测行数已接近主代码行数，这是本仓库最值钱的资产：它让 §4 里的结构重构变成有护栏的机械操作，而不是赌博。

---

## 2. 包结构与依赖方向

| 包 | 文件 | 行数 | 角色 |
|---|---|---|---|
| `ui/` | 12 | 2069 | Compose 界面、覆盖层、设置面板 |
| `virtualdisplay/` | 6 | 1084 | 副屏创建/协议/托管/传输 |
| `root/` | 5 | 1041 | root shell、屏幕观察、执行路由 |
| `files/` | 10 | 1237 | SAF 工作区、变更引擎、备份、搜索 |
| `deepseek/` | 8 | 1100 | 协议客户端、流式解析、工具流 |
| `input/` | 8 | 694 | IME、副屏文本输入策略与计划 |
| `loop/` | 1 | 618 | Agent 步进循环 |
| `chat/` | 2 | 458 | 对话与文件 Agent 控制器 |
| `information/`、`history/`、`log/`、`model/`、`action/`、`screen/`、`apps/` | 21 | 1528 | 信息源、历史、日志、模型、动作解析 |
| 顶层 `com.example.agent.rootpilot` | 7 | 2041 | `RootPilotService`、`RootPilotViewModel`、`RootPilotRunController`、`RootPilotApiConfigStore`、两个 Activity、`RootPilotRunStore` |
| `agent/`（**遗留栈**） | 24 | 2242 | 旧 `planning`(20)/`serialization`(2)/`model`(2) |
| `com.example.agent` 顶层 `MainActivity.kt` + `ui/theme/` | 4 | 502 | 旧入口与主题 |

（合计 108 文件 / 14614 行，与 §1 一致。）

依赖方向大体成立：`ui` → `RootPilotViewModel`/`RootPilotRunController` → `loop` → `root`/`virtualdisplay`/`files`/`deepseek`/`action`。

两处例外值得记住：

- `ui/` **直接 import 数据与基础设施层**：`model` 12 次、`log` 10 次、`history` 4 次、`files` 3 次、`deepseek` 3 次、`chat` 2 次（见 §4-F）。
- `rootpilot` → `agent` 单向依赖，反向为零：`ActionParser.kt:6-8`、`AgentLoop.kt:32-33`、`RootPilotService.kt:74`（见 §4-B）。

清单里还挂着两个 LAUNCHER Activity：`app/src/main/AndroidManifest.xml:14`（旧 `MainActivity`）与 `:44`（`RootPilotActivity`）。

---

## 3. 判断：做对的结构决策（建议保留）

1. **无 DI 框架，但可替换缝齐全。** 全库 20+ 个 interface 落在真正的边界上：`root/RootExecutor.kt:52`、`deepseek/DeepSeekClient.kt:54` 与 `:83`、`deepseek/DeepSeekToolChatClient.kt:12`、`screen/ScreenshotProvider.kt:26`、`files/FileTypes.kt:84`（`WorkspaceAccess`）、`files/FileChangeEngine.kt:15`、`files/FileSearchEngine.kt:23`、`history/RunHistoryStore.kt:15`、`RootPilotApiConfigStore.kt:38` 与 `:114`、`input/ImeTextInput.kt:9` 与 `:17`、`input/VirtualDisplayTextInputPolicy.kt:53`、`RootPilotRunController.kt:51`（`RootPilotRunHost`）、`app/src/main/java/com/example/agent/agent/planning/AgentExecutionEngine.kt:13`（`TodoRepository`，即新栈仍要借用的遗留类型）。
   → 结论：不引入 Hilt/Koin 也完全站得住。
2. **构造注入 + 默认参数**，`RootPilotRunController.kt:59`、`RootPilotViewModel.kt:55` 都是显式依赖列表，测试可替换。
3. **领域规则与平台解耦**：副屏文本输入拆成 policy（规则）+ plan（编辑计划）+ access（平台访问）三层，整条规则链能在 JVM 单测里跑，不需要真机。
4. **状态不可变 + `StateFlow`**，审批用 `CompletableDeferred` + token，没有散落的回调式状态机。
5. **隐私纪律进类型系统**：快照类型 `toString` 只留长度，历史描述只记 `length=`。
6. **生产与测试夹具物理隔离**：`execution-fixture` 是独立 application + 签名权限，生产 `main` 不依赖它；方向正确，保持。

---

## 4. 需要改进的地方（按收益排序）

### A. 状态所有权错位到 Service 伴生对象
- 现状：`RootPilotViewModel.kt:62` 直接暴露 `RootPilotService.uiState`；`historyState` / `clearHistory` 也是静态函数。ViewModel 实际是转发层，真相在 Service 的全局伴生状态里。
- 影响：UI 与 ViewModel 无法脱离 Service 存活单独测试；Service 生命周期与 UI 状态生命周期绑死。
- 建议：抽一个 StateStore（实例仍可由 Service 持有），Activity/ViewModel 注入；Service 只做生命周期宿主。
- TASKS 对应：无（新增观察）。

### B. 遗留栈 `agent/` 被新栈单向依赖
- 现状：`agent/` 24 文件 2242 行，反向引用为零；新栈只用到其中三处：`ActionParser.kt:6-8` + `:67`（`create_todo` 把动作重组 JSON 再丢给旧 `AgentPlanDecoder` 双重解码）、`AgentLoop.kt:32-33` + `:112`、`RootPilotService.kt:74`。
- 影响：两套并行的 Agent 运行时概念同时存在，`create_todo` 的语义校验要穿过旧解码器，原因码与错误边界都更难收敛。
- 建议：先让 `ActionParser` 直接构造 `CreateTodo`（消掉双重解码）；再把 `TodoRepository` 等最小面收进 `rootpilot`；旧栈剩余部分明确冻结或删除。
- TASKS 对应：**#5**。

### C. 隐式依赖与重复实例
- 现状：`RootPilotViewModel.kt:75`、`:79`、`:333` 各自 `HttpDeepSeekClient()`；`FileWorkspace(appContext)` 在 `:65` 内部构造。
- 影响：重复连接/线程资源；测试无法替换客户端；新增依赖只能继续往方法体里塞。
- 建议：手写一个 `AppContainer`（Application/Activity 层组装），构造参数注入；不必上 Hilt。
- TASKS 对应：无（新增观察）。

### D. 三个聚合类（当前改动成本的主要来源）
- `RootPilotRunController.kt:59`：736 行、17 处 `var` 字段 + 29 处 `synchronized`，混了运行状态机 / 快照持久化 / 审批 / trace / host 回调五种关注点。
- `loop/AgentLoop.kt:151` `runSteps`：618 行文件里约 350 行单函数，串了截图 → 规划 → 解析 → 策略 → 确认 → 执行。
- `ui/RootPilotScreen.kt:69`：530 行、35+ 参数，导航由 `LaunchedEffect`、`BackHandler`、按钮回调三处共同驱动。
- 影响：这三处是"改一处要读全文件"的根源；也是新功能试探成本最高的地方。
- 建议：RunController 先按五个关注点切 2–3 段；`runSteps` 按阶段抽私有函数或策略对象；Screen 收敛导航状态持有者并按 topBar/任务/设置分区拆 composable。
- TASKS 对应：**#15**（runSteps 拆分）；Screen 拆分见 §4-F，其余为新增观察。

### E. 配置与不变量分散
- 现状：`MAX_STEPS = 20` 在四处各自定义（`ui/RootPilotScreen.kt:530`、`RootPilotRunController.kt:723`、`history/RunHistoryStore.kt:105`、`loop/AgentLoop.kt:610`）；busy 语义散成三套（`chat.generating` / `fileAgent.busy` / `fileWorkspaceBusy`），`chat/FileAgentController.kt:55` 用 `check(!busy)` 把不变量推给调用方。
- 影响：UI 显示上限可与真实上限漂移；漏一处守卫即崩溃。
- 建议：常量归一到单一位置；busy 收敛为单一 UiBusy reducer（或把 `check` 改为容错返回）。
- TASKS 对应：**#11**（busy）。`MAX_STEPS` 原评审 **#10，已按决定移出 TASKS 维护范围**，此处仅作架构观察保留，不重开该项。

### F. `ui/` 直连数据与基础设施层
- 现状：见 §2 的 import 统计；没有 UiState 映射层。
- 影响：composable 与数据类/存储细节耦合，界面难以在无 Service、无网络的情况下测；也是 §4-D 拆 Screen 的前置障碍。
- 建议：每个画面一个 UiState + mapper，先在聊天/设置两块试点。
- TASKS 对应：无（新增观察）。

### G. 工程卫生（不属结构，但影响验证强度）
- androidTest 门控泛滥（~210 处相关调用，`VirtualDisplayCapabilityServiceInstrumentedTest.kt:50-109` 一类 12 门）→ TASKS **#2**。
- androidTest 样板复制（`ProcessBuilder("su","-c",...)` ×6、`arguments()` ×7、`FixtureClient` 嵌在测试类里被跨包 import）→ TASKS **#4**。
- proguard 全空却被引用、markwon 未进版本目录、无 lint baseline → TASKS **#13**；无一键 verify 脚本 → TASKS **#14**。

---

## 5. 建议节奏

**只做三件事（推荐顺序）**

1. **手写 `AppContainer` + 去掉三处重复 client**（§4-C）。一天内完成，立刻换来可测性与依赖可见性，不动任何业务语义。
2. **拆 `RootPilotRunController` 与 `runSteps`**（§4-D）。663 个单测就是护栏；这一步之后再改副屏/输入类功能，阅读成本会明显下降。
3. **干掉 `create_todo` 双重解码**（§4-B，TASKS #5）。顺手把新旧栈边界画清，之后才能决定旧栈留还是删。

**其次**：§4-A（状态所有权）、§4-E（busy 与常量，TASKS #11）、§4-F（UiState 层）——A 与 F 建议一起做，因为它们共用"状态归谁"的答案。

**不建议现在做**：引入 Hilt/Koin、拆 Gradle 多模块、把 `execution-fixture` 并回主模块。在 1.5 万行主代码、单人练手项目的规模下，这些的收益低于其引入的配置与认知成本。

---

## 6. 未验证与本文边界

- 本文的全部"现状"结论来自静态阅读与统计；**未做**运行期性能剖析、真实多提供方并发写入验证、Service 进程死亡实测。
- 结构判断的置信度较高，因为它有实跑背书：本轮 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon` 全绿，`git diff --check` 干净。
- 与设备有关的判断（副屏输入验收、门控测试真实通过率）不在本文范围，见 SPEC.md 的未验证项与 TASKS.md 的功能下一步。
- 本文提出的新增观察（A/C/F 及 Screen 拆分）尚未进入 TASKS.md；如需推进，应先由人工决定是否立项，再补进 TASKS。

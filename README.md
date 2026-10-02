# RootPilot

在已 Root 的 Android 手机上，通过截图和模型规划完成有限的界面操作。RootPilot 是本仓库的主入口，旧 Android Agent 保留为实验入口。

这是一个需要人工确认的实验项目，不是可靠的无人值守助手。命令成功不代表页面操作成功，真实应用的完整流程仍需逐项验证。

## 已具备的能力

- **手机直连模型**：默认 DeepSeek API（`https://api.deepseek.com`，模型 `deepseek-flash`），支持自定义 Base URL、模型和开发 Relay。首次保存后自动加载，不需要电脑桥接；服务端可用性和账号权限以连接测试为准。
- **保存 API 配置**：Token 使用 Android Keystore 管理的密钥加密，密文保存在私有、不参与备份的目录，支持更换、清除和测试连接。
- **纯文字聊天**：聊天页复用已保存 API 配置，支持多轮、停止生成、新对话、流式 Markdown 和折叠思考；可选关闭、低、高、最高思考强度。聊天不截图、不调用 Root、不执行设备动作，会话仅保留在内存中。
- **文件 Agent（受限验收通过）**：从聊天的“工作区”选择单个系统授权目录，再明确同意发送文件内容并开启。模型可列目录、读 UTF-8 文本、按文件名／内容有界检索、读取元信息，以及提出创建或精确替换；每次写入都需核对修改前后并确认。没有删除、Shell、Root 或目录外访问工具。已有小米专用空目录的真实模型和导出验收；隔离 Provider 用例不代替系统授权或文件 UI 验收，新增检索状态见 `SPEC.md`。
- **模型输出预览**：任务思考期间可展开悬浮窗查看输出；流式草稿不触发动作，完整响应仍需解析、策略校验与原有确认。
- **截图驱动操作**：模型按步规划点击、滑动、按键、等待、应用启动和文本输入；由本地策略校验后执行。
- **屏幕上下文保护（测试页限定验收通过）**：通过手机本机 Root 采集前台应用／Activity、焦点窗口归属、键盘状态；截图前后、信息查询前后与动作前比较窗口，无法确认或发生变化时停止，不自动重放。小米测试页已验证上下文采集、中文输入恢复、确认后切窗拦截，以及真实模型与生产 Service 组合；不需要电脑 ADB，也不能识别同窗口内所有内容变化。
- **只读手机信息工具（测试页限定通过，稳定性 PARTIAL）**：模型可在选择动作前调用 `get_screen_context`、`get_activity_stack`、`get_ui_tree`。前两项复用本机 Root；控件树来自可选、手动启用的无障碍服务，返回当前应用文字、状态及物理像素位置，过滤密码／敏感节点文字。工具不点击或输入，最终动作仍走原有策略与确认。生产 Service 的真实模型、一次中文输入、输入后查询及完成用例通过；确认／停止按钮由脚本调用实际监听器，不代表真人触摸。确认时停止、查询中停止及用户关闭服务后的不可用分支已通过。模型响应协议曾实际失败，成功复跑不等于根因已修复。
- **通用应用启动与中文输入**：动态查询可启动应用；通过临时输入法输入 Unicode 文本，校验目标编辑框，拒绝密码框，结束后恢复原输入法。
- **允许启动的应用**：按名称或包名搜索并勾选，自动保存，可一键全不选。首次默认不允许启动任何应用；模型的应用目录只包含勾选项，执行启动前再次校验。
- **人工确认**：提供 App、悬浮面板和通知入口。打开应用、文本输入和系统按键始终确认；点击/滑动在手动模式确认。
- **本地待办**：确认标题和截止时间后保存到应用私有存储，与旧 Agent 共用数据；同一任务内防重复。不创建系统闹钟或提醒。
- **任务与诊断**：中断任务需人工决定恢复；结构化日志按任务和步骤记录阶段、结果及耗时。模型失败区分请求契约、HTTP、网络、超时、响应协议、配置和未知类；协议失败进一步标记固定原因。诊断不记录凭据、正文、模型原文或异常原文，不改变重试与执行策略。
- **任务结果**：主页展示完成、失败、停止与接管提示，区分模型报告和本地待办保存回执；完整文案仅保留在内存中，停止不会撤销已经执行的操作。
- **任务历史与失败回顾**：从设置进入，查看本地任务时间、状态、耗时、步骤与脱敏阶段记录，支持清除。不保存任务正文、输入文本、截图、凭据或模型原文，不提供动作重放。历史与任务恢复、API 配置分开存储。

## 使用前提

- Android 15 / API 35 或更高版本；手机任务需要 Root 权限，纯文字聊天不需要。当前主要验收设备为小米 15，不承诺所有厂商兼容。
- 可访问配置的模型 API，并拥有有效凭据。
- 使用悬浮面板需手动开启“显示在其他应用上层”；使用中文输入需手动启用 RootPilot 输入法。
- 使用控件树需在系统无障碍设置中手动启用“RootPilot 页面结构读取”；不启用仍可使用截图和其他信息工具。无需关闭现有无障碍服务，共存效果以设备验收为准。

## 开始使用

主页使用任务卡和独立状态卡，任务／聊天从底部切换，设置位于右上角。设置返回进入前的主页面；任务运行时不能切到聊天，聊天生成中不能离开。键盘弹出时底部导航隐藏，收起后恢复。聊天采用分角色消息卡和底部输入卡，思考强度在输入区选择。

设置首层提供 API 配置与连接测试、允许启动的应用和人工确认开关；“权限与输入”“调试工具”进入二级页，返回先回设置。已保存的 Token 不回显，更换配置需重新输入。历史详情的进一步视觉调整尚未进行。

1. 构建并安装 Debug APK，打开 RootPilot。
2. 从主页进入“设置”，填写 Token、Base URL 和模型，保存配置并测试连接。不要把 Token 放进源码或聊天。
3. 在“设置 → 权限与输入”按需要开启悬浮窗、输入法和可选页面结构读取；Root 检查、截图和单步执行位于调试工具。
4. 在设置的“允许启动的应用”中勾选需要的应用。列表自动保存，重新打开仍有效；不勾选不会禁止其他动作。
5. 返回主页，输入非敏感任务，确认允许上传屏幕，再开始执行；按提示核对动作并确认，随时可以停止。手动确认模式在设置中调整。

在“设置 → 任务历史与失败回顾”查看最近 50 次任务，每次最多保留最后 256 条阶段事件。历史位于应用私有、不参与备份的目录；纯聊天和调试截图不记入任务历史。清除需要确认，不影响 API 配置、待办或恢复记录，也不会停止当前任务；清除后的旧任务不再写回历史。进程退出可能丢失尚未落盘的尾部事件，重新打开时未完整收尾的记录显示为“已中断”，不是可靠审计凭证。

授权上传后，当前屏幕截图、前台包名／Activity、焦点窗口所属包名、键盘状态和采样时间，以及勾选的可启动应用名称、包名会发送至配置的 API。模型查询时还会发送当前前台任务的 Activity 组件、任务 ID，以及当前应用公开的控件文字／描述／提示、资源 ID、状态和物理像素位置。密码或敏感节点及其后代的文字不发送；这不代表普通页面全部隐私均能自动识别。RootPilot 自身页面不提供控件树，原始系统诊断与焦点窗口标识不上传、不落盘；恢复任务需重新同意上传。

信息查询只接受固定空参数工具，每个动作规划步骤最多 3 次、整个任务最多 12 次，不占动作步骤；用尽后只允许返回最终动作。返回注明来源、采样时间、available／unavailable 和截断状态；最多 200 个 UI 节点、每字段 120 字符，Activity 最多 32 项，结果总上限 256 KiB。无服务、非焦点／非默认显示窗口、未知格式或采集失败不会假装为空树。查询结果仅留在当前规划步骤内存，不写入恢复快照或任务历史。工具和截图是分时采样，不是原子快照；Activity 堆栈不等于 Fragment／Compose 导航，也不保证返回键去向。

屏幕共享的显示结果不等于本机输入结果。RootPilot 输入法与确认悬浮窗使用 `FLAG_SECURE`，可能无法在共享端显示；验收需回读目标文本并检查输入法恢复，不移除保护。存在小米镜像虚拟显示时已确认文本写入与恢复；真实模型／Service 闭环已有成功证据，但响应协议失败根因未定位，整体稳定性为 PARTIAL，未做共享开关的对照实验。

应用选择只限制 `open_app`，不是完整访问隔离：链接、桌面点击、返回键仍可能进入其他应用，其界面和查询信息也可能上传。避免在密码、支付、聊天隐私等页面运行；自动模式不代表安全保证。

只需问答时进入“聊天”，发送的文本及本会话已完成的问答会发至配置的 API，不发送截图。思考强度仅影响聊天；高档位可能更慢、消耗更多 Token，仍受单请求 120 秒时限约束。聊天不落盘，进程退出会丢失；保存或清除 API 配置会清空聊天，生成时先停止才能切回任务或修改配置。流式 Markdown 支持标题、列表、引用、代码块，不自动打开链接或加载外部图片。自定义地址需要支持 SSE，现有开发 Relay 会缓冲响应，不保证实时预览。

### 文件工作区

在“工作区 → 浏览文件（仅本机）”查看授权目录，支持进入子目录、返回上级、刷新和文本预览。浏览不需要开启文件 Agent，不会把内容加入聊天或发送给模型；关闭或离开页面后清除预览，不跨进程保存。仍受单文件 64 KiB、单目录 100 项和文本格式限制。

1. 在聊天页打开“工作区”，通过系统选择器选择专用、非敏感目录。系统可能禁止选择存储根目录、下载根目录和受保护目录，应用不绕过这些限制。
2. 阅读提示后点“同意发送文件内容并开启”。选择目录本身不启用模型访问；重启应用或保存／清除 API 配置后，文件 Agent 都需重新开启。
3. 描述文件任务。读取的文件名、元信息和内容／匹配片段会发给当前配置的 API；文件只是资料，不能自行授予更多权限。写入默认展示增删行、行号和换行类型，可切换查看完整原文与新文本；差异按变更区段计算，并非最小差异，省略时会明确提示。确认仅绑定这一次预览；拒绝即结束该轮文件任务。
4. 修改前重新检查文件身份与内容，并在应用私有 `noBackup` 目录保留原文备份。可从工作区面板经系统保存界面导出备份，检查后手动恢复。撤销目录不会删除文档或备份；卸载应用会丢失未导出的备份。

第一版仅支持单文件最多 64 KiB 的 UTF-8 文本（text/*、JSON、XML）、单目录最多 100 项；精确编辑要求旧文本只出现一次，不创建文件夹。每次请求最多 8 轮／16 次工具调用。备份总额度 5 MiB，满后拒绝后续需要备份的写入，不自动删除旧备份。仅支持能验证目录从属关系的 DocumentsProvider。

文件 Agent 的 `stat_file` 返回相对路径、类型、MIME、大小和修改时间；供应商未提供的字段为未知，不读取正文。`search_files` 从指定相对目录或文件重新扫描，空路径表示授权根目录；`scope=name/content`，查询为 1–128 UTF-16 单元的区分大小写字面量，不是正则或通配符。最多深度 4、遍历 500 项、合计读取 256 KiB、返回 20 项，每文件只返回首个匹配附近至多 160 单元的片段。不建索引；超限、非文本、无法读取或其他遗漏会明确标记，不能据不完整结果认定无匹配。检索不写文件，也不改变逐次写入确认；本地浏览不会自动检索或上传。

SAF 不提供跨应用的原子比较并写入：写入前后的检查能发现部分冲突，但不能杜绝其他应用并发修改或存储服务部分写入。写入开始后停止不代表撤销，失败也不会自动重放；请检查结果及备份。正在写入时等待其回执后才允许下一轮操作。更换／撤销目录、切换文件模式会清空聊天，防止已读文件继续进入另一工作区或普通聊天。

## 构建与测试

使用支持 AGP 9.1.1 的 Android Studio、兼容的 Gradle JDK 和 Android SDK 37。项目使用 Gradle 9.3.1、Kotlin 2.4.20；首次构建需要下载依赖。compileSdk 为37，targetSdk 仍为36，最低系统仍为 Android 15 / API 35。

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

`MODEL_FAILED` 可包含白名单 `modelFailureCategory`、HTTP 失败的 `modelHttpStatus`，以及协议失败的 `modelProtocolReason`；同样的类型化字段进入任务历史。未提供字段代表未知，旧历史仍可读取。只按失败发生点分类，不分析原始消息、返回体或异常文本，也不据此自动重试。

### 独立测试页验收

`execution-fixture` 是仅 Debug 的独立测试 APK，不是产品入口。它通过签名权限保护的 Provider 只导出自己的 View 画面，排除系统、键盘和其他应用；非空内容必须是固定测试文本。生产代码不依赖该模块，RootPilot 仅 Debug Manifest 声明访问权限。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :execution-fixture:assembleDebug --no-daemon
```

在已核对的同一设备上依次安装 fixture、RootPilot Debug 和 androidTest APK（均使用同一 Debug 签名）。需已有 Root、已启用 RootPilot 输入法；真实测试还需手机已保存 DeepSeek 配置和用户同意上传测试页。首次后台启动可能被系统限制，本次小米验收先通过显式目标设备的 Root `am start` 打开 `com.example.rootpilot.fixture/.ExecutionFixtureActivity`，每次用例结束关闭测试页。

仅运行 `LiveExecutionInstrumentedTest#fixtureChannelRendersOnlyKnownContent` 并传入 `fixtureChannelAcceptance=true` 可检查不联网通道；另行明确授权后，运行 `LiveExecutionInstrumentedTest#liveModelTypesFixtureAndObservesCompletion` 并传入 `liveFixtureExecution=true` 才会调用真实 API。两个参数默认关闭，不应将整个测试套件带 live 开关运行。

真实用例只允许一次固定中文 Type，确认由脚本批准；模型必须在下一帧看到输入结果并结束，且输入法恢复断言通过才算成功。它验证 HTTP、执行循环与真实 IME，不替代正式 Service、悬浮窗／通知、物理屏幕或点击验收。

新增信息工具验收位于 `DeviceInformationInstrumentedTest`。仅运行 `readsAllThreeToolsFromFocusedFixture` 并传入 `deviceInformationAcceptance=true` 可不联网检查三个工具；另行授权并手动启用页面结构读取后，单独运行 `realModelConsumesToolsThenTypesAndObservesFixture`、传入 `liveDeviceInformationExecution=true`，才会向手机已保存的 DeepSeek API 发送测试页画面和工具结果。真实用例要求三个结果进入后续请求后才批准一次固定 Type，输入后必须再次读取树并精确匹配；不允许坐标动作。未开启服务的负向用例需用户手动关闭，使用 `deviceInformationDisabledAcceptance=true` 单独运行。所有开关默认关闭，不将整个测试套件带 live 开关运行。

需要已连接无障碍服务的信息工具用例，先正常启动 RootPilot 并确认服务连接，再用 `am instrument --no-restart` 执行。默认 instrumentation 会在开始和结束时停止目标进程，本次小米会因此将无障碍服务列入 `Crashed services`，不能靠等待当作已恢复。发生这种情况需用户手动关闭再开启 RootPilot 页面结构读取；不更改其他服务。`--no-restart` 要求目标进程已存在，进程保持不退出也不代表业务用例通过。

`RootPilotProductionServiceInstrumentedTest` 使用真实 Service、手机本机全屏采集、已保存 DeepSeek 配置及实际悬浮窗监听器，仍只操作签名保护的固定测试页。分别单独运行 `realServiceModelOverlayInputAndCompletion`（`liveProductionServiceAcceptance=true`）、`realServiceStopAtInputConfirmationRestoresIme`（`productionServiceStopAcceptance=true`）、`realServiceStopDuringActivityQuery`（`productionQueryStopAcceptance=true`）。完成用例检查一次固定 Type、输入后查询、完成、原输入法恢复及无恢复记录；确认／停止按钮通过 `performClick` 调用，不代表物理触摸，查询停止由测试发送 Service 停止命令。全屏上传可能包含测试页、系统及键盘层，运行前须另行同意并确认无敏感内容。

真实断连补验由用户仅关闭 RootPilot 页面结构读取，再单独运行 `realServiceContinuesAfterManuallyDisabledPageStructure`（`productionServiceDisconnectedAcceptance=true`）；要求真实 provider 返回 `not_enabled`，生产 Service 处理不可用回执并完成，零输入。重连必须由用户手动重新启用，再跑不联网三工具采集用例；测试代码不修改系统授权，不用默认 instrumentation 人为杀进程充作断连。

本次测试期间 GKD 与 Cumulus 保持连接；仅用户配合补验时关闭／重开 RootPilot 服务。不代表 GKD 业务、所有共存模式、进程死亡恢复、通知或真人触摸确认通过。

### 无需工作区授权的隔离文件验收

`FileAgentUsabilityInstrumentedTest` 不启动系统选择器，不读取或更改已选目录，不连接 UiAutomation。它在 App 私有 cache 中生成唯一测试目录，通过未注册、进程内的 DocumentsProvider 复用文件控制器、SAF 访问、提交校验、备份和回读。`fileSyntheticAcceptance=true` 仅运行 `syntheticProviderCreateEditBackupAndReject`，不联网；`liveFileUsability=true` 仅运行 `realModelCreatesReadsEditsAndRetainsBackup`，才向手机已保存的 DeepSeek 配置发送固定合成文件数据。两个开关默认关闭，使用 `am instrument --no-restart` 保留已连接服务。

每阶段脚本只批准一次路径、原文和新文都精确匹配的预览，不放宽生产确认。测试保留少量合成文件与备份，并输出私有目录位置；它证明核心工具链，不证明真实 SAF 授权持久化、系统导出或用户点击确认。

只读检索分别由 `fileSearchAcceptance=true` 的 `syntheticProviderSearchAndStatWithoutAdditionalWrites` 和 `liveFileSearchAcceptance=true` 的 `realModelConsumesSearchAndStatWithoutWriting` 验证。前者检查元信息未知值、名称／内容匹配、字面量及路径边界；后者核对 stat、两种搜索回执实际进入后续模型请求。均只预先创建一个固定私有测试文件，检索阶段不写入；两个开关默认关闭，联网用例只发送合成数据。按具体方法选择用例，不为运行一项而打开整个类的联网开关。

### 实际 SAF 只读检索验收

`FileSearchSafAcceptanceInstrumentedTest` 只接受用户已有授权的专用合成目录 `RootPilot-Acceptance-20260924-1700`，严格核对实际 URI、目录名、pointer 与读写 grant。`preflightKnownWorkspace`（`fileSearchSafPreflight=true`）只报告布尔值，不遍历或创建。`realSafSearchStatAndTruncation`（`fileSearchSafAcceptance=RootPilot-Acceptance-20260924-1700`）在唯一 UUID 子目录准备 29 个合成文件，验证中文多级 stat／name／content 和深度、字节、结果截断。`cancelsInFlightSafWorkspaceSearch`（`fileSearchSafCancellation` 同目录名）另建一个合成文件，用只读 forwarder 持有实际文件描述符后取消，检查关闭与不返回结果；不代表提供方 IPC 硬实时取消。

`rejectsStaleSearchAfterPrivatePointerVersionChange`（`fileSearchSafPrivatePointerSimulation` 同目录名）另建一个合成文件与私有 cache pointer，仅模拟选择版本变化，不改变用户选择或 grant。`rejectsStaleSearchAfterManualDirectorySelection`（`fileSearchSafManualSwitch` 同目录名，`fileSearchSafManualSwitchChild` 为先前实际创建的 `RootPilot-Search-<UUID>` 子目录）则扣住真实只读 IO 的回派，等待通过正常选择器授权该非敏感子目录，检查旧结果冲突、旧相对路径失效以及新选择下读取／检索。测试代码不自行选择目录或增删授权，验完后通过正常选择器恢复原专用目录。所有用例默认关闭、按方法单独执行，不联网；不读取个人目录，不删除合成产物。

真实选择器用例须先启动单个测试，收到 `ready_for_manual_directory_selection=true` 后再打开 RootPilot“聊天 → 工作区”进行选择。AndroidX runner 默认会在用例开始与结束时关闭 Activity；`--no-restart` 只保留进程，不阻止该清理。不要在用例开始前打开选择器，否则授权可能无法回到已关闭的调用页；按钮点击或系统允许不等于工作区切换成功，仍以 pointer／grant 和新旧结果断言为准。

## 源码导航

| 路径（`app/src/main/java/com/example/agent/` 下） | 职责 |
| --- | --- |
| `rootpilot/RootPilotActivity.kt`、`rootpilot/ui/` | 配置、任务界面和悬浮面板 |
| `rootpilot/RootPilotService.kt` | Android 服务入口、通知与悬浮窗宿主 |
| `rootpilot/RootPilotRunController.kt`、`rootpilot/loop/` | 任务占用、停止/销毁收尾、恢复与规划确认循环 |
| `rootpilot/deepseek/`、`rootpilot/screen/` | 模型请求和截图 |
| `rootpilot/chat/` | 独立文字会话、上下文与取消控制 |
| `rootpilot/action/`、`rootpilot/root/` | 动作解析、策略校验和 Root 执行 |
| `rootpilot/apps/`、`rootpilot/input/` | 应用目录与输入法连接 |
| `rootpilot/information/` | 固定信息工具、可选无障碍连接、当前焦点窗口语义树与敏感文字过滤 |
| `rootpilot/log/` | 脱敏结构化日志 |
| `agent/` | 旧 Agent 实验能力及复用的待办存储 |

## 当前限制与计划

最新能力状态、验证范围和后续候选项见 [SPEC.md](SPEC.md)；开发协作规则见 [AGENTS.md](AGENTS.md)。

RootPilot 的任务、聊天和设置页使用 Miuix 0.9.4 的主题、卡片、按钮与开关；任务/聊天输入框也使用 Miuix，密码输入框保留原安全输入组件。悬浮窗、输入法面板和旧 Agent 实验入口未迁移。Miuix 是实验性 Compose 组件库，不调用小米系统私有组件；它的传递依赖引入 Material3 1.5.0-alpha22，已验证范围及剩余兼容风险见 Spec。

目前系统设置搜索闭环仍为 **PARTIAL**：可以打开设置，但搜索栏点击后未进入搜索页，不能据此声称中文搜索完成。历史误点调查已暂缓。旧端侧 LiteRT-LM / Demo 后端未迁入 RootPilot，仍保留在实验入口。

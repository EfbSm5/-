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

信息查询只接受固定空参数工具，每个动作规划步骤最多 3 次、整个任务最多 12 次，不占动作步骤；用尽后只允许返回最终动作。返回注明来源、采样时间、available／unavailable 和截断状态；最多 200 个 UI 节点、每字段 120 字符，Activity 最多 32 项，结果总上限 256 KiB。无服务、非目标焦点窗口、未知格式或采集失败不会假装为空树。主屏查询接受默认显示，副屏查询仅接受本次拥有的显示与会话。查询结果仅留在当前规划步骤内存，不写入恢复快照或任务历史。工具和截图是分时采样，不是原子快照；Activity 堆栈不等于 Fragment／Compose 导航，也不保证返回键去向。

当前验收版本的设备动作请求（含只读信息工具决策）使用 LOW 思考、输出上限 65536 Token，与 [DeepSeek 普通思考模式的默认预算](https://api-docs.deepseek.com/api/create-chat-completion/)一致；聊天及文件 Agent 的思考选择和预算不受影响。LOW 仍是验收中的档位选择，不保证不超限或点位正确；仍保留单请求时限、协议校验及逐动作确认，不自动重试失败动作。

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

`MODEL_FAILED` 可包含白名单 `modelFailureCategory`、HTTP 失败的 `modelHttpStatus`，以及协议失败的 `modelProtocolReason`；同样的类型化字段进入任务历史。`INFORMATION_CALL_INVALID` 也可携带固定协议细分：`TOOL_CALL_CARDINALITY`（不是恰好一次调用）、`TOOL_NOT_ALLOWED`（非允许工具）、`TOOL_ARGUMENTS_INVALID`（参数不是可解析对象）、`TOOL_ARGUMENTS_NOT_EMPTY`（参数非空）。仅失败 RESULT 和 RUN_END 保留这些字段，不记录工具名、参数或模型原文。未提供字段代表未知，旧历史仍可读取，不能追溯归因。只按失败发生点分类，不分析原始消息、返回体或异常文本，也不据此自动重试。

设备决策请求的模型结果事件还可包含 `modelUsage`：服务端输入／输出／思考／总 Token 数，以及已解析片段的思考／正文／空白 UTF-16 字符数和固定结束原因。成功和失败均可记录；缺失 Token 字段为未知，畸形或矛盾用量标记 `usageMalformed` 并清空 Token 数，不影响原响应判定。统计只绑定当前请求的 MODEL RESULT，进入现有脱敏日志和任务历史，不进入任务恢复快照；旧历史缺少此字段仍可读。中断时的字符数可能不完整，不能据一次正常请求的用量解释另一次失败。纯聊天、文件 Agent 和旧单步动作路径不采集这些统计。

动作响应必须整份解析为单个 JSON 对象；对象后有多余内容时仍拒绝，不截取合法前缀。既有的一次协议纠正请求会发送固定格式提示，不回传被拒绝的模型原文、不增加重试次数，动作仍需策略校验和原有确认。

### 独立测试页验收

`execution-fixture` 是仅 Debug 的独立测试 APK，不是产品入口。它通过签名权限保护的 Provider 只导出自己的 View 画面，排除系统、键盘和其他应用；非空内容必须是固定测试文本。生产代码不依赖该模块，RootPilot 仅 Debug Manifest 声明访问权限。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :execution-fixture:assembleDebug --no-daemon
```

在已核对的同一设备上依次安装 fixture、RootPilot Debug 和 androidTest APK（均使用同一 Debug 签名）。需已有 Root、已启用 RootPilot 输入法；真实测试还需手机已保存 DeepSeek 配置和用户同意上传测试页。首次后台启动可能被系统限制，本次小米验收先通过显式目标设备的 Root `am start` 打开 `com.example.rootpilot.fixture/.ExecutionFixtureActivity`，每次用例结束关闭测试页。

仅运行 `LiveExecutionInstrumentedTest#fixtureChannelRendersOnlyKnownContent` 并传入 `fixtureChannelAcceptance=true` 可检查不联网通道；另行明确授权后，运行 `LiveExecutionInstrumentedTest#liveModelTypesFixtureAndObservesCompletion` 并传入 `liveFixtureExecution=true` 才会调用真实 API。两个参数默认关闭，不应将整个测试套件带 live 开关运行。

`FixtureCloseInstrumentedTest#classifySingleCloseWithoutRetry` 是默认关闭的离线关闭诊断。`fixtureCloseProbeMode=foreground` 在空测试页前台请求关闭；`background` 先核对默认 HOME 的真实候选组件，只执行一次 HOME，并验证前台／焦点后等待 30 秒。两种模式均拒绝已有任务或 IME 恢复记录，不截图、不读控件树、不联网、不输入文本；通过 `ContentProviderClient` 只请求一次关闭，区分固定 Binder／回执失败类别，不自动重试。小米后台启动受限时，沿用上文 Root 启动流程准备空 fixture；观察前置失败不代表关闭失败。`RECEIPT` 只证明收到返回值，仍须在外部核对 fixture Activity/task 已消失；失败时保留现场，确认身份后正常 UI 收尾。诊断不改变生产或原 `FixtureClient` 的关闭行为。

真实用例只允许一次固定中文 Type，确认由脚本批准；模型必须在下一帧看到输入结果并结束，且输入法恢复断言通过才算成功。它验证 HTTP、执行循环与真实 IME，不替代正式 Service、悬浮窗／通知、物理屏幕或点击验收。

新增信息工具验收位于 `DeviceInformationInstrumentedTest`。仅运行 `readsAllThreeToolsFromFocusedFixture` 并传入 `deviceInformationAcceptance=true` 可不联网检查三个工具；另行授权并手动启用页面结构读取后，单独运行 `realModelConsumesToolsThenTypesAndObservesFixture`、传入 `liveDeviceInformationExecution=true`，才会向手机已保存的 DeepSeek API 发送测试页画面和工具结果。真实用例要求三个结果进入后续请求后才批准一次固定 Type，输入后必须再次读取树并精确匹配；不允许坐标动作。未开启服务的负向用例需用户手动关闭，使用 `deviceInformationDisabledAcceptance=true` 单独运行。所有开关默认关闭，不将整个测试套件带 live 开关运行。

需要已连接无障碍服务的信息工具用例，先正常启动 RootPilot 并确认服务连接，再用 `am instrument --no-restart` 执行。默认 instrumentation 会在开始和结束时停止目标进程，本次小米会因此将无障碍服务列入 `Crashed services`，不能靠等待当作已恢复。发生这种情况需用户手动关闭再开启 RootPilot 页面结构读取；不更改其他服务。`--no-restart` 要求目标进程已存在，进程保持不退出也不代表业务用例通过。

`RootPilotProductionServiceInstrumentedTest` 使用真实 Service、手机本机全屏采集、已保存 DeepSeek 配置及实际悬浮窗监听器，仍只操作签名保护的固定测试页。分别单独运行 `realServiceModelOverlayInputAndCompletion`（`liveProductionServiceAcceptance=true`）、`realServiceStopAtInputConfirmationRestoresIme`（`productionServiceStopAcceptance=true`）、`realServiceStopDuringActivityQuery`（`productionQueryStopAcceptance=true`）。完成用例检查一次固定 Type、输入后查询、完成、原输入法恢复及无恢复记录；确认／停止按钮通过 `performClick` 调用，不代表物理触摸，查询停止由测试发送 Service 停止命令。全屏上传可能包含测试页、系统及键盘层，运行前须另行同意并确认无敏感内容。

真实断连补验由用户仅关闭 RootPilot 页面结构读取，再单独运行 `realServiceContinuesAfterManuallyDisabledPageStructure`（`productionServiceDisconnectedAcceptance=true`）；要求真实 provider 返回 `not_enabled`，生产 Service 处理不可用回执并完成，零输入。重连必须由用户手动重新启用，再跑不联网三工具采集用例；测试代码不修改系统授权，不用默认 instrumentation 人为杀进程充作断连。

本次测试期间 GKD 与 Cumulus 保持连接；仅用户配合补验时关闭／重开 RootPilot 服务。不代表 GKD 业务、所有共存模式、进程死亡恢复、通知或真人触摸确认通过。

### 应用商店搜索只读预检

`MarketSearchUiAcceptanceInstrumentedTest#inspectPublicMarketPageWithoutActionsOrNetwork` 需显式 `marketSearchPreflight=true`，默认跳过。它只在已打开的应用商店公开首页／搜索页核对前台、焦点与新鲜控件树，输出固定安全标签、资源 ID、控件状态和坐标；不启动任务、不输入查询、不提交搜索、不下载或安装。正常 RootPilot 界面发起的联网搜索验收尚未实现或运行。观察器的 Activity dump 上限为 512 KiB，其余三份输入仍为 256 KiB；超限仍整份丢弃，具体证据见 `SPEC.md`。

`RootScreenObserverInstrumentedTest#productionObserverRecognizesKnownForeground` 默认跳过，仅显式 `observerKnownPage=rootpilot` 或 `observerKnownPage=market` 时启用。先正常打开对应公开页面，再使用 `am instrument --no-restart`；测试只调用生产观察器并核对完整窗口身份与键盘状态，不启动页面、截图、输入或联网，回执及失败信息只输出固定字段。

### 系统计算器限定验收

`CalculatorLiveAcceptanceInstrumentedTest` 仅用于小米系统计算器固定算式 `123×45`，不增加生产导航特判。两个开关默认关闭：`inspectCalculatorWithoutActionsOrNetwork` 配合 `calculatorPreflight=true` 只读取前台计算器控件，输出固定安全标签、资源 ID、坐标与截断标记；`realServiceOpensCalculatorAndReadsProduct` 配合 `liveCalculatorAcceptance=true` 才调用已保存的 DeepSeek 配置和真实生产 Service。每次只选择对应方法运行，不给整个测试类打开联网开关。

联网前需另行同意固定测试页、计算器及系统层的全屏上传，确认无敏感内容、计算器当前算式为 0、RootPilot 空闲且无恢复记录。安装测试 APK 后先正常打开 RootPilot，再前台打开签名 fixture，使用 `am instrument --no-restart`。测试临时将启动列表限制为计算器，随后只通过真实悬浮窗监听器批准模型提出的启动及 `1、2、3、×、4、5、=` 七次点击；每次核对当前算式和新鲜控件的资源 ID、标签与命中范围。不会操作清除、历史、菜单或其他应用，也不会补点或自动重跑。

通过要求包含真实执行回执、等号后成功的模型控件树查询及后续请求、显示区域中的 5535 和模型完成报告。结束检查原输入法、任务快照，并恢复原配置及启动列表的原字节；无法确认本次启动／停止和执行退出时保留已知画面与环境，报告 `cleanup_unconfirmed_fixture_retained`，需先处理未退出任务，不能宣称清理成功。确认来源是脚本调用实际按钮监听器，不是真人触摸；控件树有截断标记时不声称整页完整。结果及未完成范围见 `SPEC.md`。

### 独立副屏（实验）

“设置 → 任务偏好”中的独立副屏默认关闭。开启后，从允许启动的应用中选择起始应用，再从任务页开始完整任务。每次任务新建 1080×1920、320 dpi 副屏；启动、点击、滑动和按键都需要确认。任务页显示本次副屏截图预览；同意上传后，仅该执行屏幕的截图会发送至配置的 API。

支持启动、点击、滑动、BACK／ENTER 和等待；HOME 与任务外截图仍禁用。滑动两端限于本次副屏像素范围，时长为 100–2000 ms；按键仅开放固定两个码，不开放任意 shell 或主屏回退。副屏仍隐藏输入法。单步执行每次新建副屏，先确认打开起始应用，再执行一个规划动作并关闭；下一次从新的会话重新开始，不保留上次页面。

副屏信息工具提供屏幕上下文、当前前台任务的 `get_activity_stack` 和可选 `get_ui_tree`。Activity 栈只解析拥有显示的独立区段，不包含主屏、其他任务、全局等待队列或 Intent extras；控件树只从该显示上的唯一焦点应用窗口采集。两者均绑定显示／会话／窗口，失效返回 unavailable，不借用主屏结果。沿用主屏的脱敏与容量限制，节点位置是该副屏的物理像素。既有应用页面可能迁入副屏，不是应用实例、账号或数据隔离。结束会关闭副屏页面；释放未确认时保留恢复记录并禁止新任务，不自动回退主屏。恢复只保留执行模式和起始应用，不复用旧副屏，也不恢复截图上传同意。

副屏 `type` 依赖已启用的页面结构服务，只向已聚焦、可见、启用且文本明确为空的普通文本框整段填写，最多 128 个 UTF-16 单元，支持中文、emoji、换行。它不切换输入法，也不提供主屏 `commitText` 的光标插入语义；非空、null／未知文本、非空提示造成的歧义、密码／敏感节点、未知祖先或服务／会话／目标变化均拒绝，不自动清空、替换或重试。确认前释放查询锁并恢复读取标志，确认后重新绑定同一节点及窗口；提交后仍需观察实际结果。该限定行为已通过专用页真实 Service／模型完成和输入待确认停止，不代表所有 App 编辑框兼容。

副屏树查询期间，本服务临时开启包含布局节点的读取标志；本服务的主、副屏树查询串行执行，结束或取消时恢复原标志。恢复无法确认时，当前服务实例拒绝后续树读取，不把扩展配置带入主屏查询；需要服务重新连接。不启用 `isAccessibilityTool`，不改变其他无障碍服务或敏感文字过滤。

`VirtualDisplayCapabilityInstrumentedTest` 在签名专用 `VirtualCapabilityActivity` 上离线验证，不用真实模型或 UiAutomation。`platformDeliversSwipeKeysAndAsciiWithHiddenIme`（`virtualCapabilityPlatformProbe=true`）是显示定向平台探针；`productionLoopConfirmsSwipeBackAndEnterWithoutNetwork`（`virtualCapabilityLoopAcceptance=true`）核对四次确认／执行及未确认 Type／HOME 拒绝。新入口 `productionLoopReadsOnlyOwnedActivityStackWithoutNetwork`（`virtualActivityStackAcceptance=true`）验证拥有栈及错屏／错会话拒绝；`twoIndependentSingleStepsUseFreshOwnedSessionsWithoutNetwork`（`virtualSingleStepLoopAcceptance=true`）核对两轮各一个规划动作、独立创建／关闭；`productionLoopTypesFixedUnicodeAndObservesCompletionWithoutNetwork`（`virtualUnicodeLoopAcceptance=true`）验证真实产品 Type、后帧树回读、非空及释放后的拒绝。另有 `platformSetsFixedUnicodeInEmptyOwnedEditorWithoutImeChange`（`virtualUnicodePlatformProbe=true`）和 `platformHomeTargetsOwnedDisplayWithoutChangingMain`（`virtualHomePlatformProbe=true`），不等同产品支持。所有开关默认关闭，按方法单独执行，使用 `--no-restart`；均核验环境和释放后主屏身份。首次后台访问可能未就绪，显式启动固定页后用 `closesOnlyExplicitlyLaunchedMainDisplayFixtureWithoutNetwork`（`closeExplicitVirtualFixture=true`）收尾，再打开 RootPilot；不添加生产等待或重试。限定结果见 `SPEC.md`。

`VirtualSingleStepDispatchInstrumentedTest#dispatchesVirtualSingleStepButKeepsCaptureAndApiEditingBlocked`（`virtualSingleStepDispatchAcceptance=true`）验证公开 ViewModel 入口，使用隔离合成配置并拦截 Service 派发，零联网／零设备任务；不替代真实单步验收。

`VirtualDisplayCapabilityServiceInstrumentedTest` 使用真实 Service、已保存的 DeepSeek 配置和实际悬浮窗监听器。单独运行 `realServiceConfirmsSwipeBackEnterAndObservesReceipts`（`liveVirtualCapabilityServiceAcceptance=true`），仅批准签名专用页的一次启动、向上滑动、BACK 和 ENTER；要求固定计数、本次执行回执、结果树进入后续请求及模型成功结束。停止用例单独运行 `realServiceStopsBeforeRequestedCapability`（`virtualCapabilityServiceStopAcceptance=true`），另传 `stopAction=SWIPE`、`BACK` 或 `ENTER`，仅依据固定副屏截图规划，在对应动作待确认时点击实际停止监听器，核对之后零执行。两个开关默认关闭，均会联网，必须先取得固定副屏画面／控件树上传授权并使用 `--no-restart`，不向整类开启 live 开关。测试临时选择专用页为唯一允许应用，退出与副屏释放确认后恢复原配置及启动列表字节，核对 API 密文、主屏 Activity、IME 和无障碍服务实例／标志不变。最终效果回读使用实例／显示绑定的固定计数保留回执，不把释放后的回执当成新鲜页面观察；新建实例会清空旧回执。fixture 的 Launcher 声明仍受签名权限保护，只用于既有生产应用目录发现；不在生产目录中增加特判。脚本确认不代表真人触摸；固定 BACK 回调不代表应用导航、主屏身份不变不代表主屏并行输入通过。

该类另有独立入口：`realServiceReadsOwnedActivityStackWithoutInputAndFinishes`（`liveVirtualStackReadOnlyAcceptance=true`）只允许一次 stack 查询及 Finish；`realServiceReadsOwnedActivityStackAndCompletesCapabilities`（`liveVirtualStackServiceAcceptance=true`）为 stack＋滑动／按键组合，不能用前者替代。`realServiceSingleStepClosesAfterOnePlanningAction`（`liveVirtualSingleStepServiceAcceptance=true`）核对一次规划动作后本地完成并释放；`realServiceTypesFixedUnicodeAndObservesCompletion`（`liveVirtualUnicodeServiceAcceptance=true`）核对一次固定 Type、输入后树查询及完整文本完成；`realServiceStopsAtUnicodeConfirmationWithoutInput`（`virtualUnicodeServiceStopAcceptance=true`）核对待确认停止、零输入及停止后零新执行。均默认关闭、单方法执行；联网入口沿用相同上传授权和收尾门槛。

综合入口 `realServiceCompletesCombinedStackSwipeKeysAndUnicode`（`liveVirtualCompositeServiceAcceptance=true`）限定同一新副屏完成 Open → stack／UI 树 → Swipe → BACK → ENTER → 一次 `中文🙂\n第二行` 输入 → 新帧／树 → 模型 Finish。编辑框固定在滚动区外，必须原本明确为空且已聚焦；五次确认前均核验本服务实例及读取 flags，逐动作／固定计数／完整文本和退出收尾不可用分项历史成功替代。当前综合真机取得限定 PASS，条件是实体解锁、临时全局息屏超时及不启用测试窗口保屏，详见 `SPEC.md`；不表示所有环境稳定。可选 `holdRootPilotScreen=true` 只设置本测试窗口的可恢复保屏标志，收到本次 `screenHoldReadyForActivity=true` 后才显式启动 RootPilot 新任务（`am start -W -f 0x18000000 -n com.example.agent/.rootpilot.RootPilotActivity`）；不解锁或改全局电源设置。保屏守卫失效时仅输出固定窗口存活／STARTED／可见／标志／未锁定布尔值并停止；投屏亮着或保屏标志存在不证明系统未锁定，其旧失败仍保留，不宣称已修复。

`inspectRejectedSwipeWithoutActionsOrNetwork` 是同类默认关闭的诊断入口，需 `inspectRejectedVirtualCapabilitySwipe=true`、`failedCapabilityDirectory` 和 `failedCapabilityRunId`。仅在原失败回执、最近同 run 历史和当前进程保留私有帧全部匹配时，导出固定数值坐标和原帧；不把它当作新鲜截图，不回读或归属 fixture 计数，不执行动作、联网或修改配置。原现场不匹配即拒绝，不补采冒充原失败证据。

`VirtualDisplayAcceptanceInstrumentedTest` 是默认关闭、不联网的底层验收：单独运行 `createsAndClosesEmptyVirtualDisplay` 配合 `virtualDisplayTransportAcceptance=true` 检查创建／释放；单独运行 `calculatesKnownProductOnVirtualDisplay` 配合 `virtualDisplayAcceptance=true` 才通过新鲜副屏节点操作固定算式 `123×45`。后者需要已连接的 RootPilot 页面结构读取，使用 `am instrument --no-restart`，不会清除历史或修改启动列表。测试通过不代表生产 Service、模型或真人确认闭环已通过；实际证据和未验证项见 `SPEC.md`。

`cancelsOwnedVirtualDisplay`（`virtualDisplayCancellationAcceptance=true`）检查空副屏取消清理；`inspectsVirtualCalculatorWithoutInput`（`virtualDisplayInspection=true`）只读检查指定计算器，不输入、不联网。分段续跑用例只接受先前恰好执行数字 1 且清理成功的测试回执，并重新核对当前行，不能用于任意失败任务恢复或自动重放。

副屏控件树先单独运行 `VirtualDisplayUiTreeInstrumentedTest#readsOwnedTreeAndRejectsForeignOrReleasedSession`，传入默认关闭的 `virtualUiTreeAcceptance=true`；不联网、不截图、不点击，核对树的显示／包名／像素位置以及错屏、错会话、关闭后的拒绝。算式和结果标签存在仅证明树包含这些内容，不证明它们位于当前行。另行同意发送非敏感副屏画面和工具结果后，单独运行 `VirtualDisplayServiceAcceptanceInstrumentedTest#realServiceReadsOwnedCalculatorTreeWithoutInput`（`liveVirtualUiTreeAcceptance=true`）：真实 Service／模型只读取一次树，要求当前行是 `0`，上方历史含 `123×45=5535`，并在成功结束时明确区分二者；只批准一次启动，零算式点击，不重新计算。二者均需要已连接的可选服务及 `--no-restart`；结果和未验证项见 `SPEC.md`，不将整套测试带 live 开关运行。

配置作用域的离线检查单独运行同类 `restoresFlagsOnFailureCancellationAndBeforeMainReads`，传入默认关闭的 `virtualUiTreeScopeAcceptance=true`；核对实际服务在采集异常、取消后恢复原标志，以及主屏查询等待副屏标志恢复。不读页面、不启动模型、不输入。

`VirtualDisplayServiceAcceptanceInstrumentedTest#realServiceCalculatesOnOwnedVirtualDisplay` 配合默认关闭的 `liveVirtualCalculatorAcceptance=true`，才使用手机已保存的 DeepSeek 配置运行真实 Service、模型与实际悬浮窗确认监听器。须先同意发送非敏感计算器副屏画面，初始状态仅接受 0 或当前完整 `123×45=5535`；初始条件不重复套用于已执行点击后的中间态。只批准一次启动和七个指定键，不清除历史。结束核对 RUN_END、显示消失、输入法及原配置／启动列表恢复；脚本确认不等于真人触摸。LOW／65536 在动作格式提示修正版取得新的独立单任务七键、当前结果 5535、模型成功结束和清理通过证据（65.906 秒）；此前完整 61.774 秒成功另保留。该固定用例限定 PASS；本轮仍有首键点位被拦截、零点击的失败，既有输出超限与解析失败也不因成功复跑关闭，整体稳定性仍为 PARTIAL，详见 `SPEC.md`。

真实 Service 的受控续验入口 `realServiceContinuesVerifiedPrefixWithoutReplayingIt` 默认关闭，需 `liveVirtualContinueVerifiedPrefix=true`、`firstKeyIndex`（已完成前缀长度 1–6），以及按执行顺序逗号分隔的 `priorServiceRunIds` 和 `priorServiceAcceptanceDirectories`。首份回执可证明 1–6 键，数量取自本次元数据并与实际 trace／历史逐项核对；仅旧版缺失数量字段时按一键校验。它只接受本固定算式的已核验执行／清理回执链，并逐键重新核对当前行和模型坐标；缺少回执、前缀不符或链外存在后续输入即拒绝。只执行剩余键，不清除、不补点、不自动续跑，也不是产品任务恢复能力；分段完成不能记为一次完整七键通过。

解析失败的首回执还须证明：完整 FAILED 历史以同一 run／step 的 PARSE_FAILED RESULT／RUN_END 结束，失败步没有批准或执行，且恰有一次既有协议纠正。`verifyVirtualThreeKeyParseReceipt=true` 的默认关闭离线入口检查三键回执，并拒绝少报、多报和重复链；不启动任务或联网。

`inspectsRetainedActionParseFailureWithoutDeviceActions` 仅显式 `inspectVirtualActionParseFailure=true`、`parseFailureRunId` 和 `parseFailurePid` 时启用。它只对原进程内最新 PARSE_FAILED 的现存文案归类固定原因码，无法确认文案来源时拒绝；不导出错误／模型原文，不读取截图，不发请求或执行动作。

如果回执链已证明等号执行成功、但测试在结果布局过渡时停止，可单独用 `realServiceObservesVerifiedProductWithoutReplayingEquals` 和 `liveVirtualObserveVerifiedProduct=true` 补做结果观察，并提供同样的完整回执链。它只确认启动计算器，禁止任何点击；使用生产新截图及本地当前行核验后，要求模型只报告结果。普通算式测试的结果读取以生产既有的等待与下一轮截图完成为门槛，不在等号执行回执刚到时读取布局；该观察与原输入任务分开计证。

`VirtualCalculatorModelBudgetProbeInstrumentedTest` 是另行授权、默认关闭的历史 4096 基线诊断。仅 `liveVirtualBudgetComparison=true` 且已核验的首键失败回执、零输入当前值检查及原进程保留截图全部匹配时，才各发一次 HIGH/4096、LOW/4096、HIGH/8192 请求；总计最多三次，不重试、不执行返回动作或工具、不改产品配置。三份请求除思考强度和输出预算外相同；已释放副屏的实时观察不可恢复，使用空观察并明确不属于原网络请求重放。只导出原因码、耗时和解析是否通过，不保存模型原文或凭据，也不证明动作点位正确。该次对照已完成；当前设备动作预算为 65536，原探针的基线守卫会在联网前拒绝，不作为当前版本验收入口。

`VirtualCalculatorUsageProbeInstrumentedTest` 是默认关闭、单次授权最多两请求的 8192 用量诊断；须另行同意截图上传及 API 消耗后使用 `liveVirtualUsageProbe=true`。入口绑定指定失败回执、原进程保留的已核验结果图及其哈希，固定重建最后一步，不读取当前屏幕，也不是原请求精确重放。先测 HIGH，仅输出超限时隔 10 秒测试关闭思考；持久尝试标记禁止重复运行。只输出数值、固定原因码及解析是否通过，缺失用量标为未知；不执行动作／工具、不发送 Service 命令、不改生产默认。统计器合成测试不需要开启联网开关。

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

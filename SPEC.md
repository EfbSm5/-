# RootPilot 当前状态

更新日期：2026-10-31。仅维护当前能力、验收结论和限制，不记录开发过程、逐轮测试、旧连接地址或临时产物流水。使用说明见 [README](<README.md>)；未完成任务见 [TASKS](<TASKS.md>)。

## 当前结论

- RootPilot 是面向已 Root Android 手机的实验性 Agent，不是可靠的无人值守助手。主要验收设备为小米 15，最低 Android 15 / API 35。
- **功能 #5 部分完成**：主屏 Type 进程死亡恢复、提交两侧暂停窗口和未批准时 Service 单独销毁均已有真机限定 PASS。
- **真实通知批准一次 Type 尚未通过**。Pixel 初始化与通知标题／展开适配已本机验证、独立审查。经用户授权原样归档旧失败回执后，单次真实用例已点击通知确认，随后 `NOTIFICATION_EXECUTION_FAILED`；后续同一 fixture 的只读诊断确证零提交。用户后续授权最多 100 次真实通知批准（肯定点击累计 12/100，含两阶段 B 两次），API 原授权累计 100 次另计（已用 6 次：两阶段 Type 2 次、官方搜索验收的搜索与聊天各 1 次、聊天历史手工验收 2 次）；失败必须先查证，不自动重放未知副作用。模拟器已验证修复后的 fixture 跨进程创建与 Service 传输保留输入绑定，但通知栏不会自动收起，候选尚不可直接使用。旧失败证据未改判成功。
- **主屏 Type 两阶段确认在 Medium_Phone 模拟器（API 35，临时 Magisk Root）限定 PASS**：通知 A 仅准备，回到目标且原确切 editor／connection 仍绑定时，IME 显示全文预览，B 最终确认后唯一一次 `commitText`，随后恢复原输入法。fixture 声明 `stateAlwaysHidden`，连接绑定本身不显示面板（v6 只读证据 `IME_LOCATION_ROOT_NONE`、`imeInputViewShown=false`）；生产修复仅在 TYPE_COMMIT、未锁屏且原连接仍绑定时，每个确认令牌调用一次 `requestShowSelf(0)`，令牌、密码框拒绝、单次提交、锁屏取消与恢复保护不变。无模型（固定响应）与真实模型（DeepSeek 官方，`deepseek-flash`）各一次端到端通过：提交 1、文本精确匹配、owner 完成、资源恢复与清理确认；真实模式仅上传签名 fixture 自身画面、禁用工具，委托请求 1 次。首次真实模型尝试因定位等待只要求 IME root 可见、未等布局完成，报 `IME_LOCATION_BUTTON_NOT_FULLY_VISIBLE`（B 未点、提交 0、清理确认），测试改为等待 root 完成布局后仍一次定位，独审 PASS 后复验通过；该根因为合理推断，未取得失败瞬间布局证据。限定：仅模拟器、代理点击（非人工）、单设备单次；Pixel 硬件、其他 IME／厂商、横屏多窗口未验证；不等于旧“单次通知直接批准”合同通过。
- 2026-10-31 用户恢复开发并完成 DeepSeek 官方搜索与聊天历史；经用户授权已按功能提交并推送。提交与远端状态以 Git 为准。
- 最近失败后的产品放弃流程已核实原输入法、无恢复标记／业务 Service 和 fixture 保留，但不补判原 `cleanupConfirmed=false`；五项任务配置完整恢复仍未证实。本轮结束时模拟器已正常关闭（未擦数据），临时 Root 不随冷启动保留；旧地址、PID、fixture tuple 与本机临时证据不能直接作为新会话准入。
- 既有成功只覆盖相应版本及明确场景，不代表当前代码已完成所有真机或真实模型端到端验收。v6 未单独重跑 JVM；聊天页官方联网搜索已实现并在模拟器限定通过（见功能表），聊天历史已实现，验收状态见功能表；Pixel 副屏仅有受限探针证据而非完整端到端通过。

## 能力与验收范围

| 能力 | 当前行为与有效结论 |
| --- | --- |
| 手机直连与配置 | 默认 DeepSeek，支持自定义端点／模型，保存、更换、清除及连接测试；已有手机直连真实模型验收。 |
| 截图与规划 | 截图驱动逐步动作，完整响应经解析、策略及确认后执行；固定测试页闭环通过，模型协议及点位稳定性仍 PARTIAL。 |
| 屏幕上下文保护 | 截图前后、查询前后及动作前核对窗口与目标；测试页采集、输入恢复及确认后切窗拦截通过。不是原子保护。 |
| 只读手机工具 | `get_screen_context`、`get_activity_stack`、可选无障碍 `get_ui_tree`；三工具、生产 Service 完成／停止、运行前手工断连及重连采集限定通过。查询中断连未验。 |
| 应用选择与启动 | 动态目录，仅允许勾选项执行 `open_app`；选择、名称搜索、清空和重启持久化已验，包名搜索待验。不是完整应用隔离。 |
| 主屏 Unicode 输入 | 临时 IME、目标绑定、拒绝密码框、恢复原 IME；固定页真实模型／Service 输入已有成功，正常完成补验仍有 EMPTY_OUTPUT 失败，稳定性未解决。 |
| 独立副屏 | 任务内启动、点击、滑动、BACK／ENTER、等待、栈／树查询、Unicode 及限定非空纯文本编辑；固定页组合与非空输入限定通过，整体 PARTIAL。 |
| 人工控制 | App／悬浮窗／通知入口已实现；脚本调用真实悬浮窗确认／停止监听器通过，不等于真人触摸或通知批准通过。 |
| 文字聊天 | 多轮、四档思考、流式 Markdown、停止、新对话；真实直连、页面交互及 Activity 重建已验，低档完整回答待验。 |
| 聊天历史 | 仅普通聊天已完成问答（含搜索来源，不含思考），每会话一个 Keystore 加密文件，noBackup，最多 30 个；续聊、删除单个、全部清除均需在非生成状态，后两者需确认。用过文件 Agent 的对话不保存，更换 API 配置只开新对话不删历史。独立静态审查 PASS；JVM 通过；模拟器真实 Keystore 存储测试与 ChatScreen 历史对话框测试通过；手工 1 次真实聊天后文件加密落盘（无明文）、强停进程后列表与续聊恢复、确认删除当前会话后文件移除并清空对话。模拟器仪器测试以生产 ChatController／历史仓库＋真实 Keystore 存储（本地假模型、隔离别名与目录、不联网）通过 3 项：第 31 个会话剪除最旧文件且保留 30 个、全部清除删除全部加密文件并关闭当前会话、更换 Key／Base URL／模型各开新会话且旧会话文件保留。另手工 1 次真实聊天后经历史对话框“清除全部”确认，生产目录清空、列表显示为空。配置切换仅仪器测试驱动控制器，未经设置页 UI；Pixel 未验。 |
| 聊天联网搜索 | 仅普通聊天、默认关闭、按会话开启；每条消息先调用一次 DeepSeek 官方 `/v1/tools/search`（5 条、无正文），失败则不请求模型；结果作为本轮不可信参考，来源仅以可复制纯文本展示。仅官方 Base URL 可用，文件 Agent 与设备任务不接入。模拟器真实直连 1 次搜索＋1 次回答通过（5 条来源），答案质量、计费与 Pixel 未验。 |
| 本地待办 | 确认后持久化，同任务防重复；创建、取消不写及重启读取已验。不创建系统提醒，无跨进程 exactly-once 保证。 |
| 结果与历史 | 脱敏状态、阶段、固定失败分类、详情与清除；序列化及旧记录兼容通过，正式非空列表完整视觉待验。 |
| 文件 Agent | 单个 SAF 目录内读写、元信息及有界搜索，每次写入确认、备份和回读；隔离真实模型及 external-storage SAF 限定验收通过，不代表所有 Provider。 |
| 本地文件浏览 | 目录导航、刷新及文本预览，不自动上传；核心列表／预览／差异切换已验，多级实际浏览及大文件待验。 |
| 任务／聊天／设置 UI | Miuix 主题与分层设置；部分明暗、窄屏、大字体、导航与聊天键盘场景已验，组合恢复仍有缺口。 |

## 生命周期验收

| 场景 | 结论及边界 |
| --- | --- |
| 副屏模型阶段／Tap 待确认停止 | 限定 PASS：停止后无新执行，资源释放、配置及 IME 恢复。确认来源为脚本监听器。 |
| 副屏 OpenApp 待确认进程死亡 | 限定 PASS：旧显示／helper 消失，正常重开人工接管、撤回上传授权，不自动重放。 |
| 主屏 Type 未批准时停止／进程死亡 | 限定 PASS：同一目标仍空、原 IME 恢复、恢复需人工授权。 |
| 主屏 Type 已提交且 IME 已恢复后死亡 | 限定 PASS：同一目标固定文本、两参数 `commitText` 计数仍为 1，正常重开不重放。 |
| 主屏 Type 提交两侧暂停点死亡 | 两点限定 PASS：调用前为 0 次提交；成功返回后、IME 恢复前为 1 次。debug 人工扩大窗口，不覆盖函数内部。 |
| Type 未批准时单独销毁 Service | 限定 PASS，见下节。 |
| 无障碍强杀后自动重绑 | 未通过；已有手动重新启用后的恢复证据，不代表自动修复。 |

### Service 单独销毁

真实模型提出 step0 Type、零批准时调用 `Context.stopService`。不以 ACTION_STOP、杀进程或直接销毁 controller 代替。已验证：

- 主 PID 不变，真实 Service 销毁及异步执行收尾、任务所有权释放完成。
- 销毁前旧确认在销毁后和重建后均失效；同一 fixture 实例仍空、提交计数 0。
- 原 IME 恢复，快照保留，历史为 `INTERRUPTED`。取消会新增 `RUN_END`，不能要求完整历史摘要不变。
- 正常恢复入口重建真实 Service，人工接管、撤回上传授权，未重新授权的恢复被拒绝；允许新 Service 正常空闲自停。
- 不重放证据来自完整历史、日志及 1 秒静默窗口，不是网络抓包或无限时保证。

该范围有本机验证、独立静态复审及单次真机 PASS。**不覆盖已提交／清理中的 Service 销毁、真人／通知确认、副屏提交后死亡或其他厂商。**

### 通知确认

- 自动通知批准未通过；无害通知区域采集与严格 PNG 导出已单次通过，仅显示初始紧凑态无可见动作／展开箭头，不证明长按展开或真实批准成功。
- 用户最新授权改为代理点击真实通知“确认”；后续必须记为自动化真实通知验收，不冒称人工点击，也不绕过通知链路。历史人工入口合同及失败证据保留。
- [人工宿主](<dev/type-notification-manual-confirmation.py>)及[Android 入口](<app/src/androidTest/java/com/example/agent/rootpilot/TypeProcessRecoveryInstrumentedTest.kt>)已实现并通过独立审查。通知批准须在 controller 实际成功受理后获得只读证据，精确匹配本次 runId 与被观察通知 Activity 的确认令牌摘要；悬浮先批准后补通知生命周期不能通过。debug 仅保留单个内存摘要，release 不保留观察，不输出明文令牌。
- 合同：真实模型／Service／IME与固定非敏感 fixture，step0 零批准／零提交后开始人工窗口；只自动打开一次通知栏，不自动展开、点击或首次发送批准 PendingIntent。人工窗口 120 秒，新宿主 instrumentation 等待期限 420 秒、异常时最多再等 35 秒收尾；前后置 ADB 每次 15 秒及本地回收另有界，不是全脚本 420 秒硬墙钟。旧自动入口 240 秒不变。
- 通过仍须真实通知 Activity 生命周期、绑定本次确认的通知入口实际成功批准证据、恰好一次批准及提交、同实例精确文本、COMPLETED、旧 PendingIntent 取消反证、原 IME／配置恢复及完整收尾。零自动批准尝试不证明手指来源，须结合用户操作声明。
- 单次人工真机结果 **FAIL**：`NOTIFICATION_UI_DEVICE`，`manualWaitArmed=false`、自动批准尝试 0；宿主 `instrumentation_timeout`，运行器未自行退出。用户未看到通知。批准／提交计数为 -1（未知），不能解释为 0 或通过。
- 已按用户授权仅强制停止 RootPilot 残留运行器；事后确认运行器／主进程／Service 已退出，恢复快照及 IME 恢复文件不存在，原 IME、API 配置、启动白名单及无障碍设置未变。该人工收尾不改变失败结论。未自动重跑；初始化失败的具体原因仍未确认。
- 当前仅授权 Pixel 10 Pro（硬件 `56121FDCH00D30`，API 37、KSU）；新接入设备不在授权范围。App／shell Root 已确认，充电不熄屏已启用。2026-10-28 分字段单次诊断证实 `ACCESSIBILITY_CHANGED` 来自连接期间 `accessibility_enabled` 的 ZERO→ONE，服务列表原始值不变，运行器退出后两项 Secure 原始值恢复。该失败保留，不当作 PASS。
- 初始化最小生命周期修正：全部 UI 清理后，使用公开 `getUiAutomation(DONT_SUPPRESS_ACCESSIBILITY_SERVICES | DONT_USE_ACCESSIBILITY)` 释放无障碍会话，再恢复 Configurator 并严格比较原始状态。释放失败仍阻止通过，不反射、不写回设置、不增加等待或重试。独立静态审查 PASS，针对性 JVM 测试及 AndroidTest 构建通过，Pixel 单次无模型／无截图／无执行初始化 **PASS**，PID、IME、配置、任务及原始无障碍设置保持，运行器退出后再次原样核实。[验收摘要](</private/tmp/rootpilot-pixel-init-release-once-20261028/summary.json>)及[退出后证据](</private/tmp/rootpilot-pixel-init-release-once-20261028/accessibility-boundaries.json>)为本机临时产物。
- 此 PASS 仅覆盖隔离初始化，不覆盖真实通知 Type。两个通知入口已接入生命周期修正并通过本地单测／构建／lint；独立审查首轮发现恢复证据门禁缺口，最小修正后第 2 次静态复审 PASS（Secure 原值未取得或清理失败均不能报告 baselineRestored），并重建 AndroidTest。此结论不是通知 Type 真机 PASS。Pixel 已启用 RootPilot IME（默认仍为原 Gboard），悬浮权限已允许，通知权限原已授予；官方模型配置已独立只读核实，单次真实验收仍未通过。已有本机 8765 转发但 relay 无监听；用户另行授权读取指定桌面 key 文件，官方 models 接口已返回 HTTP 200 且提供 `deepseek-flash`，未将 key 输出或落入测试产物。不得放宽身份、唯一性、隐私、预算或清理断言凑 PASS。

- 仅测试的安全配置入口及独立只读诊断均经独审；前者修正空仓检查与保存间的 Activity 竞态后复审 PASS，不修改生产存储 API。单次内存传输收到 `CHANNEL_TRUNCATED`，临时转发已清理，未重传或覆盖。随后 `inspectSavedStoreReadOnly` 真机固定布尔回执及 JUnit 均 PASS：当前加密配置可读、key 非空、官方地址与 `deepseek-flash` 精确匹配且无已知恢复标记。该证据不把旧 ACK 失败追认为成功，也不证明桌面值逐字一致；传输半关闭导致回包丢失仍为未证实原因。
- 最新真实模型通知用例 **FAIL**：`NOTIFICATION_EXECUTION_FAILED`，`notificationUiClicked=true`、`baselineRestored=true`、`cleanupReasonCode=NONE`。原断言是 run／config／step／status 复合检查；随后仅只读投影本次持久化历史的固定枚举，确认模型成功、step0 TYPE 的 `APPROVAL/CONFIRMED` 一次，随后 `EXECUTION/RESULT/FAILED`、`EXECUTION_FAILED`，历史未截断且源文件 hash 未变。具体执行失败原因仍未知；后续新增独立只读诊断经静态独审 PASS、本机构建／lint 及 helper 12 项验证后，在 Pixel 成功读取与原收据 UUID＋PID 双绑定的同一 fixture：`commitCalls=0`、`fixtureEmpty=true`、`fixtureReady=true`，两份收据字节未变。证明该存活实例此刻没有提交，不改变原失败回执，也未再次批准或重播。[只读计数证据](</var/folders/8y/3vml22gn2k58xx8d1dqhg1dw0000gn/T/rootpilot-pixel-commit-readonly-0jt05cgv/summary.json>)。[固定字段证据](</tmp/rootpilot-pixel-real-notification-archived-20261028-1403/history-fixed-fields.json>)。独立核实 Service／instrumentation 无残留，Gboard／无障碍基线恢复，run／IME 恢复文件不存在。[本机验收摘要](</private/tmp/rootpilot-pixel-real-notification-archived-20261028-1403/summary.json>)。先前标题失败目录经用户明确授权原样移出活跃回执区，文件 hash／owner／mode／mtime一致，未修改失败标记或补成功标记；新失败回执保留。[归档核验](</tmp/rootpilot-receipt-archive-mgl3fdxo/manifest.json>)。

- 无模型通知窗口／IME 探针在 Pixel 单次 **PASS**：基线至 `ACTIVITY_FOCUS` 的 editor／bound connection 身份保持一致；首次在 `ACTIVITY_DESTROYED` 采样变更，并持续至 fixture 返回，当前连接仍有效且目标仍允许，但原严格绑定 guard 会拒绝。该对照证明窗口往返能使绑定失效，不声称读到了原执行错误，也未放宽保护。原失败收据及同实例零提交保持不变，`approvalAcceptance=false`、`inputAttempted=false`，IME／无障碍／通知／UI 清理通过；独立 ADB 核实 Service／instrumentation 无残留、fixture 焦点及 Gboard 恢复。合成 PendingIntent 保持无 token，仅同主线程生命周期内短暂注入并在焦点前移除 dummy，不派发业务批准。3 个测试文件及冻结 host 经独审；1003 项 JVM 测试（新 12 项）与 11 项 host 测试通过。单文件尝试增加 `FLAG_ALT_FOCUSABLE_IM` 经本机验证及独审后，用完全相同探针做了一次对照：仍从 `ACTIVITY_DESTROYED` 起更换 editor／connection，严格 guard 仍拒绝，故修复效果 **FAIL**，不能凭诊断自身 PASS 进入真实 Type。原收据与 fixture 零提交保持，独立收尾检查通过；该无效尝试已在源码精确撤回并通过全套本机验证，设备已仅对授权 Pixel 安装还原 APK 并读回核对完整 SHA256，未清数据；新增批准授权未消耗。用户另已授权调整真实通知交互，避免切走输入窗口，仍保留精确令牌、原 editor／connection 身份和完整清理；新合同尚在设计，不能把旧 Activity 生命周期条件静默删掉就宣称通过。[修正后对照](</private/tmp/rootpilot-ime-window-after-z1h3zik7/summary.json>)。[固定字段探针回执](</private/tmp/rootpilot-ime-window-host-be62kigv/summary.json>)。

- 不切焦点的新批准交互先以 debug-only、非导出专用 Service 做无模型 transport 探针，不启动业务 Service、不读取配置、不批准或输入。5 文件及 host 独审 PASS；首轮发现失败分支清理所有权缺口，最小修复后 24 项纯测试、本机构建／lint／release 清单检查通过，host 22 项测试通过。首次全量 1021 项有 1 项 `DeepSeekStreamTest.errorAndRedirectResponses_doNotReadBodyOrPublish:234` 失败，单独复跑通过不等于全量通过，原因仍未归类。报告区分诊断完成与候选可用，候选要求全部原连接身份保持且恢复操作前实测通知栏自动关闭；不能用 finally 的关闭动作冒充生产能力。Pixel 已安装并由 host 核验审过的 main/test APK；单次真机调用被严格 runner 门禁拒绝，摘要为 `RUNNER_REJECTED`、主原因 `NONE`。原输出未保留，不能区分 runner 格式不匹配与清理导致 JUnit 失败，不宣称诊断或候选可用；不直接重跑。之后独立 ADB 核实业务／Probe Service 与 instrumentation 无残留，fixture 焦点、Gboard、无障碍原值及充电不熄屏正常；不将外部清理检查替代丢失的完整端侧回执。补审确认 host 在完整报告校验前拒绝 runner，会丢失合法清理失败的固定原因；标准 runner 的 LF/CRLF 等合成样本通过，不据此推断本次实际根因。用户额外授权一次无模型取证，须先修复并独审失败信息采集；旧失败目录和 Android 代码冻结。新 host 独审 PASS，32 项纯测试及独立失败反例通过后，执行了用户额外授权的一次无模型取证：报告完整校验通过，但端侧 `EDITOR_READY`、JUnit `-2`，清理原因列表为空；没有诊断／候选通过结论。该码本身覆盖连接等待及初始身份检查；随后静态反证经独立复核：等待失败时 observer 尚未注册，清理必记录 `SERVICE_STOP`，与完整报告的空清理原因矛盾；结合冻结源与 APK 准入一致，本次可排除等待失败，定位到 UI 初始化后 BASELINE 联合断言，具体哪个布尔为 false 仍未测得。不能由此推断 Service 传输效果或扩大超时机制。之后独立核实 Service／instrumentation 无残留、fixture 焦点、Gboard、无障碍原值及充电不熄屏正常。本次完整 transport 额外次数已用完，不直接重跑。用户随后另行授权一次仅输入就绪诊断：先补充分阶段固定布尔证据并独审，不发布通知、不批准、不调用模型、不输入或重置 fixture；该新增就绪取证尚未执行。仅就绪入口的 3 个测试文件已完成独立静态审查 PASS，原 5 秒等待、7 项身份断言与 transport 默认路径保持；新增 18 项及原 transport 24 项纯测试通过，本机构建／lint 通过。host 的 43 字段完整失败证据校验亦已独审 PASS：20 项纯测试、额外 16 个合法失败反例及 runner 状态／深层 JSON 反例通过；仅移除旧草稿多余的清理枚举，不放宽通过条件。两侧静态 PASS 均不代表真机成功；观测可能扰动时序，不代表原故障修复。[冻结材料](</private/tmp/rootpilot-input-readiness-final-ngu013ed/freeze.json>)。设备前置发现当前前台非原 fixture，安装前即停止，未运行取证；用户明确选择暂停设备操作，仅继续静态审查交接。host 执行开关保持关闭，后续取证须用户明确恢复授权。新增真实批准尚未消费。[新增取证摘要](</private/tmp/rootpilot-service-transport-host-reported-78znpmc1/summary.json>)。[失败摘要](</private/tmp/rootpilot-service-transport-host-AIsWgzxL/summary.json>)。[冻结验证报告](</private/tmp/rootpilot-service-transport-baseline-ptr1shsb/writer-report.json>)。

- 2026-10-29 用户恢复设备操作，授权连接更新为 `10.93.142.166:38925`，实测硬件仍为 `56121FDCH00D30`；充电不熄屏开启、业务／Probe Service 与 instrumentation 无残留。旧就绪源码、APK 与 host 冻结哈希全部一致且未生成执行 claim／summary。旧收据仍在，fixture 进程 PID 匹配，但 Activity Manager 全量元数据中已无 fixture Activity／任务；不能以存活进程推定旧页面实例仍可验收。用户另行允许新建独立非敏感 fixture 做一次就绪诊断，旧失败收据保留、不重绑或追认旧证据；采用独立新实例、恢复自有资源后留存非敏感测试页的最小方案，不调用旧的无绑定 finish；同签名 instrumentation 一次创建，独立 2000ms 生命周期握手不改变原 IME 5000ms 等待与七项保护。方案独审已通过，六文件实现闭包的本地 1054 项 app／3 项 fixture 单测、构建和 lint 通过。最终代码独审发现一处旧入口失败证据赋值顺序回归，v2 已仅修复该处；3 项静态回归及重编译／lint 通过，未变单测为 UP-TO-DATE，Android v2 最终独立静态复审已 PASS：唯一回归关闭，源码／delta／APK 哈希与保护文件一致；host v2 最终独立离线复审亦 PASS：用户0／CE解锁／当前锁屏独立准入、固定目录短名 tar 兼容、512 字节 header 前验后读取正文三项问题已修复；普通与 `-O` 各38项纯 mock 测试由 Writer 和 Reviewer 分别通过，精确差异及全部冻结输入哈希一致，原 host 未改。[host v2 冻结](</private/tmp/rootpilot-fresh-readiness-host-v2-nstnls43/freeze.json>)。Pixel 实测 user0 解锁命令与锁屏字段存在兼容差异，最小 host v3 修正及新端口／执行开关 v4 均经独审 PASS。已在核对硬件和解锁后安装经哈希核验的 fixture／测试 APK，运行一次已授权 FRESH 诊断：完整回执有效，但创建握手 `TIMED_OUT`、`FRESH_CREATION`，JUnit `-2`，未进入 BASELINE 或输入等待，未发布通知、调用模型、批准或输入；不能推断超时原因或诊断通过。端侧报告旧收据未改变、IME／无障碍恢复；host 还确认 runner／Service 退出、旧收据字节未变及恢复标记不存在，但新 fixture 绑定未知，整体清理仍为 `UNCONFIRMED`。后续只读检查未发现 fixture 任务、runner、Service 或恢复标记，不代替缺失的所有权确认。不重跑该次诊断；父目录检查与打开仍非原子保证。[诊断摘要](</private/tmp/rootpilot-pixel35261-once-7x0brtm_/summary.json>)。用户另授权一次仅采集启动／回调阶段的诊断，Android 增量与 host 均独审 PASS，本机构建成功，host 普通及 `-O` 各45项合成测试通过；不扩大原保护或等待。首次启动前发现锁屏，运行次数为0；用户解锁后执行一次 instrument，host 等待超时、未取得完整回执或阶段信息，无法确定启动与回调进展，禁止盲重跑。[阶段诊断摘要](</private/tmp/rootpilot-handoff-unlocked-p3x72t64/summary.json>)。静态回查发现阶段状态只在 `cleanupReadiness()` 返回后才发送；host 又只在完整回执校验后保留该状态，因此 runner 若在报告之前卡住，无法从本次摘要判断哪一阶段发生，属于诊断证据缺口，不能据此确定实际卡点。超时后 host 检查时 runner 未退出、fixture 归属及整体清理未确认；同一检查中 Service 不存在、IME／无障碍及恢复标记与旧收据检查通过。用户明确授权后仅强制停止该 Pixel 的 RootPilot 应用，随后只读确认 RootPilot 进程及 runner 不在、Service 和 fixture 任务不存在；这不能补成完整端侧清理回执。针对阶段丢失，新增仅诊断模式的固定进度即时发送和 host 超时前缀白名单保留：不保存原始输出、不以片段推断完整回执或成功；本地 test APK 构建通过，host 普通及 `-O` 各50项合成测试通过；独立离线审查 PASS，另覆盖172项进度顺序、截断和失败关闭检查。经用户新授权的单次真机诊断拿到完整回执：`START_RETURNED`，创建握手 `TIMED_OUT`，JUnit `-2`，未进入输入等待，未调用模型、发布通知、批准或输入；启动请求返回不证明 Activity 已创建，也不能区分页面未启动与本地就绪条件／回调未达成。IME、无障碍、runner、Service、恢复标记及旧收据的 host 检查通过，但 fixture 绑定未知，整体清理仍为 `UNCONFIRMED`。后续只读核查未发现 fixture 任务，进程仍存在，不能证明实例所有权或完整端侧清理。[本次诊断摘要](</private/tmp/rootpilot-handoff-progress-execution-ydaijb55/summary.json>)。状态发送可能扰动时序，迟到事件或报告不完整仍须失败关闭；当前没有实际卡点结论。用户要求先完成离线代码，真正需要真机时再询问，尚未重新操作设备。真实通知 Type 仍未通过。

- 用户另授权一次不调用模型、不输入、不批准的 fixture 生命周期／焦点取证。新增显式测试开关和独立诊断接收器，仅输出固定事件及六项布尔／未知快照，不参与 READY、身份、清理或业务通过条件；原 2 秒和单终态约束不变。Android 本地构建、定向单测及两模块 lint 通过；随后 app 全量单测 1055 项通过，fixture 单测为 UP-TO-DATE，Android 静态独审 PASS；host 普通与 `-O` 各59项纯 mock 测试及独立复审 PASS。最终 APK／host 契约绑定及执行开关微差异均独审 PASS；已在授权 Pixel 安装两测试 APK 并各核哈希，按授权执行一次。完整报告显示 `START_RETURNED`、`TIMED_OUT`、JUnit `-2`，五类生命周期事件均未收到；缺失帧不证明事件未发生，也不能确定启动或焦点根因，同步发送可扰动时序，竞态迟到帧会被 host 失败关闭。静态回查还发现前置检查只证明目标进程存在，并未记录启动时 RootPilot 的前台 Activity／焦点；因此前台状态是未测的变量，不能将后台启动限制当作已证实原因。用户允许后的独立只读快照显示当前 resumed Activity 不是 RootPilot，焦点窗口未能确认；该快照发生在失败之后，不能推定测试当时的前台状态。用户随后允许一次以前台为条件的对照：首次准备时前台转走，在 instrumentation 前停止；调整为先做只读准入再打开主界面，确认 RootPilot resumed 且窗口获得焦点后，使用字节一致的独立执行副本做了一次对照。完整报告仍为 `START_RETURNED`、`TIMED_OUT`、JUnit `-2`，五类生命周期事件均未知，未调用模型、输入或批准；这只证明调用前曾处于前台，不证明整个调用期间前台状态，也不能推定前台与否是失败原因。独立离线复核已在实际冻结 APK 的 DEX 中确认生命周期发送与独立接收代码存在，只排除源码改动未进入 APK 的疑点，不证明该次运行到相应位置。host 确认 runner／Service、IME／无障碍、恢复标记及旧收据检查正常，但 fixture 身份和整体清理未确认；独立只读检查未发现 runner／Service／fixture 任务或恢复标记残留。[对照摘要](</private/tmp/rootpilot-foreground-control-k2iho_az/summary.json>)。未调用模型、输入或批准。报告中 runner／Service 已退出、IME／无障碍设置及旧收据恢复，fixture 身份未确认，故整体清理不能判定通过；独立核查时 fixture 任务引用一度存在、随后自然消失，最终 runner／Service／fixture 任务和恢复标记均不存在，不能以此反推曾经的生命周期或所有权。[取证摘要](</private/tmp/rootpilot-fixture-lifecycle-execution-x2xadls7/summary.json>)。

- 为区分启动未到达与回传缺失，新增仅测试使用、显式启用的 fixture 进程内到达快照，复用原有状态查询，不改变原 2 秒等待或身份、IME、清理保护。Android 冻结增量及 host 契约分别通过独立静态审查；本地 app 1062 项、fixture 13 项单测和构建／lint 通过，host 普通及 `-O` 各 68 项测试通过。这些均不是设备通过；`SEEN` 只证明快照前到达指定准入点，`UNKNOWN` 不能证明没有到达。用户授权的 SDK 35 模拟器上，仅运行不调用模型或真实输入的 `RootPilotServiceStopInstrumentedTest`，2 项通过，测试目录、Service 与 instrumentation 事后检查无残留；没有运行新的到达诊断，也未证明真实通知 Type。模拟器最初的 `su -c` 不兼容，且应用身份执行 `su 0 id -u` 被拒绝，ADB 能力不能代替应用 Root。2026-10-30 用户另授权设置该模拟器 Root：使用核对 APK 哈希和签名的官方 Magisk 31.1 临时 AVD 路线，不改共享 SDK 镜像、不擦除数据。安装在原生 mount 不支持 `--make-private` 时停止并恢复 Android；核实全部本轮残留、namespace／mount ID 后，经独立静态和 mock 审查的单次续接仅用官方 BusyBox 私有化 worker，再执行上游尾部，返回 0。后验 boot=1、zygote running、SELinux Enforcing、Magisk 31100／daemon 存在，ADB 仍为 shell UID。管理器仅为 `com.example.agent` UID 10151 勾选授权；该 UID 的 `su -c id -u` 返回 0。应用进程内生产 `RootScreenObserver` 的只读 instrumentation 在前台条件确认后通过 1 项（完整窗口身份、模型请求 0）；此前一次因启动时权限弹窗导致 `known_foreground_required` 失败，未算通过。Zygisk 默认启用，已关闭配置，但当前 daemon／zygote 运行态尚未停用。独立只读核对 Magisk v31.1 官方源码：配置在 post-fs-data 缓存，单独重启 zygote 不重读配置，重放该阶段也被完成守卫拒绝；没有当前临时 AVD 安装可无损即时停用的官方保证。因此未为此重启、重装或改变 SELinux，保留待可控重启验证的限制。临时 Root 冷启动不持久，manager、`/data/adb` 及本轮 staging 残留保留，不能称完整回滚或真实通知 Type 通过。Pixel 旧无线地址不可用，尚无新的经核实连接。现有人工通知测试入口依赖其原有 fixture 启动和日志检查，不能未经适配直接作为本次安全验收。用户另授权后续尝试 DeepSeek 官方搜索和聊天历史保存，以及最多 100 次 API 调用；截至本记录该授权下调用次数为 0。

- 2026-10-30 新增独立 FRESH ServiceTransport 测试入口，明确不适用旧收据、不伪造旧实例；原 2 秒／5 秒／七项保护和生产批准链保持。五源增量独立静态审查 PASS，app 1071 项与 fixture 13 项单测、构建和 lint 通过。模拟器安装测试包后的首次调用没有有效回执，主进程出现系统 `DEPENDENCY DIED` 退出记录；后续取得同时间 fixture 异常退出元数据，不能将主进程退出归因于安装；恢复并核实新主进程前台后执行一次独立无模型诊断，输出包含 `FAILURES!!!`，动态非 PASS。主代理使用非 raw instrumentation 输出且未保存原文，JSON 报告提取失败，具体失败阶段、原因、fixture 身份及完整清理均 UNKNOWN；不能归因通知、Binder 或 Root，也不据此盲重跑。后验仅确认默认 LatinIME、无启用无障碍服务、无业务及 ProbeService 记录；fixture 进程存在，不代表身份或所有权已确认。[首次采集](</private/tmp/rootpilot-independent-execution-cc4v0u3c/result.json>)、[恢复前台后采集](</private/tmp/rootpilot-independent-execution-cf1enn17/result.json>)。用户已另授权从该授权起最多 100 次真实通知批准，替代旧一次限制；本轮为 tokenless 诊断，真实批准 0/100、API 0/100，分别计数。随后新增单次同签名只读状态入口，经独立审查和本机编译／lint、65 项分类器检查后，在 fixture PID 7379／startTicks 905444 前后连续条件下查询到 `EMPTY`，JUnit 1 项通过；只证明该时刻 current 为空，不恢复旧报告。使用已验证 raw 回执采集的一次新独立诊断得到 `Process crashed.`，系统元数据确认 fixture 先 `APP CRASH(EXCEPTION)`、主应用后 `DEPENDENCY DIED`。用户明确允许自主操作该模拟器后，有界读取该 fixture PID 的 crash buffer，确认 `BadParcelableException: ClassNotFoundException when unmarshalling: com.example.agent.rootpilot.NotificationServiceTransportDiagnosticInstrumentedTest$Probe$createFreshFixture$receiver$1`，发生在 `ExecutionFixtureActivity.java:64` 解包回调对象；跨进程发送了测试匿名子类身份，fixture 无此类。随后仅将发送端两个 ResultReceiver 经 Parcel／框架 CREATOR 正规化，保留原 Binder、Handler、时限及所有身份保护；单源增量独立静态审查 PASS，定向 61 单测、构建和 lint 通过。模拟器初次 Parcel 测试因目标进程不存在，系统处理 `--no-restart` 时空指针退出，未进入测试；启动目标应用后同进程框架 classloader／回调往返测试通过 1 项。修复后单次独立跨进程诊断亦通过 1 项：创建 `READY`、baseline 接受、七个采样阶段均保持原 editor／connection、Service 创建／派发／销毁各一次、Activity 创建／恢复均零，11 项自有资源清理全部通过。本次未复现解包崩溃，不外推 Pixel 同因；但 `automaticShadeClosed=false`、最终焦点仍为 SystemUI，因此 `transportCandidate=false`，不能把诊断通过当作真实通知 Type 通过。fixture 按设计留置，未关闭、未提交输入；下一步须解决通知栏收起且保留绑定和锁屏保护。[Parcel 运行时证据](</private/tmp/rootpilot-framework-runtime-cnr_algs/result.json>)、[跨进程完整回执](</private/tmp/rootpilot-fixed-crossprocess-xr2wjkcx/stdout.txt>)。

- 2026-10-30 既有 fixture 的单次 Root 收栏诊断：测试扩展经独立静态审查 PASS，70 项定向单测、构建与 lint 通过。首次复用主进程运行 120 秒无回执，原因未定；核实输入法和恢复文件后仅停止本应用，保留失败记录及 fixture。正常 instrumentation 新主进程执行后取得完整失败报告：完整 fixture 身份和 baseline 通过，`cmd statusbar collapse` 一次成功、退出码 0，前六个采样阶段保持原 editor／connection；最终焦点回 fixture，但 `sameEditor=false`、`sameBoundConnection=false`，故严格保护拒绝候选，未进行输入、业务批准或模型请求。11 项自有资源清理全部通过；独立后查 LatinIME、无障碍设置恢复、目标 Service／instrumentation 和主进程退出，fixture PID／epoch 未变并保留。不能据此断言所有设备或所有收栏方式均会换连接；不放宽对象一致性或原样重跑。批准与 IME 绑定顺序的调整涉及原批准语义，尚未实施。[完整失败报告](</private/tmp/rootpilot-existing-collapse-freshprocess-scpzilc8/report.json>)、[后置检查](</private/tmp/rootpilot-existing-collapse-freshprocess-scpzilc8/postcheck.json>)。

- 后续无模型结构诊断仅去掉预设标题资源 ID，保留 SystemUI 包、精确自发标题及唯一性；独审 PASS、结构单测 30 项与 host 单测 9 项通过。首次安装后沿用前次测试进程的探针在 60 秒超时，没有完整回执或 JUnit start，资源 ID 仍未确认，未读取结构文件。超时后发现本包 instrumentation 残留，已只停止 RootPilot 进程并独立核实 Service／instrumentation 无残留、原 IME 和两项无障碍设置恢复、已知恢复文件不存在；这不把原失败回执改判通过。[超时摘要](</private/tmp/rootpilot-pixel-structure-title-probe-20261028-1324/summary.json>)。

- 新启动进程的单轮 `diagnoseInitialization` **PASS**，无模型／截图／动作，IME、无障碍与基线恢复通过；随后另一新进程结构探针 **PASS**。这仅证实新环境可完成 runner 与 UI 初始化，不证明旧进程复用就是前次超时根因。结构文件在成功回执及 UUID／PID／完整白名单 schema 校验后读取，确认标题 `resourceName=android:id/title`、`packageName=com.android.systemui`，自有通知行在 depth 6；原验收错误限定 SystemUI 资源命名空间，因此下一步只修正测试侧标题匹配，真实 Type 尚未通过。[初始化对照](</private/tmp/rootpilot-pixel-fresh-init-control-20261028-1318/summary.json>) · [结构回执](</private/tmp/rootpilot-pixel-fresh-structure-20261028-1320/summary.json>) · [脱敏元数据](</private/tmp/rootpilot-pixel-fresh-structure-20261028-1320/structure-metadata.json>)。

- 标题匹配现仅允许 `android:id/title` 与 `com.android.systemui:id/title`，单次查询合并后仍要求唯一，并保留 SystemUI 包、精确自有标题及其余身份保护；972 项单测、测试 APK 构建、lint、81 项 host 单测及本轮独审 PASS。Pixel 修正后的无模型身份探针 **PASS**、完整清理通过，但不等于业务身份匹配通过：其完整 21 节点紧凑态显示 `android:id/expand_button` 可点击，`expandStatus=MISSING`，无应用名文本或描述匹配。暂不重跑真实 Type，下一步仅对无害探针按实证适配展开资源并取证展开后的身份，业务 app/header/PI 保护不变。[身份回执](</private/tmp/rootpilot-pixel-title-fixed-identity-20261028-1330/summary.json>) · [身份脱敏元数据](</private/tmp/rootpilot-pixel-title-fixed-identity-20261028-1330/identity-metadata.json>)。

- 无模型身份探针仅适配两种展开按钮资源命名空间，并保留合并唯一、fresh 同节点／同资源、行归属与最后自有通知校验；981 项单测、测试 APK 构建、lint、9 项 host 单测及独审 PASS。Pixel 单次运行 **PASS**：`expandStatus=CLICKED`，CURRENT 21 节点与 AFTER_EXPAND 26 节点均完整；展开后首次出现 `android:id/app_name_text` 精确匹配应用名，位于 `android:id/notification_header` 下。零模型／截图／业务执行，通知撤销、IME／无障碍／基线恢复全部通过。此为展开结构证据，不是真实 Type 或确认通过；下一步仅将已验证结构接入 Type 前置展开，并在确认动作前保留严格应用身份校验。[展开回执](</private/tmp/rootpilot-pixel-expand-identity-20261028-1340/summary.json>) · [脱敏身份元数据](</private/tmp/rootpilot-pixel-expand-identity-20261028-1340/identity-metadata.json>)。

## 副屏行为边界

- 2026-10-28 Pixel 10 Pro（硬件 `56121FDCH00D30`）单次离线 `platformDeliversSwipeKeysAndAsciiWithHiddenIme` **PASS**（12.364 秒）：固定签名 fixture 副屏 ASCII、BACK、ENTER、滑动均回读成功，键盘隐藏，主屏 Activity／原 IME／启用输入法及无障碍服务列表不变；副屏释放及 fixture 消失确认。零联网，不代表生产模型、副屏截图或 ServiceIME 完整链路通过。[本机临时证据](</private/tmp/rootpilot-pixel-virtual-platform-once-20261028/instrumentation.txt>)。

- 默认关闭；每次任务创建 1080×1920、320 dpi 独立显示，绑定本次显示／会话。单步每次新建、关闭，不保留跨单步页面。
- 支持启动、点击、滑动、BACK／ENTER、等待；HOME、LOCAL 键盘、尺寸／旋转、任务外截图尚未开放。平台探针可行不等于产品支持。
- 非空编辑要求完整已知旧文及合法选区；旧文、输入及最终文本均最多 128 UTF-16 单元，拒绝拆分 surrogate、敏感节点、hint／未知文本及目标变化。
- 使用一次 `SET_TEXT` 重建纯文本、至多一次 `SET_SELECTION` 恢复光标，不切 IME；不保证富文本、composing 或撤销栈。提交不确定不重试、不重放。“已写入但未确认”是保守提示，不是回读成功证明。
- 查询临时读取标志在锁内恢复；恢复未确认时当前服务实例拒绝后续主／副屏树读取。显示释放未确认时保留恢复记录并阻止新任务。
- 非空输入四项离线真机及三项真实 Service 用例限定 PASS；固定综合动作与计算器完整七键亦有限定 PASS。主屏按钮取焦后副屏计算通过，不代表键盘焦点完全隔离或真人双屏并行输入。
- 既有 Activity 可能迁入副屏，不是应用实例、账号或数据隔离。

## 工具、输入与数据合同

- 信息工具仅接受固定空参数，每步最多 3 次、每任务最多 12 次；Activity 最多 32 项，UI 最多 200 节点／深度 32／每字段 120 字符，结果最多 256 KiB。未知、超限或服务不可用不假装为空树。
- 系统观察共用 3 秒协程预算，Activity dump 上限 512 KiB，其余窗口／显示／IME 各 256 KiB；同步 IPC 不保证硬实时退出。未知窗口、目标变化或上下文不符时停止，不补点或回落主屏。
- 动作请求采用 LOW／65536；完整单个 JSON 解析、固定工具协议及既有一次协议纠正不放宽。预算不保证成功，诊断只记录固定原因与数值用量，不保存模型或异常原文。
- Token 经 Android Keystore 加密存入私有 noBackup；不得进入源码、日志、快照或测试产物。任务恢复不等于凭据恢复。
- 上传截图、屏幕元信息、工具结果及应用目录需同意；恢复时撤回上传授权。敏感节点过滤不保证普通页面所有隐私均被识别。
- 打开应用、输入、系统按键及待办创建始终确认；点击／滑动在手动模式确认。主屏输入最多 128 UTF-16，绑定编辑框，结束恢复 IME；保留 `FLAG_SECURE`。
- 停止请求不等于执行退出；收尾前不释放占用，不撤销已发生副作用，不自动重放。快照存储失败阻止继续执行。
- 历史独立于恢复快照，最多 50 次任务、每次最后 256 条事件，不提供重放；未落盘尾部可能丢失，不是可靠审计凭证。
- 联网搜索查询取用户消息前 128 个 Unicode 码点，响应上限 256 KiB；只保留无凭据的 http(s) 来源，标题／摘要等字段截断。搜索上下文不进入后续轮次，停止或搜索失败的轮次不进入历史，不自动重试。
- 聊天最多 100 条消息；单次输入 16000 字符、已完成上下文 64000 字符，单请求 120 秒。配置变化开始新对话，Markdown 不自动加载远程资源。
- 聊天历史每会话一份 AES-GCM（Keystore 别名 `rootpilot_chat_history_aes_v1`）文件，单文件 8 MiB；临时文件写入并同步后原子替换。读取校验版本、UUID 文件名与内容一致、角色交替及来源数量；失败的文件不自动删除，只计为无法读取。最多 30 个会话，超出删除最旧；保存失败不影响当前对话。清除只删除本存储命名的文件。进程被杀时最后一轮可能未保存。

## 文件 Agent 合同

- 目录授权与内容上传同意分开；默认关闭，重启或保存／清除 API 配置后需重新开启。本地浏览不上传，切目录／撤销／切换模式清空聊天。
- UTF-8 text/*、JSON、XML；单文件 64 KiB、单目录 100 项，每次请求最多 8 轮／16 次调用；备份最多 5 MiB 或 1000 项，满后拒绝新备份，不自动删除。
- 搜索为区分大小写字面量，无索引：深度 4、遍历 500 项、总读取 256 KiB、最多 20 结果，查询 128 UTF-16、片段 160 单元。不完整结果不能证明无匹配。
- 只读工具失败以固定原因回传模型继续；写入失败／拒绝／冲突终止。不可解析参数仍终止；可解析但缺参／多参时按只读／写入分类处理。
- 写入逐次确认，提交前重验身份与原文，编辑前备份、写后回读；精确替换要求旧文唯一。SAF 不提供跨应用原子事务，停止不等于撤销，不自动重放。
- 仅支持可验证目录从属关系且获得读写 grant 的 Provider，无删除、建目录、Shell、Root、网络搜索或手机动作工具。
- external-storage 的多级中文搜索、stat、截断、取消及真实 UI 切目录旧结果拦截已限定通过；其他 Provider、规模／并发与完整文件 UI 仍待验。只读失败语义已本机验证及复审，未重新做该语义的真机文件 Agent 回归。

## 已知缺口

- 模型输出超限、空输出、协议失败及点位拒绝仍有真实失败证据，整体稳定性 PARTIAL；成功复跑不等于根因修复。
- 真实时钟中文搜索在预置页面后业务限定通过，但自动测试 fixture 关闭回执失败，已手动收尾；系统设置搜索未完成，应用商店仅只读预检通过。
- 屏幕检查非原子，不能排除同窗口内容变化或 A→B→A；系统 dump 格式不是稳定 SDK。保屏、锁屏／后台冻结及未知前台问题未全面解决。
- 生命周期剩余缺口：`commitText` 内部、副屏提交后、Service 已提交／清理中销毁、查询中断连、真人悬浮确认及通知恢复组合。
- GKD／其他服务业务共存、活跃多显示、横屏／多窗口／工作资料、其他厂商／IME 未完整验收。UiAutomation 独占占用不能靠关闭其他服务绕过。
- 25 项 Compose UI 用例默认门控跳过，**不是 UI 断言通过**；显式启用仍有 ServiceLoader／宿主启动基础设施限制。完整默认 androidTest 集合未证明全通过。
- 大字体＋键盘＋生成停止、设置恢复、凭据编辑防截图生命周期、长 Markdown 与历史非空列表视觉仍有缺口。Miuix 为实验性依赖，悬浮窗／IME／旧 Agent 未迁移。
- 历史误点调查暂缓，不增加未经证实的坐标补偿。旧 Agent、LiteRT-LM／Demo 不是默认生产路径。

## 验证口径

当前工作区本机验证：951 项 JVM 测试零失败、零跳过；宿主 mock 普通／`-O` 各 116 项通过，含人工入口各 20 项；debug App／androidTest 构建、Lint 及差异检查通过。20 项人工生命周期及 3 项实际 controller 批准来源竞争测试包含在 JVM 总数内；release Kotlin 编译通过。宿主独立审查及 Android 最终静态复审 PASS；不等于真实通知／IME 验收通过，当前真机失败见通知确认一节。

静态审查、JVM／宿主 mock、构建／Lint、安装和真机结果分别计证；跳过、超时、缺回执、清理未确认不能算通过。旧版本证据不自动绑定新工作区。

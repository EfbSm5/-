# RootPilot 当前状态

更新日期：2026-10-05。生产代码基线：`9b5954f`；设备动作现为 LOW／65536。独立副屏固定计算器用例已取得一次真实 Service 单任务完整七键、5535 结果、模型成功结束及清理的限定 PASS；整体规划稳定性及更广验收仍为 PARTIAL，既有输出超限与点位拒绝不因单次通过而视为修复；提交与推送状态以 Git 为准。

本文件只保存最新状态，直接更新对应条目，不追加开发过程、历次测试数字或补验流水。使用与构建说明见 [README.md](README.md)，协作规则见 [AGENTS.md](AGENTS.md)。

## 目标与边界

RootPilot 是已 Root 的 Android 手机上的实验性 Agent：根据截图、系统上下文与模型规划执行有限界面动作，并提供文字聊天、受限文件 Agent 和本地待办。首次保存 API 配置后可手机直连，不依赖电脑 Relay。

- 手机任务需要 Android 15 / API 35 及以上和 Root；纯文字聊天不需要 Root。主要验收设备为小米 15，不承诺跨厂商兼容。
- 不按任务关键词、应用名称或固定坐标编写导航特判。
- 支付、发送消息、删除、账号与密码等敏感场景交由人工；现有保护不是对模型识别准确性或无人值守安全的保证。
- 旧 Agent、LiteRT-LM 与 Demo 后端保留为实验入口，不是 RootPilot 默认运行路径。

## 当前能力

“已验收”仅指表中明确范围，不表示所有异常分支通过，也不表示每次改动后已重跑全部真机用例。

| 能力 | 当前行为 | 验收状态 |
| --- | --- | --- |
| 手机直连与配置 | 默认 DeepSeek；保存、更换、清除、测试连接，保留自定义端点与模型 | 手机直连已有真实模型验收 |
| 截图与动作规划 | 每轮最终一个动作，可先查询只读工具；参数、步骤、重复帧／动作校验，全屏归一化坐标 | 真实模型／Service 测试页完成用例通过；响应协议失败根因未定位，稳定性 PARTIAL；真实应用搜索闭环未通过 |
| 屏幕上下文保护 | 采集前台应用／Activity、焦点窗口、键盘状态；截图前后及动作前核对 | 小米测试页采集、中文输入恢复、确认后切窗拦截及真实模型／Service 组合通过，限定范围见下 |
| 只读手机信息工具 | native function calling 查询上下文、当前任务 Activity 堆栈及可选无障碍 UI 树；查询前后核对窗口，最终动作仍经原策略与确认 | 三工具、生产 Service 完成／确认停止／查询停止、运行前手工断连后的不可用处理与重连采集通过；查询中断连、通知及稳定性仍 PARTIAL |
| 应用启动与选择 | 动态应用目录，按勾选包名过滤，启动前复核入口与最新选择 | 核心选择、名称搜索、清空、重启持久化已验；包名搜索待验 |
| Unicode 输入 | 临时输入法支持中文、emoji、换行；绑定编辑框，拒绝密码框并恢复原输入法 | 安全测试页已有真实输入验收；新增窗口保护下中文输入与恢复已验 |
| 人工控制与悬浮预览 | App／悬浮窗／通知确认；紧凑面板、停止、流式思考与动作草稿预览 | 生产 Service 实际确认／停止监听器通过，来源为脚本本地 View 点击；不代表真人触摸或通知入口 |
| 文字聊天 | 多轮、四档思考强度、流式 Markdown、停止、新对话 | 真实直连、页面交互及 Activity 重建已验；低档完整回答待验 |
| 本地待办 | 确认后创建并持久化，同任务防重复，不创建系统提醒 | 真实模型创建、取消不写入、重启读取已验 |
| 任务结果与历史 | 区分模型报告和本地回执；脱敏历史、详情、清除、跨进程读取；模型失败含固定分类／HTTP 状态／协议原因 | 历史与分类序列化测试通过，旧记录兼容；非空正式列表完整视觉待验 |
| 文件 Agent | 单个 SAF 目录内列出、读取、元信息／有界搜索、创建、精确编辑；逐次确认、原文备份与导出 | 隔离真实模型核心已通过；实际 external-storage SAF 多级中文检索、stat、三类截断、取消与真实切目录旧结果拦截已通过；不代表所有提供方 |
| 本地工作区浏览 | 子目录／上级／刷新／文本预览，Agent 关闭时可用；写入确认显示有界差异 | 列表、预览、差异切换等核心用例已验；多级真实目录与大文件待验 |
| 任务／聊天／设置界面 | Miuix 统一主题，主页精简，配置与调试收进设置，键盘适配 | 当前明暗／窄屏大字体及导航部分已验；聊天键盘手动已验，恢复与组合场景仍有缺口 |

## 当前工作：只读手机信息工具

首期开放三个只读工具，在原截图规划循环内获取更多证据。控件树采用用户确认的可选无障碍服务，手动启用；不增加节点点击／输入、任意 Shell、常驻 UIAutomator 或 Shizuku 通道，不调整防截图策略，不恢复历史误点调查。生产 Service 与真实模型仅在专用测试页限定验收。

### 信息工具行为

- `get_screen_context` 返回前台包名／Activity、焦点包名及键盘状态；内部采样必须与原目标一致。
- `get_activity_stack` 仅解析默认显示下当前前台任务的 Activity 组件，保留 top-to-bottom 顺序及任务 ID；不上传 Intent extras、路径、窗口标题和其他任务。最多 32 项，不表示 Fragment／Compose 导航或确定的 BACK 去向。
- `get_ui_tree` 按需读取当前应用的公开语义节点；RootPilot 自身拒绝读取。树所属窗口必须具有输入焦点、位于默认显示、与节点窗口一致，采集结束重新核验身份；未知或改变时返回 unavailable。最多 200 节点、深度 32、每文字字段 120 字符；返回文字、描述、提示、资源 ID、物理像素位置与交互状态。密码／敏感节点及其后代文字过滤，不等于所有普通页面隐私均可识别。
- 工具只接受单个固定名称和空参数 `{}`，每步最多 3 次、任务最多 12 次，不占动作步骤；结果最多 256 KiB。收到 native tool call 后回传对应 ID 并保留 assistant reasoning，完整最终 JSON 仍经原 ActionParser、ActionPolicy 和人工确认，没有 tool-to-executor 直通。
- 查询前先移除模型预览悬浮窗，再比较原窗口及键盘；查询结束再次比较，不符合则不发送结果。结果包含来源、观察 ID、采样起止时间、available／unavailable、固定原因码及截断状态。无服务／超时／未知格式不是空树；不能据缺失节点推断控件不存在。
- 上传同意覆盖新数据；查询数据仅驻当前规划步骤的内存历史，不进入任务快照、持久历史或日志。日志只增加固定信息阶段、工具枚举及原因码。服务不保存事件或执行无障碍动作，未启用仍可用截图。
- Activity 固定诊断独立 3 秒协程时限、512 KiB 输出上限；取消清理进程及流。UI 树有 3 秒协程与遍历期限，但同步系统 IPC 不能保证硬实时退出。查询与截图不是原子采样，窗口检查不能发现所有 A→B→A 或同窗口内容变化。

### 信息工具验证状态

- 本地：最终检查 26 秒通过，465 项 JVM 测试失败／错误／跳过均为 0；App 与测试 APK 构建通过；lint 0 errors／29 warnings，`git diff --check` 通过。新增类型化失败来源、协议固定原因、旧历史兼容与安全日志测试；既有工具协议、窗口／输入绑定、取消及检索预算测试保持通过。生产与测试代码独立静态复审 PASS；运行验收按以下范围区分，不以静态结论替代。
- 生产 Service：小米 15（Android 17）上真实 DeepSeek → native tool calls → RootPilotService → 本机全屏截图／Root／IME 完成用例 PASS（16.562 秒）。要求先读三工具、一次固定中文 Type、输入后读树、模型完成，以及原输入法恢复、恢复记录与任务快照无残留。确认来自实际安全悬浮窗的脚本 `performClick`，不代表真人／物理触摸、通知或真实应用流程。全屏只在固定测试页前台并获同意后采集，不能把 fixture 的 View-only 通道证据误当本用例。
- 停止：实际悬浮窗停止监听器在输入待确认时 PASS（6.941 秒），零输入；Activity 查询启动后发送真实 Service 停止命令 PASS（2.309 秒），要求停止早于信息回执、无后续模型请求、零执行及恢复无残留。停止命令成功本身不算通过，以事件顺序、终态与清理断言为准。
- 手工断连／重连：用户仅关闭 RootPilot 页面结构读取后，真实 provider 返回 `not_enabled`／无数据，真实 Service 获取不可用回执并继续模型结束 PASS（3.764 秒），零输入；用户重新开启后，不联网三工具采集 PASS（1.364 秒），核验焦点、Activity、标题、可编辑框及自身拒读。仅覆盖运行前已关闭与重新开启后的独立查询，不覆盖查询中的 Binder 断连／重连。
- 共存与前置：GKD／Cumulus 保持连接，仅用户操作本服务授权；`--no-restart` 不杀目标进程，`Crashed services` 为空。首次从系统设置后台启动 fixture 未就绪，未到模型，不计业务 PASS；显式前台启动固定测试页后才完成断连用例。不添加启动、等待或重试兜底；未验证 GKD 业务或所有共存模式。
- 最新证据：仓库外 `/tmp/rootpilot-production-acceptance.Z4tUxH/` 的 `checks-final.txt`、`service-protocol-classified-retry.txt`、`service-confirmation-stop-reviewed.txt`、`service-query-stop-final.txt`、`service-manual-disconnected-ready.txt`、`information-manual-reconnected.txt`。生产 Service 不依赖 fixture 模块；专用测试通道不成为正式产品入口。

### 系统计算器真实应用补验（2026-10-04，PARTIAL）

- 仅新增默认关闭的 `CalculatorLiveAcceptanceInstrumentedTest`，生产代码不变。真机上的生产 APK 与本地 APK SHA-256 同为 `742e6d8aac075790f5381fcc2688eabd6931e03a52e348d51fba312b06194061`；测试 APK 构建及独立静态复审 PASS。未重跑上一轮全部 JVM／lint；默认无开关运行时两项均按预期跳过，不计业务 PASS。
- 本轮发现 RootPilot 页面结构读取仍显示启用但处于故障。经用户授权，由 Computer Use 在正常系统设置中仅关闭／重开 RootPilot，随后系统已绑定且 `Crashed services` 为空；GKD、Cumulus、截屏服务保持连接。没有关闭投屏或通话浮窗，不启用文件 Agent，也不修改工作区授权。
- 只读计算器结构预检 PASS（稳定页 0.274 秒）。本题按钮资源 ID、标签与屏内坐标可读，树仍标记 `truncated=true`，不代表整页完整；启动过渡期间曾读取到屏外坐标，稳定页再采样恢复正常。本轮没有坐标补偿，也不恢复历史误点调查。
- 后台环境：原“智能限制后台运行”下，计算器到前台后 RootPilot 停滞，主线程 `wchan=do_freezer_trap`，而 ActivityManager 报告 `isFrozen=false`；小米 `greezer` 历史明确包含 RootPilot UID 10478 的冻结及随后执行 Service 时解冻。原用例经 STOP 和前台唤醒后取消，未批准任何数字点击，清理通过；不能归因为无障碍、投屏或第三方工具。
- 经用户授权，通过正常系统设置仅将 RootPilot 电池策略设为“无限制”，重新打开页面确认保存；未开启自启动，未调整全局冻结策略或其他服务。同一测试代码单次复跑 8.25 秒，OpenApp 执行成功、模型 UI 树查询成功，并进入首个 Tap 待确认；该时段 `greezer` 冻结列表不含 RootPilot。仅说明本次没有重现后台冻结，不证明长时稳定性或所有环境已修复。
- 当前阻塞：待确认阶段本地校验未找到唯一 `expression` 节点（预期 1、实际 0），测试在首个数字点击前失败并安全停止，零数字／运算符批准、零 Tap；未取消或放宽守卫。trace 为 OpenApp 成功 2944 ms、UI 树查询成功 6076 ms、Tap 待确认 7634 ms、STOP 7914 ms、取消结束 7920 ms。清理断言未失败，原输入法、配置和启动列表恢复，IME 恢复记录与任务快照无残留。
- 停止后不联网只读预检 PASS（0.295 秒），同一计算器页面重新读到算式 `0` 与预期按键，树仍 `truncated=true`。失败时未保留节点摘要，尚不能确认是悬浮确认窗口影响可见性、采样时序还是测试假设不适配；没有据此新增等待、坐标补偿或修改生产读取逻辑。数字点击、结果 5535、模型成功结束及真人触摸确认仍未验收，下一步是限定调查确认阶段的节点缺失。
- 证据：原冻结用例在 `/tmp/rootpilot-calculator-acceptance.qtDLRB/`；当前对照在 `/tmp/rootpilot-calculator-unrestricted.f5f2uB/`，包括 `battery-reopened.png`、`calculator-live.txt`、`calculator-trace.txt`、`calculator-controls-after.txt`、`calculator-after-stop.png` 和 `greezer-after.txt`。独立静态复审 PASS 不替代本轮真机失败／PARTIAL 结论。

### 独立虚拟显示屏 PoC（2026-10-04，限定通过）

- 独立于 RootPilot 的有界试验，不调用生产模型、不上传截图到配置的模型 API，不修改生产代码、输入法、全局系统设置、其他无障碍服务或 `FLAG_SECURE`。使用现有 scrcpy 4.1 创建 1080×1920／320 dpi 临时副屏，关闭音频、剪贴板自动同步和键盘转发；不清除计算器历史或其他数据。
- 创建／采集／点击通过：本次临时逻辑 displayId 为 872，SurfaceFlinger ID 为 `11529215046508557997`，均只记录本次证据、不得复用。计算器启动于该屏；ADB 截图显式绑定其 SurfaceFlinger ID，七次点击显式绑定逻辑 ID，并逐次根据最新画面核验 `1 → 12 → 123 → 123× → 123×4 → 123×45 → =5,535`。Computer Use 无法定位独立 scrcpy 进程为桌面 App，实际验证的是显示屏定向 ADB 输入，不是 scrcpy 客户端鼠标注入或 RootPilot 自主点击。
- 主屏限定证据：先明确返回桌面建立基线，每次副屏点击后 display 0 始终为同一个 Launcher Activity。计算前后主屏 1200×2670 图像排除已目视核对的状态栏和时钟区域后，变化像素为 0；未见副屏点击误落主屏或计算器跳回主屏。不包含用户同时物理操作、双屏同时输入文字或所有焦点切换情形。
- 已知边界：原计算器 Activity／task 19084 从主屏迁到副屏，并非独立应用实例或账号／数据隔离。副屏操作时主屏 `mCurrentFocus=null`、计算器窗口获得焦点，因此不能将“画面未被抢占”推广为键盘／全局输入焦点完全隔离。退出按 [scrcpy 默认销毁策略](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/virtual-display.md) 移除临时副屏及其中 Activity，已确认不迁回主屏；未操作退出时迁回选项，也未清除应用数据。
- 证据在 `/tmp/rootpilot-virtual-display-poc.ELZLaV/`：`scrcpy.txt`、`virtual-result.png`、`activity-{before,created,after-calculation,closed-settled}.txt`、`window-{created,after-calculation,closed-settled}.txt`、`main-comparison.txt`、`displays-closed.txt` 及明确区分事实／限制的 `verification.json`。主屏对照截图仅用于本地验证；该 PoC 成功不替代上一节 Agent 用例的失败结论。
- 经方案确认，已添加实验接入：Service 保留任务、模型和逐动作确认，由私有管道连接的本机 Root helper 持有副屏会话；截图、启动、动作坐标、窗口观察及确认共同绑定 displayId／会话身份，屏幕消失或目标跨屏即停止，不回落主屏。默认主屏路径和现有 `UiTreeWindowSnapshot` 的 display 0 限制保留；副屏使用独立严格观察解析，仅开放屏幕上下文工具。

### 副屏产品接入验证（2026-10-04，PARTIAL）

- 默认关闭，显式选择允许启动列表中的起始应用。任务持有新会话，恢复只保存模式／起始应用，清除上传同意，不保存或复用副屏 ID。副屏仅支持启动、点击、等待，强制逐动作确认；任务外截图及单步禁用。
- 每个 Root helper 使用私有匿名管道和固定协议，无外部监听端口、任意 shell 参数或凭据。截图来自自己持有的 ImageReader；输入和启动显式绑定显示 ID，不改变全局输入法，不绕过 `FLAG_SECURE`。EOF／取消／有界 watchdog 负责结束；只有释放回执、进程退出和专用显示消失核验完成后才能报告关闭成功。释放未确认不能被停止或放弃恢复记录绕过。
- 独立静态审查 PASS。已修复审查确认的跨屏切换保留上传同意、释放异常未关闭全部管道、停止与收尾终态竞态；真机还确认副屏 IME 输出会包含独立 token 字段和重复 null，改为精确字段且全部一致才判隐藏，缺失或冲突仍未知。后者及 Service 验收脚本另经窄范围独立审查 PASS。`build-11.txt` 的单测 517 项零失败，Debug／androidTest APK 和 Lint 均通过，`git diff --check` 通过。静态通过不替代运行验收。
- 不联网底层限定通过：创建／正常释放及幂等关闭（`transport-2.txt`）；取消后释放、会话失效（`cancellation.txt`）；Type／Swipe／Key 拒绝，UI_TREE 不支持，关闭后截图／点击拒绝，输入法不变，主屏执行代理调用数为 0。算式跨两次受控测试完成：首次仅执行数字 1，随后因历史行与当前行重复资源 ID 被测试拦截；只读检查确认当前行为 1 后，续跑核对原回执且仅执行其余六键，逐键状态匹配并在当前行读到 5535（`calculator-remaining-six-3.txt`、`calculator-offline-result.png`，11.438 秒）。不把分段续跑表述为一次完整七键闭环，也未清除历史。
- 真实 Service／模型分段输入已完成：原 `service-live-2.txt` 的 run `d7ccd115-6ac3-4145-bc46-04815757f309` 执行数字 1；经过逐段执行／清理回执和新鲜当前行核验，`service-continue-2/3/4.txt` 分别执行 2、3、以及 ×／4／5／=，均来自真实模型、实际悬浮窗监听器和生产执行链。最后一段 run `8ba5d073-90d9-4dd2-b024-3b913cafaa9d` 为一次 OpenApp 加四次 Tap；没有清除、重放数字、重按等号或替模型补点。停止后的零输入 `inspection-final.txt` 通过（12.685 秒），`calculator-final-readback.png` 目视确认当前行 `123×45 = 5,535`，上方历史行不作为结果证据。分段输入成功不等于单任务七键闭环通过。
- 独立真实模型结果观察通过：`service-observe-product-2.txt`，run `f5f10b4d-7b3a-4803-891d-2a8176311c8d`，2.961 秒；仅一次 OpenApp／一次实际悬浮窗确认、零 Tap。本地在新生产截图和当前行核验 5535 后，模型报告 5535 并成功 Finish，执行数和清理断言均通过，结果图为 `service-result-confirmed.jpg`。前一次相同零点击观察于 112.059 秒以 NETWORK 失败；等待 10 秒后重试成功，不据此认定网络故障已修复。
- 仍存在失败：`service-continue-1/2.txt` 的待批准坐标不在下一个指定键内，被测试拒绝；第二段拒绝前仅数字 2 已执行。`service-continue-3.txt` 执行 3 后再次出现 `response_protocol / output_limit`。最后一段等号执行成功后约 57 毫秒，测试读取当前行抛出 `CURRENT_ROW_UNAVAILABLE` 并在 SETTLE 阶段停止，未发出下一轮模型结果观察请求。针对这次已确认的采样时序问题，仅把测试结果读取放到生产既有等待／下一轮截图完成之后，并增加完整等号回执链限定的零点击模型观察入口；不增加生产等待或重试。
- 单任务完整重验仍失败：`service-full-1.txt`，run `da1f5778-1a02-4f91-b8b4-b3c0a7b5e6f2`，24.956 秒。从已完成算式重新开始，真实执行一次 OpenApp 和首键 1，下一步模型响应再次 OUTPUT_LIMIT，未执行其余六键；原分段结果观察不能替代此用例。随后 `inspection-after-full.txt` 零输入检查通过（12.656 秒），最新当前行为 1，不是此前的 5535。
- 已获授权的三次零设备动作对照完成（`budget-comparison.txt`、`budget-comparison-metadata.json`）：HIGH/4096 为 `response_protocol_output_limit`（18.314 秒），LOW/4096 同样超限（17.033 秒），HIGH/8192 为 `none` 且动作解析成功（18.894 秒）；三次 HTTP 均为 200，总测试耗时 74.312 秒。返回动作／工具均未执行，未发送 Service 命令，未创建副屏或更改配置；请求前后原状态／历史及无专用副屏断言通过。三次授权已用完，不自动追加请求。
- 对照使用原进程保留的真实生产副屏截图和重建的固定 step 1；除思考强度／预算外上下文相同，但原副屏实时观察已释放，未伪造观察，故不是原网络请求的精确重放。每组仅一次，支持继续验证输出预算方向，不足以认定根因已解决、坐标正确或整轮稳定；该轮未改生产 HIGH/4096。探针独立静态复审 PASS（源码 SHA-256 前缀 `010039aec713`），包含延迟后紧邻发送的状态复核及关闭响应前取消请求；`build-budget-probe-reviewed.txt` 的测试 APK 构建／Lint 通过，安装包哈希与产物一致。只记录固定原因码、耗时和解析结果，无模型原文或凭据产物。
- 经用户确认，设备动作请求的普通／工具决策两条路径均将输出上限改为 8192；HIGH 思考、时限、失败处理及动作确认不变，聊天／文件 Agent 不受影响。两处生产修改及三处测试修改的独立静态审查 PASS；全量 518 项 JVM 测试零失败／错误／跳过，App／测试 APK 构建和 Lint 通过（`/tmp/rootpilot-budget-8192.i4VUbh/build.txt`，38 秒），真机安装包与构建哈希一致。
- 8192 真机续验仍为 PARTIAL，证据位于 `/tmp/rootpilot-budget-8192.i4VUbh/`：首次 `service-continue-1.txt` 在截图阶段失败（6.052 秒，run `15071ca7-0c7b-40a9-adfb-5ad9b93415de`），零模型请求、零 Tap，仅启动后安全释放；不能归因于新模型预算。确认无后续输入且清理通过后仅重试一次，`service-continue-2.txt`（145.886 秒，run `315d1a6b-776e-4c31-8723-79e4f31e1084`）沿原首键回执执行剩余六键：本地新鲜当前行和生产截图均确认 `123×45=5535`，不是上方历史行；模型最后的结果观察耗时 34.720 秒，仍以 `response_protocol / output_limit` 失败，未成功 Finish。
- 上述 8192 续验的固定 trace 独立回读确认一次 OpenApp、六次 Tap 的开始／成功成对回执、七次确认，六次动作模型请求成功、最后一次结果请求失败；测试因终态失败未进入整体成功断言，`exactExecutionReceipts=false` 不改写为通过。两轮 RUN_END、显示消失、输入法不变、原配置／启动列表字节恢复均通过；没有重放首键、清空历史、重按等号或修改点位。`calculator-result.jpg` 与 `continue-2-metadata.json` 保存结果证据；只完成了旧首键＋新六键的分段计算，未追加完整七键重跑、进一步预算对照或生产修复。
- 2026-10-05 离线排查：`OUTPUT_LIMIT` 在工具流解析器中只由服务端 `finish_reason=length` 触发，本地单事件／总字节／累计字符上限使用 `STREAM_LIMIT`。新增三组回归确认：完整动作 JSON 遇到 length 仍拒绝；本地大小限制不会误报输出超限；用量统计附在正常 stop 终帧时可正常完成。独立静态审查 PASS，全量 521 项单测零失败／错误／跳过、Lint 通过（`/tmp/rootpilot-output-limit-diagnosis.NeT3GM/checks.txt`，22 秒）；此离线阶段未改生产代码／APK、未追加真实 API 请求。
- 归因边界：[DeepSeek Chat Completions 契约](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)说明 length 可能来自输出或上下文上限，并提供输入、输出和思考 Token 用量。上述失败发生时生产解析器忽略 usage，失败状态会清空流式预览；原请求未保留这些统计，不能事后确认是哪类内容耗尽额度。请求已有 JSON 指令及样例，每步独立建立工具上下文；本次 trace 无信息查询，不能归因为前六步思考原文累积。
- 经授权完成零设备动作用量诊断，仅使用 1/2 次额度：`VirtualCalculatorUsageProbeInstrumentedTest` 校验上述 step 6 失败回执和原进程保留的结果图（SHA-256 前缀 `85a75634263e`），重建最后一步的固定输入，未包含已释放的实时窗口观察，不是原网络请求的精确重放。HIGH／8192 在 1.926 秒以 HTTP 200／`stop` 正常结束，输入 2556、输出 281、其中思考 246、合计 2837 Token；思考 817、正文 81、正文空白 1 个 UTF-16 字符。动作与 Finish 类型解析通过，但不导出原文、不检查成功标志或结果文案，不能作为 5535 成功 Finish 的语义验收。第一请求未超限，按约定未发送关闭思考的第二请求；该授权不自动转为其他测试。
- 新用量探针的独立审查先发现多份 usage 可能混用旧思考计数，最小修复为每份 usage 独立赋值、缺失即未知并补回归；第一轮独立复审 PASS（源码 SHA-256 前缀 `9050bb9acf92`）。测试 APK／Lint 21 秒通过，四项设备端合成测试 PASS、联网入口默认跳过（不是五项业务通过）；live 单用例 1.984 秒 PASS 只代表诊断执行及前后原状态／历史不变、无专用副屏断言通过。零设备动作、零工具派发、零 Service 命令，不读取当前屏幕、不改生产配置。尝试标记保留且仅有 `request-1.attempted`，禁止重跑复用授权；证据为 `/tmp/rootpilot-output-limit-diagnosis.NeT3GM/{probe-build-reviewed.txt,usage-offline-reviewed.txt,usage-live.txt,usage-metadata.json}`。同一硬件与原 PID 27018 保持，只更新测试 APK，产品包未变。一次不复现不能关闭原超限问题，也不足以支持关闭生产思考或继续抬高预算。
- 本轮测试补充：默认关闭的固定算式续验校验 run 身份、前序关联、连续前缀、逐 step 批准与成功执行回执、环境清理和链外后续输入；错回执、过期回执、前缀多报、未完成等号却请求结果观察的负向检查均在启动／联网／输入前按预期拒绝。续验、回执链及最终结果采样／零点击入口的独立静态审查均 PASS（最终源码 SHA-256 前缀 `a48c3c2a4f03`）；`build-prefix-test.txt`、`checks.txt`、`build-observation-test.txt` 构建／Lint 检查通过，未变生产代码的单测任务为 UP-TO-DATE，回读报告 517 项、零失败／错误。普通完整用例尚未运行到修正后的等号观察门槛，不据零点击入口通过宣称其运行通过。
- 上述 4096 基线 Service 失败收尾限定通过：RUN_END、专用副屏消失、输入法不变、原配置及启动列表原字节恢复均由真实用例断言确认。失败请求采用 HIGH 思考与 4096 输出预算，`finish_reason=length` 映射为上述固定原因；未保存模型原文、放宽解析或修改生产重试策略。执行期间主屏仍观察到 RootPilot Activity，未验证用户同时操作主屏。
- 真机前置已恢复：同一硬件 serial `12cd0365`／型号 `24129PN74C`，初次安装的 App／测试 APK SHA-256 均与 build-11 产物一致，后续仅更新测试 APK，最新哈希见下文。经用户明确授权，使用 ADB 在正常系统界面开启 RootPilot 页面结构读取并确认系统风险提示；未直接写权限配置。组件名规范化比较确认仅新增 RootPilot，GKD／Cumulus 等既有服务未增删，快捷方式未开启。服务连接且无崩溃；只读预检通过，当前值为 0 后才进入联网验收。用于读取设置树的临时工具未能连接已有 UiAutomation 会话，未终止该会话，最终使用本地截图确认操作目标；远端临时工具已移除。
- HIGH／8192 阶段的历史边界：当时尚未完成真实模型单任务完整七键／结果再观察闭环；该固定用例的最新通过证据、当前预算与 APK 见下节。该阶段六键规划走通，后续一次重建请求正常结束，不能据此认定输出超限已解决，当时未继续提高预算、关闭生产思考、放宽解析或自动重放。进一步归因不能用成功请求的统计倒推原失败。当前仍待规划稳定性、主屏同时操作、执行期间主动停止／进程死亡及更广的厂商／应用兼容性；已有等待确认和 SETTLE 阶段的测试主动 STOP 与清理证据，不替代所有中断时机。多次底层观察曾出现前台／焦点未知并安全停止，原因未定位；后续成功不证明该问题已消失，IME 字段修复也不作为它的归因。该阶段续验及对照证据在 `/tmp/rootpilot-service-continuation.VgkMSI/`；原首键／权限证据在 `/tmp/rootpilot-virtual-reacceptance.WSOk8l/`，此前底层／构建产物在 `/tmp/rootpilot-virtual-integration.dvokKX/`。历史 8192 产品 APK SHA-256 为 `ea28c41dd4d4ecef68b628ac56f4597396714babcfa674b2c43f259786851928`；当时用量探针测试 APK 为 `a6faaca38c974b8a66f745a0b8412135f7c630f24f3da1a06c660f5cd92f13b8`，两者当时均与安装包哈希一致。该阶段预算调整后仅补诊断测试及验收文档，未提交或推送。

### 当前设备动作预算与 LOW 验收（2026-10-05，固定用例 PASS／稳定性 PARTIAL）

- 用户授权设备动作两条请求路径使用 LOW，并随后要求把输出上限从 8192 提到 65536，与 [DeepSeek 普通思考模式默认预算](https://api-docs.deepseek.com/api/create-chat-completion/)一致；不是 MAX 档位。普通／流式动作共用 builder 及工具决策路径均设置同一上限，纯聊天／文件 Agent 不变。保留原时限、解析、策略、确认与执行；预算变大不保证不超限、点位正确或任务成功，LOW 档位仍在验收中。
- LOW／65536 完整用例 PASS：`full-seven-64k.txt`，run `d948974c-71d1-4301-8286-2bf711efa870`，61.774 秒。无前序回执链，从初始完整结果行开始独立输入七键；一次 OpenApp＋七次 Tap 的开始／成功回执成对、八次真实监听器确认，`firstKeyIndex=0`、`sevenKeysApproved=true`。逐键命中与当前行核验通过，等号后新生产截图为 123×45=5535，模型下一次请求期间已独立读到当前结果，随后成功 Finish；RUN_END、副屏消失、IME 不变、原配置／启动列表及保屏标志恢复。持久化历史 66 项未截断事件、八份真实模型用量；本轮最大输出 5498 Token，未跨过旧 8192 上限，因此不能把本次成功归因为预算扩大。执行前同版本另有仅续跑剩余四键的 21.880 秒 PASS（run `816bef2e-0014-4d06-b31c-aa3e3236f355`），该轮单独计证，不合并成完整七键。
- LOW／8192 受控续验限定 PASS：run `de1bed6c-8a56-4778-9edb-f290aa85b431` 只接续已证六键后的等号，9.430 秒；run `92b6ae86-fe40-4db1-8a2e-3b409e0fdacb` 接续已证首键后的六键，90.508 秒。两次均为真实生产 Service／模型／Root 执行，当前行和结果图为 5535，模型成功报告、精确执行回执、RUN_END、副屏消失及 IME／原配置／启动列表恢复通过；后者在下一模型请求期间已观察到结果。分别持久化 18／58 项未截断历史事件，模型用量 2／7 份。确认是脚本调用真实监听器，不是真人触摸；分段成功不计作单任务七键通过。
- 保留失败：旧测试任务在首键后出现 `MODEL_REPORTED_FAILURE`，保留图为当前 1、历史 5535；仅在内存中提取固定文案标记，支持“初始状态约束可能被继续套用”的假设，不证明模型内部原因。仅澄清测试任务中初始条件与中间态的区别，未增加生产导航特判。澄清后完整入口先在首键前被 `TAP_NOT_ON_EXPECTED_KEY` 拒绝（17.881 秒，零 Tap），等 10 秒后的唯一重试 run `33f9d7b1-7143-46ad-995d-792759c1e2bf` 执行 1、2、3 后再次被该校验拒绝（40.717 秒）；乘号未确认／未执行。另一次六键续验也有零 Tap 点位拒绝后才成功的记录。各轮停止与清理均已确认，不因成功复跑抹除失败，不补点或放宽坐标范围。
- 测试续验首回执扩展为元数据所证的 1–6 键，仍核对持久化历史中的逐步开始／成功／确认、末尾步骤、身份及链外无后续输入。除模型超限或模型明确失败外，仅额外接受测试点位拒绝后在确认阶段取消的回执，要求固定失败码、STOPPED、停止标记及同一步 Tap 的 WAITING→STOP_REQUESTED→RUN_END；未确认动作不能计入前缀。默认关闭的一键／六键／三键离线入口分别验证真实数量并拒绝少报、多报、重复链；不联网、不执行动作，不作为生产自动恢复能力。
- 预算、测试回执和任务文案增量独立静态审查 PASS。65536 首次全套检查发现流式测试仍期望 8192，修正该期望后 `build-64k-final.txt` 30 秒 PASS：实际重跑 531 项 JVM 单测，零失败／错误／跳过，App／测试 APK 构建及 Lint 通过。`git diff --check` 通过。主产品 APK SHA-256 `c57d08bf3c41b2313676c1f3717dc02b971bee672002198bc448709a4a33e52b`，测试 APK `3422ebe0614103784060934b1eb6320751324cea47546b5c227374775f4841c5`，与授权小米设备安装包匹配。
- 分批交付复核：最终生产源码及验收测试／文档分别独立静态审查 PASS。模型诊断／预算批次的独立 index 快照通过 479 项单测、App／测试 APK 构建与 Lint；叠加副屏核心后另一独立快照通过 531 项单测及同样全套检查，均零失败／错误／跳过。加入八个默认关闭的验收测试后，测试 APK 构建与 Lint 再次通过；该批未改生产或 JVM 源码，不重复计单测执行。暂存树与验证快照一致，日志在 `/tmp/rootpilot-push.noaR68/batch{1,2,3}-build.txt`。本次提交整理未重新联网调用模型、安装或操作手机，真机结论沿用上文已绑定源码的证据。
- 本节证据位于 `/tmp/rootpilot-low-service.zVjJjB/`，包括 `full-seven-64k-{metadata.json,trace.jsonl,history.json,result.jpg}`、三份离线回执检查及构建输出；仅保留固定字段日志／历史、元数据及授权计算器图，不含模型原文或凭据。最后只读复核无专用副屏和任务／IME 恢复文件。仍待点位稳定性、历史首帧异常归因、主屏同时操作、执行中停止／进程死亡和更多厂商／应用兼容性；既有单次成功不替代这些验收。历史 4096／8192 探针绑定旧现场，不能当作当前预算入口；其余旧包与预算记录仅为历史证据，当前以本节为准。本次交付按模型诊断与预算、副屏核心、验收测试和文档分批提交，提交／推送状态以 Git 为准；历史误点诊断源码及 `log/` 保留本地，不纳入提交。

### 副屏停止、主屏隔离与进程恢复补验（2026-10-05，定向用例 PASS）

- 本轮只修改默认关闭的 androidTest 及专用签名测试页，不改生产行为。授权小米 15，硬件 serial `12cd0365`；本轮无线 ADB serial 仅用于当时连接，后续重新发现。已安装生产 APK SHA-256 仍为 `c57d08bf3c41b2313676c1f3717dc02b971bee672002198bc448709a4a33e52b`，不能把测试包更新称作产品修复。
- 经用户明确授权，通过 ADB 将 `system screen_off_timeout` 从 `120000` 改为 `2147483647`，Settings 与 PowerManager 均回读一致，无设备管理上限截断。该值约 24.9 天，不是真正无限；全局充电保屏仍为 0，未修改密码、锁屏保护或其他无障碍服务。后续仍观察到 `deviceLocked=1`／`showing=true`，设置值未回退；保屏不等于解锁，不绕过锁屏。
- 两个真实 Service 停止用例限定 PASS：`stops-reviewed.txt` 合计 14.504 秒，模型阶段 run `95231ac5-1cec-4483-80db-910dfea309f6`，首个 Tap 待确认阶段 run `cad27a49-24af-4f2e-84ec-c4068d7a8fc0`。分别调用真实悬浮窗“停止”监听器，均仅批准并执行一次 OpenApp、零 Tap；确认 STOPPED、对应 MODEL／APPROVAL 阶段取消 RUN_END，停止后无新截图、执行或模型请求，专用副屏消失，IME 不变、原配置及启动列表字节恢复。模型阶段证据不证明 HTTP 已到服务端或服务端计费已取消；监听器调用不是物理触摸。另有修复前模型停止 2.413 秒通过记录。
- 保留测试失败：首轮待确认停止 `stop-approval.txt`（9.908 秒）报 `TERMINAL_NOT_STOPPED`，后续持久化回执为 APPROVAL 取消、STOPPED 且零 Tap，清理均通过。测试仅等 RUN_END，没有等异步历史 Finish 状态；仅把历史脱离 RUNNING 纳入原有 5 秒条件等待，不新增生产等待、重试或兜底。修正后上述两个模式均通过。
- 主屏隔离入口默认关闭：专用签名测试页仅在 opt-in 下显示无内容计数按钮；在副屏首键、乘号、等号待确认期间，请求 host 各执行一次显式 display 0 点击，并回读按钮计数和焦点恢复，再沿原有新鲜副屏节点／待确认动作校验继续计算。主屏测试页不截图上传；不是真人同时操作或键盘焦点完全隔离证明。独立审查后统一 fixture／浮窗的屏幕坐标、检查 RootPilot 浮窗遮挡；测试页关闭失败仍继续有守卫的配置恢复；待确认停止允许合法只读查询增加模型轮次。主屏点击前仍要求测试页 resumed／可见／display 0，host 核验其 `mFocusedApp`，当前焦点只允许为空或同测试页，不放宽生产副屏守卫。
- **主屏点击＋副屏计算限定 PASS**：`isolation-retry.txt`，run `a3e4b4b3-3c15-4197-a106-455e2625244d`，46.377 秒。主屏按钮计数精确增加三次；副屏同一任务完成一次 OpenApp＋完整七次 Tap，八次真实确认监听器、九次模型请求，70 条未截断历史事件。新鲜当前行、生产副屏结果图及模型 Finish 均为 `123×45=5535`，下一模型请求期间已独立观察结果；副屏释放、主屏测试页关闭、IME 不变、原配置及启动列表字节恢复全部通过。`mainFocusLostBeforeTap=true` 证明主屏焦点确会切换，不能称全局／键盘焦点隔离。结果图已目视确认白色当前算式，不把灰色历史行算作结果。此前同入口 `isolation-main-focus.txt`（115.264 秒，run `2b251439-a185-45ca-9e49-63a77bdfde4d`）在首轮模型以 NETWORK 失败，零 Tap、零主屏点击且收尾通过；间隔超过 10 秒仅重试一次，成功不证明网络问题已修复。
- 保留前置失败：`isolation.txt` 在 0.049 秒以 `UNEXPECTED` 退出，尚未发送 Service 启动命令、创建副屏或请求模型，host 未执行主屏点击；测试页关闭也未确认，但原配置和启动列表已恢复。后续固定阶段定位到 Provider state 访问的 IllegalArgumentException；仅增加完全匹配的固定原因码，不输出异常原文，不据泛化的旧 `invalid_command` 标签归因。锁屏时的一次预检直接拒绝。`isolation-provider-specific.txt` 随后已正常启动测试页，但在首个 Tap 前被要求主屏已持有焦点的测试条件拒绝（9.446 秒，零 Tap）；结合同机已有主屏焦点为空／顶层 App 未变的证据，将焦点取回纳入上条受控点击流程，保留顶层身份和遮挡保护。先前 Provider 访问失败未单独证明根因消失，后来的通过不抹去失败记录。
- 新增默认关闭的 `VirtualDisplayProcessRecoveryInstrumentedTest`，采用主进程被 host 终止前后两个真实进程入口：准备阶段只创建副屏并等待 OpenApp 确认，零执行／截图／请求；验证阶段要求正常 Activity 重开后人工恢复状态、上传同意清空、不自动执行、旧副屏/helper 消失，以及未重新同意时 RECOVER 被拒绝。私有一次性 receipt 绑定身份与原启动列表备份，不复制凭据；只有验证通过才正常放弃本测试快照并还原。ready 期间持续检查原副屏及 helper，准备失败先作废 receipt；host 持续接收失败状态，紧邻终止进程再次校验身份和有效期。
- **待启动确认时进程死亡／恢复限定 PASS**：`process-recovery-final.txt`，run `71ce97ac-8abf-4acc-9025-8b3b8a21d1e3`，receipt `6946ea18-628e-4d12-b3d9-bf978aa6b6d0`。host 精确终止原主进程 PID 15708，正常启动 Activity 后为 PID 24046；旧 helper PID 29636、专用 display 996 消失。进程退出前的 instrumentation crash 是预期中断，不算 PASS；复开后的独立验证阶段 1.549 秒 PASS。真实状态为 RECOVERY_REQUIRED，pendingAction／frame 清空，上传同意为 false，原历史仅 RUN_START／OpenApp WAITING 两条并标记 INTERRUPTED；零执行／截图／模型请求。主动发送未重新授权的 RECOVER 被正常拒绝，之后仅放弃本测试快照并复原基线；原 API 密文摘要、启动列表字节和 IME 核验不变。没有主动终止 helper、force-stop 整包或操作其他服务；副屏释放没有跨进程管道回执，只以存活／显示消失和持久化状态计证。本用例不覆盖已执行副作用重放、Type 待确认死亡或实际 IME 切换中断。
- **收尾手动恢复 PASS，自动重绑仍未通过**：进程死亡后正常重开，连续只读检查发现 RootPilot 无障碍仍在 enabled，但列于 Crashed services、未绑定；GKD／Cumulus 仍绑定。该状态未包含于上述定向测试的基线恢复断言，不能称无障碍自动重绑通过，根因未定位。用户再次授权继续后，只用重新核对硬件 serial／型号的显式 ADB 与新鲜本地截图，通过正常系统页面关闭再开启 RootPilot 服务并完成已有权限的风险确认；未写无障碍授权列表、未启用快捷方式、未更改其他服务。恢复后 RootPilot 实际绑定，Binding／Crashed 均为空，启用服务列表与恢复前字节一致。
- 不联网功能复验 `readsAllThreeToolsFromFocusedFixture`（`--no-restart`）2.346 秒 PASS：屏幕上下文、Activity 堆栈与控件树均成功，固定测试页标题／编辑框及自身应用保护断言通过，零截图／输入／模型请求。末尾测试页已关闭，主进程仍为 PID 24046，RootPilot／GKD／Cumulus 保持绑定，IME 不变，无专用副屏和任务／IME 恢复文件；息屏设置仍为 `2147483647`，设备未锁定。本轮未改代码、未构建或安装，测试及 fixture APK 哈希与上述最终版本匹配。证据位于 `/tmp/rootpilot-a11y-restore.F4lX3h/`，包括本地设置页截图、服务状态及 `offline-three-tools.txt`；此恢复不证明强杀后自动重连问题已解决。
- 收尾视觉工具曾发生设备绑定错误：`connect --deviceId` 已明确选择小米，但后续 `act` 自动选中另一台已连接设备，日志记录了其屏幕读取与模型请求尝试；模型请求及工具内置的一次重试均返回 502，未进入点击步骤。当时立即停止该路径和后续设备操作并向用户说明，不能声称未发生额外屏幕读取／提交；此次工具失误不计作 RootPilot 功能结果。工具报告／截图保留在仓库外的原临时目录，不查看非目标画面、不纳入提交。后续恢复经用户授权仅使用显式绑定的小米 ADB 和本地截图，未再次调用该工具，也不将改用 ADB 称为工具绑定问题已修复。
- 恢复准备失败证据保留：`process-recovery.txt`（1.106 秒）以 `HELPER_IDENTITY` 停止并恢复基线，host 未收到 ready、未终止进程。活跃隔离用例中只读采集固定 ps 字段，确认本机为 `app_process /system/bin ENTRY UUID`；仅增加严格四参数形状，仍拒绝包装命令、错误路径、额外参数和 session 不符。4 正／14 反的固定文本解析入口 0.029 秒通过，`build-recovery-shapes.txt` 26 秒构建／Lint 通过，解析增量独立复审 PASS，不等于恢复功能通过。
- `process-recovery-reviewed.txt` 已到零动作 ready，但 App UID 读取系统 uptime 被拒，host 未执行 kill；准备阶段随后于 46.453 秒以 `HOST_KILL_TIMEOUT` 安全收尾，基线恢复通过，receipt 被作废。核对无任务／IME 恢复文件后，只将该测试 UUID 的完整失败目录移入同 App 私有 archive，保留备份和作废证据，不伪造 completed。host 改为固定 root uptime 读取、继续核对设备侧期限；零副作用 signal 0 检查另确认 run-as 发信号受限，因此最终 root 信号须额外核验目标 App UID、唯一包名 PID、私有 ready 身份／hash／未作废标记、期限和专用显示/helper，且只终止原主进程。失败时持续消费测试输出并等待原有有界收尾，不继续重开／验证；日志仅报固定失败码。
- 构建证据：`build-reviewed.txt`（24 秒）、`build-main-focus.txt`（27 秒）及 `build-final-stable.txt`（29 秒）通过测试 APK、fixture APK 和两者 Lint；诊断迭代亦有独立成功构建。本轮 JVM 任务为 UP-TO-DATE，不重复计作新跑 531 项；构建／安装不等于真机通过。日志、固定字段历史、计算器结果图和 host 驱动保存在 `/tmp/rootpilot-isolation.lgf0wf/`，不提交截图／临时脚本或原文。
- 停止／隔离测试、fixture、诊断增量、恢复测试与 host guard 分别经独立静态复审 PASS，真机证据单独计证。最终恢复测试 APK SHA-256 为 `5d88069c03ced6480a7051555af51602c17872960c29b89e78f094472f607acc`，fixture 为 `f8fcbb15382ce41e32b49c89b4c5f5f19d197f92b961b177788f986a686929e1`；隔离通过时测试 APK 为 `45e2f348f1173b82a23db85f6d172d7fa6bb21e95164cd84da2fd51f65998fdd`，之后只改恢复 helper 解析及其固定文本用例，隔离路径未变。用户授权后，验收代码单独提交为 `85f1a40`，本节文档另批提交，推送状态以 Git 为准；提交前测试／fixture 构建与 Lint 检查成功，主要任务为 UP-TO-DATE，不计作新真机或单测证据。原有两份历史坐标诊断和 `log/` 保持不动，不纳入提交。

### 真实 Service 用量诊断（2026-10-05，静态 PASS／真机 PARTIAL）

- 经用户确认，将数值诊断接入设备决策真实请求：在现有工具流解析器中旁路统计已解析片段的思考／正文／空白 UTF-16 字符数，提取服务端输入／输出／思考／总 Token 及固定结束原因。每次请求独立；缺失 Token 保持未知，多份 usage 整份替换，畸形或矛盾数据标记异常并清空 Token，不改变响应判定。仅该次 MODEL RESULT 进入现有日志及历史，成功／失败均可携带，不写恢复快照，不存原文或凭据；旧历史无该字段仍可读。纯聊天、文件 Agent 及旧单步动作路径不采集。HIGH／8192、提示词、时限、解析／确认／执行和失败策略均未调整。
- 六个生产文件及四个测试文件独立静态审查 PASS。新增 10 项测试覆盖超限终帧、畸形／缺失用量、多帧不串值、协议拒绝不变、真实本地 HTTP 传递、循环成功／失败透传、失败零动作、历史落盘及旧事件兼容。531 项单测零失败／错误／跳过，App／测试 APK 及 Lint 通过，`git diff --check` 通过。首次完整构建 52 秒，补齐循环与历史测试后的检查 31 秒；并发／取消隔离及满额历史容量仍主要依据静态检查，不冒称新增专项真机验证。
- 首轮完整七键验收未走到模型：`service-full.txt`，run `203ae342-37e4-4350-8a53-8c08212aa53c`，5.888 秒。真实确认并成功执行一次 OpenApp，首张截图于累计 5606 ms 失败，5745 ms RUN_END；固定 trace 为 `SCREENSHOT_FAILED`，零模型请求、零 Tap、无 `modelUsage` 样本。初始当前行及图片均未核验，不能沿用旧图认定当前仍为 5535；整体成功／完整七键断言未通过。此前曾出现同类截图阶段失败，本轮未改变截图路径，但现有证据不能确认两者底层同因或归为本次回归。
- 该轮失败后未立即重试、未新增模型消耗，副屏消失、RUN_END、输入法不变、原配置／启动列表字节恢复均通过；无残留任务及输入法恢复文件。用户随后授权自主处理本 App 范围内的必要改动，继续下节有界、不联网、零算式点击的截图诊断，不据失败自动增加等待、重试或回落主屏。最新实体解锁复验已取得真实成功／失败用量及历史落盘证据，见下文；模型为何持续思考及完整 Service 闭环仍未关闭。
- 证据位于 `/tmp/rootpilot-service-usage.3wtR0o/`：`build.txt`、`build-final.txt`、`service-full.txt`、`service-trace.txt`、`service-metadata.json`。同一硬件 serial `12cd0365`／型号 `24129PN74C`，更新安装后 RootPilot 无障碍正常连接、GKD／Cumulus 未改动；产品 APK SHA-256 `43e20f0d01dc48152c6e64ea32a3d070a5fb72df7a57ce88f02eb3e72031771c`，测试 APK `9b9a2f500e07225fdd2b79fcd3cd0bbe009a0882a293c908fb8d7f2406132bec`，均与设备安装包匹配。改动未提交或推送，其他在途修改保持原样。

### 副屏首帧诊断（2026-10-05，真机 PARTIAL）

- 新增默认关闭的 `VirtualDisplayCaptureDiagnosticInstrumentedTest`。保留态只读核验确认上述 Service 失败为 `vd_capture_failed`、无保留帧。离线入口使用生产 Session/Helper 创建独立副屏、打开限定计算器、截图一次并最终释放；不调用模型、Service 指令、Tap 或配置写入，不保存或上传图片，只输出固定原因码、数值和布尔字段。
- 首次新鲜基线 `offline-capture-baseline.txt`：创建／启动成功，截图前副屏状态为 OFF（1），4004 ms 后无图像，固定失败 `vd_capture_failed`；资源清理及原 IME／Service 状态不变。支持首帧供给受阻，不足以单凭时长定位厂商电源策略。
- 一次明确 opt-in 的自有副屏电源对照 `offline-capture-power-probe.txt`：身份与非主屏 ID 校验后，只向本次自建副屏发送一次 `cmd display power-on`，root 端 2 秒限时、外层等待 4 秒并核验退出码。命令退出 0，副屏 OFF→ON（2），74 ms 获取 83168 字节 PNG；未保存图片、未联网。但聚合环境不变断言失败，整例 FAIL，不能宣称副屏独立性通过。该版本聚合了 IME、Service、主屏状态和锁屏，无法追溯是哪项变化；前后外部观察全局 Hangup→Awake，也不能证明由该命令导致。未将电源请求接入生产，未追加启用请求。
- 拆分观察字段后执行一次不带电源请求的对照 `offline-capture-no-power-expanded.txt`：副屏仍 OFF，4002 ms 无图像；输入法及 Service 状态不变，主屏起止均 ON，清理确认，但解锁前置在测试末尾变为锁定，整体 FAIL `environment_changed`。随后只读系统策略确认 `showing=true/inputRestricted=true`，专用副屏无残留；停止设备操作，不自动解锁、不改锁屏／全局电源设置。前一轮聚合失败是否同样来自锁屏仍未证实。
- 测试独立静态审查初次发现命令退出码和超时退出证据不足，修正后 PASS；最终又将“未发命令”与“未确认退出”区分，最终源码 SHA-256 前缀 `84b3687e5ee4` 复审 PASS。保留态读取 PASS 不等于截图或业务通过；三次离线截图中只有电源对照取得图像，三轮均无模型请求和算式点击。生产截图／协议／时限／重试／配置均未改动，历史误点诊断未恢复。
- 证据在 `/tmp/rootpilot-capture-diagnosis.Cb3q25/`。最终 `checks-final.txt` 全套检查 29 秒通过，App／测试 APK 构建及 Lint 成功；单测任务为 UP-TO-DATE，回读原报告 531 项、零失败／错误／跳过，不计作本轮重新执行。`git diff --check` 通过，诊断记录独立复核 PASS。同一 serial `12cd0365`／型号 `24129PN74C`／API 37，仅更新测试 APK，最终 SHA-256 `a0f90469d3c7c1136afb8fa99ab0d2b4811f6f9236c33b6395c7eac4cc95f111` 与设备安装包一致；主产品包仍为上一节 `43e20f0d...`。后续需先解锁并保持亮屏，重新确认干净副屏的无电源干预首帧，再继续真实 Service 完整计算验收；不把一次可供帧对照当作生产修复或系统影响已排除。

### 实体解锁复验与测试窗口保屏（2026-10-05，首帧 PASS／完整 Service PARTIAL）

- 最新实体解锁条件复验：用户确认手机已解锁亮屏，同一硬件 `12cd0365`／型号 `24129PN74C`，系统 `showing=false`。沿用已审查／已安装的测试包与 `holdRootPilotScreen=true`，`offline-capture.txt` 3.599 秒 PASS：副屏创建后与截图前均 ON，219 ms 获得 83168 字节 PNG，前后未锁定、环境不变、IME／Service 不变、资源清理及窗口标志恢复通过。零模型、零 Tap、零电源请求／配置写入，没有保存或上传该图像。只证明本次条件下首帧供给正常，不能认定旧失败根因已修复或归因于小米互联。
- 首次完整入口被 `ACCESSIBILITY_UNAVAILABLE` 前置拒绝（1.705 秒，未启动任务、未联网、零输入）。只读检查确认 RootPilot 不在已启用服务中。沿用此前用户授权，通过正常系统设置界面恢复 RootPilot 页面结构读取及风险确认；组件名规范化后仅新增 RootPilot，GKD／Cumulus 保持连接，无崩溃，快捷方式未启用。UIAutomator 导出退出 137，未读取旧 XML，改用本地截图定位；未直接写系统权限配置、未向额外模型发送设置画面。
- 随后完整入口 `service-full-enabled.txt`（108.398 秒），run `f2f503f5-1cd2-4afe-91b3-d8d4f744b913`，整体 FAIL `TERMINAL_NOT_COMPLETED`：真实 Service 成功执行一次 OpenApp 与六次 Tap（1、2、3、×、4、5），七次真实悬浮窗监听器确认；七张生产副屏截图成功，八次模型请求中七次成功、最后一次失败，包含一次成功的只读屏幕上下文查询。逐键新鲜节点校验通过，但等号未执行、5535／成功 Finish 未观察，不将既有结果图或命令回执充作本次结果。
- 真实失败诊断已获得：step 6 请求耗时 34437 ms，`response_protocol / output_limit`、`finishReason=LENGTH`，输入 2614、输出 8192、其中思考 8192、总计 10806 Token；思考 27519、正文及正文空白均 0 个 UTF-16 字符，`usageMalformed=false`。确认该请求在思考阶段耗尽上限而未产生正文，不是已有正文被动作 JSON 解析拒绝；不由数值推断思考内容或无限循环原因。成功／失败八份用量均在真实 trace 和持久化历史匹配，历史 62 项事件未截断。没有保存模型原文或凭据。
- 失败收尾通过：RUN_END、副屏消失、IME 不变、原配置及启动列表字节恢复、保屏标志恢复；外部只读复核无专用副屏和恢复文件，设备仍未锁定。未重放前六键、补按等号、提高预算或关闭生产思考。现有续验入口首份回执限定为恰好一键，不能直接拿本次六键回执冒充旧链继续；若续验需先适配并独立审查真实前缀证明。官方[思考模式契约](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/)支持 LOW，但不保证它一定不会超限。
- 用户另行授权最多两次零动作诊断后，新增默认关闭的 `VirtualCalculatorEqualityEffortProbeInstrumentedTest`：绑定本次固定 run／六键回执、原 PID、最新内存日志尾与持久化事件、保留帧和环境不变条件，复用生产请求构造及用量采集。固定 noBackup 授权目录与请求标记防复用；只将同一重建请求改为 LOW／8192，仅仍 `output_limit` 才在 10 秒后关闭思考对照。没有当前屏幕读取、Service 指令、返回动作／工具派发、配置修改或原文落盘；已释放的实时观察不可恢复，明确不是原网络请求精确重放。
- 该诊断仅消耗 1/2 次额度：LOW／8192，HTTP 200／STOP，1401 ms，输入 2586、输出 220、其中思考 195、合计 2806 Token；思考 518、正文 58 个 UTF-16 字符，动作与 Tap 类型解析成功。未检查点位正确性或执行点击，不作为等号／5535／完整闭环通过。首请求未超限，按约定不发送关闭思考的第二请求，不挪用剩余额度。`live.txt` 1.454 秒 PASS 仅代表诊断执行及原状态／历史／无副屏断言通过；持久化原任务回执与此前快照一致，只有 `request-1.attempted`。本次成功支持评估低思考方向，不证明原故障消失或所有任务质量不变，生产 HIGH／8192 保持不变。
- 新诊断入口独立静态审查 PASS，源码 SHA-256 `80f873d41cbdf3e0e6b6371663d6f2c029f9497a3b6daa63133f1f25dfe2e67a`；两个不联网合成检查 PASS，live 默认跳过，不将 runner 的三项计数当三项业务通过。测试 APK 构建／Lint 30 秒 PASS，`git diff --check` PASS；未重跑既有 531 项 JVM 单测。只更新测试 APK，SHA-256 `04b3e566b2dd3a1daadcd00d7480937e3642ca5d3b8083b5d7b4df2bb2a4cb08` 与设备一致；主产品仍 `43e20f0d...`，PID 27899 不变，既有测试辅助源码未变。未提交或推送。
- 本次首帧／Service 证据在 `/tmp/rootpilot-physical-unlock.Mwgxa4/`：离线及两份 Service 输出、`service-trace.jsonl`、`service-history.json`、`service-metadata.json` 和本地权限前后截图。零动作诊断证据在 `/tmp/rootpilot-equality-effort.qI0Gwu/`：`build-fixed.txt`、`offline.txt`、`live.txt`、`metadata.json`、`prior-history-after.json`。以下保留此前测试辅助与失败条件的证据边界。

- 用户回复已解锁后，同一设备／原 PID 的系统策略曾确认未锁定。无电源干预基线 `offline-capture.txt` 仍在 5.542 秒内失败：副屏 OFF、4003 ms 无首帧、结束时已锁定；主屏起止 ON，IME／Service 状态不变，专用副屏清理确认。未进入真实模型或算式输入。
- 仅在 androidTest 新增默认关闭的 `holdRootPilotScreen=true`。测试先核对无运行任务、无恢复文件、已解锁，注册本 App 的 Activity 生命周期回调并发出 `screenHoldReadyForActivity=true`；主代理收到本轮握手后，只执行一次普通 ADB 启动 RootPilot 主屏新任务。测试在原有 5 秒准备预算内绑定实际 Activity／decor、等待正常初始化和焦点，临时设置自己窗口的 `FLAG_KEEP_SCREEN_ON`。运行期检查窗口／生命周期／标志和锁定状态，失效则发起取消并走原有清理；finally 注销回调、恢复原标志和 finish。仅窗口标志，不写系统息屏时间、不请求唤醒／解锁／副屏电源；[Android 保屏契约](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)不保证后台或系统主动锁定条件下仍保持唤醒。主产品不含此测试辅助，完整 Service 用例仅增加 opt-in 包装，原模型、逐动作确认及回执断言不变；本轮未运行其联网入口。
- 保留入口失败证据，不计作业务通过：同步启动方式在 `offline-capture-screen-hold.txt` 45.024 秒超时，未绑定窗口；之后仅停止并正常冷启动 RootPilot，以收尾未确认的启动状态，未清数据或操作其他 App，App PID 因此更换。第二版 `offline-capture-screen-hold-bounded.txt` 在 5.014 秒明确失败 `screen_hold_launch_unconfirmed`；改为真实 Application 回调后，`offline-capture-screen-hold-callback.txt` 已绑定并恢复标志，但 0.858 秒在过早的焦点检查失败。最终只把焦点条件等待纳入既有 5 秒预算，不增加固定等待或动作重试。
- 最终 `offline-capture-screen-hold-focus.txt`（6.881 秒）成功进入截图诊断，但整体 FAIL `screen_hold_lost`。本次专用副屏创建后为 ON（2），计算器启动后的截图前采样为 OFF（1）；创建后、启动后和截图前的解锁检查均通过，截图 4002 ms 后仍 `vd_capture_failed`，结束时锁定。只能确认 OFF 在“已解锁”采样下也出现，不能把所有截图失败都归因于先锁屏，更不能证明应用启动、电源策略或互联是根因。保屏标志设置／恢复、专用副屏清理、IME／Service 不变均有回执；主屏起止 ON 不代表全程系统电源状态不变。零模型请求、零 Tap、零电源请求、无图片保存或上传。随后只读确认无专用副屏残留。
- 独立审查发现并修复窗口重建后保屏条件失效、Context 包装链无法可靠取得 Activity 两项测试缺陷；回调／guard／清理最终静态 PASS，焦点等待增量复核 PASS。最终 helper 源码 SHA-256 前缀 `1749c0810a38`，诊断测试 `6123faf09733`，Service 验收测试 `91ad8f335a4f`。最终测试 APK 构建与 Lint 32 秒通过（`build-screen-hold-focus.txt`），`git diff --check` 通过；本轮未重跑 JVM 单测，也没有新的生产功能通过结论。
- 上述历史测试辅助产物在 `/tmp/rootpilot-unlocked-recheck.K3hYPH/`。主产品 APK 为 `43e20f0d01dc48152c6e64ea32a3d070a5fb72df7a57ce88f02eb3e72031771c`；测试 APK SHA-256 为 `8e13ab8c91ff76c8e4fbcb62cdb36f1d21ee91a07d3cc13b49918bd503b26843`，与设备安装包匹配。`screen_hold_lost` 是多个 guard 条件的合并原因码，不能仅凭它断言是哪项触发取消，末尾锁定采样也不确定精确锁定时点。用户随后明确此前仅在小米互联窗口操作；上文实体解锁条件对照已完成，不能把互联操作与实体电源／锁定状态等同，也不据此直接认定互联为根因。未提交或推送，其他在途修改保留。

### 生产模型失败分类

- 请求契约、HTTP、网络、超时、响应协议、配置、未知类均在原失败发生点类型化；HTTP 仅带有效状态码，native 工具协议仅带固定原因枚举。AgentLoop 与 FileAgentController 原样传递，任务 trace／历史只保存白名单字段，历史页面显示固定中文类别；不保存 URL、请求正文、模型返回或异常原文，不用 message 文本猜原因。
- 旧历史的可空诊断字段缺失时仍可读取；普通纯文字流协议不强行添加未知细分。只增加可观测性，没有调整 max_tokens、时限、协议守卫、自动重试、授权、确认、IME 或恢复策略。
- 一次真实 Service 在三信息查询成功后、Type／确认之前失败，固定类别 `RESPONSE_PROTOCOL`，零执行（`service-live-reviewed.txt`／`service-trace.txt`）。补充协议细分后仅一次允许复跑成功，未再次观察细分失败原因；没有证据把它等同此前输入后 `MODEL_FAILED`，也未证明根因已修复。稳定性仍 PARTIAL，不以成功复跑归因屏幕共享或增加输入兜底。

### 当前可用性补验与共享边界

- 手机分支只新增固定诊断，未改变请求、动作或输入行为；最新生产 Service 证据见上。既有 AgentLoop-only 成功证据位于 `/tmp/rootpilot-search-and-model.CiWxqs/phone-failure-classified*.txt`，失败保留于 `/tmp/rootpilot-usability.La1M02/`；不把失败或跳过计为通过。
- 检测到 `com.xiaomi.mirror` 的镜像虚拟显示；逻辑覆盖状态记录为 `OFF`，未关闭共享、切换其他服务或做人为开关对照，不能声称所有活跃共享输入模式已通过。失败用例 `phone-live-diagnostic.txt` 中的本机文本、树及恢复断言能独立于共享画面核验输入，但不能解释模型请求失败。
- RootPilot 输入法与悬浮窗保留 `FLAG_SECURE`。按 [Android 窗口契约](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)，受保护窗口不能出现在截图或非安全显示中；共享端看不到不等于本机输入失败，也不能据此取消输入目标绑定或本地确认。

## 文件 Agent 可用性与只读检索

- `FileAgentUsabilityInstrumentedTest` 使用未注册、进程内 DocumentsProvider 和唯一私有 cache 目录；不调用系统选择器，不访问或替换原工作区选择，不增加实际 URI grant。复用生产 FileAgentController、SafTreeAccess、FileChangeEngine 和 FileBackupStore。
- 前一基线的本地假模型／合成 Provider 核心回归 PASS（0.035 秒）：创建、精确编辑、原文备份及拒绝后不写入，最终两次提交；证据为 `/tmp/rootpilot-search-and-model.CiWxqs/files-core-regression.txt`。真实 DeepSeek 读写核心已有 PASS（8.39 秒，未在本轮重跑）：列目录、创建、读取后编辑、再次读取回执进入模型请求，最终文本与原文备份正确、两次精确预览确认、结束无 busy／pending 残留。文件均为私有 cache 中实际生成的合成数据，不截图。
- 确认由脚本逐次批准；测试保留少量合成文件与备份，私有位置见上述当前回归日志及此前 `/tmp/rootpilot-usability.La1M02/files-live.txt`。该用例不覆盖系统 selector、真实 SAF grant 持久化、文件 UI 或系统导出；既有专用目录的历史验收不自动覆盖所有提供方。

只读 `stat_file` 与 `search_files` 已实现；本轮只补提供方与边界验收，不改变目录授权、正文上传同意、写入确认／备份或文件 UI：

- stat 返回相对路径、类型及可空 MIME、大小、修改时间；缺失字段明确未知。名称搜索不读正文；内容搜索只接受原有 UTF-8 文本类型，区分大小写字面量，不执行正则或通配符。
- 限定当前授权目录，指定路径空表示根；深度 4、遍历 500 项、总读取 256 KiB、结果 20 项、查询 128 UTF-16 单元、片段 160 单元、遗漏明细 20 项。每文件取首个匹配；保留遗漏总数与固定原因，显式 complete／truncated／cannot_prove_no_match，不完整结果不能证明无匹配。
- 每次重新扫描，无内存或持久化索引；沿用受保护的 SAF 操作和取消检查，内容读取前重验路径归属，结果返回前重验目录选择。目录外、身份改变、撤销或取消不会返回旧结果。供应商变化与扫描不是原子快照，不承诺并发期间目录内容不变。
- 前一基线的隔离检索用例 PASS：本地 Provider 0.018 秒、真实 DeepSeek 3.345 秒；回执进入后续请求且检索没有写入，证据为 `/tmp/rootpilot-search-and-model.CiWxqs/files-search-{synthetic,live}.txt`，未在本轮重跑。所有用例默认关闭，不用隔离 Provider 充作系统授权验收。
- 实际 SAF：严格核对用户已授权的 `RootPilot-Acceptance-20260924-1700` URI、pointer、标签及读写 grant；本机 external-storage provider 上多级中文 name／content／stat、UTF-8 字面量、深度 4／读取 256 KiB／结果 20 项的截断断言 PASS（1.523 秒）。仅在唯一 UUID 子树准备 29 个合成文件，无既有文件改写、selection／grant 变更或删除；证据为新目录的 `saf-search.txt`。
- 取消 PASS（0.165 秒）：另一个唯一子目录中一个合成文件，真实 FD 打开后通过只读 metadata forwarder 取消，要求流关闭且无结果；不是 provider IPC 硬实时取消。私有 pointer 版本模拟 PASS（0.241 秒）：再一个合成文件、实际 SAF IO 与私有 cache pointer，要求 IO 回派前版本改变导致 `CONFLICT`；实际用户 selection／grant 未改，不能当作 UI 切目录。三项共保留 31 个非敏感合成文件及少量私有 pointer 数据，没有删除；证据为 `saf-cancel.txt`／`saf-private-pointer.txt`。
- 真实 UI 切目录 PASS（47.248 秒，`saf-projection-selection-ready.txt`）：在真实只读 IO 回派前，经 RootPilot“聊天 → 工作区 → 系统选择器”切到本轮已有的合成检索子目录；核验 pointer 版本、原 grant 撤回与新 grant、旧结果 `CONFLICT`／不返回、旧相对路径失效，以及新目录下读取／stat／搜索正确和内容不变。测试代码没有选择或改写授权，没有新建、改写或删除文件，不联网。选择由用户明确授权后通过 Mac“小米互联服务”投屏的 Computer Use 点击完成，不是 RootPilot 自主导航、模型点击或真人触摸证据。已另获最终系统授权确认，通过正常选择器恢复原专用目录；不遍历／创建的回读核验标签、pointer 与读写 grant 均有效（`saf-restored-preflight.txt`，0.015 秒），文件 Agent 保持关闭。其他提供方、文件浏览 UI 多级操作、规模与并发仍待验。

以下两项仍为研究候选，未实现、未验收，不据此增加权限：

| 优先级 | 方向与实际价值 | 复用点、边界与最小验收 |
| --- | --- | --- |
| 2 | JSON 字段级变更及小批量预览，减少模型拼接 old_text 的负担 | 本地字段／类型校验生成确定的新文本，再走现有 FileChangeEngine、差异预览、逐文件确认、备份与回读。多文件顺序写入不是原子事务，不自动回滚或重放；验两个 JSON 文件、字段缺失／类型错误、第二文件冲突与拒绝后不继续写入 |
| 3 | 受控产物交接：文件草稿中的短片段用于页面输入 | 由真实回执／回读绑定相对路径、目录选择和内容 hash，再另行确认手机输入；文件授权、内容上传与页面动作确认分离。当前 task 会进入恢复快照，不能直接拼入文件正文；需单独定义内存与恢复边界。沿用 128 UTF-16 输入上限，不静默截断或拆批；验产物变化、目录切换、目标切窗、拒绝与无自动发送 |

现有目录选择仍要求读写 grant，“只读检索”不表示已支持只读授权目录。[Android SAF 契约](https://developer.android.com/training/data-storage/shared/documents-files)仍由用户选择授权范围，测试通道不代替该授权。实际文件格式、目录规模／提供方和首个页面目标尚未明确；因此暂不扩大文件上限、复杂文档格式、Shell 或无确认批量写入。

## 已有屏幕上下文与输入边界

- 手机本机 `su` 采集系统状态，模型只接收白名单解析的包名／Activity、焦点包名、键盘状态和包围截图的采样时间；原始诊断与窗口标识不上传、不落盘。
- 截图前后窗口未知或变化时，不发送该帧。确认后先移除悬浮面板，再核对窗口；点击、滑动、按键和启动应用还要求键盘状态已知且一致。
- 文本输入允许切换输入法引起的键盘变化，但仍核对原窗口、目标包名与编辑框身份；等待动作不做执行前窗口核对。
- 状态变化或无法确认时停止，原因码 `SCREEN_CONTEXT_CHANGED`；不自动返回、补点或重放。恢复任务需重新同意上传。
- 四条固定诊断命令共用 3 秒协程时限，每条最多 256 KiB，超时／超限不解析截断内容；取消清理进程与流。系统进程启动／清理不保证硬实时。解析覆盖 AOSP 15／16 与本次小米 Android 17 已观察格式，仅接受可确认的默认显示状态；可容纳两份诊断均确认休眠、无焦点且无活动的副屏。全局等待停止／结束队列与显示区段分开，窗口块按缩进结束，避免误归属；不据此放行真实副屏活动或身份冲突。

### 已有限定验收证据

- **真机限定通过（2026-09-30 基线）**：小米 15（Android 17）上四个显式 `screenContextAcceptance=true` 用例通过：不接 UiAutomation 的本机采集、测试页前台／Activity／焦点及键盘状态（单次采集 234 ms）、模拟模型下真实截图与中文输入／原输入法恢复、确认后切应用拦截。测试先确认键盘窗口出现再比对状态，不给生产代码增加等待。模型为本地 fake，不请求真实 API、不上传截图；负向用例禁止真实点击注入。
- **证据**：本次本地检查与四项真机结果位于仓库外 `/tmp/rootpilot-screen-resume.3ubdvh/`，临时诊断探针已从代码移除。通过仅覆盖专用测试页，不代表真实应用或全部设备兼容。
- **待验收／下一动作**：生产 Service／悬浮窗监听器组合见本轮限定证据；真人／物理触摸、通知确认、其他厂商及活跃多显示仍待验，不擅自关闭 GKD／Shizuku。
- **真实输入闭环通过**：小米 15 上真实 DeepSeek → AgentLoop → SuRootExecutor／IME 完成一次固定中文输入，随后观察新画面并报告完成；共两帧，测试耗时 3.762 秒。独立断言输入框精确匹配、原输入法恢复、IME 恢复记录及任务快照无残留；测试页关闭后已退出前台。确认由测试脚本批准，不代表真实触摸、悬浮窗／通知确认或生产 Service 验收。
- **独立测试通道**：`execution-fixture` 仅有 Debug APK，无网络及文件保存；签名保护的 Provider 只开放状态、View 画面和关闭命令。只导出空文本或固定测试文本，排除系统／IME／其他窗口。测试禁用所有坐标动作，仅允许一次固定 Type，生产“禁止向自身应用输入”保持不变。
- **补验依据与限制**：`/tmp/rootpilot-fixture-acceptance.FS5mbd/` 内 `channel-foreground.txt`（不联网通道）与 `live.txt`（真实模型）通过；静态审查 PASS，构建通过，App lint 0 errors／29 warnings、fixture lint 0 errors／4 warnings。首次直接后台启动未能取得 Provider，预先显式打开 fixture 后通过；未证明首次无人辅助启动可用，不扩大为启动兼容修复。此通道不覆盖完整物理屏幕采集、坐标点击或系统界面视觉。

## 安全与数据约束

- Token 仅经 Android Keystore 密钥加密存入私有 noBackup 目录，不进入源码、日志、任务快照或测试产物；凭据恢复与任务恢复分离。
- 截图、屏幕元信息、当前任务 Activity／控件树及所选应用目录须经上传同意才发给配置的 API。应用列表只限制 `open_app`，不是截图／工具数据过滤或完整应用访问隔离；同包名重装不提供签名级隔离。
- 打开应用、文本输入、系统按键及待办创建始终确认；点击／滑动在手动模式确认。输入最多 128 UTF-16 单元，确认绑定具体编辑框，取消与结束恢复原输入法。
- 流式草稿不执行，只有完整响应通过校验后进入确认链路；预览只驻内存。Markdown 不自动打开链接或加载远程图片。
- 停止请求不等于执行退出；收尾完成前不释放任务占用。停止不撤销副作用，中断任务不自动重放；任务快照读写／清除失败阻止后续执行并要求人工处理。
- 任务历史与恢复快照独立：历史仅含脱敏字段，最多 50 次任务、每次最后 256 条事件，不提供重放。未落盘尾部可能丢失，不能作为可靠审计或动作成功证明。
- 聊天仅驻内存，配置变更隔离旧会话；最多 100 条消息，单次输入 16000 字符、已完成上下文 64000 字符。单模型请求 120 秒总时限，不代表整个多步任务的总时限；开发 Relay 缓冲响应，不保证实时流式展示。
- 文件目录授权与模型内容上传同意分开；文件 Agent 默认关闭，重启或保存／清除 API 配置后需重新开启。本地浏览不自动上传，更换／撤销目录或切换文件模式清空聊天。
- 文件写入逐次确认并绑定不可变预览；提交前检查目录、身份与原文 hash，编辑前备份、写后回读。写入失败不自动重放，开始写入后停止不代表撤销；SAF 不保证跨应用原子性。
- 文件仅支持 UTF-8 text/*、JSON、XML，单文件 64 KiB、单目录 100 项，每次请求最多 8 轮／16 次工具调用；备份上限 5 MiB 或 1000 项，满后拒绝需备份的新写入，不自动删旧备份。无删除、建目录、Shell、Root、网络搜索或手机动作工具。

## 已知限制与未验证项

- **真实应用只读搜索仍为 PARTIAL**：可打开系统设置，但搜索栏点击未进入搜索页，尚未完成中文输入和结果读取；部分确认使用电脑辅助，不能称完全独立闭环。
- **手机模型收尾稳定性仍为 PARTIAL**：生产 Service 成功闭环及一次 Type 前的响应协议失败均有真实证据；历史 HIGH／4096 和 HIGH／8192 副屏计算器请求均捕获 `response_protocol / output_limit`，其中 HIGH／8192 续验曾完成六键但最终结果请求失败。后续 LOW／8192 分段续验、LOW／65536 单任务完整七键已通过，详见“当前设备动作预算与 LOW 验收”；仍有 LOW 点位拒绝记录，不能据单次成功认定根因消失。该协议细分不能追溯归因此前所有 `MODEL_FAILED`，也不能称已修复或归因共享。
- **历史误点诊断暂缓**：没有确认固定坐标偏移，也没有证明已修复；不继续取证或增加坐标补偿。
- **屏幕保护非原子**：同窗口内容变化、检查后瞬间切窗及 A→B→A 可能漏检；不能证明动作成功。系统诊断格式不是稳定 SDK；主屏模式仍拒绝活跃或无法确认闲置的副屏，实验副屏只接受本次拥有的显示／会话及明确的目标窗口。
- **GKD 共存未完整验收**：本轮 RootPilot 专用测试页的生产停止及用户手工断连／重连均未关闭 GKD／Cumulus；未验证它们的业务、所有共存模式或查询中 Binder 断连。此前 Root UiAutomation 注册失败不作为 GKD 根因证据。
- **生命周期与兼容**：已限定覆盖副屏模型阶段停止、Tap 待确认停止和 OpenApp 待确认时进程死亡；实际收尾发现 RootPilot 无障碍 enabled 但 crashed／未绑定，已通过系统界面手动恢复并通过不联网三工具复验，不代表自动重绑通过。生产 Service 单独销毁／重建、输入待确认时进程死亡、真实通知／悬浮窗与实际输入法恢复组合，横屏／多窗口／工作资料／其他厂商和键盘仍需验证。主屏按钮取焦后副屏计算通过，不代表全局键盘焦点隔离。
- **界面**：最新聊天布局的大字体＋键盘＋生成中停止、键盘收起恢复、设置二级页状态恢复、生产凭据编辑防截图生命周期、长 Markdown 与历史非空列表视觉尚未完整覆盖；旧页面通过结果不自动覆盖新布局。
- **文件与网络**：实际 external-storage SAF 的多级检索已有限定证据；其他 DocumentsProvider、文件浏览 UI、多级大文件性能、并发修改／部分写入、真实模型拒绝／取消组合及真实 DNS／TLS／服务端故障尚未完整验证。
- **技术边界**：Miuix 使用实验性依赖；悬浮窗、输入法面板及旧 Agent 未迁移。聊天进程死亡不保留会话；待办无跨进程 exactly-once 保证，也不是离线规划能力。

## 下一步

1. 已有固定失败类别；仅在再次真实失败时收集协议细分或 HTTP／网络类固定证据，不保存原文，不自动重放已成功的输入，不据未证实假设改预算或协议守卫。
2. 在用户另行选定的非敏感应用中验证“启动 → 中文搜索 → 读取结果”；保持历史误点诊断暂缓边界。
3. 无障碍已手动恢复，强杀后的自动重绑异常仍需单独归因。在已确认的专用范围内补真人／通知确认、Service 单独销毁恢复、输入待确认／副作用执行期间的进程死亡及查询中断连；不把受控 OpenApp 待确认死亡或运行前手工断连当作所有生命周期场景通过。
4. 按实际风险补界面、厂商、活跃多显示与 GKD 业务共存；节点点击、跨步缓存和更多信息源仍需独立定范围，不扩展后台无人值守敏感操作。
5. 补其他 SAF 提供方及实际文件 UI；JSON 字段编辑、批量预览和文件到手机的交接仍需独立定范围，不把研究候选算成已实现功能。

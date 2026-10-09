# RootPilot 真机操作工具链评估

日期：2026-10-09
评估者：DSH 会话。§1–§8 为只读排查结论；§9 记录用户授权后实际执行的安装与配置改动。

## 0. 结论摘要

1. **不需要批量安装新工具。** 这台机器上的 adb / scrcpy / 内部 CLI 已能覆盖 RootPilot 的真机操作需求。
2. **adb 多份的问题已修，但初版判断需要更正**：本机有两份 adb（brew cask 37.0.1 / SDK 37.0.0），实际跑起了 3 个 adb server（5037 / 5038 / 5039）。补查后确认**补丁级版本差异并不会触发 server 重启**（adb 比较的是 `ADB_SERVER_VERSION`，两者同为 41），所以"设备忽然 offline 的主要来源"这一说法在本次证据下不成立，应降级为潜在风险。已把 SDK platform-tools 前置到 PATH，确保 shell 与 Gradle/Studio 命中同一份。详见 §3.1。
3. **`direnv` + `uv` 已装**：前者把 `-s <serial>` 从纪律变成默认（`.envrc` 按机型动态解析 `ANDROID_SERIAL`），后者用于隔离 Python CLI、不再污染系统解释器。
4. **`jadx` / `apktool` / `hyperfine` / `lnav` 已装**（第三方 App 验收取证、耗时采样、`RootPilotTrace` 日志分析）；`frida-tools` 只装了主机侧，未 push 设备端。
5. **明确不建议装**：`uiautomator2` / `airtest` / `weditor` —— 会往设备装 atx-agent 并抢占输入法与无障碍服务，与 RootPilot 自身的 IME 和无障碍依赖直接冲突，会制造假的验收失败。

## 1. 评估范围与证据边界

排查内容（均为只读）：adb 安装位置与版本、已连接设备、常见 Android 工具是否存在、Homebrew 包清单、Python 工具链与镜像库、内部 CLI、设备侧包清单、仓库真机相关约定。

**未做**：未安装任何工具；未在设备上执行 shell 命令（只做了 `adb devices -l` 发现设备）；未确认设备的 root 实际可用状态；未验证 `direnv`/`uv` 的安装后行为。下文凡属推断均已标注。

## 2. 现状盘点

### 2.1 已具备

| 类别 | 工具 | 实测 |
|---|---|---|
| ADB | `/opt/homebrew/bin/adb` | 1.0.41 / **37.0.1-15733141** |
| ADB | `~/Library/Android/sdk/platform-tools/adb` | 1.0.41 / **37.0.0-14910828** |
| 投屏/录制 | `scrcpy` | 5.0.1，支持 `--new-display` / `--display-id` / `-N --no-playback` |
| 图像对比 | ImageMagick `compare` / `magick` | 7.1.2-32 Q16-HDRI |
| 视频 | `ffmpeg` | 已装 |
| Python | 系统 python3.9.6 + pip 26.0.1 | Pillow 11.3.0、numpy 2.0.2 可用 |
| VCS | `gh`、`git` | 已装 |
| 内部 CLI | `ccli`、`bitscli`、`bytedcli`、`lark-cli`、`frida-probe` | 均在 PATH |
| 其他 | `jq`、`rg`、`node`(npm 11.6.1) | 已装 |

Homebrew 相关 cask：`android-platform-tools`、`android-commandlinetools`。

### 2.2 缺失

`pipx` / `uv` / `poetry` / `pyenv`、`direnv`、`hyperfine`、`lnav`、`fzf`、`tmux`、`jadx`、`apktool`、`bundletool`、`pidcat`、`frida-tools`(Python 包)、`uiautomator2`、`airtest`。

### 2.3 已连接设备

```
UQG0220421007017      usb:2-1   product:ELS-AN00   model:ELS_AN00        （华为）
10.93.133.110:36201   tcp       product:dada      model:24129PN74C      （无线）
```

`24129PN74C` + codename `dada` 对应小米 15，与 `README.md` 记载的主验收机一致（**推断，未进设备核对型号/root 状态**）。设备侧仅有 `com.example.rootpilot.fixture`（`execution-fixture`，`namespace`/`applicationId` = `com.example.rootpilot.fixture`），无 frida-server、无 uiautomator agent 残留，属干净状态。

## 3. 三个真实问题（先修，不属于"装新工具"）

### 3.1 adb 多份多 server —— 已处理，但影响被初版高估

实测（2026-10-09）：

| server 端口 | PID | 可执行文件 |
|---|---|---|
| 127.0.0.1:**5037**（默认） | 62637 | `~/Library/Android/sdk/platform-tools/adb`（37.0.0） |
| 127.0.0.1:5038 | 7968 | `/opt/homebrew/Caskroom/android-platform-tools/37.0.1/platform-tools/adb` |
| 127.0.0.1:5039 | 36095 | 同上（37.0.1） |

初始判断是"两份版本不一致会互相踢 server"，**补查后不成立**：adb 客户端与服务端比对的是 `ADB_SERVER_VERSION`（整数），两个 37.x 二进制同为 41，因此 37.0.1 的客户端可以正常与 37.0.0 的 server 通信，实测未发生重启或设备掉线。真正的风险是：

- 不同工具各自起独立 server（5038 / 5039 明显是某内部 CLI 用 `-L tcp:PORT` 隔离出来的），同一台设备在不同 server 上视图不一致，排查时容易"明明连着却看不到"。
- 一旦某份 adb 升级到 `ADB_SERVER_VERSION` 不同的版本，才会开始互相重启；这是潜在风险，不是当前故障。

已采取的修复（最小、可回退）：把 SDK platform-tools 前置到 `~/.zshrc` 的 PATH，使交互 shell 与 Gradle/Studio（`sdk.dir`）命中同一份 adb，不再由 `/opt/homebrew/bin` 抢先。**未删除** Homebrew cask，也**未** kill 任何现有 server。

### 3.2 双设备同时在线 —— `-s <serial>` 必须成为默认

仓库规则要求除发现设备外所有 ADB 命令显式 `-s <serial>`；无线端口会变化，不能硬编码历史地址，也不能自动切到另一台设备。这是"人记纪律"最容易失守的点，建议由 `direnv` 在仓库目录自动注入 `ANDROID_SERIAL`（`.envrc` 属机器相关文件，需 gitignore）。

### 3.3 Python 工具链无隔离

系统解释器为 python 3.9.6，pip 26.0.1。`pip3 install` 会直接落到 `~/Library/Python/3.9`，后续装工具会持续污染系统 Python，且 macOS 自带的 pip 受 SIP/权限约束。先装 `uv`（或 `pipx`）再装其他 Python CLI。

## 4. 推荐安装清单

| 优先级 | 工具 | 在本项目中的作用 | 设备侧影响 | 安装方式 |
|---|---|---|---|---|
| ★★★ | `direnv` | 仓库目录自动 `export ANDROID_SERIAL=<小米 serial>`，把"每条 adb 带 `-s`"变成默认行为，直接消掉双设备串台风险 | 无 | `brew install direnv` |
| ★★★ | `uv` | Python CLI 隔离与固定版本；避免继续污染系统 3.9 | 无 | `brew install uv` |
| ★★ | `jadx` | 真机验收第三方 App 时读 manifest / 资源 / 字符串，确认包名、Activity、按钮文案，不必靠猜 | 无 | `brew install jadx` |
| ★★ | `apktool` | 与 jadx 互补，解包资源与 smali 层取证 | 无 | `brew install apktool` |
| ★★ | `hyperfine` | 项目反复出现"截图 / UI 树 / 输入法切换 / 虚拟屏单步耗时"，`RootPilotTrace` 只给单次值；需多采样统计时替代手写 for 循环 | 无 | `brew install hyperfine` |
| ★ | `lnav` | 分析 `RootPilotTrace`（`app/src/main/java/com/example/agent/rootpilot/log/RunTrace.kt:145`，`TAG = "RootPilotTrace"`），可按时间轴 + SQL 过滤，优于 `adb logcat \| grep`。产物必须放仓库外 | 无 | `brew install lnav` |
| ★ | `frida-tools` | 补齐已有 `frida-probe` + `frida-runtime-probe` 技能的底层（`frida-ps -Ua`、`frida-trace`） | 需往 root 设备 push frida-server | `uv tool install frida-tools` |

`pidcat` 未列入：项目年久失维护，`lnav` 或 `adb logcat -s RootPilotTrace` 已覆盖需求。

## 5. 明确不建议安装

| 工具 | 原因 |
|---|---|
| `uiautomator2` / `airtest` / `weditor` | 会往设备安装 atx-agent APK，并**抢占输入法与无障碍服务**。RootPilot 恰恰依赖自己的 IME（Unicode 中文输入）和无障碍服务（控件树读取），会制造假的验收失败。若确需使用，只能在另一台设备上跑 |
| 再装一份 `platform-tools` | 双份 adb 冲突已存在，见 3.1 |
| 封装 `adb kill-server` 的便利脚本 | 违反 `AGENTS.md` 真机安全约定，且掩盖 3.1 的根因 |
| `perfetto` | 仅在虚拟显示 / 渲染性能成为验收项时再评估，当前无该需求 |
| `bundletool` | 项目直接安装 Debug APK，不涉及 AAB 拆分 |

## 6. 零安装即可用的现成能力

- **`scrcpy 5.0.1` 的 `--new-display` / `--display-id`**：可用系统侧虚拟显示作为**对照系**，验证项目用 `su` 拉起的 `VirtualDisplayHelper`（`app/src/main/java/com/example/agent/rootpilot/virtualdisplay/VirtualDisplayHelper.kt`）行为是否符合预期。与 `VirtualDisplayParityInstrumentedTest` 的 parity 目标直接相关。
- **截图 diff**：ImageMagick 7 的 `compare` + Pillow 11.3 / numpy 2.0.2 已具备，无需新增。
- **录制证据**：`scrcpy --no-playback --record=<仓库外路径>` 可直接产出录像证据。
- **日志**：`adb -s <serial> logcat -s RootPilotTrace`。
- **应用清单**：`adb -s <serial> shell pm list packages`。

## 7. 未验证项与剩余风险

1. 无线设备 `24129PN74C` 是否即 `README.md` 所述主验收机（小米 15）、其 root 是否实际可用，未验证。`.envrc` 的默认目标就建立在这个推断上。
2. 5038 / 5039 两个非默认端口 adb server 属于哪个工具（推测为内部 CLI 的隔离 server），未查证；未做任何处置。
3. Android Studio（GUI 启动）是否与 shell 使用同一 server，未验证；仅验证了 shell 侧 PATH 已命中 SDK 版本。
4. `direnv` 注入 `ANDROID_SERIAL` 后，Gradle 的 `connectedAndroidTest` 是否遵循该变量，未验证。
5. `frida-tools` 只装了主机侧（`frida` / `frida-ps` 等 17 个可执行文件），**未往设备 push frida-server**，因此尚未验证版本匹配与对 RootPilot 的影响。
6. `jadx` / `apktool` 依赖新装的 Homebrew `openjdk 27`；未验证它们与被 `JAVA_HOME` 固定的 JDK 17 是否冲突（jadx 自带启动脚本，apktool 走 PATH 上的 java）。

## 8. 待决策

- 无线设备若非主验收机，需修改 `.envrc` 中的 `MODEL`。
- 是否删除 Homebrew cask `android-platform-tools`（当前保留，仅靠 PATH 顺序区分）。
- 是否往设备 push frida-server 以启用 frida 探针。
- 是否把本评估文档纳入版本库（当前为未跟踪文件）。

## 9. 本次执行记录（2026-10-09，用户授权后）

安装（Homebrew，含依赖）：
`direnv 2.38.1`、`uv 0.12.24`、`hyperfine 2.0.0`、`lnav 0.14.1`、`jadx 1.5.6`、`apktool 3.0.3`，依赖 `ncurses 6.6`、`bash 5.3.20`、`openjdk 27`。

安装（uv tool，主机侧隔离环境）：
`frida-tools 14.11.0`（含 `frida 17.23.1`），可执行文件位于 `~/.local/bin`。

配置改动：

| 文件 | 改动 |
|---|---|
| `~/.zshrc:130-138` | Android 块内 PATH 由 `$PATH:$ANDROID_HOME/platform-tools:...` 改为 `$ANDROID_HOME/platform-tools:...:$PATH`，使 adb 命中 SDK 版本 |
| `~/.zshrc` 末尾 | 新增 direnv hook：`command -v direnv >/dev/null 2>&1 && eval "$(direnv hook zsh)"` |
| `/Users/bytedance/AndroidAgent/.gitignore` | 新增忽略 `.envrc`、`.envrc.local`（+4 行） |
| `/Users/bytedance/AndroidAgent/.envrc` | 新增，按 `model:24129PN74C` 动态解析并导出 `ANDROID_SERIAL` |

验证结果：

- `direnv exec` 输出 `ANDROID_SERIAL=10.93.133.110:36201`；`git check-ignore -v .envrc` 命中 `.gitignore:20`。
- 新交互 shell 中 `command -v adb` = `/Users/bytedance/Library/Android/sdk/platform-tools/adb`，版本 `37.0.0-14910828`；`_direnv_hook` 已加载。
- 切换后 `adb devices -l` 两台设备仍在位，未发生掉线。
- 未执行任何 `adb kill-server`；未修改任何现有 server。

未纳入本次执行：`uiautomator2` / `airtest` / `weditor`（见 §5，不建议安装）、`perfetto`、`bundletool`。

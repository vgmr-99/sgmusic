<div align="center">

<img src="docs/assets/launcher_preview.png" width="96" alt="拾光音乐图标" />

# 拾光音乐（Timbre）

**拾起每一段时光的声音**

<p>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Version" src="https://img.shields.io/badge/version-1.4.9-orange" />
  <img alt="License" src="https://img.shields.io/badge/License-GPL%20v3-blue" />
</p>

原生 Android 音乐播放器 · 多源统一播放 · LX 自定义源 · 逐字歌词 · 本地优先

[下载 APK](https://github.com/vgmr-99/sgmusic/releases) · [English README](README_EN.md) · [实现概览](docs/implementation.md)

</div>

> [!IMPORTANT]
> **二次开发声明**
>
> 本仓库 fork 自上游开源项目 **[NeriPlayer（音理音理!）](https://github.com/cwuom/NeriPlayer)**，与上游作者无关联。
> 这里不复述上游文档，功能说明以本仓库当前代码为准，上游完整介绍请访问 [上游仓库](https://github.com/cwuom/NeriPlayer)。
>
> 本 fork 的主要增量：LX 自定义源接入与取流策略、品牌与界面改版、播放页模板化与控件样式、YouTube / Bilibili 相关能力移植。
> **整体严格遵循上游 GPL-3.0 协议**（见 `LICENSE`）；二次开发部分同样以 GPL-3.0 发布。分发时必须附带 LICENSE 与源码，保留上游署名和协议头，不得以本项目名义冒充上游原作。

> [!NOTE]
> 仅供学习与研究。只在拥有权利、授权或平台规则允许的范围内访问、播放或保存内容。
> 本项目不提供媒体文件、密钥，也不提供绕过付费、DRM 或地区限制的方案及公共媒体代理/再分发服务。
> 在线能力依赖你自己的第三方账号授权，会员与受限内容遵循原平台规则。
> 本项目及维护者不接受任何形式的赞助、捐赠或商业资助。

---

## 亮点

- **多源同队列**：本地音频、网易云、Bilibili、YouTube Music、自建 Navidrome / OpenSubsonic 服务器、LX 自定义源统一进入播放队列。
- **LX 优先取流**：除 YouTube 外，在线曲目优先尝试已就绪的 LX 脚本；脚本失败才回退内置通道。QQ / 酷狗 / 酷我 / 咪咕的搜索结果也依赖 LX 播放。
- **播放页可定制（1.4.9）**：三套布局模板 + 进度条 / 播放键 / 底部功能键三类样式独立组合，互不捆绑。
- **歌词生态**：LRC / YRC / TTML 逐行与逐字、翻译与音译、跨源合并；悬浮窗、状态栏、通知、蓝牙车载、小米超级岛等多出口。
- **音频栈**：Media3 + 自研 Native DSP、10 段 EQ / AutoEQ、32-bit 高解析与 USB 独占（比特完美）。
- **本地优先**：缓存、下载、歌单、历史、统计默认留在设备；歌单与统计可按需同步到自己的 GitHub 仓库或 WebDAV，不同步音频文件。
- **工程化**：14 个自有模块、KSP 设置项生成、模块化与覆盖率门禁、Native 多 ABI CI、手动发布工作流。

## 功能一览

### 音源与发现

| 来源 | 搜索与浏览 | 播放与特色 |
| --- | --- | --- |
| 本地音频 | SAF 导入、扫描、ID3 元数据、封面、内嵌/侧载歌词 | 本地歌单与「我喜欢的音乐」；FFmpeg 扩展解码支持 APE / WMA / DSD / AIFF |
| 网易云音乐 | 扫码 / 网页 / 短信登录；歌单、专辑、歌手、每日推荐、私人 FM、雷达、评论、百科 | 内置直连作为回退；歌单可同步回账号 |
| Bilibili | 扫码 / Cookie 登录；歌曲 / 视频 / UP 主三类搜索，收藏夹与合集 | CC 字幕转歌词、SponsorBlock 跳过、评论时间坐标点击跳转 |
| YouTube Music | Cookie 或匿名会话；搜索、创作者、歌单、首页推荐、歌词 | 6 种客户端可选，PoToken / WebView 挑战缓存，预热秒播 |
| 自建音乐服务器 | Navidrome / OpenSubsonic：账号管理、目录浏览、搜索 | 播放取流、下载、歌词；协议说明见 [音乐服务器 API（预览）](docs/music-server-api.md) |
| QQ / 酷狗 / 酷我 / 咪咕 | 探索页搜索与发现页浏览 | 无内置取流通道，播放依赖 LX 脚本 |
| LX 自定义源 | `.js` 文件或链接导入，多脚本并存 | 见下方「LX 自定义源」 |

探索页还支持识别网易云 / Bilibili / YouTube 链接直接打开对应内容。Bilibili 首页分区支持排序与显示项编辑。

**实际取流顺序**：本地文件与完整缓存优先复用 → 自建服务器走独立解析 → YouTube 走内置链路 → 其余在线曲目先试 LX 脚本，失败再回退网易云直连、Bilibili 等内置通道。

### LX 自定义源

- 设置 → 播放 → 自定义源（LX）导入 `.js` 脚本或脚本链接；系统也可用拾光音乐直接打开 `.js` 文件。
- 多个脚本按顺序尝试，首个完成 `search → 匹配 → musicUrl` 的脚本胜出；每个脚本可单独启用、停用、删除、加备注。
- 支持 LX 协议的 array / object 两种 `sources` 声明、宿主 HTTP 桥与 JS 定时器队列。
- 音质探测支持快速 / 全部两种模式，覆盖 128k、192k、320k、flac、flac24bit、hires、atmos、master 八档。
- 脚本状态与错误持久化到 `filesDir/lx_sources.json`，设置页可直接查看诊断。
- 已移除全局总开关：只要有状态为 OK 且启用的脚本即生效。

### 界面与播放页

- 默认 **简洁（Clean）** 风格；首页、探索、曲库、设置使用钉住的小 TopAppBar；首次引导可「跳过全部」使用默认选项。
- **播放页模板**：传统（封面在上）、强调（大封面主导）、Apple Music（顶部小封面 + 歌词主体）三套真实布局。
- **控件样式独立配置**：进度条（波形 / 分段刻度 / 胶囊）、播放键（经典 / 圆环 / 扁平）、底部功能键（悬浮底座 / 带文字 / 液态玻璃），自由组合。
- 标题与歌手居中截断、歌词页底部栏同步、功能键尺寸跟随字号档位；播放页支持沉浸模式、长按封面切歌词、竖屏滑动切歌、自动隐藏控制条。
- 三种桌面小组件（4×2 播放控制 / 2×2 紧凑 / 4×1 条状）与四个启动器快捷方式（继续播放、探索、媒体库、随机喜欢）。
- 手机 / 平板分态适配：平板横屏侧栏导航、竖屏底栏，播放页与歌词页按屏幕宽度调整。

### 歌词

- LRC / YRC / TTML 解析，逐行与逐字（卡拉 OK）显示，支持翻译、音译与两种高亮强调样式。
- 跨源合并：网易云原文与翻译、LRCLIB 音译、AMLL TTML 逐词时间戳、酷狗 / QQ 歌词按偏好选择与合并。
- Bilibili CC 字幕偏移；本地内嵌与侧载歌词兜底。
- 输出：悬浮歌词（颜色 / 描边 / 阴影 / 字号 / 位置可调）、魅族状态栏歌词、Android 16 Live Update 通知歌词、蓝牙车载歌词、动态岛与小米超级岛（需 Shizuku）。
- 可向 Lyricon / SuperLyric 提供逐字歌词与翻译的歌词 API。

### 音效与输出

- Media3 ExoPlayer + 自研 Native DSP：10 段参数均衡、AutoEq 文本导入（ParametricEQ / GraphicEQ）、预设分享码、按输出设备记忆设置。
- 虚拟低音、人声消除、3D 环绕、混响、压缩、限幅、响度标准化（ITU-R BS.1770）、扬声器优化。
- 倍速与音调调节（含 Nightcore 联动）、省电 / 均衡 / 高音质处理模式、声道平衡。
- 高分辨率输出与 USB 独占：优先申请 32-bit PCM，可选「比特完美」跳过音效、淡入淡出与重采样。

### 数据、隐私与可靠性

- 本地优先：歌单、历史、统计、缓存、下载、授权信息默认保存在设备。
- 备份与恢复：GitHub 同步、WebDAV 同步、本地备份；**只同步元数据，不同步音频文件**。
- 下载：智能下载、最多 8 个并行任务、缓存上限与清理、文件名占位符（ `%artist%` / `%title%` / `%album%` / `%source%` / `%id%` ）。
- 脱机模式、安全模式（崩溃 / ANR 后只开放日志导出、配置导出、登录重置等恢复操作）、崩溃与 ANR 日志、调试探针（连点版本号 7 次开启）。
- 应用内检查更新并下载安装包；「一起听」支持房间、邀请链接、自定义服务器与身份 UUID（服务端见 `np-submodule/NeriPlayer-LTW`）。

## 近期更新

- **1.4.9**：播放页三套模板 + 三类控件样式独立配置；设置合并为「播放页控制」卡片，标题居中、控件尺寸不再跳变。
- **1.4.8**：Bilibili 三类搜索；评论时间坐标点击跳转；首页模块顺序与显示项可编辑；YouTube 取流对齐上游。
- **1.4.7**：YouTube 重定向循环修复、PoToken 落盘缓存、点按预热秒播，默认音质调整为「极高」。

## 快速开始

1. 从 [Releases](https://github.com/vgmr-99/sgmusic/releases) 下载 APK。发布资产命名规则为 `Timbre-<version>[-<abi>].apk`，正式发布同时提供 `SHA256SUMS.txt` 与构建元数据，建议一并下载校验。
2. 首次启动可点「跳过全部（使用默认选项）」，随后按需要在设置中配置登录、音质、歌词与音效。
3. 需要 LX 时：设置 → 播放 → 自定义源（LX）→ 导入 `.js`，确认脚本状态为 OK；无需总开关。
4. 应用内「关于」可检查更新；反馈与建议也在这里。

## 从源码构建

| 要求 | 版本 |
| --- | --- |
| JDK | 17（跑 Android 16 Robolectric 测试需同时可见 JDK 21） |
| Android SDK | compileSdk 37 / targetSdk 36 / minSdk 28（Android 9+） |
| NDK / CMake | 27.0.12077973 / 3.22.1 |
| Gradle / AGP / Kotlin / KSP | 9.6.1（wrapper） / 9.4.1 / 2.4.10 / 2.3.10 |
| 其他 | Git（版本逻辑需读取仓库）与 git 子模块；Node.js 22（一起听 Worker） |

```bash
git clone --recursive https://github.com/vgmr-99/sgmusic.git
cd sgmusic
./gradlew :app:assembleDebug --console=plain
```

Windows PowerShell：

```powershell
$env:JAVA_HOME='D:\path\to\jdk17'
$env:ANDROID_HOME='D:\path\to\android-sdk'
$env:GRADLE_USER_HOME='D:\path\to\gradle-cache'
.\gradlew.bat :app:assembleDebug --console=plain
```

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；Release APK：`Timbre-1.4.9.apk`（默认仅 `arm64-v8a`）。
- 多 ABI：`./gradlew :app:assembleRelease -PbuildAllReleaseAbis=true`。
- Release 签名：通过 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 提供；PR / IDE 构建可用 `-PallowUnsignedRelease=true` 打未签名包。**不要把签名材料提交进仓库。**
- 版本名唯一来源是 `gradle.properties` 的 `appVersion`（当前 `1.4.9`）；构建身份由 `BuildConfig.BUILD_UUID` / `BUILD_TIMESTAMP` 追溯。
- 质量门禁：`./gradlew verifyModularization`（模块边界 + JVM 测试 + 合并 CRAP + 域依赖 + lint）；Native host 测试与四 ABI 编译见 [质量工具](tools_pub/quality/README.md) 与 `android_native_ci.yml`。

## 项目结构

14 个自有 Android 库统一登记在 `gradle/owned-modules.txt`，完整边界说明见 [实现概览](docs/implementation.md)。

| 模块 | 职责 |
| --- | --- |
| `:common` / `:network` | 共享工具与文案、HTTP / Cookie / Range / Web 登录基础能力 |
| `:model` / `:database` | 跨模块契约与 Room 持久化、迁移 |
| `:native` | Native 崩溃处理、DSP、USB 协议与 PCM，产出 `lib_neri.so` |
| `:playback:logic` / `:playback:runtime` | 播放策略与 Media3 引擎、音源解析、服务、音效、USB 输出 |
| `:download:logic` / `:download:runtime` | 下载规则、队列、传输、受管文件与后台任务 |
| `:lyrics` | LRC / YRC / TTML 解析、翻译对齐、时间轴与歌词 SDK 接入 |
| `:platform` | 网易云 / Bilibili / YouTube Music / Subsonic 协议、账号、评论、远程歌词 |
| `:local` | 本地媒体、设置、历史统计、备份与同步宿主适配 |
| `:sync` | GitHub / WebDAV 传输、归档编解码、合并与并发保护 |
| `:listentogether` | 一起听协议、房间会话、重连与播放同步 |

`build-logic` 提供统一构建插件；`ksp-annotations` / `ksp-processor` 生成设置项；`hidden-api` 仅提供编译期桩。`np-submodule` 包含上游歌词库、一起听 Worker 服务端与未参与主构建的上游资料树。

## 文档

- [实现概览](docs/implementation.md) / [English](docs/implementation_EN.md)：构建版本、模块边界与关键实现路径
- [Kotlin 辅助工具约定](docs/kotlin-helpers.md) / [English](docs/kotlin-helpers_EN.md)
- [音乐服务器 API（预览）](docs/music-server-api.md)
- [贡献指南](CONTRIBUTING.md) / [English](CONTRIBUTING_EN.md)、[行为准则](CODE_OF_CONDUCT.md)
- [质量工具](tools_pub/quality/README.md)、[FFmpeg 扩展解码构建](tools_pub/extaudio/README.md)、[性能采集](tools_pub/performance/README.md)

## 致谢与协议

- 感谢上游 **[NeriPlayer](https://github.com/cwuom/NeriPlayer)** 的开源贡献。
- 本项目按 **GPL-3.0** 发布。第三方组件许可见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)（QuickJS 宿主 Apache-2.0、FFmpeg 6.0 LGPL-2.1-or-later、libusb LGPL-2.1-or-later 等）。
- 分发时保留 LICENSE、版权声明与上游署名；问题请走本仓库 Issue，上游问题请到上游仓库反馈。

如果这个项目对你有用，欢迎点 Star。

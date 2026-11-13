<h1 align="center">拾光音乐</h1>

<div align="center">

<img src="docs/assets/launcher_preview.png" width="120" alt="拾光音乐图标" />

<h3>拾取时光，美好音乐 🎵</h3>

<p>原生 Android 音频播放器，支持 Android 9（API 28）及以上。</p>

<p>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" />
</p>

</div>

> [!IMPORTANT]
> **二次开发声明 / Fork notice**
>
> 本仓库是基于上游开源项目 **[NeriPlayer（音理音理!）](https://github.com/cwuom/NeriPlayer)** 进行的二次开发（fork）。
> 上游作者与本仓库维护者无关联关系。本项目**不复制上游自述文档**，仅在此说明本 fork 的来源、改动与协议遵守情况；上游完整文档与功能介绍请以
> [上游仓库](https://github.com/cwuom/NeriPlayer) 为准。
>
> 本 fork 在上游基础上主要新增 **LX 自定义音乐源接口与适配**，并做品牌与体验调整（见下方“本 fork 的改动”）。
> **严格执行上游仓库的协议**：本项目整体仍为 **GNU General Public License v3.0**（见 `LICENSE`），
> 二次开发部分同按 GPL-3.0 发布；新增的 QuickJS 宿主与 LX 兼容层接口契约均为独立重写，
> 不直接复制 `lx-music-mobile` 等项目的宿主脚本原文。分发时需同附 LICENSE 与源码，并保留上游署名与协议头。

---

## 项目简介

拾光音乐是一款基于 NeriPlayer 构建的原生 Android 播放器。它把网易云音乐、Bilibili、YouTube Music、本地音频等来源统一到一个播放队列里，
在此基础上把 **LX 自定义源** 作为**最后一环兜底**接入：内置来源都取不到播放地址时，才按顺序尝试已就绪的 LX 脚本。

数据默认本地保存：缓存、下载、歌单、历史、统计、设置与授权信息默认留在设备上；歌单与统计可按需同步到你自己的 GitHub 仓库或 WebDAV 远端。

> 仅供学习与研究使用。请只在你拥有权利、授权或第三方平台规则允许的范围内访问、播放或保存内容。
> 本项目不提供媒体文件、密钥、规避付费/DRM/地区限制的方案，也不提供公共媒体代理或再分发服务。
> 在线音频能力依赖你在第三方平台上的账号授权，会员或受限内容仍需遵循原平台规则。
> 本项目及维护者不接受任何形式的赞助、捐赠或商业资助。

## 本 fork 的改动

### LX 自定义源（核心新增）

- 将 LX 作为播放链路的最后一兜底：网易云 / 本地 / Bilibili 等内置来源（含地区与无 URL 等失败）都不可用时，再走 LX。
- 探索页（未登录网易云时）降级为 LX 搜索，LX 就绪时自动可用。
- 多脚本并存，按顺序尝试，首个 `search + 匹配 + musicUrl` 成功即采用；每个脚本可单独启用/删除。
- 设置 → 播放 → LX 自定义源里导入 `.js` 脚本（也内置了一个可用的示例脚本供对照）；支持 LX 协议的 array/object 两种 sources 声明、宿主 HTTP 桥、JS 定时器队列。
- LX 协议分发与 `lx.send('inited', …)` 的落点已对齐；脚本诊断（状态/错误）持久化到 `lx_sources.json` 并可查看。
- 移除 LX 总开关：只要有就绪（OK）的已启用脚本即可兜底/搜索；脚本自身的启用开关仍有效。

### 品牌与体验

- 应用名统一为 **拾光音乐**（桌面图标名、首页标题、小部件同源）。
- 默认使用**简洁界面风格（Clean）**，设置里不再展示“界面风格”选择入口（覆盖新装默认；已装设备保留既有选择）。
- 首次引导新增“**跳过全部（使用默认选项）**”。
- 首页、探索、曲库及设置等页面改用钉住的小 TopAppBar，不再使用 LargeTopAppBar 折叠头。

> 图标与启动图：拾光音乐 LOGO（无限带 + 星光）为本 fork 品牌素材。

## 功能一览

- **多源统一播放**：网易云、Bilibili、YouTube Music、本地音频、自建音乐服务器（预览版）同队列播放；LX 兜底位于最后。
- **歌词**：LRC / YRC / TTML 逐行与逐字、B 站字幕、翻译/音译、多源匹配，支持悬浮/状态栏/蓝牙车载等输出。
- **音效**：Media3 引擎、自研 DSP、10 段 EQ、AutoEQ、32-bit 输出、USB 独占。
- **界面**：Material You 与 Clean 主题、流体背景、桌面小组件、启动器快捷方式；默认 Clean。
- **本地优先**：缓存、下载、歌单、历史、统计默认本机；可选同步元数据到自己控制的 GitHub 仓库或 WebDAV（不同步音频文件）。
- **可恢复运行**：脱机模式、安全模式、崩溃/ANR 日志与调试探针。

## 快速开始

1. 从 [Release](https://github.com/vgmr-99/sgmusic/releases) 下载 APK，或从源码编译（见下方“从源码构建”）。
2. 首次启动可用“跳过全部”，之后在设置里按需配置音源、歌词、音效与同步。
3. 需要 LX 兜底时：设置 → 播放 → LX 自定义源 → 导入 `.js` 音源脚本，确认脚本状态为 OK 即可（无需总开关）。

## 从源码构建

环境：JDK 17、Android SDK、Git（Gradle 版本逻辑依赖 `git`）。

```powershell
$env:JAVA_HOME='D:\path\to\jdk17'
$env:ANDROID_HOME='D:\path\to\android-sdk'
$env:GRADLE_USER_HOME='D:\path\to\gradle-cache'
.\gradlew.bat :app:assembleDebug --console=plain
```

APK 产物：`app/build/outputs/apk/debug/app-debug.apk`。

验证命令（与本 fork 集成时使用的门禁）：

```powershell
.\gradlew.bat :platform:verifyDomainDependencies :local:testDebugUnitTest --tests "moe.ouom.neriplayer.data.settings.playback.PlaybackPreferenceSnapshotTest"
```

## 致谢与协议

- 感谢上游 **NeriPlayer** 团队的开源贡献：https://github.com/cwuom/NeriPlayer 。
- 本 fork 严格遵守上游仓库的开源协议（GPL-3.0)，分发时须保留 LICENSE、版权声明与上游署名，不得以本项目名义冒充上游原作。
- 第三方组件许可证汇总见 `THIRD_PARTY_LICENSES.md`（含本 fork 新增的 QuickJS 宿主 Apache-2.0 条目）。

## 关于 Star

如果本 fork 的 LX 适配对你有帮助，欢迎点个 Star ❤️
问题与建议请走 Issue；上游问题请优先到上游仓库反馈。

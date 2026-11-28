<div align="center">

<img src="docs/assets/launcher_preview.png" width="96" alt="Timbre icon" />

# Timbre（拾光音乐）

**Pick up time, enjoy music.**

<p>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" />
  <img alt="Version" src="https://img.shields.io/badge/version-1.4.9-orange" />
  <img alt="License" src="https://img.shields.io/badge/License-GPL%20v3-blue" />
</p>

Native Android music player · unified sources · LX custom sources · word-synced lyrics · local-first data

[Download APK](https://github.com/vgmr-99/sgmusic/releases) · [中文 README](README.md) · [Implementation notes](docs/implementation_EN.md)

</div>

> [!IMPORTANT]
> **Fork notice**
>
> This repository is a fork of **[NeriPlayer](https://github.com/cwuom/NeriPlayer)** and is not affiliated with the upstream author.
> This README describes the current state of this fork only; see the upstream repository for upstream documentation.
>
> Main additions: LX custom source integration and URL-resolution strategy, rebranding and UI rework,
> customizable Now Playing layouts, and YouTube / Bilibili features.
> **The whole project remains under GPL-3.0** (`LICENSE`). Fork additions are GPL-3.0 as well.
> Redistribution must include the LICENSE and source code and preserve upstream attribution; do not present this fork as the upstream project.

> [!NOTE]
> For study and research only. Access, play or save content only where you have the rights, authorization, or platform permission.
> No media files, keys, paywall / DRM / region-lock circumvention, public media proxy or redistribution service is provided.
> Online features rely on your own platform accounts. This project and its maintainer accept no sponsorship, donations or commercial funding.

---

## Highlights

- **One queue, many sources**: local audio, Netease Cloud, Bilibili, YouTube Music, self-hosted Navidrome / OpenSubsonic servers and LX custom sources.
- **LX-first resolution**: except for YouTube, online tracks try enabled LX scripts first and fall back to built-in resolvers. QQ / Kugou / Kuwo / Migu search results need LX for playback.
- **Customizable Now Playing (1.4.9)**: three layout templates plus independently configurable progress bar, playback buttons and bottom action bar.
- **Lyrics**: LRC / YRC / TTML, karaoke and line modes, translation / transliteration, cross-source merging, floating / status bar / notification / Bluetooth / Xiaomi island outputs.
- **Audio stack**: Media3 plus native DSP, 10-band EQ / AutoEQ, 32-bit high-res output and USB exclusive bit-perfect mode.
- **Local-first**: cache, downloads, playlists, history and stats stay on device. Playlists and stats can sync to your own GitHub repo or WebDAV; audio files are never synced.
- **Engineered**: 14 owned modules, KSP-generated settings, modularization / coverage gates, native multi-ABI CI, manual release workflow.

## Features

### Sources and discovery

| Source | Browse / search | Playback |
| --- | --- | --- |
| Local audio | SAF import, scanning, ID3 metadata, embedded / sidecar lyrics | Local playlists and favorites; FFmpeg decoding for APE / WMA / DSD / AIFF |
| Netease Cloud | QR / web / SMS login, playlists, albums, artists, daily recommendations, private FM, radar, comments, encyclopedia | Built-in direct URL as fallback; playlist sync back to account |
| Bilibili | QR / Cookie login, song / video / creator search, favorites and collections | CC subtitles as lyrics, SponsorBlock, clickable timestamps in comments |
| YouTube Music | Cookie or anonymous sessions, search, creators, playlists, home feed, lyrics | Six client options, PoToken / WebView challenge cache, warm-up for fast start |
| Self-hosted server | Navidrome / OpenSubsonic: accounts, browsing, search | Streaming, downloads, lyrics; see [music server API (preview)](docs/music-server-api.md) |
| QQ / Kugou / Kuwo / Migu | Explore search and discovery pages | No built-in stream channel; playback requires LX scripts |
| LX custom sources | Import `.js` files or URLs, multiple scripts | See below |

The Explore page recognizes Netease / Bilibili / YouTube links. Bilibili home sections support custom ordering and visibility.

**Actual resolution order**: local files and complete cache first → self-hosted server via its own resolver → YouTube via built-in chain → for other online tracks, LX scripts first, then built-in Netease / Bilibili fallback.

### LX custom sources

- Settings → Playback → Custom sources (LX); the app can also open `.js` files directly.
- Scripts are tried in order; the first one to complete `search → match → musicUrl` wins. Each script can be enabled, disabled, deleted or annotated.
- Supports both array and object `sources` declarations, a host HTTP bridge and the JS timer queue.
- Quick / full quality probing across 128k, 192k, 320k, flac, flac24bit, hires, atmos and master.
- Diagnostics persist to `filesDir/lx_sources.json`. There is no global switch: any enabled script in OK state is enough.

### Interface and Now Playing

- Clean UI by default; pinned small TopAppBar across Home / Explore / Library / Settings; “Skip all” in onboarding.
- Three real Now Playing templates: Classic, Expressive and Apple Music.
- Independent control styles: progress bar (wave / segmented / capsule), playback buttons (classic / ring / flat), bottom action bar (dock / labeled / liquid glass).
- Immersive mode, long-press artwork to switch lyrics, swipe to skip, auto-hiding controls, phone / tablet layouts.
- Three home widgets (4×2, 2×2, 4×1) and four launcher shortcuts (resume, explore, library, shuffle favorites).

### Lyrics

- LRC / YRC / TTML parsing with line and word sync, translation, transliteration and highlight styles.
- Cross-source merging from Netease, Kugou, QQ, LRCLIB and AMLL TTML; Bilibili CC subtitle offset; embedded and sidecar lyrics.
- Floating lyrics, Meizu status bar lyrics, Android 16 Live Update notifications, Bluetooth car lyrics, dynamic island and Xiaomi super island (Shizuku).
- Optional lyric API for Lyricon and SuperLyric.

### Audio effects and output

- Media3 ExoPlayer with native DSP: 10-band EQ, AutoEq import, shareable presets, per-output-device memory.
- Virtual bass, vocal removal, surround, reverb, compression, limiter, loudness normalization (ITU-R BS.1770), speaker optimization.
- Playback speed and pitch (Nightcore), power / balanced / high-quality processing modes, channel balance.
- High-res output and USB exclusive mode with 32-bit PCM priority and optional bit-perfect path.

### Data, privacy and reliability

- Local-first storage of playlists, history, stats, cache, downloads and credentials.
- Backup and restore via GitHub sync, WebDAV sync and local export. **Metadata only, never audio files.**
- Smart downloads, up to 8 parallel tasks, cache limits and cleanup, filename placeholders (`%artist%`, `%title%`, `%album%`, `%source%`, `%id%`).
- Offline mode, safe mode after crashes / ANR, crash and ANR logs, debug tools (seven taps on the version number).
- In-app update checks and APK installation; Listen Together rooms, invite links and custom servers (Worker backend in `np-submodule/NeriPlayer-LTW`).

## Recent changes

- **1.4.9**: three Now Playing templates and three independent control styles; merged appearance settings, centered titles, no control-size jumps.
- **1.4.8**: Bilibili three-way search; clickable comment timestamps; editable home section order and visibility; YouTube resolution aligned with upstream.
- **1.4.7**: YouTube redirect-loop fix, on-disk PoToken cache, warm-up playback, default quality raised to “very high”.

## Quick start

1. Download an APK from [Releases](https://github.com/vgmr-99/sgmusic/releases). Assets follow `Timbre-<version>[-<abi>].apk`; official releases also include `SHA256SUMS.txt` and build metadata for verification.
2. Use “Skip all” during onboarding, then configure accounts, quality, lyrics and effects as needed.
3. For LX: Settings → Playback → Custom sources (LX) → import a `.js` script and check that its status is OK. No global switch is required.
4. The About page provides in-app updates and feedback entry.

## Build from source

| Requirement | Version |
| --- | --- |
| JDK | 17 (JDK 21 also needed for Android 16 Robolectric tests) |
| Android SDK | compileSdk 37 / targetSdk 36 / minSdk 28 (Android 9+) |
| NDK / CMake | 27.0.12077973 / 3.22.1 |
| Gradle / AGP / Kotlin / KSP | 9.6.1 (wrapper) / 9.4.1 / 2.4.10 / 2.3.10 |
| Also needed | Git and git submodules; Node.js 22 for the Listen Together Worker |

```bash
git clone --recursive https://github.com/vgmr-99/sgmusic.git
cd sgmusic
./gradlew :app:assembleDebug --console=plain
```

PowerShell:

```powershell
$env:JAVA_HOME='D:\path\to\jdk17'
$env:ANDROID_HOME='D:\path\to\android-sdk'
$env:GRADLE_USER_HOME='D:\path\to\gradle-cache'
.\gradlew.bat :app:assembleDebug --console=plain
```

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`; release APK: `Timbre-1.4.9.apk` (`arm64-v8a` by default).
- All ABIs: `./gradlew :app:assembleRelease -PbuildAllReleaseAbis=true`.
- Release signing uses `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`; `-PallowUnsignedRelease=true` allows unsigned builds for PR / IDE. **Never commit signing material.**
- The single source of truth for the version name is `appVersion` in `gradle.properties` (currently `1.4.9`); build identity is tracked via `BuildConfig.BUILD_UUID` / `BUILD_TIMESTAMP`.
- Quality gate: `./gradlew verifyModularization` (module boundaries, JVM tests, merged CRAP, domain dependencies and lint). Native host tests and four-ABI builds run in `android_native_ci.yml`.

## Project structure

14 owned Android libraries are listed in `gradle/owned-modules.txt`. See [implementation notes](docs/implementation_EN.md) for full boundaries.

| Modules | Responsibility |
| --- | --- |
| `:common` / `:network` | Shared utilities and strings; HTTP, Cookie, Range and web login |
| `:model` / `:database` | Cross-module contracts and Room persistence |
| `:native` | Native crash handling, DSP, USB and PCM, producing `lib_neri.so` |
| `:playback:logic` / `:playback:runtime` | Playback policy, Media3 engine, URL resolution, service, effects, USB |
| `:download:logic` / `:download:runtime` | Download rules, queue, transfer, managed files and background jobs |
| `:lyrics` | LRC / YRC / TTML parsing, translation alignment and lyric SDKs |
| `:platform` | Netease / Bilibili / YouTube Music / Subsonic protocols, accounts, comments and remote lyrics |
| `:local` | Local media, settings, history, stats, backup and sync host adapters |
| `:sync` | GitHub / WebDAV transfer, archive codecs, merging and concurrency |
| `:listentogether` | Listen Together protocol, rooms, reconnection and playback sync |

`build-logic` holds convention plugins; `ksp-annotations` / `ksp-processor` generate settings; `hidden-api` provides compile-only stubs. `np-submodule` contains upstream lyric libraries, the Listen Together Worker and upstream reference trees not part of the main build.

## Documentation

- [Implementation notes](docs/implementation_EN.md) / [中文](docs/implementation.md)
- [Kotlin helper conventions](docs/kotlin-helpers_EN.md) / [中文](docs/kotlin-helpers.md)
- [Music server API (preview)](docs/music-server-api.md)
- [Contributing](CONTRIBUTING_EN.md) / [中文](CONTRIBUTING.md), [Code of conduct](CODE_OF_CONDUCT.md)
- [Quality tools](tools_pub/quality/README.md), [FFmpeg decoder build](tools_pub/extaudio/README.md), [Performance tools](tools_pub/performance/README.md)

## Credits and license

- Thanks to upstream **[NeriPlayer](https://github.com/cwuom/NeriPlayer)** contributors.
- Licensed under **GPL-3.0**. Third-party notices: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) (QuickJS host Apache-2.0, FFmpeg 6.0 LGPL-2.1-or-later, libusb LGPL-2.1-or-later, etc.).
- Keep the LICENSE, copyright notices and upstream attribution when redistributing. Report fork issues here; upstream issues go to the upstream repository.

Star the repo if it helps you.

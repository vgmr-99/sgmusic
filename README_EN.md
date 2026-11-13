<h1 align="center">拾光音乐 (Shiguang Music)</h1>

<div align="center">

<h3>Pick up time, enjoy music 🎵</h3>

<p>Native Android audio player for Android 9 (API 28) and above.</p>

<p>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" />
</p>

</div>

> [!IMPORTANT]
> **Fork notice**
>
> This repository is a fork (secondary development) of the upstream open-source project
> **[NeriPlayer](https://github.com/cwuom/NeriPlayer)**. It is not affiliated with the upstream author.
> This README does not copy upstream documentation; see the [upstream repository](https://github.com/cwuom/NeriPlayer)
> for full upstream docs.
>
> This fork mainly adds an **LX custom music-source interface** and rebrands to 拾光音乐 (see “Fork changes”).
> **Strictly comply with the upstream license**: the whole project remains under **GNU General Public License v3.0**
> (see `LICENSE`), including the fork additions. The LX host layer was reimplemented independently and does not copy
> host scripts from other LX clients verbatim. When distributing, include the LICENSE and source code and keep
> upstream attribution.

---

## About

拾光音乐 is a native Android player built on NeriPlayer, unifying Netease, Bilibili, YouTube Music and local audio
in one queue, with **LX custom sources as the last-resort fallback**: only when built-in sources cannot resolve a
playable URL do enabled LX scripts get tried in order.

Data is local-first: cache, downloads, playlists, history, stats and credentials stay on device by default;
metadata can be synced to your own GitHub repo or WebDAV.

> For study/research only. Do not access, play or save content without the necessary rights or platform permission.
> No media files, keys, circumvention of paywalls/DRM/region locks, or redistribution services are provided.
> Online audio depends on your own platform accounts; membership/restricted content follows platform rules.

## Fork changes

### LX custom sources (main addition)

- LX as the last fallback after Netease/local/Bilibili, Explore search fallback when Netease is unavailable.
- Multiple scripts, sequential try, first `search + match + musicUrl` wins; per-script enable/delete.
- Import `.js` scripts in Settings → Playback → LX custom sources (a sample script is bundled for reference).
- Supports both array/object `sources`, host HTTP bridge and JS timer queue; diagnostics persisted in `lx_sources.json`.
- No global LX switch: any ready (OK) enabled script enables fallback/search.

### Brand & UX

- Display name unified to **拾光音乐** (launcher, home title, widgets).
- Default to **Clean UI style**; the style picker is no longer shown.
- Onboarding adds “Skip all (use defaults)”. Pages use a pinned small TopAppBar.

> Icon & splash: infinity ribbon + sparkle logo is the 拾光音乐 brand asset.

## Features (summary)

- Multi-source playback, word-synced lyrics (LRC / YRC / TTML), DSP/EQ, local-first storage,
  optional GitHub/WebDAV metadata sync, crash/ANR logs, widgets and shortcuts.

## Build

JDK 17 + Android SDK + Git:

```powershell
$env:JAVA_HOME='D:\path\to\jdk17'
$env:ANDROID_HOME='D:\path\to\android-sdk'
.\gradlew.bat :app:assembleDebug --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Credits & License

- Thanks to the upstream **NeriPlayer** contributors: https://github.com/cwuom/NeriPlayer
- This fork strictly follows the upstream **GPL-3.0** license; keep LICENSE, copyright notices and attribution
  when redistributing. Third-party notices: `THIRD_PARTY_LICENSES.md`.

# extaudio — 本地稀有格式解码库

`app/src/main/jniLibs/arm64-v8a/libextaudio.so` 的**可复现构建源**。它只服务于本地文件
（APE / WMA / ASF / DSF / AIFF），在线音源永远不会进入这个解码器——路由判定在
`ExtAudioRoutingDataSource.shouldUseExtAudio()`，并有单测锁定该安全边界。

## 来源与完整性（务必保留）

- FFmpeg **6.0** 官方源码：`https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz`
- 官方签名：`https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz.asc`
- 签名者：**FFmpeg release signing key `<ffmpeg-devel@ffmpeg.org>`**
  指纹 `FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8`
- 公钥来自 `https://keyserver.ubuntu.com/pks/lookup?op=get&search=0xFCF986EA15E6E293A5644F10B4322F04D67658D8`
  （keys.openpgp.org 返回的是无 UID 的隐私版公钥，gpg 会跳过导入，不要用）
- 已验证结果：`gpg: Good signature from "FFmpeg release signing key"`。

换版本时**必须**重新执行 GPG 校验，不要信任任何第三方预编译 `.so`。

## 只包含什么

`--disable-everything` 后仅启用：

| 类型 | 项目 |
|---|---|
| decoder | `ape`, `wmav1`, `wmav2`, `wmapro`, `wmalossless`, `dsd_lsbf`, `dsd_msbf`, `dsd_lsbf_planar`, `dsd_msbf_planar` |
| demuxer | `ape`, `asf`, `dsf`, `aiff`, `iff` |

DSD 两种容器都支持：DSF 由 `dsf` demuxer 映射为 `DSD_*_PLANAR`；DFF/DSDIFF 没有专用
demuxer，但通用 `iff` demuxer 会识别 `FRM8/DSD `（`libavformat/iff.c:160,166`）并映射为
`AV_CODEC_ID_DSD_MSBF`。两类解码器都必须启用。
| protocol | `file`（**没有网络协议**） |
| 其他 | `swresample`；关闭 programs / doc / network / autodetect / shared |

没有 encoder、没有视频、没有 filter。链接时 `--exclude-libs,ALL` 隐藏全部 FFmpeg 符号，
动态符号表只导出 7 个 `Java_..._ExtAudioNative_*` 函数。

## 构建

依赖本地 NDK 28 / make / bash / Python，路径写死在脚本顶部，换机器时改那几行即可。

```bash
bash tools_pub/extaudio/build_ffmpeg_arm64.sh   # 生成 out-arm64 静态库
bash tools_pub/extaudio/build_extaudio_so.sh    # 生成 jniLibs/arm64-v8a/libextaudio.so
```

产物 strip 后约 **766 KB**。Release 只打 `arm64-v8a`，因此这是全部体积增量。

## 安全设计

- native 侧只编译 `file` 协议，传入网络 URL 也打不开；
- `open/read/seek/close` 全部校验魔数句柄、Java 数组边界、文件上限 2 GiB、PCM 帧上限 8 MiB；
- 任何解码错误返回 `-2`，Kotlin 侧转 `IOException`，fail-closed，不把半帧数据交给播放器；
- Java 侧路由只接受 scheme 为 `file`/`content` 且扩展名命中白名单的本地文件；
- `content://` 通过 `openFileDescriptor` 映射到 `/proc/self/fd/N`，不整文件拷贝到缓存。

## 已知限制

- DSD 解码为高采样率 PCM，内存/磁盘占用高于有损格式；
- DFF 里若使用 DST 压缩帧（`ID_DST`）则无法解码，本构建只有 `DSD_MSBF` 解码器，未启用 DST；
- 未接入平台 MediaCodec 的硬件解码，全部是软件解码；
- 解码路径尚未做真机长按/快进边界的实机验证，请在测试包上重点验证 seek。

/*
 * extaudio_jni.c - minimal, hardened JNI bridge for local-file audio decoding.
 *
 * Security notes:
 *  - only the `file` protocol is compiled into the bundled FFmpeg, so remote
 *    URLs cannot be opened even if a caller passes one;
 *  - every native entry point validates a magic-tagged handle;
 *  - Java array bounds are checked before every write;
 *  - input file size and PCM frame size are capped;
 *  - any decode error is returned to Java and fails closed.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <errno.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>

#define EXT_MAGIC 0x45585441u          /* "EXTA" */
#define EXT_MAX_FILE_BYTES (2LL * 1024 * 1024 * 1024)
#define EXT_MAX_PCM_BYTES (8 * 1024 * 1024)
#define EXT_MAX_CHANNELS 8
#define EXT_MAX_SAMPLE_RATE 384000

typedef struct {
    uint32_t magic;
    AVFormatContext *fmt;
    AVCodecContext *dec;
    SwrContext *swr;
    AVFrame *frame;
    AVPacket *pkt;
    int audio_stream;
    int sample_rate;
    int channels;
    int64_t duration_us;
    int connected;
    uint8_t *pcm;
    int pcm_cap;
    int pcm_len;
    int pcm_pos;
    int packet_active;
    int eof;
    int flush_sent;
    /* 自定义 AVIO（content:// fd）相关，path 打开方式时为 NULL */
    int io_fd;
    unsigned char *io_buffer;
    AVIOContext *io_ctx;
} ExtCtx;

/* ---- 自定义 AVIO 回调：直接从 Android 传进来的 fd 读，绕开 /proc/self/fd ---- */
typedef struct {
    int fd;
    int64_t size;
} FdIo;

static int fd_read(void *opaque, uint8_t *buf, int buf_size) {
    FdIo *io = (FdIo *)opaque;
    ssize_t n = read(io->fd, buf, (size_t)buf_size);
    if (n == 0) return AVERROR_EOF;   /* FFmpeg 约定：0 由 read 返回表示 EOF */
    if (n < 0) return (errno == EINTR) ? AVERROR(EAGAIN) : AVERROR(errno);
    return (int)n;
}

static int64_t fd_seek(void *opaque, int64_t offset, int whence) {
    FdIo *io = (FdIo *)opaque;
    if (whence == AVSEEK_SIZE) return io->size;
    int w = whence & ~AVSEEK_FORCE;   /* 去掉 FFmpeg 的 FORCE 标志，保留 SEEK_SET/CUR/END */
    off_t r = lseek(io->fd, (off_t)offset, w);
    if (r == (off_t)-1) return AVERROR(errno);
    return (int64_t)r;
}

static ExtCtx *valid(JNIEnv *env, jlong h) {
    (void)env;
    if (h == 0) return NULL;
    ExtCtx *c = (ExtCtx *)(intptr_t)h;
    if (c->magic != EXT_MAGIC) return NULL;
    return c;
}

static void reset_pending(ExtCtx *c) {
    c->pcm_pos = 0;
    c->pcm_len = 0;
}

static void free_ctx(ExtCtx *c) {
    if (!c) return;
    c->magic = 0;
    if (c->pkt) av_packet_free(&c->pkt);
    if (c->frame) av_frame_free(&c->frame);
    if (c->swr) swr_free(&c->swr);
    if (c->dec) avcodec_free_context(&c->dec);
    if (c->fmt) avformat_close_input(&c->fmt);   /* 会释放 io_ctx 的 pb（若已设 CUSTOM_IO） */
    if (c->io_ctx) { av_freep(&c->io_ctx->buffer); avio_context_free(&c->io_ctx); }
    if (c->io_buffer) av_freep(&c->io_buffer);
    if (c->io_fd > 0) close(c->io_fd);
    if (c->pcm) av_freep(&c->pcm);
    free(c);
}

/** path 与 fd 两条打开路径共用的「打开后」初始化。返回 0 成功，-1 失败。 */
static int finish_open(ExtCtx *c) {
    if (avformat_find_stream_info(c->fmt, NULL) < 0) return -1;

    int stream = av_find_best_stream(c->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream < 0) return -1;
    c->audio_stream = stream;

    AVCodecParameters *par = c->fmt->streams[stream]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(par->codec_id);
    if (!codec) return -1;

    c->dec = avcodec_alloc_context3(codec);
    if (!c->dec) return -1;
    if (avcodec_parameters_to_context(c->dec, par) < 0) return -1;
    if (avcodec_open2(c->dec, codec, NULL) < 0) return -1;

    c->channels = par->ch_layout.nb_channels;
    if (c->channels <= 0 || c->channels > EXT_MAX_CHANNELS) return -1;
    c->sample_rate = par->sample_rate;
    if (c->sample_rate <= 0 || c->sample_rate > EXT_MAX_SAMPLE_RATE) return -1;

    int64_t dur = c->fmt->streams[stream]->duration;
    if (dur <= 0) dur = c->fmt->duration;
    if (dur > 0 && dur != AV_NOPTS_VALUE) {
        AVRational tb = c->fmt->streams[stream]->time_base;
        double seconds = (double)dur * av_q2d(tb);
        if (seconds > 0 && seconds < 1.0e7) {
            c->duration_us = (int64_t)(seconds * 1000000.0);
        }
    }

    c->frame = av_frame_alloc();
    c->pkt = av_packet_alloc();
    if (!c->frame || !c->pkt) return -1;

    if (swr_alloc_set_opts2(&c->swr,
                            &par->ch_layout, AV_SAMPLE_FMT_S16, c->sample_rate,
                            &par->ch_layout, c->dec->sample_fmt, c->sample_rate,
                            0, NULL) < 0 || !c->swr) return -1;
    if (swr_init(c->swr) < 0) return -1;

    c->connected = 1;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_open(
        JNIEnv *env, jclass cls, jstring jpath) {
    (void)cls;
    if (jpath == NULL) return 0;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return 0;

    ExtCtx *c = (ExtCtx *)calloc(1, sizeof(ExtCtx));
    if (!c) { (*env)->ReleaseStringUTFChars(env, jpath, path); return 0; }
    c->magic = EXT_MAGIC;
    c->audio_stream = -1;

    int rc = avformat_open_input(&c->fmt, path, NULL, NULL);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (rc < 0 || !c->fmt) goto fail;

    int64_t size = avio_size(c->fmt->pb);
    if (size > EXT_MAX_FILE_BYTES) goto fail;

    if (finish_open(c) < 0) goto fail;
    return (jlong)(intptr_t)c;

fail:
    free_ctx(c);
    return 0;
}

/**
 * 通过 Android 传进来的可从 fd 直接读（content:// / SAF 场景）。
 * fd 会被 dup 一份，调用方可以立刻关闭原来的 ParcelFileDescriptor。
 */
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_openFd(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    if (fd < 0) return 0;

    ExtCtx *c = (ExtCtx *)calloc(1, sizeof(ExtCtx));
    if (!c) return 0;
    c->magic = EXT_MAGIC;
    c->audio_stream = -1;

    c->io_fd = dup(fd);
    if (c->io_fd < 0) goto fail;
    if (lseek(c->io_fd, 0, SEEK_SET) == (off_t)-1) goto fail;

    FdIo *io = (FdIo *)calloc(1, sizeof(FdIo));
    if (!io) goto fail;
    io->fd = c->io_fd;
    struct stat st;
    io->size = (fstat(c->io_fd, &st) == 0 && S_ISREG(st.st_mode))
                   ? (int64_t)st.st_size : -1;
    if (io->size > EXT_MAX_FILE_BYTES) { free(io); goto fail; }

    /* buffer 归 AVIOContext 管理，avio_context_free 会释放；这里单独留指针避免重复释放 */
    const int buf_size = 64 * 1024;
    unsigned char *buf = (unsigned char *)av_malloc(buf_size);
    if (!buf) { free(io); goto fail; }
    c->io_ctx = avio_alloc_context(buf, buf_size, 0, io, fd_read, NULL, fd_seek);
    if (!c->io_ctx) { av_freep(&buf); free(io); goto fail; }

    c->fmt = avformat_alloc_context();
    if (!c->fmt) goto fail;
    c->fmt->pb = c->io_ctx;
    c->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    int rc = avformat_open_input(&c->fmt, NULL, NULL, NULL);
    if (rc < 0 || !c->fmt) goto fail;   /* 失败时 avformat_close_input 未必释放 pb，靠 free_ctx 兜底 */

    if (finish_open(c) < 0) goto fail;
    return (jlong)(intptr_t)c;

fail:
    free_ctx(c);
    return 0;
}

JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_sampleRate(
        JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    ExtCtx *c = valid(env, h);
    return c ? c->sample_rate : 0;
}

JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_channelCount(
        JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    ExtCtx *c = valid(env, h);
    return c ? c->channels : 0;
}

JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_durationUs(
        JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    ExtCtx *c = valid(env, h);
    return c ? c->duration_us : 0;
}

/* Returns bytes written, -1 for EOF, -2 for error. */
JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_read(
        JNIEnv *env, jclass cls, jlong h,
        jbyteArray jbuf, jint offset, jint length) {
    (void)cls;
    ExtCtx *c = valid(env, h);
    if (!c || jbuf == NULL || offset < 0 || length <= 0) return -2;
    jsize arr_len = (*env)->GetArrayLength(env, jbuf);
    if (offset > arr_len || length > arr_len - offset) return -2;

    for (;;) {
        if (c->pcm_pos < c->pcm_len) break;   /* still have decoded bytes to hand out */
        if (c->eof) return -1;

        /* Only send a new packet after the decoder has drained the previous one.
         * APE decodes one packet into many frames; sending early returns EAGAIN. */
        if (!c->packet_active) {
            if (!c->flush_sent) {
                int rc = av_read_frame(c->fmt, c->pkt);
                if (rc == AVERROR_EOF) {
                    /* Flush the decoder so buffered frames are not lost. */
                    if (avcodec_send_packet(c->dec, NULL) < 0) { c->eof = 1; return -1; }
                    c->flush_sent = 1;
                } else if (rc < 0) {
                    return -2;
                } else if (c->pkt->stream_index != c->audio_stream) {
                    av_packet_unref(c->pkt);
                    continue;
                } else {
                    if (avcodec_send_packet(c->dec, c->pkt) < 0) {
                        av_packet_unref(c->pkt);
                        return -2;
                    }
                    c->packet_active = 1;
                }
            } else {
                /* already sent the NULL flush packet */
            }
        }

        int rc = avcodec_receive_frame(c->dec, c->frame);
        if (rc == AVERROR(EAGAIN)) {
            /* Decoder has no more frames for the current packet: safe to unref it. */
            if (c->packet_active) {
                av_packet_unref(c->pkt);
                c->packet_active = 0;
            }
            if (c->flush_sent) { c->eof = 1; return -1; }
            continue;
        }
        if (rc == AVERROR_EOF) {
            c->eof = 1;
            return -1;
        }
        if (rc < 0) {
            if (c->packet_active) {
                av_packet_unref(c->pkt);
                c->packet_active = 0;
            }
            return -2;
        }

        /* Got one decoded frame. Keep the packet referenced until EAGAIN. */
        int nb = c->frame->nb_samples;
        int need = nb * c->channels * (int)sizeof(int16_t);
        if (nb <= 0 || need <= 0 || need > EXT_MAX_PCM_BYTES) {
            av_frame_unref(c->frame);
            return -2;
        }
        if (need > c->pcm_cap) {
            uint8_t *tmp = (uint8_t *)av_realloc(c->pcm, (size_t)need);
            if (!tmp) { av_frame_unref(c->frame); return -2; }
            c->pcm = tmp;
            c->pcm_cap = need;
        }
        uint8_t *out[] = { c->pcm };
        int converted = swr_convert(c->swr, out, nb,
                                    (const uint8_t **)c->frame->extended_data, nb);
        av_frame_unref(c->frame);
        if (converted <= 0) return -2;
        c->pcm_len = converted * c->channels * (int)sizeof(int16_t);
        c->pcm_pos = 0;
    }

    int avail = c->pcm_len - c->pcm_pos;
    int n = length < avail ? length : avail;
    (*env)->SetByteArrayRegion(env, jbuf, offset, n,
                               (const jbyte *)(c->pcm + c->pcm_pos));
    c->pcm_pos += n;
    return n;
}

/* Returns JNI_TRUE on success. */
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_seek(
        JNIEnv *env, jclass cls, jlong h, jlong positionUs) {
    (void)env; (void)cls;
    ExtCtx *c = valid(env, h);
    if (!c || positionUs < 0) return JNI_FALSE;

    int64_t ts = av_rescale_q(positionUs, AV_TIME_BASE_Q,
                              c->fmt->streams[c->audio_stream]->time_base);
    int rc = av_seek_frame(c->fmt, c->audio_stream, ts, AVSEEK_FLAG_ANY);
    if (rc < 0) return JNI_FALSE;
    avcodec_flush_buffers(c->dec);
    swr_free(&c->swr);

    AVCodecParameters *par = c->fmt->streams[c->audio_stream]->codecpar;
    if (swr_alloc_set_opts2(&c->swr,
                            &par->ch_layout, AV_SAMPLE_FMT_S16, c->sample_rate,
                            &par->ch_layout, c->dec->sample_fmt, c->sample_rate,
                            0, NULL) < 0 || !c->swr) return JNI_FALSE;
    if (swr_init(c->swr) < 0) return JNI_FALSE;

    if (c->packet_active) { av_packet_unref(c->pkt); c->packet_active = 0; }
    av_frame_unref(c->frame);
    reset_pending(c);
    c->eof = 0;
    c->flush_sent = 0;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_extaudio_ExtAudioNative_close(
        JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    free_ctx(valid(env, h));
}

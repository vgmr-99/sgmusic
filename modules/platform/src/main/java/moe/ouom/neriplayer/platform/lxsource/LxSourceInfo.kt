package moe.ouom.neriplayer.platform.lxsource

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * LX Music 自定义源的最小契约（按公开协议文档独立实现，GPL-3.0）
 * 协议参考：lxmusic.toside.cn/desktop|mobile/custom-source
 * - globalThis.lx 提供 EVENT_NAMES/inited/request/updateAlert、on/send/request、utils、env、currentScriptInfo
 * - inited 声明 sources/actions/qualitys；request 回调返回 Promise
 * - lx.request 由宿主代发
 *
 * 阶段 1 只做 musicUrl 解析（播放地址），不做搜索/榜单/歌单。
 */
enum class LxPlatform(val code: String) {
    KW("kw"),
    KG("kg"),
    TX("tx"),
    WY("wy"),
    MG("mg"),
    QS("qs"),
    LOCAL("local");

    /**
     * 对应的应用内 channelId。
     *
     * 洛雪协议用的是 wy/kg/tx/kw/mg/qs，应用内用的是 netease/kugou/qq/kw/mg/qishui，
     * 同一平台两边字符串不同，不能直接拿 code 去比对歌曲来源。
     */
    fun channelId(): String = when (this) {
        WY -> "netease"
        KG -> "kugou"
        TX -> "qq"
        QS -> "qishui"
        else -> code
    }

    companion object {
        /**
         * 各平台在应用内可能出现的来源标识。
         *
         * 洛雪协议用 wy/kg/tx/kw/mg/qs，应用内用 netease/kugou/qq/kw/mg/qishui，
         * 两种写法都要认，否则「歌曲来源平台」和「LX 音源平台」会对不上。
         */
        private val CHANNEL_ALIASES: Map<LxPlatform, Set<String>> = mapOf(
            WY to setOf("netease", "wy", "cloud", "cloudmusic", "neteasecloudmusic"),
            KG to setOf("kugou", "kg"),
            TX to setOf("qq", "tx", "qqmusic", "tencent"),
            KW to setOf("kuwo", "kw"),
            MG to setOf("migu", "mg"),
            QS to setOf("qishui", "qs"),
            LOCAL to setOf("local"),
        )

        /** 按应用内 channelId 反查洛雪平台；不属于这些平台的返回 null */
        fun forChannelId(channelId: String?): LxPlatform? {
            if (channelId.isNullOrBlank()) return null
            val normalized = channelId.trim().lowercase()
            return entries.firstOrNull { CHANNEL_ALIASES[it]?.contains(normalized) == true }
        }

        /** 平台是否与歌曲来源渠道一致（channelId 与洛雪 code 都接受） */
        fun matchesChannelId(platform: LxPlatform, channelId: String?): Boolean {
            if (channelId.isNullOrBlank()) return false
            val normalized = channelId.trim().lowercase()
            return CHANNEL_ALIASES[platform]?.contains(normalized) == true
        }
    }
}

enum class LxQuality(val code: String) {
    Q128("128k"),
    Q192("192k"),
    Q320("320k"),
    FLAC("flac"),
    FLAC24("flac24bit"),
    HIRES("hires"),
    ATMOS("atmos"),
    MASTER("master");

    companion object {
        /** 界面展示顺序固定：128k -> 320k -> 无损 -> 母带 */
        val STANDARD_ORDER: List<LxQuality> = listOf(Q128, Q320, FLAC, FLAC24)

        /**
         * 完整音质序（对齐 Mint-Music 的 `_qualityFallbackChain`）。
         *
         * 取流失败时按这个顺序**只向下**降级：
         * 128k → 192k → 320k → flac → flac24bit → hires → atmos → master
         */
        val FALLBACK_ORDER: List<LxQuality> = listOf(
            Q128, Q192, Q320, FLAC, FLAC24, HIRES, ATMOS, MASTER,
        )
    }
}

data class CurrentScriptInfo(
    val name: String? = null,
    val author: String? = null,
    val version: String? = null,
    val description: String? = null,
    val srcUrl: String? = null,
)

data class LxSourceInfo(
    val status: Boolean = true,
    val env: String = "mobile",
    /**
     * 脚本声明的音源表：**key 原样保留**（wy/kg/kw/tx/mg/...），值是该 source 声明的音质。
     *
     * key 是宿主匹配平台的唯一依据（洛雪协议里 sources 的 key 就是平台代码），
     * 所以归一化时绝不能丢 —— Mint 把 sources 拍平成列表丢了 key，是它自己的 bug。
     */
    val sources: Map<String, List<String>> = emptyMap(),
    /** 每个 source 声明的 action（一律小写），判断时大小写不敏感 */
    val sourceActions: Map<String, List<String>> = emptyMap(),
    /** 每个 source 的展示名 */
    val sourceNames: Map<String, String> = emptyMap(),
    /** 全局 actions（旧形态：inited 顶层声明） */
    val actions: List<String> = emptyList(),
    val alert: String? = null,
    /**
     * 脚本注册了 `lx.on('request', handler)`（具备取流能力）。
     *
     * 这是 Mint 判定「脚本是可用音源」的**首要**条件：
     * Mint 的 `__lxEnsureReady` 只要求 handler 存在 + init 完成，
     * 不要求脚本发过 `lx.send('inited')`。
     */
    val hasRequestHandler: Boolean = false,
    /** 脚本自己导出了 `module.exports.musicUrl`（desktop / CeruMusic 风格） */
    val hasScriptMusicUrl: Boolean = false,
    /**
     * 脚本可用（能取流）但**没有声明 sources**。
     *
     * 这时认为所有平台都可用 —— 与 Mint 一致：Mint 把 sources 拍平成列表后，
     * 即使拿不到 source key，只要注册了 request handler 依然能取流。
     */
    val fallbackAllSources: Boolean = false,
) {
    /**
     * 平台是否被脚本声明为可用音源。
     *
     * 优先按 sources 的 key 精确匹配（大小写不敏感）；
     * 脚本没声明 sources 但注册了 handler 时（[fallbackAllSources]）一律认。
     */
    fun supports(platform: LxPlatform): Boolean = sourceKey(platform) != null || fallbackAllSources

    /** 该 source 声明的音质列表 */
    fun qualities(platform: LxPlatform): List<String> =
        sourceKey(platform)?.let { sources[it] }?.takeIf { it.isNotEmpty() }
            ?: if (fallbackAllSources) DEFAULT_QUALITIES else emptyList()

    fun supportsQuality(platform: LxPlatform, quality: LxQuality): Boolean =
        qualities(platform).contains(quality.code)

    /**
     * 该 source 是否支持某个 action（大小写不敏感）。
     *
     * 脚本写 'musicUrl' / 'musicurl' / 'MUSICURL' 都认；
     * 没声明 actions 的 source 按洛雪约定只当 musicUrl 可用。
     */
    fun supportsAction(platform: LxPlatform, action: String): Boolean {
        val wanted = action.trim().lowercase()
        if (wanted.isEmpty()) return false
        val key = sourceKey(platform)
        if (key == null) {
            // 没声明 sources 的脚本：按洛雪约定只当 musicUrl 可用
            return fallbackAllSources && wanted == "musicurl"
        }
        val declared = sourceActions[key]
        if (declared == null) return wanted == "musicurl"
        return declared.any { it.trim().lowercase() == wanted }
    }

    /** 该 source 的展示名（脚本没给则用 key） */
    fun sourceName(platform: LxPlatform): String? =
        sourceKey(platform)?.let { sourceNames[it] ?: it }

    private fun sourceKey(platform: LxPlatform): String? {
        val code = platform.code
        if (sources.containsKey(code)) return code
        return sources.keys.firstOrNull { it.equals(code, ignoreCase = true) }
    }

    companion object {
        /** 脚本没声明音质时的缺省档位（洛雪约定） */
        val DEFAULT_QUALITIES = listOf("128k", "320k", "flac")
    }
}

enum class LxSourceState { INIT, OK, FAIL, DISABLED }

data class LxSourceItem(
    val id: String,
    val name: String,
    val scriptText: String,
    val state: LxSourceState = LxSourceState.INIT,
    val info: LxSourceInfo? = null,
    val error: String? = null,
    val enabled: Boolean = true,
)

sealed interface LxResult<out T> {
    data class Ok<T>(val value: T) : LxResult<T>
    data class Err(val code: String, val message: String) : LxResult<Nothing>
}

/**
 * 脚本是不是「压根没有歌词能力」。
 *
 * 社区脚本的 request handler 对没实现的 action 会回
 * `action not support: lyric`（洛雪 / Mint 生态的通用文案）。
 * 命中这个就说明「不是这首歌没歌词，而是这个脚本不提供歌词」，
 * 可以把该脚本永久标记为不提供歌词，后续所有歌都直接跳过。
 */
fun LxResult.Err.isLyricUnsupported(): Boolean {
    if (code != "script_error") return false
    val text = message.trim().lowercase()
    return text.contains("not support") ||
        text.contains("not supported") ||
        text.contains("unsupported action") ||
        text.contains("action not")
}

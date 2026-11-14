package moe.ouom.neriplayer.platform.search.api.client.mint

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 *
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintBrowseApiBase
 * Created: 2026/11/13
 */

import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import java.util.Locale

/**
 * 分类可以异步刷新的平台额外实现的接口。
 *
 * 酷狗的标签是写死的，但网易云、酷我、QQ 的分类标签来自线上接口，
 * 拿不到就没法显示中文标签名。接线方在展示标签栏之前调一次
 * [refreshCategories]，把真实分类拉回来并缓存住；[MintPlatformBrowseApi.categories]
 * 会随之返回最新结果。
 *
 * 签名与 [moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi.refreshCategories]
 * 一致，实现类的同一个 override 同时满足两个接口。
 */
interface MintRefreshableCategories {

    /**
     * 拉取平台真实的分类标签。
     *
     * @return 刷新后的分类 key 列表，与 [MintPlatformBrowseApi.categories] 一致
     */
    suspend fun refreshCategories(): List<String>
}

/**
 * 五个平台实现共用的底座。
 *
 * 只做两件事：把来源代号翻译成仓库既有的搜索平台枚举，
 * 以及提供「卡片 provider 字段」的取值（该字段不允许为空）。
 */
abstract class MintBrowseApiBase(
    protected val http: MintHttp
) : MintPlatformBrowseApi {

    /** 真实来源代号：wy / kg / kw / tx / mg。 */
    abstract override val providerCode: String

    /**
     * 卡片与曲目里的 provider 字段。
     *
     * [moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard] 与
     * [moe.ouom.neriplayer.platform.search.api.PlatformSearchResult] 的 provider
     * 都是非空的，而来源代号必须能落到某个枚举上，
     * 这里统一走 [MintPlatformBrowseApi.provider]。
     */
    protected val cardProvider: PlatformSearchProvider
        get() = provider
}

/**
 * 合并前几页的结果。
 *
 * 浏览页的接口没有分页参数（[MintPlatformBrowseApi.browse] 只收分类），
 * 所以这里在内部把前 [pages] 页拉下来拼在一起，某页为空就提前结束。
 */
internal suspend fun <T> collectPages(
    pages: Int,
    fetch: suspend (Int) -> List<T>
): List<T> {
    val collected = ArrayList<T>()
    for (page in 1..pages) {
        val items = runCatching { fetch(page) }.getOrDefault(emptyList())
        if (items.isEmpty()) break
        collected.addAll(items)
    }
    return collected
}

/**
 * 播放量的中文紧凑写法。
 *
 * 平台原始播放量最后要交给
 * [moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport.parsePlayCount] 解析，
 * 这里先把数字变成「1.2万」这种文案。
 *
 * @param strictGreater true 时用严格大于（酷我 / QQ 的口径），
 *        false 时用大于等于（网易云 / 酷狗 / 咪咕的口径）
 */
internal fun formatPlayCount(value: Long, strictGreater: Boolean = false): String = when {
    strictGreater && value > 100_000_000L -> String.format(Locale.US, "%.1f亿", value / 100_000_000.0)
    !strictGreater && value >= 100_000_000L -> String.format(Locale.US, "%.1f亿", value / 100_000_000.0)
    strictGreater && value > 10_000L -> String.format(Locale.US, "%.1f万", value / 10_000.0)
    !strictGreater && value >= 10_000L -> String.format(Locale.US, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

/** 数字实体：`&#127769;` / `&#x1F319;`。先处理它们，避免与命名实体互相二次解码。 */
private val NUMERIC_HTML_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

/**
 * HTML 实体解码：接口里的歌名、歌单名经常带着 `&amp;` 这类命名实体，
 * 汽水等平台还会直接塞 `&#127769;` 这样的 emoji 数字实体，不解码会原样显示成乱码串。
 */
internal fun decodeHtmlEntities(text: String): String {
    val numericDecoded = NUMERIC_HTML_ENTITY.replace(text) { match ->
        val raw = match.groupValues[1]
        val codePoint = try {
            if (raw.startsWith("x", ignoreCase = true)) raw.substring(1).toInt(16) else raw.toInt()
        } catch (_: NumberFormatException) {
            return@replace match.value
        }
        // 代理区不能单独成字符，超范围的码点也原样保留
        if (codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
            String(Character.toChars(codePoint))
        } else {
            match.value
        }
    }
    return numericDecoded
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
}

/** 把卡片 id 里的源前缀去掉，只留下平台侧的真实 id。 */
internal fun rawCardId(card: PlatformBrowseCard): String =
    PlatformBrowseSupport.stripProviderPrefix(card.id)

package moe.ouom.neriplayer.data.model.bilibili.search

import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderProfile

data class SearchVideoItem(
    val aid: Long,
    val bvid: String,
    val titleHtml: String,
    val titlePlain: String,
    val author: String,
    val mid: Long,
    val coverUrl: String,
    val durationSec: Int,
    val play: Long?,
    val pubdate: Long?
)

data class SearchVideoPage(
    val page: Int,
    val pageSize: Int,
    val numResults: Int,
    val numPages: Int,
    val items: List<SearchVideoItem>
)

/** B站 UP 主搜索结果页（探索页「创作者」分类使用，对齐上游 cwuom/NeriPlayer）。 */
data class SearchUploaderPage(
    val page: Int,
    val numPages: Int,
    val items: List<UploaderProfile>
)

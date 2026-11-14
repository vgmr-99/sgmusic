package moe.ouom.neriplayer.ui.viewmodel.tab

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
 *
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/ExploreViewModel
 * Created: 2025/8/11
 */

import android.content.Context
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.viewmodel.feedbackText
import moe.ouom.neriplayer.core.player.persistence.toSongItem
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.collection.FavFolder
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.buildBiliPartSong
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.buildBiliSongAlbum
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResult
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResultType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager.biliClient
import moe.ouom.neriplayer.core.player.PlayerManager.neteaseClient
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSongDetail
import moe.ouom.neriplayer.platform.search.api.HotSearchEntry
import moe.ouom.neriplayer.platform.search.api.PlatformSearchApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import moe.ouom.neriplayer.platform.search.api.stablePlatformSongId
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.platform.youtube.api.transport.youtubeMusicThumbnailUrl
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.util.search.searchValues
import org.json.JSONObject

private const val TAG = "NERI-ExploreVM"
private const val NETEASE_SEARCH_PAGE_SIZE = 30

/** 浏览页缓存有效期：5 分钟 */
private const val PLATFORM_BROWSE_CACHE_TTL_MS = 5 * 60 * 1000L

/**
 * 浏览卡片的整体超时。
 *
 * 底层 OkHttp 只设了 connect 15s / read 30s，且 browse() 内部还有多层串行兜底
 * （直连失败退代理、热门歌单空了再试标签、多页拼接），最坏能挂好几分钟。
 * 这里给整条链加一个硬上限，超时就落到「加载失败 + 点我重试」，不再无限转圈。
 */
private const val PLATFORM_BROWSE_TIMEOUT_MS = 12_000L

/** 分类标签刷新是非关键路径，超时更短，失败也不影响卡片展示。 */
private const val PLATFORM_CATEGORY_TIMEOUT_MS = 8_000L

private data class PlatformBrowseCacheEntry(
    val timestamp: Long,
    val cards: List<PlatformBrowseCard>
)

/**
 * 探索页各平台真实支持的能力。
 *
 * 不支持的能力不渲染入口，避免用户点进去才看到「暂不支持」的死路：
 * - 咪咕只有浏览（歌单/排行榜），没有搜索接口；
 * - 热搜榜只有网易云 / QQ音乐 / 酷狗有数据；
 * - B站 / YouTube Music 只有搜索，没有可浏览的歌单广场。
 */
internal enum class ExploreCapability { SEARCH, BROWSE, HOT_SEARCH }

internal fun exploreSourceCapabilities(source: SearchSource): Set<ExploreCapability> = when (source) {
    SearchSource.NETEASE,
    SearchSource.QQ_MUSIC,
    SearchSource.KUGOU -> setOf(
        ExploreCapability.SEARCH,
        ExploreCapability.BROWSE,
        ExploreCapability.HOT_SEARCH
    )
    SearchSource.QISHUI,
    SearchSource.KUWO -> setOf(ExploreCapability.SEARCH, ExploreCapability.BROWSE)
    SearchSource.MIGU -> setOf(ExploreCapability.BROWSE)
    SearchSource.BILIBILI,
    SearchSource.YOUTUBE_MUSIC -> setOf(ExploreCapability.SEARCH)
    // 链接识别是独立的粘贴入口，输入框要保留，但不属于常规搜索
    SearchSource.LINK_RECOGNITION -> setOf(ExploreCapability.SEARCH)
}

internal fun exploreSourceSupports(source: SearchSource, capability: ExploreCapability): Boolean =
    capability in exploreSourceCapabilities(source)

/**
 * 热搜榜去重。
 *
 * 上游偶尔返回同名条目（实测酷狗「慌啥子嘛」出现两次），
 * 不去掉会让 LazyRow 的 key 冲突直接抛异常。UI 侧 key 也带 index 做第二层保险。
 */
internal fun dedupeHotSearchEntries(entries: List<HotSearchEntry>): List<HotSearchEntry> =
    entries.distinctBy { "${it.source.name}|${it.title}" }
private const val PLATFORM_SEARCH_PAGE_SIZE = 20
private const val YOUTUBE_MUSIC_SEARCH_LIMIT = 30
private const val BILI_RESOURCE_TYPE_COLLECTION = 21

/**
 * Tag key to Chinese API category mapping
 */
val TAG_TO_API_CATEGORY = mapOf(
    "tag_all" to "全部",
    "tag_pop" to "流行",
    "tag_soundtrack" to "影视原声",
    "tag_chinese" to "华语",
    "tag_nostalgia" to "怀旧",
    "tag_rock" to "摇滚",
    "tag_acg" to "ACG",
    "tag_western" to "欧美",
    "tag_fresh" to "清新",
    "tag_night" to "夜晚",
    "tag_children" to "儿童",
    "tag_folk" to "民谣",
    "tag_japanese" to "日语",
    "tag_romantic" to "浪漫",
    "tag_study" to "学习",
    "tag_korean" to "韩语",
    "tag_work" to "工作",
    "tag_electronic" to "电子",
    "tag_cantonese" to "粤语",
    "tag_dance" to "舞曲",
    "tag_sad" to "伤感",
    "tag_game" to "游戏",
    "tag_afternoon_tea" to "下午茶",
    "tag_healing" to "治愈",
    "tag_rap" to "说唱",
    "tag_light_music" to "轻音乐"
)

/** 定义搜索源 */
enum class SearchSource {
    YOUTUBE_MUSIC,
    NETEASE,
    QQ_MUSIC,
    KUGOU,
    QISHUI,
    KUWO,
    MIGU,
    BILIBILI,
    LINK_RECOGNITION
}

/**
 * 搜索分类
 *
 * 网易云用 apiType 区分三类；其它平台按同名分类映射到各自接口。
 */
enum class ExploreSearchType(val apiType: Int) {
    SONG(apiType = 1),
    PLAYLIST(apiType = 1000),
    ARTIST(apiType = 100)
}

/** 旧名保留别名，避免大面积改动既有引用 */
typealias NeteaseExploreSearchType = ExploreSearchType

enum class YouTubeExploreSearchType(
    val filter: YouTubeMusicSearchFilter?
) {
    SONG(YouTubeMusicSearchFilter.Song),
    VIDEO(YouTubeMusicSearchFilter.Video),
    CREATOR(null)
}

data class NeteaseSearchArtistResult(
    val artist: NeteaseArtistSummary,
    val picUrl: String?,
    val musicSize: Int,
    val albumSize: Int
)

sealed class ExploreSearchResult {
    abstract val stableKey: String

    data class Song(val song: SongItem) : ExploreSearchResult() {
        override val stableKey: String = listOfNotNull(
            "song",
            song.channelId,
            song.audioId,
            song.subAudioId,
            song.mediaUri,
            song.id.toString()
        ).joinToString("|")
    }

    data class Playlist(
        val playlist: PlaylistSummary,
        val sourceTag: String = "netease",
        val webUrl: String? = null,
        val owner: String = "",
        /**
         * 平台内的原始歌单 id。
         *
         * [playlist.id] 是给列表 key 用的哈希值，反推不出平台 id；
         * 汽水这类没有取流接口的平台要靠它去拉歌单曲目，所以得单独留一份。
         */
        val rawId: String? = null
    ) : ExploreSearchResult() {
        override val stableKey: String = "$sourceTag|playlist|${playlist.id}"
    }

    data class YouTubePlaylist(val playlist: YouTubeMusicPlaylist) : ExploreSearchResult() {
        override val stableKey: String = "youtubeMusic|playlist|${playlist.playlistId.ifBlank { playlist.browseId }}"
    }

    data class BilibiliPlaylist(val playlist: BiliPlaylist) : ExploreSearchResult() {
        override val stableKey: String =
            "bilibili|playlist|${playlist.kind}|${playlist.mediaId}|${playlist.mid}"
    }

    data class Artist(
        val result: NeteaseSearchArtistResult,
        val sourceTag: String = "netease",
        val webUrl: String? = null
    ) : ExploreSearchResult() {
        override val stableKey: String = "$sourceTag|artist|${result.artist.id}"
    }

    data class YouTubeCreator(val creator: YouTubeMusicCreatorSummary) : ExploreSearchResult() {
        override val stableKey: String = "youtubeMusic|creator|${creator.browseId}"
    }

    data class Notice(
        val title: String,
        val message: String
    ) : ExploreSearchResult() {
        override val stableKey: String = "notice|$title|$message"
    }
}

data class ExploreUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val playlists: List<PlaylistSummary> = emptyList(),
    val selectedTag: String = "tag_all",  // String resource key
    val searching: Boolean = false,
    val searchError: UiText? = null,
    val searchResults: List<SongItem> = emptyList(),
    val searchItems: List<ExploreSearchResult> = emptyList(),
    val searchHasMore: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val searchLoadMoreError: UiText? = null,
    val searchPage: Int = 0,
    val searchKeyword: String = "",
    val searchDisplayQuery: String = "",
    val selectedSearchSource: SearchSource = SearchSource.NETEASE,
    val selectedNeteaseSearchType: NeteaseExploreSearchType = NeteaseExploreSearchType.SONG,
    val selectedPlatformSearchType: PlatformSearchType = PlatformSearchType.SONG,
    val selectedYouTubeMusicSearchType: YouTubeExploreSearchType = YouTubeExploreSearchType.SONG,
    val isNeteaseLoggedIn: Boolean = false,
    val ytMusicPlaylists: List<YouTubeMusicPlaylist> = emptyList(),
    val ytMusicPlaylistsLoading: Boolean = false,
    val ytMusicPlaylistsError: UiText? = null,
    val hotSearchEntries: List<HotSearchEntry> = emptyList(),
    val hotSearchLoading: Boolean = false,
    /** 平台浏览页（默认页）的歌单 / 榜单卡片 */
    val platformBrowseCards: List<PlatformBrowseCard> = emptyList(),
    /** 当前卡片属于哪个平台，切源时用来判断是否需要重新加载，避免串台显示上一个平台的数据 */
    val platformBrowseSource: SearchSource? = null,
    val platformBrowseLoading: Boolean = false,
    val platformBrowseError: UiText? = null,
    val platformBrowseCategory: String = "",
    /**
     * 当前平台的分类标签。
     *
     * 标签是异步刷回来的，放在 state 里 Compose 才能感知变化；
     * 之前用 remember(selectedSearchSource) 只取一次，线上标签永远不显示。
     */
    val platformBrowseCategories: List<String> = emptyList(),
    /** 展开中的榜单及其曲目 */
    val platformBrowseTracks: List<PlatformSearchResult> = emptyList(),
    val platformBrowseTracksLoading: Boolean = false,
    val platformBrowseTrackTitle: String = "",
    /**
     * 展开中歌单的官方页面链接。
     *
     * 正常情况下点歌单就能在站内展开曲目；只有接口读不到时，
     * 才用它兜底给一个「去官方页面看」的入口。
     */
    val platformBrowseTrackWebUrl: String? = null,
    /**
     * 展开中的曲目面板属于哪个入口。
     *
     * 浏览页和搜索页共用同一套曲目状态，不区分的话，
     * 浏览页展开的歌单会残留到搜索页，把搜索结果整个挡住。
     */
    val platformBrowseTrackScope: PlatformBrowseTrackScope = PlatformBrowseTrackScope.NONE
)

/** 展开中的曲目面板属于哪个入口 */
enum class PlatformBrowseTrackScope {
    /** 没有展开任何歌单 */
    NONE,

    /** 浏览页（分类歌单 / 榜单）展开的 */
    BROWSE,

    /** 搜索结果里的歌单展开的 */
    SEARCH
}

/**
 * 网易云搜索不再要求登录。
 *
 * 未登录时走匿名会话（本地生成 csrf），登录后自动改用账号态，
 * 因此这里对任何登录状态都放行。
 */
internal fun isNeteaseExploreSearchAvailable(authState: SavedCookieAuthState): Boolean = true

/** 是否已登录网易云，仅用于界面提示，不影响搜索能否发起 */
internal fun isNeteaseAccountSignedIn(authState: SavedCookieAuthState): Boolean {
    return authState != SavedCookieAuthState.Missing
}

internal fun ExploreUiState.withNeteaseSearchError(error: UiText): ExploreUiState = copy(
    searching = false,
    searchError = error,
    searchResults = emptyList(),
    searchItems = emptyList(),
    searchHasMore = false,
    searchLoadingMore = false,
    searchLoadMoreError = null,
    searchPage = 0
)

internal fun ExploreUiState.withYouTubeDisabled(): ExploreUiState {
    val searchState = if (selectedSearchSource == SearchSource.YOUTUBE_MUSIC) {
        copy(
            selectedSearchSource = SearchSource.NETEASE,
            searching = false,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = "",
            searchError = null
        )
    } else {
        this
    }
    return searchState.copy(
        ytMusicPlaylists = emptyList(),
        ytMusicPlaylistsLoading = false,
        ytMusicPlaylistsError = null
    )
}

internal fun rankExploreSongSearchResults(
    query: String,
    songs: List<SongItem>
): List<SongItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank() || songs.size <= 1) return songs

    val scoredSongs = songs.mapIndexed { index, song ->
        RankedSongSearchResult(
            song = song,
            index = index,
            score = SearchTextMatcher.score(normalizedQuery, song.searchTokens())
        )
    }
    val matchedSongs = scoredSongs.filter { it.score != null }
    if (matchedSongs.isEmpty()) return songs

    return matchedSongs
        .sortedWith(
            compareBy<RankedSongSearchResult> { it.score ?: Int.MAX_VALUE }
                .thenBy { it.index }
        )
        .map { it.song } +
        scoredSongs
            .filter { it.score == null }
            .map { it.song }
}

private data class RankedSongSearchResult(
    val song: SongItem,
    val index: Int,
    val score: Int?
)

private fun SongItem.searchTokens(): List<Any?> {
    return searchValues()
}

internal fun mergeExploreSearchResults(
    existing: List<ExploreSearchResult>,
    incoming: List<ExploreSearchResult>
): List<ExploreSearchResult> {
    if (existing.isEmpty()) return incoming.distinctBy { it.stableKey }
    if (incoming.isEmpty()) return existing

    val seen = existing.mapTo(mutableSetOf()) { it.stableKey }
    val merged = ArrayList<ExploreSearchResult>(existing.size + incoming.size)
    merged += existing
    incoming.forEach { item ->
        if (seen.add(item.stableKey)) {
            merged += item
        }
    }
    return merged
}

internal fun searchSongItems(items: List<ExploreSearchResult>): List<SongItem> {
    return items.mapNotNull { (it as? ExploreSearchResult.Song)?.song }
}

internal fun hasMoreExploreSearchResults(
    totalCount: Int?,
    loadedCount: Int,
    pageItemCount: Int,
    pageSize: Int
): Boolean {
    return if (totalCount != null) {
        loadedCount < totalCount
    } else {
        pageItemCount >= pageSize
    }
}

internal fun shouldLoadExploreSearchMore(
    resultCount: Int,
    lastVisibleItemIndex: Int?,
    hasMore: Boolean,
    searching: Boolean,
    loadingMore: Boolean,
    loadMoreFailed: Boolean = false,
    prefetchDistance: Int = 6
): Boolean {
    if (!hasMore || searching || loadingMore || loadMoreFailed || resultCount <= 0) return false
    val lastVisible = lastVisibleItemIndex ?: return false
    return lastVisible >= resultCount - prefetchDistance
}

private data class ExploreSearchFetchResult(
    val items: List<ExploreSearchResult>,
    val page: Int,
    val hasMore: Boolean
) {
    val songs: List<SongItem> = searchSongItems(items)
}

class ExploreViewModel(application: Application) : AndroidViewModel(application) {
    private val baseApplication = application
    private val app: Context get() = LanguageManager.applyLanguage(baseApplication)
    private val neteaseRepo = AppContainer.neteaseCookieRepo
    private var highQualityLoadJob: Job? = null
    private var platformBrowseJob: Job? = null

    /**
     * 浏览页数据的短期缓存。
     *
     * 切走再切回同一个平台时不必重新拉一遍上游接口，
     * 上游本来就随时可能改版，短 TTL 既省流量也少踩接口抖动。
     */
    private val platformBrowseCache = mutableMapOf<String, PlatformBrowseCacheEntry>()

    /** 分类标签按平台缓存：刷新成功一次就复用，切 tab 不再重复打线上分类接口。 */
    private val platformBrowseCategoriesCache = mutableMapOf<SearchSource, List<String>>()
    private val categoryRefreshInFlight = mutableSetOf<SearchSource>()

    private var highQualityLoadCookies: Map<String, String>? = null
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private var hotSearchJob: Job? = null
    private var ytMusicPlaylistsJob: Job? = null
    private var ytMusicPlaylistsPending = false
    private var searchRequestVersion = 0L
    private var youtubeEnabled = YouTubeFeatureGate.isEnabled()

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState

    init {
        viewModelScope.launch {
            neteaseRepo.authHealthFlow.collect { health ->
                // 搜索本身不再依赖登录，这里只跟踪登录态用于界面提示
                _uiState.value = _uiState.value.copy(
                    isNeteaseLoggedIn = isNeteaseAccountSignedIn(health.state)
                )
            }
        }
        viewModelScope.launch {
            neteaseRepo.cookieFlow.collect {
                NPLogger.d(TAG, "cookieFlow updated, reload high quality playlists tag=${_uiState.value.selectedTag}")
                loadHighQuality()
            }
        }
        viewModelScope.launch {
            AppContainer.settingsRepo.youtubeEnabledFlow.collect { enabled ->
                youtubeEnabled = enabled
                if (!enabled) {
                    disableYouTubeSource()
                }
            }
        }
        loadHotSearch()
    }

    /** 设置当前搜索源 */
    fun setSearchSource(source: SearchSource) {
        if (source == SearchSource.YOUTUBE_MUSIC && !youtubeEnabled) return
        if (source == _uiState.value.selectedSearchSource) return
        NPLogger.d(TAG, "setSearchSource: ${_uiState.value.selectedSearchSource} -> $source")
        searchJob?.cancel()
        searchMoreJob?.cancel()
        // 浏览页按平台隔离：取消在途请求并清掉上一个平台的卡片/分类/曲目，
        // 否则切换 tab 时会一直显示旧平台（通常是网易云）的榜单。
        platformBrowseJob?.cancel()
        invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            selectedSearchSource = source,
            platformBrowseCards = emptyList(),
            platformBrowseSource = null,
            platformBrowseLoading = false,
            platformBrowseError = null,
            platformBrowseCategory = "",
            // 先用已缓存/内置的标签把分类栏画出来，线上标签异步补齐
            platformBrowseCategories = platformBrowseCategoriesCache[source]
                ?: platformBrowseApiFor(source)?.categories.orEmpty(),
            platformBrowseTracks = emptyList(),
            platformBrowseTracksLoading = false,
            platformBrowseTrackTitle = "",
            platformBrowseTrackWebUrl = null,
            platformBrowseTrackScope = PlatformBrowseTrackScope.NONE,
            // 各平台开放的分类不一样，切源时把分类落到该平台支持的第一项
            selectedPlatformSearchType = supportedSearchTypes(source)
                .firstOrNull { it == _uiState.value.selectedPlatformSearchType }
                ?: supportedSearchTypes(source).firstOrNull()
                ?: PlatformSearchType.SONG,
            searching = false,
            searchResults = emptyList(), // 切换源时清空结果
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = "",
            searchError = null
        )
        loadHotSearch()
    }

    /**
     * 加载当前来源的热搜榜。
     *
     * 网易云、QQ 音乐、酷狗提供榜单；其它来源直接清空，不影响原有搜索。
     */
    fun loadHotSearch() {
        val source = _uiState.value.selectedSearchSource
        val chartApi = if (exploreSourceSupports(source, ExploreCapability.HOT_SEARCH)) {
            when (source) {
                SearchSource.NETEASE -> AppContainer.cloudMusicChartApi
                SearchSource.QQ_MUSIC -> AppContainer.qqMusicChartApi
                SearchSource.KUGOU -> AppContainer.kugouChartApi
                else -> null
            }
        } else {
            null
        }
        if (chartApi == null) {
            // 不支持热搜榜的平台直接清空，不发请求也不留旧平台的数据
            hotSearchJob?.cancel()
            _uiState.value = _uiState.value.copy(hotSearchEntries = emptyList(), hotSearchLoading = false)
            return
        }
        hotSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(hotSearchLoading = true)
        val requestedSource = source
        hotSearchJob = viewModelScope.launch {
            val entries = try {
                // 上游会返回同名条目（实测酷狗「慌啥子嘛」重复），
                // 去重后再交给 LazyRow，避免 key 冲突崩溃和无意义的重复 chip。
                dedupeHotSearchEntries(chartApi.hotSearch())
            } catch (e: CancellationException) {
                // 快速切换 tab 时旧任务被取消属正常行为，不要记成「加载失败」
                throw e
            } catch (error: Exception) {
                NPLogger.w(TAG, "加载热搜榜失败: ${error.message.orEmpty()}")
                emptyList()
            }
            if (requestedSource != _uiState.value.selectedSearchSource) return@launch
            _uiState.value = _uiState.value.copy(
                hotSearchEntries = entries,
                hotSearchLoading = false
            )
        }
    }

    fun setNeteaseSearchType(type: NeteaseExploreSearchType) {
        if (type == _uiState.value.selectedNeteaseSearchType) return
        NPLogger.d(TAG, "setNeteaseSearchType: ${_uiState.value.selectedNeteaseSearchType} -> $type")
        searchJob?.cancel()
        searchMoreJob?.cancel()
        invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            selectedNeteaseSearchType = type,
            searching = false,
            searchError = null,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = ""
        )
    }

    /**
     * 设置新平台（QQ/酷狗/汽水）的搜索分类。
     *
     * 切换分类会清空当前结果，与网易云的分类切换行为一致。
     */
    fun setPlatformSearchType(type: PlatformSearchType) {
        if (type == _uiState.value.selectedPlatformSearchType) return
        NPLogger.d(
            TAG,
            "setPlatformSearchType: ${_uiState.value.selectedPlatformSearchType} -> $type"
        )
        searchJob?.cancel()
        searchMoreJob?.cancel()
        invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            selectedPlatformSearchType = type,
            searching = false,
            searchError = null,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = "",
            // 换分类等于换了一批结果，之前展开的歌单要收起来
            platformBrowseTracks = emptyList(),
            platformBrowseTrackTitle = "",
            platformBrowseTracksLoading = false,
            platformBrowseTrackWebUrl = null,
            platformBrowseTrackScope = PlatformBrowseTrackScope.NONE
        )
    }

    /** 当前来源支持哪些搜索分类 */
    fun supportedSearchTypes(source: SearchSource): List<PlatformSearchType> = when (source) {
        SearchSource.QQ_MUSIC -> AppContainer.qqPlatformSearchApi.supportedTypes.toList()
        SearchSource.KUGOU -> AppContainer.kugouSearchApi.supportedTypes.toList()
        SearchSource.QISHUI -> AppContainer.qishuiSearchApi.supportedTypes.toList()
        SearchSource.KUWO -> AppContainer.kuwoSearchApi.supportedTypes.toList()
        else -> emptyList()
    }

    fun setYouTubeMusicSearchType(type: YouTubeExploreSearchType) {
        if (type == _uiState.value.selectedYouTubeMusicSearchType) return
        NPLogger.d(
            TAG,
            "setYouTubeMusicSearchType: ${_uiState.value.selectedYouTubeMusicSearchType} -> $type"
        )
        searchJob?.cancel()
        searchMoreJob?.cancel()
        invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            selectedYouTubeMusicSearchType = type,
            searching = false,
            searchError = null,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = "",
            searchDisplayQuery = ""
        )
    }

    /** 统一搜索入口 */
    fun search(keyword: String, displayQuery: String = keyword) {
        val apiKeyword = keyword.trim()
        val matchQuery = displayQuery.trim().ifBlank { apiKeyword }
        if (apiKeyword.isBlank()) {
            NPLogger.d(TAG, "search cleared because keyword is blank")
            searchJob?.cancel()
            searchMoreJob?.cancel()
            invalidateSearchRequest()
            _uiState.value = _uiState.value.copy(
                searching = false,
                searchResults = emptyList(),
                searchItems = emptyList(),
                searchHasMore = false,
                searchLoadingMore = false,
                searchLoadMoreError = null,
                searchPage = 0,
                searchKeyword = "",
                searchDisplayQuery = "",
                searchError = null
            )
            return
        }
        val source = _uiState.value.selectedSearchSource
        val requestVersion = beginSearchRequest(apiKeyword, matchQuery)
        // 新一轮搜索等于换了一批结果，之前展开的歌单要收起来，
        // 否则展开的曲目面板会一直挡在搜索结果上面。
        closePlatformBrowseTracks()
        NPLogger.d(
            TAG,
            "search start: source=$source, request=$requestVersion, keyword=$apiKeyword, display=$matchQuery"
        )
        when (source) {
            SearchSource.NETEASE -> searchNetease(apiKeyword, matchQuery, requestVersion)
            SearchSource.QQ_MUSIC,
            SearchSource.KUGOU,
            SearchSource.QISHUI,
            SearchSource.KUWO -> searchPlatformSource(apiKeyword, matchQuery, requestVersion)
            SearchSource.MIGU -> searchUnsupportedSource(apiKeyword, requestVersion)
            SearchSource.BILIBILI -> searchBilibili(apiKeyword, matchQuery, requestVersion)
            SearchSource.YOUTUBE_MUSIC -> searchYouTubeMusic(apiKeyword, matchQuery, requestVersion)
            SearchSource.LINK_RECOGNITION -> searchRecognizedLink(apiKeyword, requestVersion)
        }
    }

    fun loadMoreSearchResults() {
        val state = _uiState.value
        val source = state.selectedSearchSource
        if (
            state.searchKeyword.isBlank() ||
            !state.searchHasMore ||
            state.searching ||
            state.searchLoadingMore ||
            searchMoreJob?.isActive == true
        ) {
            return
        }

        val neteaseType = state.selectedNeteaseSearchType
        val keyword = state.searchKeyword
        val matchQuery = state.searchDisplayQuery.ifBlank { keyword }
        val nextPage = state.searchPage + 1
        val requestVersion = searchRequestVersion
        _uiState.value = state.copy(searchLoadingMore = true, searchLoadMoreError = null)
        NPLogger.d(
            TAG,
            "search load more: source=$source, request=$requestVersion, keyword=$keyword, page=$nextPage, type=$neteaseType"
        )
        searchMoreJob = viewModelScope.launch {
            try {
                val result = when (source) {
                    SearchSource.NETEASE -> fetchNeteaseSearchPage(
                        keyword = keyword,
                        matchQuery = matchQuery,
                        page = nextPage,
                        type = neteaseType
                    )
                    SearchSource.QQ_MUSIC,
                    SearchSource.KUGOU,
                    SearchSource.QISHUI,
                    SearchSource.KUWO -> fetchPlatformSearchPage(
                        source = source,
                        keyword = keyword,
                        matchQuery = matchQuery,
                        page = nextPage,
                        type = state.selectedPlatformSearchType
                    )
                    SearchSource.BILIBILI -> fetchBilibiliSearchPage(
                        keyword = keyword,
                        matchQuery = matchQuery,
                        page = nextPage
                    )
                    SearchSource.YOUTUBE_MUSIC,
                    SearchSource.LINK_RECOGNITION,
                    SearchSource.MIGU -> return@launch
                }
                updateSearchStateIfCurrent(requestVersion, source) {
                    val merged = mergeExploreSearchResults(it.searchItems, result.items)
                    it.copy(
                        searching = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchError = null,
                        searchItems = merged,
                        searchResults = searchSongItems(merged),
                        searchPage = result.page,
                        searchHasMore = result.hasMore
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search load more failed: source=$source, request=$requestVersion, keyword=$keyword, page=$nextPage",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, source) {
                    it.copy(
                        searchLoadingMore = false,
                        searchLoadMoreError = searchErrorMessage(source, e),
                        searchHasMore = true
                    )
                }
            }
        }
    }

    /** 搜索 Bilibili 视频 */
    private fun searchBilibili(keyword: String, matchQuery: String, requestVersion: Long) {
        searchJob = viewModelScope.launch {
            try {
                val result = fetchBilibiliSearchPage(keyword, matchQuery, page = 1)
                NPLogger.d(
                    TAG,
                    "search Bilibili success: request=$requestVersion, keyword=$keyword, count=${result.items.size}, page=${result.page}, hasMore=${result.hasMore}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.BILIBILI) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = result.songs,
                        searchItems = result.items,
                        searchPage = result.page,
                        searchHasMore = result.hasMore
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search Bilibili failed: request=$requestVersion, keyword=$keyword",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.BILIBILI) {
                    it.copy(
                        searching = false,
                        searchError = feedbackText(
                            CoreCommonR.string.error_bilibili_search,
                            e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    private suspend fun fetchBilibiliSearchPage(
        keyword: String,
        matchQuery: String,
        page: Int
    ): ExploreSearchFetchResult {
        val searchPage = withContext(Dispatchers.IO) {
            biliClient.searchVideos(keyword = keyword, page = page)
        }
        val songs = rankExploreSongSearchResults(
            query = matchQuery,
            songs = searchPage.items.map { it.toSongItem() }
        )
        return ExploreSearchFetchResult(
            items = songs.map { ExploreSearchResult.Song(it) },
            page = searchPage.page,
            hasMore = searchPage.page < searchPage.numPages && searchPage.items.isNotEmpty()
        )
    }

    private fun beginSearchRequest(keyword: String, displayQuery: String): Long {
        searchJob?.cancel()
        searchMoreJob?.cancel()
        val requestVersion = invalidateSearchRequest()
        _uiState.value = _uiState.value.copy(
            searching = true,
            searchError = null,
            searchResults = emptyList(),
            searchItems = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false,
            searchLoadMoreError = null,
            searchPage = 0,
            searchKeyword = keyword,
            searchDisplayQuery = displayQuery
        )
        return requestVersion
    }

    private fun invalidateSearchRequest(): Long {
        searchRequestVersion += 1
        return searchRequestVersion
    }

    private fun isSearchRequestCurrent(requestVersion: Long, source: SearchSource): Boolean {
        val currentState = _uiState.value
        return searchRequestVersion == requestVersion && currentState.selectedSearchSource == source
    }

    private inline fun updateSearchStateIfCurrent(
        requestVersion: Long,
        source: SearchSource,
        transform: (ExploreUiState) -> ExploreUiState
    ) {
        if (!isSearchRequestCurrent(requestVersion, source)) {
            val currentState = _uiState.value
            NPLogger.d(
                TAG,
                "drop stale search update: source=$source, request=$requestVersion, currentRequest=$searchRequestVersion, currentSource=${currentState.selectedSearchSource}"
            )
            return
        }
        _uiState.value = transform(_uiState.value)
    }

    fun loadHighQuality(cat: String? = null) {
        val currentState = _uiState.value
        val realCat = cat ?: currentState.selectedTag
        val cookies = neteaseRepo.getCookiesOnce()
        if (
            highQualityLoadJob?.isActive == true &&
            currentState.loading &&
            currentState.selectedTag == realCat &&
            highQualityLoadCookies == cookies
        ) {
            return
        }
        val previousTag = currentState.selectedTag
        val previousPlaylists = currentState.playlists

        highQualityLoadJob?.cancel()
        highQualityLoadCookies = cookies.toMap()
        _uiState.value = currentState.copy(
            loading = true,
            error = null,
            selectedTag = realCat
        )
        NPLogger.d(
            TAG,
            "loadHighQuality start: tag=$realCat, apiCategory=${TAG_TO_API_CATEGORY[realCat] ?: realCat}, previousCount=${previousPlaylists.size}"
        )
        highQualityLoadJob = viewModelScope.launch {
            try {
                // Convert tag key to Chinese API category
                val apiCategory = TAG_TO_API_CATEGORY[realCat] ?: realCat
                val raw = withContext(Dispatchers.IO) {
                    neteaseClient.getHighQualityPlaylists(apiCategory, 50, 0L)
                }
                val mapped = parseExploreHighQualityPlaylists(raw)
                NPLogger.d(TAG, "loadHighQuality success: tag=$realCat, count=${mapped.size}")

                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = null,
                    playlists = mapped,
                    selectedTag = realCat
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val shouldRestorePreviousContent = previousPlaylists.isNotEmpty() && realCat != previousTag
                NPLogger.e(
                    TAG,
                    "loadHighQuality failed: tag=$realCat, restorePrevious=$shouldRestorePreviousContent",
                    e
                )
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = feedbackText(
                        CoreCommonR.string.error_load_playlist,
                        e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                    ),
                    playlists = if (shouldRestorePreviousContent) previousPlaylists else emptyList(),
                    selectedTag = if (shouldRestorePreviousContent) previousTag else realCat
                )
            }
        }
    }

    /**
     * 加载平台浏览页（默认页）的歌单 / 榜单。
     *
     * 三个约束：
     * - 切平台一律落回该平台的默认分类（热门歌单 / 排行榜），打开就有内容，不用再点一次；
     * - 分类标签走独立的后台任务，与卡片加载并行，不再串行挡住首屏；
     * - 整条卡片链路有硬超时，超时落到可重试的错误态，杜绝无限转圈。
     */
    fun loadPlatformBrowse(source: SearchSource, category: String? = null) {
        val api = platformBrowseApiFor(source) ?: return
        val realCategory = category?.takeIf { it.isNotBlank() } ?: api.defaultCategory
        val categories = platformBrowseCategoriesCache[source] ?: api.categories

        val sameTarget = _uiState.value.platformBrowseSource == source &&
            realCategory == _uiState.value.platformBrowseCategory
        // 同一平台同一分类且正在加载时不重复发起；跨平台的在途任务必须先取消，
        // 否则旧平台的异步结果会覆盖新平台的卡片。
        if (sameTarget && platformBrowseJob?.isActive == true) return
        platformBrowseJob?.cancel()

        val cacheKey = "${source.name}|$realCategory"
        val cachedCards = platformBrowseCache[cacheKey]
            ?.takeIf { System.currentTimeMillis() - it.timestamp < PLATFORM_BROWSE_CACHE_TTL_MS }
            ?.cards
        if (cachedCards != null) {
            NPLogger.d(
                TAG,
                "platform browse cache hit: $source/$realCategory cards=${cachedCards.size}"
            )
            _uiState.value = _uiState.value.copy(
                platformBrowseSource = source,
                platformBrowseLoading = false,
                platformBrowseError = null,
                platformBrowseCategory = realCategory,
                platformBrowseCategories = categories,
                platformBrowseCards = cachedCards,
                platformBrowseTracks = emptyList(),
                platformBrowseTrackTitle = "",
                platformBrowseTrackWebUrl = null,
                platformBrowseTrackScope = PlatformBrowseTrackScope.NONE
            )
            // 缓存命中也顺手在后台补分类，下次进来标签栏就是完整的
            refreshBrowseCategories(source, api)
            return
        }

        _uiState.value = _uiState.value.copy(
            platformBrowseSource = source,
            platformBrowseLoading = true,
            platformBrowseError = null,
            platformBrowseCategory = realCategory,
            platformBrowseCategories = categories,
            platformBrowseCards = emptyList(),
            platformBrowseTracks = emptyList(),
            platformBrowseTrackTitle = "",
            platformBrowseTrackWebUrl = null,
            platformBrowseTrackScope = PlatformBrowseTrackScope.NONE
        )
        // 分类刷新不再挡卡片：两个任务并行发出
        refreshBrowseCategories(source, api)
        platformBrowseJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val cards = withTimeoutOrNull(PLATFORM_BROWSE_TIMEOUT_MS) {
                    api.browse(realCategory)
                }
                if (_uiState.value.selectedSearchSource != source) return@launch
                if (cards == null) {
                    NPLogger.w(TAG, "platform browse timeout: $source/$realCategory")
                    _uiState.value = _uiState.value.copy(
                        platformBrowseSource = source,
                        platformBrowseLoading = false,
                        platformBrowseError = feedbackText(
                            CoreCommonR.string.error_load_playlist,
                            feedbackText(CoreCommonR.string.github_sync_failed_message)
                        )
                    )
                    return@launch
                }
                // 空结果不写缓存：接口偶发返回空（随机 device_id 被风控）时，
                // 缓存下来会让用户在 TTL 内一直看到空页，重试也没用
                if (cards.isNotEmpty()) {
                    platformBrowseCache[cacheKey] = PlatformBrowseCacheEntry(
                        timestamp = System.currentTimeMillis(),
                        cards = cards
                    )
                }
                NPLogger.d(
                    TAG,
                    "platform browse ready: $source/$realCategory cards=${cards.size} in ${System.currentTimeMillis() - startedAt}ms"
                )
                _uiState.value = _uiState.value.copy(
                    platformBrowseSource = source,
                    platformBrowseLoading = false,
                    platformBrowseError = null,
                    platformBrowseCategory = realCategory,
                    platformBrowseCards = cards
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.selectedSearchSource != source) return@launch
                _uiState.value = _uiState.value.copy(
                    platformBrowseSource = source,
                    platformBrowseLoading = false,
                    platformBrowseError = feedbackText(
                        CoreCommonR.string.error_load_playlist,
                        e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                    )
                )
            }
        }
    }

    /**
     * 后台刷新分类标签。
     *
     * 每个平台成功一次就缓存复用，切 tab 不再重复打线上分类接口；失败允许下次重试。
     * 结果写进 state，Compose 才会把线上标签补进分类栏（原先 remember 只取一次，永远补不上）。
     */
    private fun refreshBrowseCategories(source: SearchSource, api: PlatformBrowseApi) {
        if (platformBrowseCategoriesCache.containsKey(source)) return
        if (!categoryRefreshInFlight.add(source)) return
        viewModelScope.launch {
            try {
                val refreshed = withTimeoutOrNull(PLATFORM_CATEGORY_TIMEOUT_MS) {
                    api.refreshCategories()
                }
                if (!refreshed.isNullOrEmpty()) {
                    platformBrowseCategoriesCache[source] = refreshed
                    if (_uiState.value.selectedSearchSource == source) {
                        _uiState.value = _uiState.value.copy(
                            platformBrowseCategories = refreshed,
                            platformBrowseCategory = _uiState.value.platformBrowseCategory
                                .ifBlank { api.defaultCategory }
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.d(TAG, "browse categories refresh failed: $source ${e.message.orEmpty()}")
            } finally {
                categoryRefreshInFlight.remove(source)
            }
        }
    }

    /**
     * 展开某个榜单 / 歌单的曲目。
     *
     * [scope] 区分这个面板属于浏览页还是搜索页：
     * 两处共用同一份曲目状态，不区分的话浏览页展开的歌单会残留到搜索页。
     */
    fun loadPlatformBrowseTracks(
        card: PlatformBrowseCard,
        scope: PlatformBrowseTrackScope = PlatformBrowseTrackScope.BROWSE
    ) {
        val api = platformBrowseApiFor(_uiState.value.selectedSearchSource) ?: return
        if (_uiState.value.platformBrowseTracksLoading) return
        _uiState.value = _uiState.value.copy(
            platformBrowseTracksLoading = true,
            platformBrowseTracks = emptyList(),
            platformBrowseTrackTitle = card.title,
            platformBrowseTrackWebUrl = card.webUrl,
            platformBrowseTrackScope = scope
        )
        viewModelScope.launch {
            try {
                val tracks = api.tracks(card)
                _uiState.value = _uiState.value.copy(
                    platformBrowseTracksLoading = false,
                    platformBrowseTracks = tracks
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    platformBrowseTracksLoading = false,
                    platformBrowseError = feedbackText(
                        CoreCommonR.string.error_load_playlist,
                        e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                    )
                )
            }
        }
    }

    /** 收起展开中的榜单 */
    fun closePlatformBrowseTracks() {
        _uiState.value = _uiState.value.copy(
            platformBrowseTracks = emptyList(),
            platformBrowseTrackTitle = "",
            platformBrowseTracksLoading = false,
            platformBrowseTrackWebUrl = null,
            platformBrowseTrackScope = PlatformBrowseTrackScope.NONE
        )
    }

    private fun platformBrowseApiFor(source: SearchSource): PlatformBrowseApi? = when (source) {
        SearchSource.NETEASE -> AppContainer.mintNeteaseBrowseApi
        SearchSource.KUGOU -> AppContainer.mintKugouBrowseApi
        SearchSource.QQ_MUSIC -> AppContainer.mintQqBrowseApi
        SearchSource.QISHUI -> AppContainer.qishuiBrowseApi
        SearchSource.KUWO -> AppContainer.mintKuwoBrowseApi
        SearchSource.MIGU -> AppContainer.mintMiguBrowseApi
        else -> null
    }

    /**
     * 搜索结果里的歌单 -> 浏览卡片。
     *
     * 只有声明了 [PlatformBrowseApi.supportsSearchPlaylistTracks] 的平台才返回卡片，
     * 也就是确认能用同一套 id 读到曲目的平台（目前是汽水）；
     * 其它平台返回 null，由界面退回「跳官方页面」，避免出现空面板死路。
     */
    fun searchPlaylistToBrowseCard(
        sourceTag: String,
        rawId: String?,
        title: String,
        coverUrl: String?,
        trackCount: Int,
        webUrl: String?
    ): PlatformBrowseCard? {
        val provider = PlatformSearchProvider.fromChannel(sourceTag) ?: return null
        val api = provider.toSearchSource()?.let { platformBrowseApiFor(it) } ?: return null
        if (!api.supportsSearchPlaylistTracks) return null
        val raw = rawId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return PlatformBrowseCard(
            id = PlatformBrowseSupport.qualifyId(provider, raw),
            title = title,
            coverUrl = coverUrl?.takeIf { it.isNotBlank() },
            trackCount = trackCount,
            webUrl = webUrl,
            provider = provider
        )
    }

    /** 平台枚举 -> 探索页的平台来源 */
    private fun PlatformSearchProvider.toSearchSource(): SearchSource? = when (this) {
        PlatformSearchProvider.NETEASE -> SearchSource.NETEASE
        PlatformSearchProvider.QQ -> SearchSource.QQ_MUSIC
        PlatformSearchProvider.KUGOU -> SearchSource.KUGOU
        PlatformSearchProvider.QISHUI -> SearchSource.QISHUI
        PlatformSearchProvider.KUWO -> SearchSource.KUWO
        PlatformSearchProvider.MIGU -> SearchSource.MIGU
    }

    /**
     * 平台浏览页的分类标签。
     *
     * UI 直接读 `uiState.platformBrowseCategories`（异步刷新会推进 state）；
     * 这里保留给其它调用方，优先返回已刷新的结果，没刷新时退回内置标签。
     */
    fun platformBrowseCategories(source: SearchSource): List<String> =
        platformBrowseCategoriesCache[source]
            ?: _uiState.value.platformBrowseCategories.takeIf {
                it.isNotEmpty() && _uiState.value.platformBrowseSource == source
            }
            ?: platformBrowseApiFor(source)?.categories.orEmpty()

    /**
     * 平台浏览页的分类标签展示名。
     *
     * 分类 key 是「id-digest」这类组合串，直接显示没法看，
     * 由实现方把 key 翻译成中文名。
     */
    fun platformBrowseCategoryLabel(source: SearchSource, category: String): String =
        platformBrowseApiFor(source)?.categoryLabel(category) ?: category

    /**
     * 搜索网易云歌曲。
     *
     * 未登录时用匿名会话直接搜；匿名搜索失败再退回 LX 自定义源，
     * 两者都不可用时才把错误显示出来。
     */
    private fun searchNetease(keyword: String, matchQuery: String, requestVersion: Long) {
        val type = _uiState.value.selectedNeteaseSearchType
        searchJob = viewModelScope.launch {
            try {
                val result = fetchNeteaseSearchPage(keyword, matchQuery, page = 1, type = type)
                NPLogger.d(
                    TAG,
                    "search Netease success: request=$requestVersion, keyword=$keyword, type=$type, count=${result.items.size}, hasMore=${result.hasMore}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.NETEASE) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = result.songs,
                        searchItems = result.items,
                        searchPage = result.page,
                        searchHasMore = result.hasMore
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search Netease failed: request=$requestVersion, keyword=$keyword",
                    e
                )
                // 匿名搜索失败时再试 LX 自定义源，两者都不可用才提示错误
                if (isLxSearchAvailable()) {
                    searchLx(keyword, matchQuery, requestVersion)
                    return@launch
                }
                updateSearchStateIfCurrent(requestVersion, SearchSource.NETEASE) {
                    it.withNeteaseSearchError(
                        feedbackText(
                            CoreCommonR.string.error_netease_search,
                            e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                        )
                    )
                }
            }
        }
    }

    /**
     * 该源没有可用的公开搜索接口（咪咕的搜索需要签名且已失效）。
     *
     * 明确提示而不是静默返回空结果，用户才知道去浏览页看歌单与榜单。
     */
    private fun searchUnsupportedSource(keyword: String, requestVersion: Long) {
        searchJob = viewModelScope.launch {
            updateSearchStateIfCurrent(requestVersion, SearchSource.MIGU) {
                it.copy(
                    searching = false,
                    searchError = feedbackText(CoreCommonR.string.error_platform_search_unsupported),
                    searchResults = emptyList(),
                    searchItems = emptyList(),
                    searchHasMore = false,
                    searchLoadingMore = false,
                    searchLoadMoreError = null,
                    searchPage = 0
                )
            }
        }
    }

    /**
     * 多平台搜索（QQ音乐 / 酷狗 / 汽水 / 酷我）。
     *
     * 这些平台都不需要登录账号就能搜，分类与网易云保持一致（歌曲 / 歌单 / 歌手）；
     * 平台本身没开放的分类不会出现在类型栏里。
     */
    private fun searchPlatformSource(keyword: String, matchQuery: String, requestVersion: Long) {
        val source = _uiState.value.selectedSearchSource
        val type = _uiState.value.selectedPlatformSearchType
        searchJob = viewModelScope.launch {
            try {
                val result = fetchPlatformSearchPage(
                    source = source,
                    keyword = keyword,
                    matchQuery = matchQuery,
                    page = 1,
                    type = type
                )
                NPLogger.d(
                    TAG,
                    "search platform success: source=$source, request=$requestVersion, " +
                        "keyword=$keyword, type=$type, count=${result.items.size}, hasMore=${result.hasMore}"
                )
                updateSearchStateIfCurrent(requestVersion, source) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = result.songs,
                        searchItems = result.items,
                        searchPage = result.page,
                        searchHasMore = result.hasMore
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search platform failed: source=$source, request=$requestVersion, keyword=$keyword",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, source) {
                    it.withNeteaseSearchError(
                        feedbackText(
                            CoreCommonR.string.error_platform_search,
                            e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                        )
                    )
                }
            }
        }
    }

    /**
     * LX 自定义源搜索可用性：LX 总开关已移除，只要至少一个 OK 的已启用脚本即可。
     * 未登录时作为网易云搜索的自动替代，播放时走 LX 兜底。
     */
    private suspend fun isLxSearchAvailable(): Boolean {
        return AppContainer.lxSourceRepository.items.value.any {
            it.enabled && it.state == moe.ouom.neriplayer.platform.lxsource.LxSourceState.OK
        }
    }

    /** LX 自定义源搜索：结果直接是带 songmid 的 SongItem，播放由 LX musicUrl 解析兜底。 */
    private fun searchLx(keyword: String, matchQuery: String, requestVersion: Long) {
        searchJob = viewModelScope.launch {
            try {
                val songs = withContext(Dispatchers.IO) {
                    AppContainer.lxSourceRepository.searchSongsForUi(keyword)
                }.let { rankExploreSongSearchResults(matchQuery, it) }
                if (!isSearchRequestCurrent(requestVersion, SearchSource.NETEASE)) return@launch
                NPLogger.d(
                    TAG,
                    "search LX success: request=$requestVersion, keyword=$keyword, count=${songs.size}"
                )
                val items = songs.map(ExploreSearchResult::Song)
                updateSearchStateIfCurrent(requestVersion, SearchSource.NETEASE) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = songs,
                        searchItems = items,
                        searchPage = 1,
                        searchHasMore = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "search LX failed: request=$requestVersion, keyword=$keyword", e)
                updateSearchStateIfCurrent(requestVersion, SearchSource.NETEASE) {
                    it.copy(
                        searching = false,
                        searchError = feedbackText(
                            CoreCommonR.string.error_netease_search,
                            e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    private fun searchRecognizedLink(input: String, requestVersion: Long) {
        searchJob = viewModelScope.launch {
            try {
                val target = recognizeExploreLink(input)
                if (target == null) {
                    updateSearchStateIfCurrent(requestVersion, SearchSource.LINK_RECOGNITION) {
                        it.copy(
                            searching = false,
                            searchError = feedbackText(CoreCommonR.string.explore_link_invalid),
                            searchResults = emptyList(),
                            searchItems = emptyList(),
                            searchHasMore = false,
                            searchPage = 0
                        )
                    }
                    return@launch
                }

                val item = withContext(Dispatchers.IO) {
                    resolveExploreLinkTarget(target)
                }
                NPLogger.d(
                    TAG,
                    "link recognized: request=$requestVersion, target=$target, item=${item.stableKey}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.LINK_RECOGNITION) {
                    val items = listOf(item)
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = searchSongItems(items),
                        searchItems = items,
                        searchPage = 1,
                        searchHasMore = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "link recognition failed: request=$requestVersion, input=$input", e)
                updateSearchStateIfCurrent(requestVersion, SearchSource.LINK_RECOGNITION) {
                    it.copy(
                        searching = false,
                        searchError = feedbackText(
                            CoreCommonR.string.error_link_recognition,
                            e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    private suspend fun resolveExploreLinkTarget(target: ExploreLinkTarget): ExploreSearchResult {
        return when (target) {
            is ExploreLinkTarget.NeteaseSong -> ExploreSearchResult.Song(
                fetchLinkedNeteaseSong(target.id)
            )
            is ExploreLinkTarget.NeteasePlaylist -> ExploreSearchResult.Playlist(
                fetchLinkedNeteasePlaylist(target.id)
            )
            is ExploreLinkTarget.NeteaseArtist -> ExploreSearchResult.Artist(
                fetchLinkedNeteaseArtist(target.id)
            )
            is ExploreLinkTarget.NeteaseShortLink -> resolveExploreLinkTarget(
                resolveNeteaseShortLink(target.url)
            )
            is ExploreLinkTarget.BiliVideo -> fetchLinkedBiliVideo(target)
            is ExploreLinkTarget.BiliFavoriteFolder -> ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliFavoriteFolder(target.mediaId)
            )
            is ExploreLinkTarget.BiliFavoriteFolderByOwner -> ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliFavoriteFolder(target.ownerMid, target.folderId)
            )
            is ExploreLinkTarget.BiliCollection -> ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliCollection(target.ownerMid, target.seasonId)
            )
            is ExploreLinkTarget.BiliShortLink -> resolveExploreLinkTarget(
                resolveBiliShortLink(target.url)
            )
            is ExploreLinkTarget.YouTubeVideo -> ExploreSearchResult.Song(
                fetchLinkedYouTubeVideo(target)
            )
            is ExploreLinkTarget.YouTubePlaylist -> ExploreSearchResult.YouTubePlaylist(
                fetchLinkedYouTubePlaylist(target.playlistId)
            )
            is ExploreLinkTarget.Unsupported -> ExploreSearchResult.Notice(
                title = app.getString(CoreCommonR.string.explore_link_unsupported_title),
                message = app.getString(
                    CoreCommonR.string.explore_link_unsupported_message,
                    target.platform,
                    target.type
                )
            )
        }
    }

    private suspend fun resolveBiliShortLink(url: String): ExploreLinkTarget {
        val finalUrl = expandExploreRedirectUrl(url, AppContainer.sharedOkHttpClient)
        return recognizeExploreLink(finalUrl)
            ?.takeIf { it !is ExploreLinkTarget.BiliShortLink }
            ?: error(app.getString(CoreCommonR.string.explore_link_invalid))
    }

    private suspend fun resolveNeteaseShortLink(url: String): ExploreLinkTarget {
        val finalUrl = expandExploreRedirectUrl(url, AppContainer.sharedOkHttpClient)
        return recognizeExploreLink(finalUrl)
            ?.takeIf { it !is ExploreLinkTarget.NeteaseShortLink }
            ?: error(app.getString(CoreCommonR.string.explore_link_invalid))
    }

    private fun fetchLinkedNeteaseSong(songId: Long): SongItem {
        val raw = neteaseClient.getSongDetail(listOf(songId))
        return parseNeteaseSongDetail(raw) ?: SongItem(
            id = songId,
            name = app.getString(CoreCommonR.string.explore_link_netease_song_fallback, songId),
            artist = "",
            album = "",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = "https://music.163.com/#/song?id=$songId",
            channelId = "netease",
            audioId = songId.toString()
        )
    }

    private fun fetchLinkedNeteasePlaylist(playlistId: Long): PlaylistSummary {
        return runCatching {
            parseLinkedNeteasePlaylist(
                raw = neteaseClient.getPlaylistDetail(playlistId),
                fallbackId = playlistId
            )
        }.getOrElse {
            PlaylistSummary(
                id = playlistId,
                name = app.getString(CoreCommonR.string.explore_link_netease_playlist_fallback, playlistId),
                picUrl = "",
                playCount = 0L,
                trackCount = 0
            )
        }
    }

    private fun fetchLinkedNeteaseArtist(artistId: Long): NeteaseSearchArtistResult {
        return runCatching {
            parseLinkedNeteaseArtist(
                raw = neteaseClient.getArtistDetail(artistId),
                fallbackId = artistId,
                fallbackName = app.getString(CoreCommonR.string.explore_link_netease_artist_fallback, artistId)
            )
        }.getOrElse {
            NeteaseSearchArtistResult(
                artist = NeteaseArtistSummary(
                    id = artistId,
                    name = app.getString(CoreCommonR.string.explore_link_netease_artist_fallback, artistId)
                ),
                picUrl = null,
                musicSize = 0,
                albumSize = 0
            )
        }
    }

    private suspend fun fetchLinkedBiliVideo(
        target: ExploreLinkTarget.BiliVideo
    ): ExploreSearchResult {
        val info = target.bvid
            ?.let { biliClient.getVideoBasicInfoByBvid(it) }
            ?: target.avid
                ?.let { biliClient.getVideoBasicInfoByAvid(it) }
            ?: error(app.getString(CoreCommonR.string.explore_link_invalid))
        val collectionTarget = info.toExploreLinkCollectionTarget(target)
        if (collectionTarget != null) {
            return ExploreSearchResult.BilibiliPlaylist(
                fetchLinkedBiliCollection(
                    ownerMid = collectionTarget.ownerMid,
                    seasonId = collectionTarget.seasonId
                )
            )
        }
        return ExploreSearchResult.Song(info.toExploreLinkSong(target))
    }

    private suspend fun fetchLinkedBiliFavoriteFolder(mediaId: Long): BiliPlaylist {
        val folder = biliClient.getFavFolderInfo(mediaId)
        return folder.toExploreBiliPlaylist(BiliPlaylistKind.CREATED_FAVORITE)
    }

    private suspend fun fetchLinkedBiliFavoriteFolder(
        ownerMid: Long,
        folderId: Long
    ): BiliPlaylist {
        val created = biliClient.getUserCreatedFavFolders(ownerMid)
        created.firstOrNull { it.fid == folderId || it.mediaId == folderId }?.let { folder ->
            return hydrateLinkedBiliFolder(folder, BiliPlaylistKind.CREATED_FAVORITE)
        }

        val collected = runCatching {
            biliClient.getUserCollectedFavFolders(ownerMid)
        }.getOrDefault(emptyList())
        collected.firstOrNull { it.fid == folderId || it.mediaId == folderId }?.let { folder ->
            return hydrateLinkedBiliFolder(folder, BiliPlaylistKind.COLLECTED_FAVORITE)
        }
        error(app.getString(CoreCommonR.string.explore_link_invalid))
    }

    private suspend fun hydrateLinkedBiliFolder(
        folder: FavFolder,
        fallbackKind: BiliPlaylistKind
    ): BiliPlaylist {
        if (folder.itemType == BILI_RESOURCE_TYPE_COLLECTION) {
            return fetchLinkedBiliCollection(folder.mid, folder.mediaId)
        }
        val detail = runCatching {
            biliClient.getFavFolderInfo(folder.mediaId)
        }.getOrDefault(folder)
        return detail.toExploreBiliPlaylist(fallbackKind)
    }

    private suspend fun fetchLinkedBiliCollection(
        ownerMid: Long,
        seasonId: Long
    ): BiliPlaylist {
        val page = biliClient.getCollectionArchives(
            mid = ownerMid,
            seasonId = seasonId,
            page = 1
        )
        val meta = page.meta
        return BiliPlaylist(
            mediaId = meta.seasonId.takeIf { it > 0L } ?: seasonId,
            fid = 0L,
            mid = meta.mid.takeIf { it > 0L } ?: ownerMid,
            title = meta.title.ifBlank {
                app.getString(CoreCommonR.string.explore_link_bili_playlist_fallback, seasonId)
            },
            count = meta.total,
            coverUrl = meta.coverUrl,
            kind = BiliPlaylistKind.COLLECTION
        )
    }

    private fun FavFolder.toExploreBiliPlaylist(
        fallbackKind: BiliPlaylistKind
    ): BiliPlaylist {
        val resolvedKind = if (itemType == BILI_RESOURCE_TYPE_COLLECTION) {
            BiliPlaylistKind.COLLECTION
        } else {
            fallbackKind
        }
        return BiliPlaylist(
            mediaId = mediaId,
            fid = fid,
            mid = mid,
            title = title.ifBlank {
                app.getString(CoreCommonR.string.explore_link_bili_playlist_fallback, mediaId)
            },
            count = count,
            coverUrl = coverUrl.replaceFirst("http://", "https://"),
            kind = resolvedKind,
            subtitle = upperName
        )
    }

    private suspend fun fetchLinkedYouTubeVideo(target: ExploreLinkTarget.YouTubeVideo): SongItem {
        val matched = runCatching {
            AppContainer.youtubeMusicClient.search(target.videoId, limit = 5)
                .firstOrNull { it.videoId == target.videoId }
        }.getOrNull()
        if (matched != null) {
            val matchedSong = matched.toSongItem(app)
            return matchedSong.copy(
                coverUrl = matchedSong.coverUrl ?: youtubeMusicThumbnailUrl(matched.videoId),
                originalCoverUrl = matchedSong.originalCoverUrl
                    ?: youtubeMusicThumbnailUrl(matched.videoId),
                mediaUri = buildYouTubeMusicMediaUri(
                    videoId = matched.videoId,
                    playlistId = target.playlistId
                ),
                playlistContextId = target.playlistId
            )
        }

        val metadata = runCatching {
            AppContainer.youtubeMusicClient.getVideoMetadata(target.videoId)
        }.getOrNull()
        val displayAlbum = app.getString(CoreCommonR.string.youtube_search_type_video)
        val displayName = metadata?.title.orEmpty().ifBlank {
            app.getString(CoreCommonR.string.explore_link_youtube_video_fallback, target.videoId)
        }
        val displayArtist = metadata?.authorName.orEmpty().ifBlank { "YouTube" }
        val coverUrl = metadata?.thumbnailUrl.orEmpty().ifBlank {
            youtubeMusicThumbnailUrl(target.videoId)
        }
        return SongItem(
            id = stableYouTubeMusicId(target.videoId),
            name = displayName,
            artist = displayArtist,
            album = displayAlbum,
            albumId = stableYouTubeMusicId("${target.videoId}|$displayAlbum"),
            durationMs = 0L,
            coverUrl = coverUrl,
            mediaUri = buildYouTubeMusicMediaUri(
                videoId = target.videoId,
                playlistId = target.playlistId
            ),
            originalName = displayName,
            originalArtist = displayArtist,
            originalCoverUrl = coverUrl,
            channelId = "youtubeMusic",
            audioId = target.videoId,
            playlistContextId = target.playlistId
        )
    }

    private suspend fun fetchLinkedYouTubePlaylist(playlistId: String): YouTubeMusicPlaylist {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        return runCatching {
            val detail = AppContainer.youtubeMusicClient.getPlaylistDetailPreview(browseId)
            YouTubeMusicPlaylist(
                browseId = detail.browseId.ifBlank { browseId },
                playlistId = detail.playlistId.ifBlank { playlistId.removePrefix("VL") },
                title = detail.title.ifBlank {
                    app.getString(CoreCommonR.string.explore_link_youtube_playlist_fallback, playlistId)
                },
                subtitle = detail.subtitle,
                coverUrl = detail.coverUrl,
                trackCount = detail.trackCount ?: detail.tracks.size
            )
        }.getOrElse {
            YouTubeMusicPlaylist(
                browseId = browseId,
                playlistId = playlistId.removePrefix("VL"),
                title = app.getString(CoreCommonR.string.explore_link_youtube_playlist_fallback, playlistId),
                subtitle = "",
                coverUrl = "",
                trackCount = 0
            )
        }
    }

    private fun parseLinkedNeteasePlaylist(
        raw: String,
        fallbackId: Long
    ): PlaylistSummary {
        val root = JSONObject(raw)
        val playlist = root.optJSONObject("playlist")
            ?: return PlaylistSummary(
                id = fallbackId,
                name = app.getString(CoreCommonR.string.explore_link_netease_playlist_fallback, fallbackId),
                picUrl = "",
                playCount = 0L,
                trackCount = 0
            )
        return PlaylistSummary(
            id = playlist.optLong("id", fallbackId),
            name = playlist.optString(
                "name",
                app.getString(CoreCommonR.string.explore_link_netease_playlist_fallback, fallbackId)
            ),
            picUrl = playlist.optString("coverImgUrl", "")
                .replaceFirst("http://", "https://"),
            playCount = playlist.optLong("playCount", 0L),
            trackCount = playlist.optInt("trackCount", 0)
        )
    }

    private suspend fun fetchNeteaseSearchPage(
        keyword: String,
        matchQuery: String,
        page: Int,
        type: NeteaseExploreSearchType
    ): ExploreSearchFetchResult {
        val offset = (page - 1).coerceAtLeast(0) * NETEASE_SEARCH_PAGE_SIZE
        val raw = requestNeteaseSearch(keyword = keyword, offset = offset, type = type.apiType)
        val parsed = parseNeteaseSearchResults(raw, type)
        val items = if (type == NeteaseExploreSearchType.SONG) {
            rankExploreSongSearchResults(matchQuery, parsed.items.mapNotNull {
                (it as? ExploreSearchResult.Song)?.song
            }).map { ExploreSearchResult.Song(it) }
        } else {
            parsed.items
        }
        return ExploreSearchFetchResult(
            items = items,
            page = page,
            hasMore = hasMoreExploreSearchResults(
                totalCount = parsed.totalCount,
                loadedCount = offset + parsed.items.size,
                pageItemCount = parsed.items.size,
                pageSize = NETEASE_SEARCH_PAGE_SIZE
            )
        )
    }

    /**
     * 拉取多平台搜索的一页结果。
     *
     * 各平台的返回字段已经由平台层统一成 PlatformSearchResult，
     * 这里只负责映射成界面上能渲染的 ExploreSearchResult。
     */
    private suspend fun fetchPlatformSearchPage(
        source: SearchSource,
        keyword: String,
        matchQuery: String,
        page: Int,
        type: PlatformSearchType
    ): ExploreSearchFetchResult {
        val api = platformSearchApiFor(source)
            ?: return ExploreSearchFetchResult(items = emptyList(), page = page, hasMore = false)
        val results = api.search(keyword = keyword, type = type, page = page.coerceAtLeast(1))
        val items = if (type == PlatformSearchType.SONG) {
            rankExploreSongSearchResults(matchQuery, results.map { it.toSongItem() })
                .map { ExploreSearchResult.Song(it) }
        } else {
            results.map { it.toExploreResult() }
        }
        return ExploreSearchFetchResult(
            items = items,
            page = page,
            // 各平台搜索接口都不返回总数，拿到满页就认为还有下一页
            hasMore = results.size >= PLATFORM_SEARCH_PAGE_SIZE
        )
    }

    private fun platformSearchApiFor(source: SearchSource): PlatformSearchApi? = when (source) {
        SearchSource.QQ_MUSIC -> AppContainer.qqPlatformSearchApi
        SearchSource.KUGOU -> AppContainer.kugouSearchApi
        SearchSource.QISHUI -> AppContainer.qishuiSearchApi
        SearchSource.KUWO -> AppContainer.kuwoSearchApi
        else -> null
    }

    /** 平台搜索结果 -> 歌曲 */
    private fun PlatformSearchResult.toSongItem(): SongItem {
        val providerCode = provider.code
        return SongItem(
            id = stablePlatformSongId(provider, id),
            name = name,
            artist = subtitle,
            album = album,
            albumId = 0L,
            durationMs = durationMs,
            coverUrl = coverUrl,
            channelId = providerCode,
            audioId = id
        )
    }

    /** 平台搜索结果 -> 通用搜索结果（歌单 / 歌手复用网易云的展示模型） */
    private fun PlatformSearchResult.toExploreResult(): ExploreSearchResult {
        val providerCode = provider.code
        return when (type) {
            PlatformSearchType.PLAYLIST -> ExploreSearchResult.Playlist(
                playlist = PlaylistSummary(
                    id = stablePlatformSongId(provider, id),
                    name = name,
                    picUrl = coverUrl.orEmpty(),
                    playCount = 0L,
                    trackCount = extraCount.toInt()
                ),
                sourceTag = providerCode,
                webUrl = webUrl,
                owner = subtitle,
                rawId = id
            )
            PlatformSearchType.ARTIST -> ExploreSearchResult.Artist(
                result = NeteaseSearchArtistResult(
                    artist = NeteaseArtistSummary(
                        id = stablePlatformSongId(provider, id),
                        name = name
                    ),
                    picUrl = coverUrl,
                    musicSize = 0,
                    albumSize = 0
                ),
                sourceTag = providerCode,
                webUrl = webUrl
            )
            PlatformSearchType.SONG -> ExploreSearchResult.Song(toSongItem())
        }
    }

    suspend fun getVideoInfoByAvid(avid: Long): VideoBasicInfo {
        return withContext(Dispatchers.IO) {
            biliClient.getVideoBasicInfoByAvid(avid)
        }
    }

    /**
     * 网易云搜索请求体。
     *
     * 登录态优先走 WEAPI（保留账号相关结果）；未登录、或 WEAPI 被服务端拒收
     * （code 50000005）时改走 eapi：eapi 只用固定盐做签名，不需要账号 Cookie，
     * 因此**没登录也能搜**。两条通道返回的字段结构一致。
     */
    private suspend fun requestNeteaseSearch(keyword: String, offset: Int, type: Int): String {
        if (neteaseClient.hasLogin()) {
            val weapi = runCatching {
                withContext(Dispatchers.IO) {
                    neteaseClient.searchSongs(
                        keyword = keyword,
                        limit = NETEASE_SEARCH_PAGE_SIZE,
                        offset = offset,
                        type = type,
                        usePersistedCookies = true
                    )
                }
            }.getOrNull()
            if (weapi != null && !weapi.contains("\"code\":50000005")) {
                return weapi
            }
            NPLogger.d(TAG, "网易云 WEAPI 搜索被拒，改走 eapi 匿名通道")
        }
        return withContext(Dispatchers.IO) {
            neteaseClient.searchSongsEapiCancellable(
                keyword = keyword,
                limit = NETEASE_SEARCH_PAGE_SIZE,
                offset = offset,
                type = type
            )
        }
    }

    /**
     * 将 Bilibili 视频的分P转换为通用的 SongItem
     * @param page 分P信息
     * @param basicInfo 视频的基本信息
     * @param coverUrl 视频封面
     * @return 转换后的 SongItem
     */
    fun toSongItem(page: VideoPage, basicInfo: VideoBasicInfo, coverUrl: String): SongItem {
        return buildBiliPartSong(page, basicInfo, coverUrl)
    }

    private fun searchYouTubeMusic(keyword: String, matchQuery: String, requestVersion: Long) {
        if (!youtubeEnabled) return
        val searchType = _uiState.value.selectedYouTubeMusicSearchType
        searchJob = viewModelScope.launch {
            try {
                val items = when (searchType) {
                    YouTubeExploreSearchType.SONG,
                    YouTubeExploreSearchType.VIDEO -> {
                        val songs = withContext(Dispatchers.IO) {
                            AppContainer.youtubeMusicClient.search(
                                query = keyword,
                                limit = YOUTUBE_MUSIC_SEARCH_LIMIT,
                                filter = requireNotNull(searchType.filter)
                            ).map { it.toSongItem(app) }
                        }.let { rankExploreSongSearchResults(matchQuery, it) }
                        songs.map(ExploreSearchResult::Song)
                    }
                    YouTubeExploreSearchType.CREATOR -> {
                        withContext(Dispatchers.IO) {
                            AppContainer.youtubeMusicClient.searchCreators(
                                query = keyword,
                                limit = YOUTUBE_MUSIC_SEARCH_LIMIT
                            )
                        }.map(ExploreSearchResult::YouTubeCreator)
                    }
                }
                if (!isSearchRequestCurrent(requestVersion, SearchSource.YOUTUBE_MUSIC)) return@launch
                NPLogger.d(
                    TAG,
                    "search YouTube Music success: request=$requestVersion, type=$searchType, keyword=$keyword, count=${items.size}"
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.YOUTUBE_MUSIC) {
                    it.copy(
                        searching = false,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchResults = searchSongItems(items),
                        searchItems = items,
                        searchPage = 1,
                        searchHasMore = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(
                    TAG,
                    "search YouTube Music failed: request=$requestVersion, type=$searchType, keyword=$keyword",
                    e
                )
                updateSearchStateIfCurrent(requestVersion, SearchSource.YOUTUBE_MUSIC) {
                    it.copy(
                        searching = false,
                        searchError = feedbackText(
                            CoreCommonR.string.error_youtube_search,
                            e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
                        ),
                        searchResults = emptyList(),
                        searchItems = emptyList(),
                        searchHasMore = false,
                        searchLoadingMore = false,
                        searchLoadMoreError = null,
                        searchPage = 0
                    )
                }
            }
        }
    }

    /** 加载 YouTube Music 歌单列表 */
    fun loadYtMusicPlaylists() {
        if (!youtubeEnabled) return
        if (ytMusicPlaylistsJob?.isActive == true) {
            ytMusicPlaylistsPending = true
            NPLogger.d(TAG, "loadYtMusicPlaylists coalesced while loading")
            return
        }
        ytMusicPlaylistsPending = false
        _uiState.value = _uiState.value.copy(ytMusicPlaylistsLoading = true, ytMusicPlaylistsError = null)
        NPLogger.d(TAG, "loadYtMusicPlaylists start")
        ytMusicPlaylistsJob = viewModelScope.launch {
            try {
                val library = withContext(Dispatchers.IO) {
                    AppContainer.youtubeMusicClient.getLibraryPlaylists(
                        resolveMissingTrackCounts = false
                    )
                }
                val playlists = library.map { pl ->
                    YouTubeMusicPlaylist(
                        browseId = pl.browseId,
                        playlistId = pl.playlistId,
                        title = pl.title,
                        subtitle = pl.subtitle,
                        coverUrl = pl.coverUrl,
                        trackCount = pl.trackCount ?: 0
                    )
                }
                NPLogger.d(TAG, "loadYtMusicPlaylists success: count=${playlists.size}")
                _uiState.value = _uiState.value.copy(
                    ytMusicPlaylistsLoading = false,
                    ytMusicPlaylists = playlists,
                    ytMusicPlaylistsError = null
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                NPLogger.e(TAG, "loadYtMusicPlaylists failed", e)
                _uiState.value = _uiState.value.copy(
                    ytMusicPlaylistsLoading = false,
                    ytMusicPlaylistsError = feedbackText(CoreCommonR.string.error_youtube_search,
                        e.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message))
                )
            } finally {
                val completedJob = coroutineContext[Job]
                if (ytMusicPlaylistsJob === completedJob) {
                    ytMusicPlaylistsJob = null
                    if (ytMusicPlaylistsPending && youtubeEnabled) {
                        ytMusicPlaylistsPending = false
                        loadYtMusicPlaylists()
                    }
                }
            }
        }
    }

    private fun disableYouTubeSource() {
        if (_uiState.value.selectedSearchSource == SearchSource.YOUTUBE_MUSIC) {
            searchJob?.cancel()
            searchMoreJob?.cancel()
            invalidateSearchRequest()
        }
        ytMusicPlaylistsJob?.cancel()
        ytMusicPlaylistsJob = null
        ytMusicPlaylistsPending = false
        _uiState.value = _uiState.value.withYouTubeDisabled()
    }

    private fun searchErrorMessage(source: SearchSource, error: Exception): UiText {
        val fallback = error.message ?: feedbackText(CoreCommonR.string.github_sync_failed_message)
        return when (source) {
            SearchSource.NETEASE -> feedbackText(CoreCommonR.string.error_netease_search, fallback)
            SearchSource.BILIBILI -> feedbackText(CoreCommonR.string.error_bilibili_search, fallback)
            SearchSource.YOUTUBE_MUSIC -> feedbackText(CoreCommonR.string.error_youtube_search, fallback)
            SearchSource.LINK_RECOGNITION -> feedbackText(CoreCommonR.string.error_link_recognition, fallback)
            SearchSource.QQ_MUSIC,
            SearchSource.KUGOU,
            SearchSource.QISHUI,
            SearchSource.KUWO -> feedbackText(CoreCommonR.string.error_platform_search, fallback)
            SearchSource.MIGU -> feedbackText(CoreCommonR.string.error_platform_search_unsupported)
        }
    }
}

internal fun parseExploreHighQualityPlaylists(raw: String): List<PlaylistSummary> {
    val result = mutableListOf<PlaylistSummary>()
    val root = JSONObject(raw)
    val code = root.optInt("code", -1)
    if (code != 200) {
        NPLogger.w(TAG, "parsePlaylists unexpected code=$code")
        return emptyList()
    }
    val arr = root.optJSONArray("playlists") ?: return emptyList()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        result.add(PlaylistSummary(
            id = obj.optLong("id"),
            name = obj.optString("name"),
            picUrl = obj.optString("coverImgUrl").replace("http://", "https://"),
            playCount = obj.optLong("playCount"),
            trackCount = obj.optInt("trackCount")
        ))
    }
    return result
}

internal fun parseLinkedNeteaseArtist(
    raw: String,
    fallbackId: Long,
    fallbackName: String
): NeteaseSearchArtistResult {
    val root = JSONObject(raw)
    val artist = root.optJSONObject("data")?.optJSONObject("artist")
        ?: root.optJSONObject("artist")
    if (artist == null) {
        return NeteaseSearchArtistResult(
            artist = NeteaseArtistSummary(id = fallbackId, name = fallbackName),
            picUrl = null,
            musicSize = 0,
            albumSize = 0
        )
    }
    return NeteaseSearchArtistResult(
        artist = NeteaseArtistSummary(
            id = artist.optLong("id", fallbackId),
            name = artist.optString("name", fallbackName).ifBlank { fallbackName }
        ),
        picUrl = artist.linkedNeteaseArtistPicUrl(),
        musicSize = artist.optInt("musicSize", 0),
        albumSize = artist.optInt("albumSize", 0)
    )
}

private fun JSONObject.linkedNeteaseArtistPicUrl(): String? {
    return optString("cover", "")
        .ifBlank { optString("picUrl", "") }
        .ifBlank { optString("avatar", "") }
        .ifBlank { optString("img1v1Url", "") }
        .replaceFirst("http://", "https://")
        .takeIf { it.isNotBlank() }
}

/** Bilibili 搜索结果到通用 SongItem 的转换器 */
private fun SearchVideoItem.toSongItem(): SongItem {
    return SongItem(
        id = this.aid, // 使用 avid 作为唯一ID
        name = this.titlePlain,
        artist = this.author,
        album = buildBiliSongAlbum(bvid = this.bvid), // 标记来源
        albumId = 0L,
        durationMs = this.durationSec * 1000L,
        coverUrl = this.coverUrl,
        channelId = "bilibili",
        audioId = this.aid.toString()
    )
}

private fun VideoBasicInfo.toSongItem(): SongItem {
    return SongItem(
        id = aid,
        name = title,
        artist = ownerName,
        album = buildBiliSongAlbum(bvid = bvid),
        albumId = 0L,
        durationMs = durationSec * 1000L,
        coverUrl = coverUrl,
        channelId = "bilibili",
        audioId = aid.toString()
    )
}

internal fun VideoBasicInfo.toExploreLinkSong(
    target: ExploreLinkTarget.BiliVideo
): SongItem {
    val selectedPage = target.cid
        ?.let { cid -> pages.firstOrNull { page -> page.cid == cid } }
        ?: target.page?.let { pageNumber ->
            pages.firstOrNull { page -> page.page == pageNumber }
        }
    return selectedPage?.let { page ->
        toSongItem().copy(
            album = buildBiliSongAlbum(cid = page.cid, bvid = bvid),
            durationMs = page.durationSec * 1_000L,
            subAudioId = page.cid.toString()
        )
    } ?: toSongItem()
}

internal fun VideoBasicInfo.toExploreLinkCollectionTarget(
    target: ExploreLinkTarget.BiliVideo
): ExploreLinkTarget.BiliCollection? {
    if (!target.isCollectionShare) return null
    val seasonId = target.seasonId ?: ugcSeason?.id ?: return null
    val collectionOwnerMid = collectionOwnerMid() ?: return null
    return ExploreLinkTarget.BiliCollection(
        ownerMid = collectionOwnerMid,
        seasonId = seasonId
    )
}

private fun VideoBasicInfo.collectionOwnerMid(): Long? {
    val seasonMid = ugcSeason?.mid ?: 0L
    return when {
        seasonMid > 0L -> seasonMid
        ownerMid > 0L -> ownerMid
        else -> null
    }
}

internal fun YouTubeMusicSearchResult.toSongItem(app: Context): SongItem {
    val displayArtist = artist.ifBlank { "YouTube" }
    val displayAlbum = album.ifBlank {
        when (type) {
            YouTubeMusicSearchResultType.Song -> app.getString(CoreCommonR.string.youtube_search_type_song)
            YouTubeMusicSearchResultType.Video -> app.getString(CoreCommonR.string.youtube_search_type_video)
        }
    }
    return SongItem(
        id = stableYouTubeMusicId(videoId),
        name = title,
        artist = displayArtist,
        album = displayAlbum,
        albumId = stableYouTubeMusicId("$videoId|$displayAlbum"),
        durationMs = durationMs,
        coverUrl = coverUrl.ifBlank { youtubeMusicThumbnailUrl(videoId) },
        mediaUri = buildYouTubeMusicMediaUri(videoId),
        originalName = title,
        originalArtist = displayArtist,
        originalCoverUrl = coverUrl.ifBlank { youtubeMusicThumbnailUrl(videoId) },
        channelId = "youtubeMusic",
        audioId = videoId
    )
}

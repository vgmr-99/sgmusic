package moe.ouom.neriplayer.core.player.metadata

import android.app.Application
import android.util.LruCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.lyrics.repository.AmllLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.youtube.api.client.YouTubeMusicClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/**
 * 自动歌词模式下的外部歌词兜底。
 *
 * 背景：汽水 / 咪咕 / 酷我这些平台没有内置歌词接口，用 LX 音源播放时
 * `song.id` 拿去查网易云歌词必然落空。以前默认「自动」模式下完全没有
 * 多源匹配通道，用户手动指定歌词源才会触发，所以表现为「用 LX 音乐源都读不到歌词」。
 */
class PlayerLyricsProviderExternalFallbackTest {
    @Before
    @After
    fun resetLyricsCaches() {
        PlayerLyricsProvider.clearLyricsCaches()
    }

    @Test
    fun `automatic mode falls back to high confidence external lyrics when platform has none`() = runTest {
        val fixture = Fixture(
            id = 70_001L,
            matcherAnswers = mapOf(
                EditableLyricMatchSource.KUGOU to listOf(
                    rankedCandidate(
                        source = EditableLyricMatchSource.KUGOU,
                        title = TITLE,
                        artist = ARTIST,
                        durationMs = DURATION_MS,
                        lyrics = "[00:01.00]外部歌词第一行"
                    )
                )
            )
        )

        fixture.withEnvironment {
            val lyrics = fixture.original()

            assertEquals(listOf("外部歌词第一行"), lyrics.map { it.text })
            // 酷狗排在第一位，命中后不应该继续查询后面的源
            assertEquals(listOf(EditableLyricMatchSource.KUGOU), fixture.queriedSources)
        }
    }

    @Test
    fun `automatic mode keeps searching later sources when the first one has no usable match`() = runTest {
        val fixture = Fixture(
            id = 70_002L,
            matcherAnswers = mapOf(
                EditableLyricMatchSource.KUGOU to listOf(
                    rankedCandidate(
                        source = EditableLyricMatchSource.KUGOU,
                        title = TITLE,
                        artist = ARTIST,
                        durationMs = DURATION_MS,
                        lyrics = "[00:01.00]低置信歌词",
                        confidence = EditableLyricMatchConfidence.LOW
                    )
                ),
                EditableLyricMatchSource.QQ_MUSIC to listOf(
                    rankedCandidate(
                        source = EditableLyricMatchSource.QQ_MUSIC,
                        title = TITLE,
                        artist = ARTIST,
                        durationMs = DURATION_MS,
                        lyrics = "[00:01.00]QQ 命中歌词"
                    )
                )
            )
        )

        fixture.withEnvironment {
            val lyrics = fixture.original()

            assertEquals(listOf("QQ 命中歌词"), lyrics.map { it.text })
            assertEquals(
                listOf(
                    EditableLyricMatchSource.KUGOU,
                    EditableLyricMatchSource.CLOUD_MUSIC,
                    EditableLyricMatchSource.QQ_MUSIC
                ),
                fixture.queriedSources
            )
        }
    }

    @Test
    fun `automatic mode rejects external lyrics whose song identity does not match`() = runTest {
        val fixture = Fixture(
            id = 70_003L,
            matcherAnswers = mapOf(
                EditableLyricMatchSource.KUGOU to listOf(
                    rankedCandidate(
                        source = EditableLyricMatchSource.KUGOU,
                        title = "完全不同的歌",
                        artist = "另一个歌手",
                        durationMs = DURATION_MS,
                        lyrics = "[00:01.00]错版本歌词"
                    )
                )
            )
        )

        fixture.withEnvironment {
            val lyrics = fixture.original()

            assertTrue(lyrics.isEmpty())
        }
    }

    @Test
    fun `automatic mode does not query external sources when the platform already has lyrics`() = runTest {
        val fixture = Fixture(
            id = 70_004L,
            neteasePayload = """{"code":200,"lrc":{"lyric":"[00:01.00]平台自带歌词"}}""",
            matcherAnswers = mapOf(
                EditableLyricMatchSource.KUGOU to listOf(
                    rankedCandidate(
                        source = EditableLyricMatchSource.KUGOU,
                        title = TITLE,
                        artist = ARTIST,
                        durationMs = DURATION_MS,
                        lyrics = "[00:01.00]不应该出现的歌词"
                    )
                )
            )
        )

        fixture.withEnvironment {
            val lyrics = fixture.original()

            assertEquals(listOf("平台自带歌词"), lyrics.map { it.text })
            assertTrue(fixture.queriedSources.isEmpty())
        }
    }

    @Test
    fun `external lyrics are reused for the same song instead of re-querying the matcher`() = runTest {
        val fixture = Fixture(
            id = 70_005L,
            matcherAnswers = mapOf(
                EditableLyricMatchSource.KUGOU to listOf(
                    rankedCandidate(
                        source = EditableLyricMatchSource.KUGOU,
                        title = TITLE,
                        artist = ARTIST,
                        durationMs = DURATION_MS,
                        lyrics = "[00:01.00]缓存歌词"
                    )
                )
            )
        )

        fixture.withEnvironment {
            assertEquals(listOf("缓存歌词"), fixture.original().map { it.text })
            val queriedAfterFirstLoad = fixture.queriedSources.size
            assertEquals(listOf("缓存歌词"), fixture.original().map { it.text })

            assertEquals(queriedAfterFirstLoad, fixture.queriedSources.size)
        }
    }

    private class Fixture(
        val id: Long,
        val neteasePayload: String = """{"code":200,"nolyric":true,"lrc":{"lyric":""},"tlyric":{"lyric":""}}""",
        val matcherAnswers: Map<EditableLyricMatchSource, List<RankedEditableLyricMatch>> = emptyMap()
    ) {
        val song = SongItem(
            id = id,
            name = TITLE,
            artist = ARTIST,
            album = "某张专辑",
            albumId = 1L,
            durationMs = DURATION_MS,
            coverUrl = null,
            mediaUri = "https://example.com/audio.mp3",
            channelId = "qishui",
            audioId = "7675915828206094352"
        )
        val queriedSources = mutableListOf<EditableLyricMatchSource>()

        private val application = Application()
        private val netease = mock<NeteaseClient>(Answer { invocation ->
            if (invocation.method.name != "getLyricNew") return@Answer Answers.RETURNS_DEFAULTS.answer(invocation)
            neteasePayload
        })
        private val neteaseCache = mock<LruCache<Long, NeteaseLyricsCacheEntry>>(Answers.RETURNS_DEFAULTS)
        private val matcher = mock<EditableLyricsMatcher>(Answer { invocation ->
            when (invocation.method.name) {
                "matchHighConfidenceLyricsForSource" -> {
                    val source = invocation.getArgument<EditableLyricMatchSource>(1)
                    queriedSources += source
                    matcherAnswers[source].orEmpty()
                }

                "matchLyrics" -> emptyList<RankedEditableLyricMatch>()
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        private val amll = mock<AmllLyricsRepository>(Answers.RETURNS_DEFAULTS)
        private val youtube = Mockito.mock(YouTubeMusicClient::class.java)
        private val lrcLib = Mockito.mock(LrcLibLyricsRepository::class.java)
        private val youtubeCache = mock<LruCache<String, YouTubeMusicLyricsCacheEntry>>(Answers.RETURNS_DEFAULTS)
        private val biliClient = Mockito.mock(BiliClient::class.java)

        suspend fun original(): List<LyricEntry> =
            PlayerLyricsProvider.getLyrics(
                song, application, netease, neteaseCache, youtube, lrcLib, matcher, amll,
                false, false, LyricSourcePreference.Automatic, youtubeCache, "bilibili",
                biliClient, BiliSubtitleLyricsOptions()
            )

        suspend fun withEnvironment(block: suspend () -> Unit) {
            val registry = PlayerDependencies::class.java.getDeclaredField("registry")
                .apply { isAccessible = true }.get(null)
            val environment = registry.javaClass.getDeclaredField("environment").apply { isAccessible = true }
            val previous = environment.get(registry)
            environment.set(registry, null)
            try {
                PlayerDependencies.install(
                    PlayerEnvironment(
                        application,
                        Mockito.mock(PlayerRepositoryDependencies::class.java),
                        Mockito.mock(PlayerDownloadAccess::class.java),
                        Mockito.mock(PlayerListenTogetherAccess::class.java),
                        Mockito.mock(PlayerPresentationHost::class.java),
                        { false },
                        { Job().apply { complete() } }
                    )
                )
                block()
            } finally {
                environment.set(registry, previous)
            }
        }
    }

    private companion object {
        const val TITLE = "枪火（相对那就针锋相对）"
        const val ARTIST = "陈一发"
        const val DURATION_MS = 180_000L

        fun rankedCandidate(
            source: EditableLyricMatchSource,
            title: String,
            artist: String,
            durationMs: Long,
            lyrics: String,
            confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.HIGH
        ): RankedEditableLyricMatch {
            return RankedEditableLyricMatch(
                candidate = EditableLyricMatchCandidate(
                    id = "${source.name}-candidate",
                    source = source,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    lyrics = lyrics
                ),
                score = 120,
                durationDeltaMs = kotlin.math.abs(DURATION_MS - durationMs),
                confidence = confidence
            )
        }

        inline fun <reified T : Any> mock(answer: Answer<Any?>): T = Mockito.mock(T::class.java, answer)
    }
}

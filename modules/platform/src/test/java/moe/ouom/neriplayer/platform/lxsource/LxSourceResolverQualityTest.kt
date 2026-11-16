package moe.ouom.neriplayer.platform.lxsource

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LX 播放页音质可选项：只列出脚本声明支持的档位。
 */
class LxSourceResolverQualityTest {
    @Test
    fun `only declared qualities are exposed in fixed display order`() {
        val options = LxSourceResolver.lxQualityOptions(
            listOf("flac", "320k", "128k")
        )
        // 固定顺序 128k -> 320k -> 无损，与脚本返回的先后无关
        assertEquals(listOf("128k", "320k", "flac"), options.map { it.key })
    }

    @Test
    fun `custom declared qualities beyond standard order are still offered`() {
        val options = LxSourceResolver.lxQualityOptions(
            listOf("hires", "atmos", "320k")
        )
        // 标准序里只有 320k，其余自定义档一并列出来供切换
        assertEquals(setOf("320k", "hires", "atmos"), options.map { it.key }.toSet())
    }

    @Test
    fun `no declaration means no quality switch`() {
        assertEquals(emptyList<String>(), LxSourceResolver.lxQualityOptions(emptyList()).map { it.key })
        assertEquals(emptyList<String>(), LxSourceResolver.lxQualityOptions(listOf(" ", "")).map { it.key })
    }
}

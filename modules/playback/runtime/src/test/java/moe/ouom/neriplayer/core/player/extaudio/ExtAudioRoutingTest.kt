package moe.ouom.neriplayer.core.player.extaudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 安全边界：扩展解码器只服务本地文件，网络音源必须走原 Media3 链路。 */
class ExtAudioRoutingTest {

    @Test
    fun `http sources never use the extended decoder`() {
        assertFalse(shouldUseExtAudio("http", "track.ape"))
        assertFalse(shouldUseExtAudio("https", "track.wma"))
        assertFalse(shouldUseExtAudio("HTTPS", "TRACK.DSF"))
    }

    @Test
    fun `local extended formats are routed`() {
        assertTrue(shouldUseExtAudio("file", "track.ape"))
        assertTrue(shouldUseExtAudio("content", "track.WMA"))
        assertTrue(shouldUseExtAudio(null, "track.dsf"))
        assertTrue(shouldUseExtAudio("file", "track.dff"))
        assertTrue(shouldUseExtAudio("file", "track.aiff"))
    }

    @Test
    fun `local mainstream formats keep the stock pipeline`() {
        assertFalse(shouldUseExtAudio("file", "track.mp3"))
        assertFalse(shouldUseExtAudio("content", "track.flac"))
        assertFalse(shouldUseExtAudio("file", "track.m4a"))
        assertFalse(shouldUseExtAudio("file", "no-extension"))
        assertFalse(shouldUseExtAudio("file", null))
    }
}

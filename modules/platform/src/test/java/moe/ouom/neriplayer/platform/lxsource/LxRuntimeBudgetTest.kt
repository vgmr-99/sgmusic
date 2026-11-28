package moe.ouom.neriplayer.platform.lxsource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient

/**
 * 取流预算（多后端轮询音源防「一直加载」）的单测。
 *
 * 背景：墨澜这类音源在一条 evaluate 里同步跑 8+ 后端 × 各 8~15 秒，
 * 宿主的 deadline 轮询要等 evaluate 返回后才计时，形同虚设。
 * 预算下沉到原生 HTTP 桥：单请求超时被裁剪到剩余预算，耗尽后直接短路。
 */
class LxRuntimeBudgetTest {

    private fun runtime(): LxRuntime =
        LxRuntime(LxHttpClient(OkHttpClient.Builder().build()), "{}")

    @Test
    fun `no budget keeps the script timeout untouched`() {
        val r = runtime()
        // 预算未激活（0）：尊重脚本自己传的 timeout
        assertEquals(15_000L, r.resolveBudgetedTimeout(15_000L, 0L, now = 1_000L))
    }

    @Test
    fun `script timeout smaller than budget left is kept`() {
        val r = runtime()
        // 预算剩 10s，脚本要 3s：不裁剪，尊重脚本
        val budget = 11_000L
        assertEquals(3_000L, r.resolveBudgetedTimeout(3_000L, budget, now = 1_000L))
    }

    @Test
    fun `script timeout is clamped to remaining budget`() {
        val r = runtime()
        // 预算剩 5s，脚本要 15s：裁剪到剩余的 5s
        val budget = 6_000L
        assertEquals(5_000L, r.resolveBudgetedTimeout(15_000L, budget, now = 1_000L))
    }

    @Test
    fun `exhausted budget signals short circuit`() {
        val r = runtime()
        // 预算已过：-1 让桥直接短路返回超时，不再发请求
        val budget = 5_000L
        assertEquals(-1L, r.resolveBudgetedTimeout(15_000L, budget, now = 5_001L))
    }

    @Test
    fun `exactly at the deadline counts as exhausted`() {
        val r = runtime()
        val budget = 5_000L
        assertEquals(-1L, r.resolveBudgetedTimeout(15_000L, budget, now = 5_000L))
    }

    @Test
    fun `clamped timeout never exceeds the remaining budget`() {
        val r = runtime()
        // 一串时序模拟：预算 20s，脚本每个后端要 15s
        var now = 0L
        val budget = now + 20_000L
        // 第 1 个请求：不裁剪（15s < 20s）
        var t = r.resolveBudgetedTimeout(15_000L, budget, now)
        assertEquals(15_000L, t)
        // 假设第 1 个后端跑满 15s 才失败
        now += 15_000L
        // 第 2 个请求：只剩 5s，被裁剪
        t = r.resolveBudgetedTimeout(15_000L, budget, now)
        assertEquals(5_000L, t)
        // 第 2 个也跑满
        now += 5_000L
        // 第 3 个请求：预算耗尽，短路
        assertTrue(r.resolveBudgetedTimeout(15_000L, budget, now) < 0L)
    }
}

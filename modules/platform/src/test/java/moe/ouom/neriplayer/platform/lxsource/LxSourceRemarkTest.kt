package moe.ouom.neriplayer.platform.lxsource

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** LX 脚本备注名：纯展示字段，不能改变文件名（同名替换语义依赖文件名）。 */
class LxSourceRemarkTest {

    private fun fixture(): Pair<LxSourceRepository, InMemoryLxSourceStore> {
        val store = InMemoryLxSourceStore()
        val repository = LxSourceRepository(
            http = LxHttpClient(OkHttpClient()),
            preloadJs = "",
            store = store
        )
        return repository to store
    }

    @Test
    fun `legacy items default to empty remark`() {
        val item = LxSourceItem(id = "1", name = "legacy.js", scriptText = "//script")
        assertEquals("", item.remark)
    }

    @Test
    fun `setRemark persists remark without changing the script file name`() = runTest {
        val (repository, store) = fixture()
        // 停用状态避免 init() 触发 QuickJS 求值（JVM 单测没有原生运行时）
        store.save(
            LxSourceItem(
                id = "1",
                name = "netease-backup.js",
                scriptText = "//script",
                enabled = false
            )
        )
        repository.init()

        repository.setRemark("1", "  网易云备用  ")

        val updated = repository.items.first().single()
        assertEquals("网易云备用", updated.remark)
        assertEquals("netease-backup.js", updated.name)
        assertEquals("网易云备用", store.loadAll().single().remark)
    }

    @Test
    fun `setRemark can clear a remark back to the file name fallback`() = runTest {
        val (repository, store) = fixture()
        store.save(
            LxSourceItem(id = "2", name = "bili.js", scriptText = "//", remark = "B站", enabled = false)
        )
        repository.init()

        repository.setRemark("2", "")

        assertEquals("", repository.items.first().single().remark)
    }
}

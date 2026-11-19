package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DownloadOperationDao 的高风险 SQL 回归测试。
 *
 * 该 DAO 有 112 个 @Query，其中游标分页 (queue_order, created_at_ms, operation_id)
 * 与 source_hint_json 的 instr 匹配极易在改动时出错，且此前零测试覆盖。
 * 这里用真实内存 SQLite 验证分页不重不漏、backlog 判断正确。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DownloadOperationDaoPagingTest {
    private lateinit var db: NeriUserDataDatabase
    private lateinit var dao: DownloadOperationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.downloadOperationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun operation(
        id: String,
        queueOrder: Int,
        createdAtMs: Long,
        state: String = "pending",
        sourceHintJson: String = "{}",
        stopRequestedByUser: Boolean = false
    ) = DownloadOperationEntity(
        operationId = id,
        stableKey = "stable-$id",
        libraryId = "lib-$id",
        state = state,
        queueOrder = queueOrder,
        sourceHintJson = sourceHintJson,
        stagingDirName = "staging-$id",
        bytesWritten = 0L,
        totalBytes = null,
        resumeJson = null,
        retryCount = 0,
        nextRetryAtMs = null,
        lastErrorCode = null,
        stopRequestedByUser = stopRequestedByUser,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs
    )

    @Test
    fun `postCoreHeaders pages by cursor without gaps or duplicates`() = runTest {
        // queue_order 有意重复，验证复合游标用 created_at_ms + operation_id 打破平局
        dao.upsert(operation("a", queueOrder = 0, createdAtMs = 100))
        dao.upsert(operation("b", queueOrder = 0, createdAtMs = 100))
        dao.upsert(operation("c", queueOrder = 1, createdAtMs = 200))
        dao.upsert(operation("d", queueOrder = 2, createdAtMs = 300))
        dao.upsert(operation("e", queueOrder = 3, createdAtMs = 400))

        val page1 = dao.postCoreHeadersFirst("pending", limit = 2)
        assertEquals(listOf("a", "b"), page1.map { it.operationId })

        val last = page1.last()
        val page2 = dao.postCoreHeadersAfter(
            "pending", last.queueOrder, last.createdAtMs, last.operationId, limit = 2
        )
        assertEquals(listOf("c", "d"), page2.map { it.operationId })

        val last2 = page2.last()
        val page3 = dao.postCoreHeadersAfter(
            "pending", last2.queueOrder, last2.createdAtMs, last2.operationId, limit = 2
        )
        assertEquals(listOf("e"), page3.map { it.operationId })

        val all = (page1 + page2 + page3).map { it.operationId }
        assertEquals(all.toSet().size, all.size) // 不重复
        assertEquals(5, all.size) // 不遗漏
    }

    @Test
    fun `postCoreHeaders excludes user stopped operations`() = runTest {
        dao.upsert(operation("keep", 0, 100))
        dao.upsert(operation("stopped", 1, 200, stopRequestedByUser = true))

        val page = dao.postCoreHeadersFirst("pending", limit = 10)
        assertEquals(listOf("keep"), page.map { it.operationId })
        assertTrue(dao.hasPostCoreBacklog(listOf("pending")))
    }

    @Test
    fun `hasPostCoreBacklog only counts pending states`() = runTest {
        assertFalse(dao.hasPostCoreBacklog(listOf("pending")))

        dao.upsert(operation("done", 0, 100, state = "completed"))
        assertFalse(dao.hasPostCoreBacklog(listOf("pending")))

        dao.upsert(operation("waiting", 1, 200, state = "pending"))
        assertTrue(dao.hasPostCoreBacklog(listOf("pending")))
    }

    @Test
    fun `hasMobilePostCoreBacklog matches the requiresWifi false hint`() = runTest {
        dao.upsert(operation("wifi-only", 0, 100, sourceHintJson = "{\"a\":1,\"requiresWifiNetwork\":true,\"b\":2}"))
        assertFalse(dao.hasMobilePostCoreBacklog(listOf("pending")))

        dao.upsert(operation("mobile-ok", 1, 200, sourceHintJson = "{\"a\":1,\"requiresWifiNetwork\":false,\"b\":2}"))
        assertTrue(dao.hasMobilePostCoreBacklog(listOf("pending")))
    }
}

package moe.ouom.neriplayer.data.settings.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PreviewThemePromptRepositoryTest {

    @Test
    fun `a fresh install defaults to clean without showing the preview prompt`() = runTest {
        withStore {
            assertFalse(PreviewThemePromptRepository(it).shouldPromptFlow.first())
            // 无存储值时按默认 Clean，不应出现预览主题二选一弹窗
        }
    }

    @Test
    fun `preview choice is never prompted regardless of stored style`() = runTest {
        withStore { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }
            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())

            store.edit { it[SettingsKeys.UI_STYLE] = "clean" }
            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())
        }
    }

    @Test
    fun `declining survives reopening storage and keeps the existing theme`() = runTest {
        val file = "reopen-test"
        withStore(file) { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }

            PreviewThemePromptRepository(store).answer(enable = false)

            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
        }
        withStore(file) { store ->
            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())
            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
        }
    }

    @Test
    fun `accepting publishes clean and updates the startup cache after one commit`() = runTest {
        withStore { store ->
            store.edit { it[SettingsKeys.DYNAMIC_COLOR] = false }
            val writes = ControlledWritesStore(store)
            val preferences = InMemorySharedPreferencesRegistry()
            val context = preferences.install(mock(Context::class.java))
            val repository = PreviewThemePromptRepository(writes) { style ->
                assertEquals(1, writes.successfulWrites)
                persistThemeUiStyle(context, style)
            }

            repository.answer(enable = true)

            val styles = store.data.map { UiStyle.fromStorage(it[SettingsKeys.UI_STYLE]) }
            assertEquals(UiStyle.Clean, styles.first())
            assertFalse(repository.shouldPromptFlow.first())
            assertFalse(store.data.first()[SettingsKeys.DYNAMIC_COLOR] ?: true)
            assertEquals("clean", preferences["theme_snapshot_cache"].snapshot()["ui_style"])
            assertEquals(1, writes.successfulWrites)
        }
    }

    @Test
    fun `a failed answer keeps the choice pending without changing theme or cache`() = runTest {
        withStore { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }
            val failure = IOException("settings write failed")
            val writes = ControlledWritesStore(store).apply { writeFailure = failure }
            val preferences = InMemorySharedPreferencesRegistry()
            val context = preferences.install(mock(Context::class.java))
            val repository = PreviewThemePromptRepository(writes) { persistThemeUiStyle(context, it) }

            val result = runCatching { repository.answer(enable = true) }

            assertSame(failure, result.exceptionOrNull())
            assertFalse(repository.shouldPromptFlow.first())
            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
            assertTrue(preferences["theme_snapshot_cache"].snapshot().isEmpty())
            assertEquals(0, writes.successfulWrites)
        }
    }

    @Test
    fun `already enabled clean counts as answered after switching back to material`() = runTest {
        val file = "reopen-test"
        withStore(file) { store ->
            store.edit { it[SettingsKeys.UI_STYLE] = "clean" }

            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())
            store.edit { it[SettingsKeys.UI_STYLE] = "material" }
        }
        withStore(file) { store ->
            assertFalse(PreviewThemePromptRepository(store).shouldPromptFlow.first())
            assertEquals("material", store.data.first()[SettingsKeys.UI_STYLE])
        }
    }

    private val memoryStores = mutableMapOf<String, MemoryPreferencesDataStore>()

    private suspend fun TestScope.withStore(
        key: String = "default-${System.nanoTime()}",
        action: suspend (DataStore<Preferences>) -> Unit
    ) {
        // 用内存 DataStore 代替文件实现，规避 Windows 上 .tmp rename 的偶发竞争；
        // 按 key 缓存以模拟「重新打开同一份存储」的持久化语义。
        val store = memoryStores.getOrPut(key) { MemoryPreferencesDataStore() }
        action(store)
    }

    /** 仅用于本测试的最小 DataStore<Preferences> 实现。 */
    private class MemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class ControlledWritesStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var writeFailure: IOException? = null
        var successfulWrites = 0
            private set
        override val data = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeFailure?.let { throw it }
            return delegate.updateData(transform).also { successfulWrites++ }
        }
    }
}

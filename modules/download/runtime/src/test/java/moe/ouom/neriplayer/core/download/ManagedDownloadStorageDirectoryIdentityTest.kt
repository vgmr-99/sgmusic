package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageDirectoryIdentityTest {

    @Test
    fun `directoryIdentity uses stable tree document id`() {
        val rawUri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FTimbre/document/primary%3AMusic%2FTimbre"

        val identity = ManagedDownloadStorage.directoryIdentity(rawUri)

        assertEquals(
            "tree:com.android.externalstorage.documents:primary:Music/Timbre",
            identity
        )
    }

    @Test
    fun `directoryIdentity ignores query and fragment noise`() {
        val rawUri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FTimbre?persisted=true#anchor"

        val identity = ManagedDownloadStorage.directoryIdentity(rawUri)

        assertEquals(
            "tree:com.android.externalstorage.documents:primary:Music/Timbre",
            identity
        )
    }

    @Test
    fun `areEquivalentDirectoryUris matches same directory with different uri text`() {
        val first =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FTimbre"
        val second =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FTimbre/document/primary%3AMusic%2FTimbre?fromPicker=1"

        assertTrue(ManagedDownloadStorage.areEquivalentDirectoryUris(first, second))
    }

    @Test
    fun `areEquivalentDirectoryUris distinguishes different directories`() {
        val first =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FTimbre"
        val second =
            "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTimbre"

        assertFalse(ManagedDownloadStorage.areEquivalentDirectoryUris(first, second))
    }

    @Test
    fun `areEquivalentDirectoryUris treats null pair as default directory`() {
        assertTrue(ManagedDownloadStorage.areEquivalentDirectoryUris(null, null))
    }
}

package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceDatabaseTest {
    @Test fun `network cache refresh preserves favorites and local usage metadata`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, SpaceDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.spaceDao()
            val original = SpaceEntity("owner/demo", "owner", "demo", "Old", "", "gradio", "Image Generation",
                10, "https://owner-demo.hf.space", isFavorite = true, isCurated = true, lastUsedAt = 123L)
            dao.insertSpace(original)
            dao.cacheSpaces(listOf(original.copy(title = "New", likes = 99, isFavorite = false,
                isCurated = false, lastUsedAt = 0L)))
            val refreshed = dao.getSpaceById(original.id)!!
            assertEquals("New", refreshed.title)
            assertEquals(99, refreshed.likes)
            assertTrue(refreshed.isFavorite)
            assertTrue(refreshed.isCurated)
            assertEquals(123L, refreshed.lastUsedAt)

            dao.setFavorite(original.id, false)
            dao.cacheSpaces(listOf(original))
            assertFalse(dao.getSpaceById(original.id)!!.isFavorite)
        } finally {
            db.close()
        }
    }
}

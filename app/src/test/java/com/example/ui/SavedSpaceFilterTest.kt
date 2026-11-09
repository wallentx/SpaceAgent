package com.example.ui

import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class SavedSpaceFilterTest {
    private val favorite = HfSpace("owner/art", "owner", "art", "Local Art", "Offline generator", "gradio",
        "Image Generation", 3, "https://owner-art.hf.space", isFavorite = true)

    @Test fun `search category and SDK apply to saved entries without a live search result`() {
        val filter = SavedSpaceFilter("art generator", SpaceCategoryFilter.IMAGE_GEN, SpaceSdkFilter.GRADIO, SpaceSortOption.LIKES)
        val spaces = listOf(favorite, favorite.copy(id = "owner/not-saved", isFavorite = false),
            favorite.copy(id = "owner/docker", sdk = "docker"), favorite.copy(id = "owner/code", category = "Code & Dev"))
        assertEquals(listOf(favorite), filter.apply(spaces))
        assertTrue(filter.copy(query = "missing").apply(spaces).isEmpty())
    }

    @Test fun `removing a favorite immediately removes it from saved results`() {
        val filter = SavedSpaceFilter("", SpaceCategoryFilter.ALL, SpaceSdkFilter.ALL, SpaceSortOption.LIKES)
        assertEquals(listOf(favorite), filter.apply(listOf(favorite)))
        assertTrue(filter.apply(listOf(favorite.copy(isFavorite = false))).isEmpty())
    }
}

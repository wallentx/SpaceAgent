package com.example.ui

import com.example.model.HfSpace
import com.example.model.SpaceCategoryFilter
import com.example.model.SpaceSdkFilter
import com.example.model.SpaceSortOption

internal data class SavedSpaceFilter(
    val query: String,
    val category: SpaceCategoryFilter,
    val sdk: SpaceSdkFilter,
    val sort: SpaceSortOption
) {
    fun apply(spaces: List<HfSpace>): List<HfSpace> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = spaces.filter { space ->
            val text = "${space.id} ${space.title} ${space.description} ${space.category}"
            space.isFavorite && terms.all { text.contains(it, ignoreCase = true) } &&
                (category == SpaceCategoryFilter.ALL || space.category.equals(category.displayName, true) ||
                    space.category.equals(category.id, true)) &&
                (sdk == SpaceSdkFilter.ALL || space.sdk.equals(sdk.sdkKey, true))
        }
        return if (sort == SpaceSortOption.LIKES) filtered.sortedByDescending { it.likes } else filtered
    }
}

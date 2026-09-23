package com.cloudimage.core.network.wallhaven

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Wallhaven API (https://wallhaven.cc/help/api).
 *
 * Every field except [WallhavenWallpaperDto.id] is optional: provider APIs
 * evolve, and a missing field must degrade — not crash — thanks to the
 * shared `Json { ignoreUnknownKeys = true }` configuration.
 */
@Serializable
data class WallhavenSearchResponseDto(
    val data: List<WallhavenWallpaperDto> = emptyList(),
    val meta: WallhavenMetaDto = WallhavenMetaDto(),
)

@Serializable
data class WallhavenWallpaperDto(
    val id: String,
    val url: String? = null,
    @SerialName("short_url") val shortUrl: String? = null,
    val views: Int? = null,
    val favorites: Int? = null,
    val source: String? = null,
    val purity: String? = null,
    val category: String? = null,
    @SerialName("dimension_x") val dimensionX: Int? = null,
    @SerialName("dimension_y") val dimensionY: Int? = null,
    val resolution: String? = null,
    val ratio: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val colors: List<String> = emptyList(),
    val path: String? = null,
    val thumbs: WallhavenThumbsDto? = null,
)

@Serializable
data class WallhavenThumbsDto(
    val large: String? = null,
    val original: String? = null,
    val small: String? = null,
)

/** Pagination envelope; Wallhaven reports pages 1-based. */
@Serializable
data class WallhavenMetaDto(
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("last_page") val lastPage: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val total: Int? = null,
    val query: String? = null,
    val seed: String? = null,
) {
    /** True when `current_page < last_page`; false when both are known and equal. */
    val hasNextPage: Boolean
        get() = currentPage != null && lastPage != null && currentPage < lastPage
}

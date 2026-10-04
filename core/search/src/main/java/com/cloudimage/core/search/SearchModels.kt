package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper

/**
 * The provider id minted onto every wallpaper the global search returns.
 * It is deliberately not a real source id — the detail screen's
 * recommendation row and per-source detail lookups simply find no match and
 * degrade silently, while favorites, downloads, apply and share treat a
 * search result exactly like any other wallpaper.
 */
const val GLOBAL_SEARCH_PROVIDER_ID = "global-search"

/**
 * How large a search result must be. [ANY] passes everything; every other
 * tier demands the tier's resolution fits inside the image in EITHER
 * orientation — a 2160x3840 phone wallpaper counts as 4K, an ultrawide
 * 3840x1600 does not (its short side is only FHD-class).
 *
 * Results whose dimensions are unknown never pass a real tier: we cannot
 * verify them, and a wallpaper app that guesses lies to its users.
 */
enum class SearchSizeTier(
    val minWidth: Int,
    val minHeight: Int,
) {
    ANY(0, 0),
    HD(1280, 720),
    FHD(1920, 1080),
    QHD(2560, 1440),
    UHD(3840, 2160),
    ;

    /** Whether a result of these (possibly unknown) dimensions passes this tier. */
    fun matches(
        width: Int?,
        height: Int?,
    ): Boolean {
        if (this == ANY) return true
        if (width == null || height == null || width <= 0 || height <= 0) return false
        return (width >= minWidth && height >= minHeight) || (height >= minWidth && width >= minHeight)
    }

    /**
     * The query modifier that biases a search toward this tier — the same
     * vocabulary the backend's own pagination rides on ("hd", "high
     * resolution", "4k"), appended to the query so every page leans toward
     * the size asked for. Null for [ANY]: an unfiltered search sends the
     * query verbatim. This only BIASSES; [matches] is still the exact
     * verification that decides what survives.
     */
    fun queryToken(): String? =
        when (this) {
            ANY -> null
            HD -> "hd"
            FHD -> "high resolution"
            QHD -> "4k"
            UHD -> "4k"
        }
}

/** Filters applied to a global image search. */
data class GlobalSearchFilters(
    val sizeTier: SearchSizeTier = SearchSizeTier.ANY,
    val safeSearch: Boolean = true,
)

/**
 * One page of engine results. [hasMore] comes from the RAW batch — an
 * all-filtered or all-duplicate page must not kill infinite scroll, because
 * the next page may still hold fresh matches.
 */
data class ImageSearchPage(
    val results: List<Wallpaper>,
    val hasMore: Boolean,
    val page: Int,
)

/** Why a global search failed — each value maps to its own user-facing message. */
enum class ImageSearchError {
    /** The free engine is throttling us right now; retrying after a pause usually works. */
    RATE_LIMITED,

    /** Transport-level failure (no route, DNS, reset). */
    NETWORK,

    /** The exchange exceeded the client's timeouts. */
    TIMEOUT,

    /** The engine answered, but with an HTTP error status. */
    SERVER,

    /** The engine answered 2xx but the body was not what the contract promises. */
    BAD_RESPONSE,
}

/** The engine contract's result type: typed failures, never exceptions. */
sealed interface ImageSearchResult {
    data class Success(
        val page: ImageSearchPage,
    ) : ImageSearchResult

    data class Failure(
        val error: ImageSearchError,
        val detail: String? = null,
    ) : ImageSearchResult
}

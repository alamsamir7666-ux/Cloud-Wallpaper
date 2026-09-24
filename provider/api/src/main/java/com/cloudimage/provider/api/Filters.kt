package com.cloudimage.provider.api

/**
 * Provider-agnostic filter selections: selected values grouped by key.
 *
 * The host UI renders filter chips from values the provider (or the app,
 * for built-ins) understands, packs the selection into a [Filters] and
 * passes it to [WallpaperProvider.search]. Each provider documents its own
 * keys — unknown keys are ignored by that provider, and the host treats
 * the whole object as opaque. Example:
 * `Filters.of("categories" to "anime", "purity" to "110")`.
 *
 * ## Host vocabulary
 *
 * The app's browse UI speaks a fixed vocabulary of keys and values. A
 * provider that understands them slots into the shared filter sheet
 * without any bespoke UI; anything else it declares would need its own
 * rendering (V2 territory):
 *
 * - `"query"` — free text; also mirrored into the [WallpaperProvider.search]
 *   `query` parameter, so this key is rarely needed inside filters,
 * - `"category"` — `"general"` / `"anime"` / `"people"` (multi-select),
 * - `"purity"` — `"sfw"` / `"sketchy"` (multi-select; the host never
 *   requests `"nsfw"` in V1 — it drops that value before calling),
 * - `"sorting"` — `"toplist"` / `"date"` / `"random"` / `"relevance"`,
 * - `"order"` — `"desc"` / `"asc"`,
 * - `"seed"` — a random-sort stabilizer (single value; the host generates
 *   one per feed session).
 *
 * Providers ignore whatever they cannot express; e.g. an API without
 * category buckets simply never reads `"category"`.
 */
class Filters private constructor(
    internal val selections: Map<String, Set<String>>,
) {
    /** True when no key has any selected value. */
    val isEmpty: Boolean get() = selections.values.all { it.isEmpty() }

    /** True when [value] is selected under [key]. */
    fun isSelected(
        key: String,
        value: String,
    ): Boolean = selections[key]?.contains(value) == true

    /** All selected values for [key]; empty when the key is untouched. */
    fun valuesFor(key: String): Set<String> = selections[key].orEmpty()

    override fun equals(other: Any?): Boolean = other is Filters && other.selections == selections

    override fun hashCode(): Int = selections.hashCode()

    override fun toString(): String = "Filters($selections)"

    companion object {
        /** The neutral element — nothing selected, every key untouched. */
        val None: Filters = Filters(emptyMap())

        /** Builds from single-value pairs; later pairs under a key accumulate. */
        fun of(vararg selections: Pair<String, String>): Filters =
            of(
                selections
                    .groupBy(keySelector = { (key, _) ->
                        key
                    }, valueTransform = { (_, value) -> value })
                    .mapValues { (_, values) -> values.toSet() },
            )

        /** Builds from explicit key-to-values mappings; empty sets are dropped. */
        fun of(selections: Map<String, Set<String>>): Filters = Filters(selections.filterValues { it.isNotEmpty() })
    }
}

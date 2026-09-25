package com.cloudimage.provider.api

/**
 * One named feed of the home screen (v1.0.9) — the CloudStream `mainPage`
 * model: a home is not one grid but a stack of titled section rows, each
 * with its own pagination.
 *
 * A section is a query preset: [filters] speaks the host vocabulary
 * documented on [Filters] (`category`, `sorting`, `order`, `seed`); the
 * host translates it into its own query pipeline and keeps ownership of
 * content ratings (the user's SFW setting always applies on top). Keys the
 * host does not understand are ignored, exactly like the filter sheet.
 *
 * Implementations should be cheap and offline — the host calls
 * [WallpaperProvider.sections] on every feed start.
 */
data class HomeSection(
    /** Stable identifier of the section inside its provider. */
    val id: String,
    /** Row title, shown verbatim unless the host recognizes the id. */
    val title: String,
    /** Host-vocabulary filter preset this section loads with. */
    val filters: Filters = Filters.None,
) {
    companion object {
        /**
         * The id of the default section every provider that does not
         * override [WallpaperProvider.sections] gets. The host uses it to
         * recognize "the provider only has the generic feed" and can then
         * label the row with the source name instead.
         */
        const val DEFAULT_ID = "popular"
    }
}

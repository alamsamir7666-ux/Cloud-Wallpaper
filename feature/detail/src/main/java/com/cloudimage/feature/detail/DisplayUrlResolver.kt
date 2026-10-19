package com.cloudimage.feature.detail

import java.net.URI

/**
 * The fullscreen viewer's display tier (v1.2.26): a lighter URL for the
 * same image, shown instantly while — or instead of — a multi-megabyte
 * original that slow networks cannot carry.
 *
 * ## Why this exists
 *
 * The preview screen used to mount the ORIGINAL file and nothing else.
 * For most sources that is a reasonable file, but scraping-grade sites
 * publish originals no phone network can move in one piece: the v1.2.26
 * live audit of wallpapercave.com measured the newest feed at 2.5–7.9 MB
 * per original (5120×2880 user uploads) while the site itself ships
 * card- and display-sized renders of the same files that are one to two
 * orders of magnitude smaller. On a 32 KB/s mobile connection — the very
 * conditions the field report came from — a 6 MB original needs three
 * minutes of continuous transfer and dies at the first twenty-second
 * stall, while the 250 KB display render lands in eight seconds. The
 * symptom was exactly that asymmetry: grids full of small thumbnails,
 * a fullscreen preview stuck on "Tap to retry".
 *
 * The viewer therefore loads a display tier FIRST when one is known
 * (see [WallpaperViewerPage]): it renders within seconds on any network,
 * and the original still loads behind it — the tier stays visible under
 * the failure surface if the original never arrives, instead of a black
 * screen with a retry pill. Apply, save and share keep downloading the
 * original itself; the tier is a display concern only.
 *
 * ## The rewrite table
 *
 * Only URL families that were verified live (2026-10-19, several files
 * per family) are rewritten; everything else answers [url] unchanged:
 *
 * - `wallpapercave.com/wp/{file}` → `/dwp2x/{file}` — the site's own 2x
 *   display render: a JPEG capped at 1400 px wide, 100–250 KB observed,
 *   always decodable. Files whose original is smaller than the cap are
 *   served byte-identical from `/dwp2x/`, so the rewrite never wastes
 *   quality or bytes on them.
 * - `wallpapercave.com/uwp/{file}` → `/fuwp/{file}` — the user-upload
 *   preview, a ~330 px JPEG around 10 KB (the originals run 2.5–7.9 MB;
 *   `/dwp1x/` and `/dwp2x/` answer 415 for user uploads, so `/fuwp/` is
 *   the only decodable tier that family has).
 * - `mir-s3-cdn-cf.behance.net/project_modules/source/{file}` →
 *   `project_modules/fs/{file}` — the Behance extension's originals (the
 *   `source` family) measured 1–3.6 MB per file while the `fs` render —
 *   1920 px, the size the site's own project viewer shows — carries the
 *   same image in 0.1–1.1 MB (3–9× smaller, verified live across
 *   modules). The CDN is open, so the tier needs no clearance.
 *
 * The match is on whole path segments — `/wp/` and `/uwp/` as complete
 * directories, never as substrings, so thumbnail families like
 * `/dwp1x/`, `/uwpr/` or `/fuwp/` pass through untouched (idempotent),
 * and so do foreign hosts that merely contain those letters. Host
 * matching is case-insensitive and accepts the `www.` form. The resolver
 * is total: any malformed input answers the input unchanged, never an
 * exception.
 */
internal object DisplayUrlResolver {
    private val WALLPAPERCAVE_HOSTS =
        setOf("wallpapercave.com", "www.wallpapercave.com")

    /** Directory the original lives in → directory the display render lives in. */
    private val DISPLAY_DIRS =
        mapOf(
            "wp" to "dwp2x",
            "uwp" to "fuwp",
        )

    /** The Behance image CDN — open, no clearance needed. */
    private const val BEHANCE_CDN_HOST = "mir-s3-cdn-cf.behance.net"

    /**
     * The Behance module family rewrite: `project_modules/source/{file}`
     * is the multi-megabyte original; the same file's `fs` render is the
     * 1920-px display the site's own viewer shows. Keyed on the family
     * segment (the SECOND path segment, after `project_modules`), whole
     * segments only, so `fs` itself and every other family pass through
     * untouched.
     */
    private val BEHANCE_MODULE_DIRS =
        mapOf(
            "source" to "fs",
        )

    /**
     * The URL the viewer should display first. Identical to [url] when no
     * verified display tier exists for it.
     */
    fun displayUrlFor(url: String): String {
        if (url.isBlank()) return url
        val uri =
            runCatching { URI(url) }.getOrNull() ?: return url
        val scheme = uri.scheme?.lowercase() ?: return url
        if (scheme != "http" && scheme != "https") return url
        val host = uri.host?.lowercase() ?: return url
        val path = uri.rawPath ?: return url
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return url
        if (host == BEHANCE_CDN_HOST) {
            // project_modules/{family}/{file} — the family names the render.
            if (segments.firstOrNull() == "project_modules") {
                val family = segments.getOrNull(1) ?: return url
                val displayFamily = BEHANCE_MODULE_DIRS[family] ?: return url
                val file = segments.getOrNull(2) ?: return url
                return buildString {
                    append("https://")
                    append(BEHANCE_CDN_HOST)
                    append("/project_modules/")
                    append(displayFamily)
                    append('/')
                    append(file)
                }
            }
            return url
        }
        if (host !in WALLPAPERCAVE_HOSTS) return url
        val displayDir = DISPLAY_DIRS[segments[0]] ?: return url
        val file = segments.getOrNull(1) ?: return url
        return buildString {
            append("https://")
            append(host)
            append('/')
            append(displayDir)
            append('/')
            append(file)
        }
    }
}

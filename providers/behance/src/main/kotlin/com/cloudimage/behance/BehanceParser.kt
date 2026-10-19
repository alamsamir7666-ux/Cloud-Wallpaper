package com.cloudimage.behance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hand-rolled mining of behance.net's server-rendered state — the scraping
 * half of this provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the
 * module jar and nothing else, so a dependency like Jsoup would be missing
 * at load time (the host supplies the contract and kotlinx.serialization,
 * nothing more). Everything below is therefore stdlib string work plus the
 * JSON facade over the one payload that matters: the
 * `<script type="application/json" id="beconfig-store_state">` blob every
 * Behance HTML page embeds — the exact data the site's own React tree
 * hydrates from, read the way the site's browser does.
 *
 * The shapes were captured live (2026-10) and are kept narrow on purpose:
 * every accessor keys on stable JSON paths (`search…nodes`, `project…
 * modules`, `imageSizes.allAvailable`) rather than document order, so a
 * cosmetic redesign degrades parsing to "nothing found" instead of
 * producing garbage. All functions are pure and total: bad input yields
 * empty lists and nulls, never exceptions — callers decide what a miss
 * means.
 *
 * ## The URL families (verified live, several files each)
 *
 * Module images live on `mir-s3-cdn-cf.behance.net/project_modules/{family}/{file}`
 * with the family deciding the render size: `source` is the original file
 * (observed up to several megabytes — the download target), `fs` a 1920-px
 * render, `hd` 1240, `disp` 600, `max_632` the grid thumbnail family, plus
 * `_webp` twins of each. Every image module's `allAvailable` list carries
 * the non-webp ladder complete, and the file token
 * (`6a409a162276423.63d2c8a9c7b29.png` — 7 hex + project id, dot, 14 hex,
 * dot, extension) is IDENTICAL across families, so any entry discloses
 * every other one. That token is the wallpaper's identity: the provider's
 * grid ids embed it so a working thumb and original URL can be derived
 * from the id alone, with no fetch at all.
 */
internal object BehanceParser {
    /** One search hit: a project container, addressed by id + slug pair. */
    data class ProjectRef(
        /** Numeric project id, e.g. `162276423`. */
        val id: String,
        /** URL slug — REQUIRED for server-rendered project pages. */
        val slug: String,
        val name: String,
        val author: String?,
    )

    /** A search page's project list plus its GraphQL cursor pagination. */
    data class SearchPage(
        val projects: List<ProjectRef>,
        /** Opaque base64 cursor for the `?after=` parameter; null when exhausted. */
        val endCursor: String?,
        val hasMore: Boolean,
    )

    /** One wallpaper-grade image inside a project. */
    data class ModuleImage(
        /** The file token shared by every family URL of this module. */
        val file: String,
        /** Grid-sized render (`max_632` family), absolute. */
        val thumbUrl: String,
        /** The original file (`source` family), absolute. */
        val fullUrl: String,
        /** Layout dimensions — aspect-true, scaled to the project canvas. */
        val width: Int?,
        val height: Int?,
        /** The ORIGINAL file's true width, published in the render ladder. */
        val sourceWidth: Int?,
    )

    /** The definitive record of one project page. */
    data class ProjectRecord(
        val name: String,
        val author: String?,
        val tags: List<String>,
        val modules: List<ModuleImage>,
        val sourceUrl: String?,
        val mature: Boolean,
    )

    /** The state blob every SSR page embeds; null when absent or unreadable. */
    private val STATE_SCRIPT =
        Regex(
            """<script\s+type="application/json"\s+id="beconfig-store_state"\s*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The bot-gate cookie a 403 challenge page carries, literal in its body. */
    private val CHALLENGE_COOKIE = Regex("""js_challenge_value=([a-f0-9]+)""")

    /** JSON facade over the state blob; unknown keys ignored throughout. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extracts the `js_challenge_value` token from a 403 challenge body —
     * the cookie the site's own JavaScript sets before reloading. The value
     * is literally in the markup, which is what makes the gate replayable
     * by a plain HTTP client: present it as a Cookie header on the next
     * GET and the page serves.
     */
    fun challengeCookie(body: String): String? {
        val match = CHALLENGE_COOKIE.find(body) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Search results: `search.projects.search.nodes` — the same GraphQL
     * nodes the site's results grid hydrates from — filtered to public,
     * unflagged projects, plus the cursor pair that paginates them
     * (`?after={endCursor}`). Dead, private or flagged containers drop
     * here; the maturity gate only exists on the project page itself, so
     * it is applied where the data is.
     */
    fun parseSearch(html: String): SearchPage? {
        val root = stateOf(html) ?: return null
        val search =
            runCatching {
                root.jsonObject["search"]!!
                    .jsonObject["projects"]!!
                    .jsonObject["search"]!!
                    .jsonObject
            }.getOrNull() ?: return null
        val nodes = search["nodes"] as? JsonArray ?: return SearchPage(emptyList(), null, false)
        val projects =
            nodes
                .mapNotNull { node ->
                    val o = node as? JsonObject ?: return@mapNotNull null
                    val id = o.int("id")?.toString() ?: return@mapNotNull null
                    val slug = o.str("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (o.bool("isFlagged") == true) return@mapNotNull null
                    if (o.bool("isPrivate") == true) return@mapNotNull null
                    if (o.str("privacyLevel")?.uppercase() !in listOf(null, "PUBLIC")) return@mapNotNull null
                    ProjectRef(
                        id = id,
                        slug = slug,
                        name = o.str("name").orEmpty(),
                        author = (o["owners"] as? JsonArray)?.firstOwnerName(),
                    )
                }.toList()
        val pageInfo = search["pageInfo"] as? JsonObject
        val hasMore = pageInfo?.bool("hasNextPage") ?: false
        val cursor = pageInfo?.str("endCursor")?.takeIf { it.isNotBlank() && hasMore }
        return SearchPage(projects, cursor, hasMore)
    }

    /**
     * The project page's record: `project.project` with its module images.
     * Text/media modules drop (no `imageSizes`); image modules ride the
     * `allAvailable` ladder with `source` as the original and `max_632` as
     * the grid render, falling back to the widest available family and the
     * module's own `src` when the site omits one — never worse than what
     * the page itself publishes. A page whose state is missing or empty
     * answers null: the caller falls back to the id-derived record.
     */
    fun parseProject(html: String): ProjectRecord? {
        val root = stateOf(html) ?: return null
        val project =
            runCatching {
                root.jsonObject["project"]!!
                    .jsonObject["project"]!!
                    .jsonObject
            }.getOrNull() ?: return null
        if (project.isEmpty()) return null
        val modules =
            (project["modules"] as? JsonArray)
                ?.mapNotNull { module ->
                    val o = module as? JsonObject ?: return@mapNotNull null
                    moduleImage(o)
                }.orEmpty()
        val tags =
            (project["tags"] as? JsonArray)
                ?.mapNotNull { tag -> (tag as? JsonObject)?.str("title")?.trim() }
                .orEmpty()
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(MAX_TAGS)
        return ProjectRecord(
            name = project.str("name").orEmpty(),
            author = (project["owners"] as? JsonArray)?.firstOwnerName(),
            tags = tags,
            modules = modules,
            sourceUrl = project.str("url")?.takeIf { it.isNotBlank() },
            mature = project.bool("hasMatureContent") == true,
        )
    }

    /**
     * One module's image record: picks the thumb and original out of
     * `imageSizes.allAvailable`, skipping the `_webp` twins (the non-webp
     * family is always present and the file's own extension is the truth —
     * the list's `type` field claims JPG for .png files, so it is ignored).
     *
     * The original is the `source` entry; when a module omits it (not
     * observed live, but the list is per-upload) the widest render stands
     * in — a download of a 1920-px file beats a dead link. The thumb is
     * `max_632`, falling back to `disp`, then to the module's own `src`
     * (the 600-px render the page itself shows). The file token is shared
     * by every family URL, so it is read from whichever entry supplied the
     * original.
     */
    private fun moduleImage(module: JsonObject): ModuleImage? {
        val available = module.availableImages() ?: return null
        val sourceEntry = available.firstOrNull { "/source/" in it.url }
        val original =
            sourceEntry
                ?: available.filter { it.width != null }.maxByOrNull { it.width ?: 0 }
                ?: available.firstOrNull()
                ?: module.str("src")?.let { Available(url = it, width = null) }
                ?: return null
        val thumb =
            available.firstOrNull { "/max_632/" in it.url }
                ?: available.firstOrNull { "/disp/" in it.url }
                ?: original
        val file = original.url.substringAfterLast('/')
        if (file.isBlank() || '.' !in file || '/' in file) return null
        return ModuleImage(
            file = file,
            thumbUrl = absolute(thumb.url),
            fullUrl = absolute(original.url),
            width = module.int("width"),
            height = module.int("height"),
            // Only the source entry's width is the file's true width; a
            // fallback family's width is that RENDER's, not the original's.
            sourceWidth = sourceEntry?.width,
        )
    }

    /**
     * The module's render ladder as flat pairs, `_webp` twins skipped.
     * Non-image modules (text, embeds) carry no `imageSizes` at all and
     * answer null — that is the filter that keeps them out.
     */
    private fun JsonObject.availableImages(): List<Available>? {
        val list =
            (this["imageSizes"] as? JsonObject)
                ?.get("allAvailable") as? JsonArray
                ?: return null
        return list
            .mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val url = o.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if ("_webp" in url) return@mapNotNull null
                if ("/project_modules/" !in url) return@mapNotNull null
                Available(url = url, width = o.int("width"))
            }.ifEmpty { null }
    }

    /** First owner's display name — the author line the detail screen shows. */
    private fun JsonArray.firstOwnerName(): String? = (firstOrNull() as? JsonObject)?.str("displayName")?.takeIf { it.isNotBlank() }

    /** The embedded state object, or null for pages without/with a broken one. */
    private fun stateOf(html: String): JsonElement? {
        val blob = STATE_SCRIPT.find(html)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { json.parseToJsonElement(blob) }.getOrNull()
    }

    /** Site-relative URLs become absolute; already-absolute ones pass through. */
    fun absolute(url: String): String =
        when {
            url.startsWith("https://") || url.startsWith("http://") -> url
            url.startsWith("/") -> "$CDN_HOST$url"
            else -> url
        }

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        when (str(key)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private data class Available(
        val url: String,
        val width: Int?,
    )

    /** The open image CDN every module URL lives on. */
    const val CDN_HOST = "https://mir-s3-cdn-cf.behance.net"

    private const val MAX_TAGS = 8
}

package com.cloudimage.feature.extensions

import java.util.Base64

/** Route contract for the extensions feature, consumed by :app navigation. */
object ExtensionsDestination {
    const val route = "extensions"

    /** The pushed repository browser detail — one repo's extension catalog. */
    const val repoArg = "repoId"
    const val repoRoute = "extensions/repo/{$repoArg}"

    /** The pushed bundle detail — one bundle inside one repo's index (v1.2.2). */
    const val bundleArg = "bundleId"
    const val bundleRoute = "extensions/repo/{$repoArg}/bundle/{$bundleArg}"

    /** The pushed installed-extensions list, reached from the counts bar. */
    const val installedRoute = "extensions/installed"

    /**
     * Builds the repo detail route. [repoId] is the repo's index URL, so it
     * can contain `/`, `:` and `%` — path-reserved characters that would
     * either break the route match or risk a double-decode (NavType applies
     * its own Uri decoding). Base64 URL-safe has no reserved characters at
     * all: the route always matches in one segment and [decodeRepoId] is
     * the only decode that ever runs.
     */
    fun createRepoRoute(repoId: String): String = "extensions/repo/${encodeRepoId(repoId)}"

    /**
     * Builds the bundle detail route. Both ids ride the same Base64
     * URL-safe encoding as the repo id — bundle ids are author-chosen
     * slugs and may contain any character the route would reserve.
     */
    fun createBundleRoute(
        repoId: String,
        bundleId: String,
    ): String = "extensions/repo/${encodeRepoId(repoId)}/bundle/${encodeRepoId(bundleId)}"

    /** The path-safe form of [repoId] that rides in the route. */
    fun encodeRepoId(repoId: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(repoId.toByteArray(Charsets.UTF_8))

    /** Reverses [encodeRepoId]; a malformed value falls back to itself. */
    fun decodeRepoId(encoded: String): String =
        runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrDefault(encoded)
}

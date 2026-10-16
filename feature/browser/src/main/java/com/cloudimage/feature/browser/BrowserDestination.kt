package com.cloudimage.feature.browser

import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The in-app browser's address in the nav graph (v1.2.19).
 *
 * The browser is the escape hatch for a zone whose Cloudflare challenge
 * refused every automated rung — the app opens the site in a real
 * browsing session, the user clears the verification by hand, and the
 * earned clearance lands in the SAME system WebView cookie jar the
 * solver's warm start reads. The next Retry replays under it instead of
 * dying at the handshake.
 */
object BrowserDestination {
    const val route = "browser?url={url}"

    const val urlArg = "url"

    /**
     * Builds the route for [url]. The URL is form-encoded: nav arguments
     * live inside one query string, so a raw URL's own `?` and `&` would
     * otherwise shred the argument.
     */
    fun createRoute(url: String): String = "browser?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8.name())

    /** Decodes the argument back into the URL to load. */
    fun decodeUrl(argument: String): String = Uri.decode(argument)
}

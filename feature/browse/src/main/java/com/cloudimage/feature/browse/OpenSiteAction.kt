package com.cloudimage.feature.browse

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * The escape hatch affordance every browse error surface shares (v1.2.19):
 * when a failure carries [NetworkError.Source.challengeUrl] — Cloudflare's
 * protection refused every automated rung — the honest next step is for
 * the USER to clear it by hand in the in-app browser, not another Retry.
 *
 * CloudStream's Cloudflare story ends exactly here: their in-app
 * WebviewFragment lets the user solve the challenge once in a real
 * browsing session, and the clearance their interceptor then harvests
 * from the shared cookie jar is what keeps the source working. This
 * button is this app's version of that exit — rendered only when there
 * is a site to open, silent for every other failure kind.
 */
@Composable
internal fun OpenSiteAction(
    challengeUrl: String?,
    onOpenSite: (String) -> Unit,
) {
    if (challengeUrl != null) {
        TextButton(onClick = { onOpenSite(challengeUrl) }) {
            Text(stringResource(R.string.browse_error_open_site))
        }
    }
}

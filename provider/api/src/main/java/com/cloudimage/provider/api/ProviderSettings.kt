package com.cloudimage.provider.api

/**
 * Host-supplied per-provider configuration, handed to every plugin in
 * [WallpaperProvider.configure].
 *
 * V1 exposes exactly one channel: the optional API key the user pasted for
 * this provider in the app's settings. The lookup is by provider id
 * (`settings.apiKey(meta.id)`); a null return means "no key" and the plugin
 * must degrade — serve keyless content or fail with a readable error,
 * never crash.
 *
 * The host snapshots its key store when this is called; plugins should read
 * the value lazily per request (not cache it beyond the settings object's
 * lifetime) so key changes take effect after the host reloads providers.
 */
fun interface ProviderSettings {
    /** The user's API key for [providerId], or null when none is stored. */
    fun apiKey(providerId: String): String?
}

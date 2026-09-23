package com.cloudimage.extensions.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The manifest entry of an extension package: `extension.json` at the zip
 * root, next to the payload (a `classes.dex` in real packages).
 *
 * The manifest is the single source of identity and compatibility:
 * - [id] is the stable key for install/uninstall/browse wiring,
 * - [apiVersion] is checked against the host contract before anything
 *   from the package is ever loaded,
 * - [entryClass] names the single [com.cloudimage.provider.api.WallpaperProvider]
 *   implementation the engine should instantiate.
 */
@Serializable
data class ExtensionManifest(
    val id: String,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val author: String = "",
    val description: String = "",
    val apiVersion: Int,
    val entryClass: String,
) {
    /**
     * Applies field constraints, throwing IllegalArgumentException with a
     * human-readable reason on the first violation. Called from [parse]
     * before a manifest is ever trusted.
     */
    fun validate(): ExtensionManifest {
        require(ID_PATTERN.matches(id)) {
            "id must be lower-case reverse-DNS with at least two segments, was '$id'"
        }
        require(name.isNotBlank()) { "name must not be blank" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(versionCode >= 1) { "versionCode must be >= 1, was $versionCode" }
        require(apiVersion >= 1) { "apiVersion must be >= 1, was $apiVersion" }
        require(ENTRY_CLASS_PATTERN.matches(entryClass)) {
            "entryClass must be a fully-qualified class name, was '$entryClass'"
        }
        return this
    }

    companion object {
        /** Reverse-DNS, lower-case, at least two segments ("cloudimage.demo"). */
        val ID_PATTERN: Regex = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

        /** Fully-qualified class name for the entry point. */
        val ENTRY_CLASS_PATTERN: Regex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")

        /** Zip entry name of the manifest inside an extension package. */
        const val ENTRY_NAME = "extension.json"

        private val json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                explicitNulls = false
            }

        /**
         * Decodes and validates a manifest, throwing IllegalArgumentException
         * with a readable reason when the text is not a valid manifest —
         * callers translate that into an [ExtensionError.InvalidManifest].
         */
        fun parse(text: String): ExtensionManifest {
            val manifest =
                try {
                    json.decodeFromString(serializer(), text)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException("manifest is not valid JSON: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("manifest is not valid JSON: ${e.message}")
                }
            return manifest.validate()
        }
    }
}

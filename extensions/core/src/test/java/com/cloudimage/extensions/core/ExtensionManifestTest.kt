package com.cloudimage.extensions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtensionManifestTest {
    @Test
    fun parsesFullManifestWithDefaults() {
        val manifest =
            ExtensionManifest.parse(
                """
                {
                  "id": "cloudimage.demo",
                  "name": "Demo Walls",
                  "versionName": "1.2.0",
                  "versionCode": 3,
                  "apiVersion": 1,
                  "entryClass": "com.cloudimage.fixture.demo.DemoWallpaperProvider"
                }
                """.trimIndent(),
            )

        assertEquals("cloudimage.demo", manifest.id)
        assertEquals("Demo Walls", manifest.name)
        assertEquals("1.2.0", manifest.versionName)
        assertEquals(3, manifest.versionCode)
        assertEquals(1, manifest.apiVersion)
        assertEquals("", manifest.author)
        assertEquals("", manifest.description)
    }

    @Test
    fun ignoresUnknownKeys() {
        val manifest =
            ExtensionManifest.parse(
                """
                {
                  "id": "cloudimage.demo",
                  "name": "Demo",
                  "versionName": "1.0.0",
                  "versionCode": 1,
                  "apiVersion": 1,
                  "entryClass": "com.cloudimage.fixture.demo.DemoWallpaperProvider",
                  "futureField": {"nested": true}
                }
                """.trimIndent(),
            )

        assertEquals("cloudimage.demo", manifest.id)
    }

    @Test
    fun rejectsMalformedJson() = assertParseFails("not json at all")

    @Test
    fun rejectsMissingRequiredFields() = assertParseFails("""{"id": "cloudimage.demo"}""")

    @Test
    fun rejectsUppercaseId() =
        assertParseFails(
            TestPackages.manifestJson(id = "Cloudimage.Demo"),
        )

    @Test
    fun rejectsSingleSegmentId() =
        assertParseFails(
            TestPackages.manifestJson(id = "wallhaven"),
        )

    @Test
    fun rejectsBlankName() =
        assertParseFails(
            TestPackages.manifestJson(name = "  "),
        )

    @Test
    fun rejectsBlankVersionName() =
        assertParseFails(
            TestPackages.manifestJson(versionName = ""),
        )

    @Test
    fun rejectsNonPositiveVersionCode() =
        assertParseFails(
            TestPackages.manifestJson(versionCode = 0),
        )

    @Test
    fun rejectsNonPositiveApiVersion() =
        assertParseFails(
            TestPackages.manifestJson(apiVersion = 0),
        )

    @Test
    fun rejectsMalformedEntryClass() =
        assertParseFails(
            TestPackages.manifestJson(entryClass = "not a class name!"),
        )

    private fun assertParseFails(text: String) {
        try {
            ExtensionManifest.parse(text)
            fail("expected IllegalArgumentException for: $text")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}

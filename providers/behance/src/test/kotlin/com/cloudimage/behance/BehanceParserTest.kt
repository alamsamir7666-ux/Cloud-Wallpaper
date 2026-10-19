package com.cloudimage.behance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser units against fixtures cut from behance.net's embedded state —
 * the exact JSON shapes the live site serves, trimmed to the paths the
 * parsers key on. Bad input must answer empty or null, never throw.
 */
class BehanceParserTest {
    /** A search node as the live site serves it (plus the noise around it). */
    private fun node(
        id: Int,
        slug: String,
        name: String,
        flagged: Boolean = false,
        privacy: String = "PUBLIC",
    ): String =
        """{"id":$id,"oid":"abc","url":"https://www.behance.net/gallery/$id/$slug",""" +
            """"isBoosted":false,"isFlagged":$flagged,"isPrivate":false,"slug":"$slug",""" +
            """"name":"$name","publishStatus":"PUBLISHED","privacyLevel":"$privacy",""" +
            """"owners":[{"id":1,"username":"user","displayName":"Trey C.","images":{}}],""" +
            """"covers":{"allAvailable":[]},"stats":{"appreciations":{"all":49}}}"""

    private fun searchHtml(
        nodes: List<String>,
        hasMore: Boolean,
        cursor: String?,
    ): String {
        val pageInfo =
            if (hasMore) {
                """{"hasNextPage":true,"endCursor":"$cursor"}"""
            } else {
                """{"hasNextPage":false,"endCursor":"$cursor"}"""
            }
        return """<html><body><div>noise</div>
            <script type="application/json" id="beconfig-store_state">{"search":{"projects":{"search":
            {"nodes":[${nodes.joinToString(",")}],"pageInfo":$pageInfo}}},"user":{}}
            </script></body></html>"""
    }

    /** One image module's render ladder — families as the live site lists them. */
    private fun imageModule(
        hash: String,
        w: Int,
        h: Int,
        sourceW: Int,
        withSource: Boolean = true,
    ): String {
        val source =
            if (withSource) {
                """{"url":"https://mir-s3-cdn-cf.behance.net/project_modules/source/$hash","width":$sourceW,"type":"JPG"},"""
            } else {
                ""
            }
        return """{"id":915380531,"__typename":"ImageModule","fullBleed":1,"width":$w,"height":$h,
            "src":"https://mir-s3-cdn-cf.behance.net/project_modules/disp/$hash",
            "imageSizes":{"allAvailable":[$source
            {"url":"https://mir-s3-cdn-cf.behance.net/project_modules/max_632_webp/$hash","width":632,"type":"WEBP"},
            {"url":"https://mir-s3-cdn-cf.behance.net/project_modules/max_632/$hash","width":632,"type":"JPG"},
            {"url":"https://mir-s3-cdn-cf.behance.net/project_modules/disp/$hash","width":600,"type":"JPG"}]},"caption":""}"""
    }

    private fun projectHtml(
        modules: List<String>,
        mature: Boolean = false,
        tags: String = """[{"id":1,"title":"gradient"},{"id":2,"title":"minimal"},{"id":1,"title":"Gradient"}]""",
    ): String =
        """<html><body>
            <script type="application/json" id="beconfig-store_state">{"project":{"project":{
            "id":162276423,"slug":"Gradient-Wallpapers","name":"Gradient Wallpapers",
            "url":"https://www.behance.net/gallery/162276423/Gradient-Wallpapers",
            "hasMatureContent":$mature,"privacyLevel":"PUBLIC",
            "owners":[{"displayName":"Trey C.","username":"trey"}],
            "tags":$tags,
            "modules":[${modules.joinToString(",")}]}},
            "user":{},"gates":{}}
            </script></body></html>"""

    @Test
    fun `search nodes parse and dead projects drop`() {
        val page =
            BehanceParser.parseSearch(
                searchHtml(
                    listOf(
                        node(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                        node(333, "Flagged-Project", "Flagged Project", flagged = true),
                        node(777, "Private-Project", "Private Project", privacy = "PRIVATE"),
                        node(222, "Abstract-Collection", "Abstract Collection"),
                    ),
                    hasMore = true,
                    cursor = "MjQ=",
                ),
            )
        assertEquals(2, page?.projects?.size)
        val first = page!!.projects[0]
        assertEquals("111", first.id)
        assertEquals("Gradient-Wallpapers", first.slug)
        assertEquals("Gradient Wallpapers", first.name)
        assertEquals("Trey C.", first.author)
        assertTrue(page.hasMore)
        assertEquals("MjQ=", page.endCursor)
    }

    @Test
    fun `search exhaustion carries no cursor`() {
        val page = BehanceParser.parseSearch(searchHtml(emptyList(), hasMore = false, cursor = null))
        assertEquals(0, page?.projects?.size)
        assertNull(page?.endCursor)
        assertEquals(false, page?.hasMore)
    }

    @Test
    fun `pages without the state blob answer null`() {
        assertNull(BehanceParser.parseSearch("<html><body>nothing embedded</body></html>"))
        assertNull(BehanceParser.parseSearch(""))
        assertNull(BehanceParser.parseProject("<html><body>nothing embedded</body></html>"))
    }

    @Test
    fun `malformed state json answers null instead of throwing`() {
        val broken = """<script type="application/json" id="beconfig-store_state">{"search": nope</script>"""
        assertNull(BehanceParser.parseSearch(broken))
    }

    @Test
    fun `image modules pick thumb and original out of the ladder`() {
        val record =
            BehanceParser.parseProject(
                projectHtml(
                    listOf(
                        imageModule("6a409a162276423.63d2c8a9c7b29.png", 600, 400, 4500),
                        """{"id":2,"__typename":"TextModule","caption":"just text"}""",
                    ),
                ),
            )
        assertEquals(1, record?.modules?.size)
        val image = record!!.modules[0]
        assertEquals("6a409a162276423.63d2c8a9c7b29.png", image.file)
        assertEquals(
            "https://mir-s3-cdn-cf.behance.net/project_modules/max_632/6a409a162276423.63d2c8a9c7b29.png",
            image.thumbUrl,
        )
        assertEquals(
            "https://mir-s3-cdn-cf.behance.net/project_modules/source/6a409a162276423.63d2c8a9c7b29.png",
            image.fullUrl,
        )
        assertEquals(600, image.width)
        assertEquals(400, image.height)
        assertEquals(4500, image.sourceWidth)
        assertEquals(false, record.mature)
    }

    @Test
    fun `modules without a source entry fall back to the widest render`() {
        val record =
            BehanceParser.parseProject(
                projectHtml(listOf(imageModule("abc123.def456.png", 600, 600, 0, withSource = false))),
            )
        val image = record!!.modules[0]
        // No source entry: the widest render with a width (max_632, 632 px) stands in.
        assertEquals(
            "https://mir-s3-cdn-cf.behance.net/project_modules/max_632/abc123.def456.png",
            image.fullUrl,
        )
        assertNull(image.sourceWidth)
    }

    @Test
    fun `project tags dedupe case-insensitively and the mature flag rides along`() {
        val record =
            BehanceParser.parseProject(projectHtml(listOf(imageModule("h1.h2.png", 600, 400, 900)), mature = true))
        assertEquals(listOf("gradient", "minimal"), record?.tags)
        assertEquals(true, record?.mature)
        assertEquals("Trey C.", record?.author)
        assertEquals("https://www.behance.net/gallery/162276423/Gradient-Wallpapers", record?.sourceUrl)
    }

    @Test
    fun `the challenge cookie is extracted from a 403 body`() {
        val body =
            """<html><body><script>
                document.cookie = "js_challenge_value=206d88e33db1b0d18e54e653413734629d069693; Path=/";
                window.location.reload();
            </script></body></html>"""
        assertEquals("206d88e33db1b0d18e54e653413734629d069693", BehanceParser.challengeCookie(body))
        assertNull(BehanceParser.challengeCookie("<html><body>a real page</body></html>"))
        assertNull(BehanceParser.challengeCookie(""))
    }

    @Test
    fun `absolute builds CDN urls from site-relative ones`() {
        assertEquals(
            "https://mir-s3-cdn-cf.behance.net/project_modules/source/x.png",
            BehanceParser.absolute("/project_modules/source/x.png"),
        )
        assertEquals(
            "https://example.com/a.png",
            BehanceParser.absolute("https://example.com/a.png"),
        )
    }
}

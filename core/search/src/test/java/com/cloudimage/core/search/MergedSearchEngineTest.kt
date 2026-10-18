package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.search.ImageSearchResult.Failure
import com.cloudimage.core.search.ImageSearchResult.Success
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merged engine's lane-failure contract: both lanes answer as one
 * interleaved page, a dead secondary is invisible, a dead primary degrades
 * to the secondary alone, both dead surfaces the primary's failure, and a
 * slow secondary is bounded by its timeout.
 */
class MergedSearchEngineTest {
    private val filters = GlobalSearchFilters()

    /** An engine scriptable per call: what to answer, and how slowly. */
    private class FakeEngine(
        override val name: String,
        private val script: MutableList<(Int) -> ImageSearchResult>,
    ) : ImageSearchEngine {
        val askedPages = mutableListOf<Int>()

        override suspend fun search(
            query: String,
            page: Int,
            filters: GlobalSearchFilters,
        ): ImageSearchResult {
            askedPages.add(page)
            // removeAt(0), deliberately NOT removeFirst(): removeFirst
            // compiles against JDK 21 to java.util.List's SequencedCollection
            // method, which older test JVMs lack - a NoSuchMethodError that
            // lane guards swallow into silent nulls (the CI-only failure
            // this suite once chased). removeAt(0) is Kotlin stdlib, every
            // JDK, identical semantics.
            return script.removeAt(0).invoke(page)
        }
    }

    private fun result(
        vararg ids: String,
        hasMore: Boolean = false,
    ): ImageSearchResult =
        Success(
            ImageSearchPage(
                results =
                    ids.map { id ->
                        Wallpaper(
                            id = id,
                            providerId = GLOBAL_SEARCH_PROVIDER_ID,
                            thumbUrl = "https://example.com/$id-thumb.jpg",
                            fullUrl = "https://example.com/$id.jpg",
                        )
                    },
                hasMore = hasMore,
                page = 1,
            ),
        )

    @Test
    fun `both lanes interleave round-robin and dedup shared ids`() =
        runTest {
            val primary =
                FakeEngine(
                    "Browser",
                    mutableListOf(
                        { result("a", "b", "c", hasMore = true) },
                    ),
                )
            val secondary =
                FakeEngine(
                    "Reddit",
                    mutableListOf(
                        { result("x", "a", "y") },
                    ),
                )
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 1_000)

            val outcome = merged.search("mountain", 1, filters)

            val page = (outcome as Success).page
            assertEquals(listOf("a", "x", "b", "a", "c", "y").distinct(), page.results.map { it.id })
            assertTrue(page.hasMore)
            assertEquals(1, page.page)
        }

    @Test
    fun `a failed secondary is invisible`() =
        runTest {
            val primary =
                FakeEngine(
                    "Browser",
                    mutableListOf({ result("a", hasMore = true) }),
                )
            val secondary =
                FakeEngine(
                    "Reddit",
                    mutableListOf({ Failure(ImageSearchError.RATE_LIMITED) }),
                )
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 1_000)

            val outcome = merged.search("mountain", 1, filters)

            val page = (outcome as Success).page
            assertEquals(listOf("a"), page.results.map { it.id })
            assertTrue(page.hasMore)
        }

    @Test
    fun `a dead primary degrades to the secondary alone`() =
        runTest {
            val primary =
                FakeEngine(
                    "Browser",
                    mutableListOf({ Failure(ImageSearchError.NETWORK, detail = "bridge gone") }),
                )
            val secondary =
                FakeEngine(
                    "Reddit",
                    mutableListOf({ result("x", "y", hasMore = true) }),
                )
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 1_000)

            val outcome = merged.search("mountain", 1, filters)

            val page = (outcome as Success).page
            assertEquals(listOf("x", "y"), page.results.map { it.id })
            assertTrue(page.hasMore)
        }

    @Test
    fun `both lanes failing surfaces the primary failure`() =
        runTest {
            val primary =
                FakeEngine(
                    "Browser",
                    mutableListOf({ Failure(ImageSearchError.TIMEOUT, detail = "bridge timed out") }),
                )
            val secondary =
                FakeEngine(
                    "Reddit",
                    mutableListOf({ Failure(ImageSearchError.NETWORK) }),
                )
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 1_000)

            val outcome = merged.search("mountain", 1, filters)

            val failure = outcome as Failure
            assertEquals(ImageSearchError.TIMEOUT, failure.error)
            assertEquals("bridge timed out", failure.detail)
        }

    @Test
    fun `a secondary slower than its budget is dropped for the page`() =
        runTest {
            val primary =
                FakeEngine(
                    "Browser",
                    mutableListOf({ result("a", hasMore = true) }),
                )
            val secondary =
                object : ImageSearchEngine {
                    override val name: String = "SlowReddit"

                    override suspend fun search(
                        query: String,
                        page: Int,
                        filters: GlobalSearchFilters,
                    ): ImageSearchResult {
                        delay(10_000)
                        return result("x")
                    }
                }
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 50)

            val outcome = merged.search("mountain", 1, filters)

            val page = (outcome as Success).page
            assertEquals(listOf("a"), page.results.map { it.id })
        }

    @Test
    fun `hasMore rides either lane`() =
        runTest {
            val primary =
                FakeEngine(
                    "Browser",
                    mutableListOf({ result("a", hasMore = false) }),
                )
            val secondary =
                FakeEngine(
                    "Reddit",
                    mutableListOf({ result("x", hasMore = true) }),
                )
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 1_000)

            val outcome = merged.search("mountain", 1, filters)

            assertTrue((outcome as Success).page.hasMore)
        }

    @Test
    fun `prewarm warms only the primary`() =
        runTest {
            var primaryWarmed = false
            var secondaryWarmed = false
            val primary =
                object : ImageSearchEngine {
                    override val name: String = "Browser"

                    override suspend fun prewarm() {
                        primaryWarmed = true
                    }

                    override suspend fun search(
                        query: String,
                        page: Int,
                        filters: GlobalSearchFilters,
                    ): ImageSearchResult = Failure(ImageSearchError.NETWORK)
                }
            val secondary =
                object : ImageSearchEngine {
                    override val name: String = "Reddit"

                    override suspend fun prewarm() {
                        secondaryWarmed = true
                    }

                    override suspend fun search(
                        query: String,
                        page: Int,
                        filters: GlobalSearchFilters,
                    ): ImageSearchResult = Failure(ImageSearchError.NETWORK)
                }
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 1_000)

            merged.prewarm()

            assertTrue(primaryWarmed)
            assertFalse(secondaryWarmed)
        }

    @Test
    fun `the name carries both lanes`() {
        val merged =
            MergedSearchEngine(
                primary = FakeEngine("Browser", mutableListOf()),
                secondary = FakeEngine("Reddit", mutableListOf()),
                secondaryTimeoutMs = 1_000,
            )
        assertEquals("Browser+Reddit", merged.name)
    }

    @Test
    fun `cancelling the caller cancels the search instead of completing it`() =
        runTest {
            // The primary parks mid-search — exactly the shape an in-flight
            // bridge request has when the user commits a new query.
            val release = CompletableDeferred<Unit>()
            val primary =
                object : ImageSearchEngine {
                    override val name: String = "Browser"

                    override suspend fun search(
                        query: String,
                        page: Int,
                        filters: GlobalSearchFilters,
                    ): ImageSearchResult {
                        release.await()
                        return result("a")
                    }
                }
            val secondary =
                FakeEngine(
                    "Reddit",
                    mutableListOf({ result("x") }),
                )
            val merged = MergedSearchEngine(primary, secondary, secondaryTimeoutMs = 1_000)

            var completedNormally = false
            val job =
                launch {
                    merged.search("mountain", 1, filters)
                    completedNormally = true
                }
            job.cancelAndJoin()

            // The dead search must not have lived on to return a page —
            // its results would stomp the fresh search's state.
            assertFalse(completedNormally)
            release.cancel()
        }
}

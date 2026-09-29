package com.cloudimage.feature.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.SaveError
import com.cloudimage.core.data.repository.SaveResult
import com.cloudimage.core.data.repository.SourceCapability
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.viewer.ViewerSession
import com.cloudimage.core.model.Downloaded
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.testing.FakeDownloadsRepository
import com.cloudimage.core.testing.FakeFavoritesRepository
import com.cloudimage.core.testing.FakeHistoryRepository
import com.cloudimage.core.testing.FakeWallpaperApplier
import com.cloudimage.core.testing.FakeWallpaperSaver
import com.cloudimage.core.testing.FakeWallpaperSources
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The preview screen's logic is tested against the shared fakes: state
 * transitions, history records, re-entrancy guards and event emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val applier = FakeWallpaperApplier()
    private val saver = FakeWallpaperSaver()
    private val favorites = FakeFavoritesRepository()
    private val history = FakeHistoryRepository()
    private val downloads = FakeDownloadsRepository()
    private val sources = FakeWallpaperSources()

    private val wallpaper =
        Wallpaper(
            id = "e1abc2",
            providerId = "wallhaven",
            thumbUrl = "https://w.wallhaven.cc/full/e1abc2/large.jpg",
            fullUrl = "https://w.wallhaven.cc/full/e1abc2/original.png",
            width = 1920,
            height = 1080,
        )

    /** [wallpaper] carrying tags — the seed the recommendation row needs. */
    private val taggedWallpaper = wallpaper.copy(tags = listOf("forest", "mist", "night", "mountain"))

    /** A capable source entry: SEARCH + TAGS, the More-like-this gate. */
    private val capableSource =
        SourceInfo(
            id = "wallhaven",
            name = "Wallhaven",
            requiresApiKey = false,
            capabilities = setOf(SourceCapability.SEARCH, SourceCapability.TAGS),
        )

    private fun createViewModel(
        encoded: String?,
        session: ViewerSession = ViewerSession(),
    ): DetailViewModel =
        DetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(DetailDestination.arg to encoded)),
            applier = applier,
            saver = saver,
            favoritesRepository = favorites,
            historyRepository = history,
            downloadsRepository = downloads,
            sources = sources,
            viewerSession = session,
        )

    @Test
    fun decodesWallpaperFromTheRouteArgument() {
        val viewModel = createViewModel(DetailDestination.encode(wallpaper))

        assertEquals(wallpaper, viewModel.state.value.wallpaper)
    }

    @Test
    fun detailsLoadWhenTheListingCannotStateDimensions() =
        runTest {
            sources.setSources(capableSource)
            val undimensioned = wallpaper.copy(width = null, height = null)
            val record =
                WallpaperDetails(
                    wallpaper = undimensioned,
                    author = "dreemaxx",
                    resolution = "3840x2159",
                    fileSizeBytes = 3_627_606,
                )
            sources.enqueueDetails(NetworkResult.Success(record))

            val viewModel = createViewModel(DetailDestination.encode(undimensioned))
            advanceUntilIdle()

            assertEquals(record, viewModel.state.value.details)
            assertEquals(listOf(undimensioned), sources.detailsCalls)
        }

    @Test
    fun detailsAreNotFetchedWhenTheListingAlreadyKnowsDimensions() =
        runTest {
            sources.setSources(capableSource)

            val viewModel = createViewModel(DetailDestination.encode(wallpaper))
            advanceUntilIdle()

            assertNull(viewModel.state.value.details)
            assertTrue(sources.detailsCalls.isEmpty())
        }

    @Test
    fun detailsFailureDegradesSilentlyToTheListingValues() =
        runTest {
            sources.setSources(capableSource)
            val undimensioned = wallpaper.copy(width = null, height = null)
            sources.enqueueDetails(NetworkResult.Failure(NetworkError.Source("source failed")))

            val viewModel = createViewModel(DetailDestination.encode(undimensioned))
            advanceUntilIdle()

            assertNull(viewModel.state.value.details)
            assertEquals(undimensioned, viewModel.state.value.wallpaper)
        }

    @Test
    fun moreLikeThisLoadsOverTopTagsFromTheSameSource() =
        runTest {
            sources.setSources(capableSource)
            val lookalike = taggedWallpaper.copy(id = "zzz999")
            sources.enqueueSearch(
                NetworkResult.Success(Page(wallpapers = listOf(lookalike, taggedWallpaper), nextPage = null)),
            )

            val viewModel = createViewModel(DetailDestination.encode(taggedWallpaper))
            advanceUntilIdle()

            // The wallpaper itself is filtered out of its own row.
            assertEquals(listOf(lookalike), viewModel.state.value.moreLikeThis)
            // Top three tags join the query; the source is pinned.
            val call = sources.searchCalls.single()
            assertEquals("forest mist night", call.first.text)
            assertEquals(1, call.second)
            assertEquals("wallhaven", sources.searchSourceIds.single())
        }

    @Test
    fun moreLikeThisBroadensToTheSingleTagWhenTheCombinationMatchesNothing() =
        runTest {
            sources.setSources(capableSource)
            val lookalike = taggedWallpaper.copy(id = "zzz999")
            sources.enqueueSearch(NetworkResult.Success(Page(wallpapers = emptyList(), nextPage = null)))
            sources.enqueueSearch(
                NetworkResult.Success(Page(wallpapers = listOf(lookalike), nextPage = null)),
            )

            val viewModel = createViewModel(DetailDestination.encode(taggedWallpaper))
            advanceUntilIdle()

            assertEquals(listOf(lookalike), viewModel.state.value.moreLikeThis)
            assertEquals(
                listOf("forest mist night", "forest"),
                sources.searchCalls.map { it.first.text },
            )
        }

    @Test
    fun moreLikeThisStaysHiddenWhenTheSourceLacksTagSearch() =
        runTest {
            sources.setSources(
                SourceInfo(
                    id = "wallhaven",
                    name = "Wallhaven",
                    requiresApiKey = false,
                    capabilities = setOf(SourceCapability.SEARCH),
                ),
            )

            val viewModel = createViewModel(DetailDestination.encode(taggedWallpaper))
            advanceUntilIdle()

            val settled = viewModel.state.value
            assertTrue(settled.moreLikeThis.isEmpty())
            assertTrue(sources.searchCalls.isEmpty())
        }

    @Test
    fun moreLikeThisStaysHiddenWhenTheItemHasNoTags() =
        runTest {
            sources.setSources(capableSource)

            val viewModel = createViewModel(DetailDestination.encode(wallpaper))
            advanceUntilIdle()

            val settled = viewModel.state.value
            assertTrue(settled.moreLikeThis.isEmpty())
            assertTrue(sources.searchCalls.isEmpty())
        }

    @Test
    fun moreLikeThisStaysHiddenWhenTheSourceIsUnknown() =
        runTest {
            sources.setSources(capableSource.copy(id = "some.other.source"))

            val viewModel = createViewModel(DetailDestination.encode(taggedWallpaper))
            advanceUntilIdle()

            val settled = viewModel.state.value
            assertTrue(settled.moreLikeThis.isEmpty())
            assertTrue(sources.searchCalls.isEmpty())
        }

    @Test
    fun moreLikeThisHidesWhenTheSourceFails() =
        runTest {
            sources.setSources(capableSource)
            sources.enqueueSearch(NetworkResult.Failure(NetworkError.Timeout))

            val viewModel = createViewModel(DetailDestination.encode(taggedWallpaper))
            advanceUntilIdle()

            val settled = viewModel.state.value
            assertTrue(settled.moreLikeThis.isEmpty())
        }

    @Test
    fun invalidArgumentYieldsEmptyStateAndNoHistory() =
        runTest {
            val viewModel = createViewModel(encoded = "not-a-valid-argument!!!")

            assertNull(viewModel.state.value.wallpaper)
            advanceUntilIdle()
            assertTrue(history.entries.isEmpty())

            viewModel.onApply(ApplyTarget.HOME)
            viewModel.onSave()
            viewModel.onShare()
            advanceUntilIdle()

            assertTrue(applier.calls.isEmpty())
            assertTrue(saver.galleryCalls.isEmpty())
            assertTrue(saver.shareCalls.isEmpty())
        }

    @Test
    fun openingTheScreenRecordsViewedHistory() =
        runTest {
            createViewModel(DetailDestination.encode(wallpaper))
            advanceUntilIdle()

            assertEquals(listOf(HistoryAction.VIEWED), history.entries.map { it.action })
            assertEquals(wallpaper, history.entries.single().wallpaper)
        }

    @Test
    fun favoriteFlagFollowsTheRepository() =
        runTest {
            favorites.setFavorites(listOf(Favorite(wallpaper, addedAtMillis = 1L)))
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isFavorite)

            viewModel.onToggleFavorite()
            advanceUntilIdle()

            assertTrue(favorites.favorites.isEmpty())
        }

    @Test
    fun applySuccessRecordsAppliedHistoryAndEmitsEvent() =
        runTest {
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.events.test {
                viewModel.onApply(ApplyTarget.BOTH)
                advanceUntilIdle()

                assertEquals(OperationState.Succeeded, viewModel.state.value.applyOp)
                assertEquals(
                    listOf(HistoryAction.VIEWED, HistoryAction.APPLIED),
                    history.entries.map { it.action },
                )
                assertEquals(listOf(wallpaper to ApplyTarget.BOTH), applier.calls)
                assertEquals(DetailEvent.WallpaperApplied, awaitItem())
            }
        }

    @Test
    fun applyFailureEmitsMappedError() =
        runTest {
            applier.result = ApplyResult.Failure(ApplyError.UNSUPPORTED)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.events.test {
                viewModel.onApply(ApplyTarget.LOCK)
                advanceUntilIdle()

                assertEquals(OperationState.Failed(DetailError.UNSUPPORTED), viewModel.state.value.applyOp)
                assertEquals(
                    DetailEvent.ActionFailed(DetailAction.APPLY, DetailError.UNSUPPORTED),
                    awaitItem(),
                )
                assertTrue(history.entries.none { it.action == HistoryAction.APPLIED })
            }
        }

    @Test
    fun applyIsIgnoredWhileAlreadyRunning() =
        runTest {
            applier.gate = CompletableDeferred()
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.onApply(ApplyTarget.HOME)
            assertEquals(OperationState.Running, viewModel.state.value.applyOp)

            viewModel.onApply(ApplyTarget.LOCK)
            assertEquals(listOf(wallpaper to ApplyTarget.HOME), applier.calls)

            applier.gate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(wallpaper to ApplyTarget.HOME), applier.calls)
        }

    @Test
    fun applyCanBeRetriedAfterFailure() =
        runTest {
            applier.result = ApplyResult.Failure(ApplyError.OFFLINE)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.onApply(ApplyTarget.HOME)
            advanceUntilIdle()
            assertEquals(OperationState.Failed(DetailError.OFFLINE), viewModel.state.value.applyOp)

            applier.result = ApplyResult.Success
            viewModel.onApply(ApplyTarget.HOME)
            advanceUntilIdle()

            assertEquals(OperationState.Succeeded, viewModel.state.value.applyOp)
            assertEquals(2, applier.calls.size)
        }

    @Test
    fun saveSuccessRecordsDownloadedHistoryAndEmitsEvent() =
        runTest {
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.events.test {
                viewModel.onSave()
                advanceUntilIdle()

                assertEquals(OperationState.Succeeded, viewModel.state.value.saveOp)
                assertEquals(
                    listOf(HistoryAction.VIEWED, HistoryAction.DOWNLOADED),
                    history.entries.map { it.action },
                )
                assertEquals(listOf(wallpaper), saver.galleryCalls)
                assertEquals(DetailEvent.WallpaperSaved, awaitItem())
            }
        }

    @Test
    fun saveProgressFlowsIntoTheStateAndClearsOnCompletion() =
        runTest {
            saver.progressScript =
                listOf(
                    1_200L to 4_500L,
                    3_400L to 4_500L,
                    4_500L to 4_500L,
                )
            saver.gate = CompletableDeferred()
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.onSave()
            runCurrent()

            // Mid-flight: running state with the LAST tick's bytes on it.
            assertTrue(viewModel.state.value.saveOp is OperationState.Running)
            assertEquals(
                DownloadProgress(bytesRead = 4_500L, totalBytes = 4_500L),
                viewModel.state.value.downloadProgress,
            )
            // The label's math: half the total, and honest null when unknown.
            assertEquals(0.5f, DownloadProgress(2_250L, 4_500L).fraction)
            assertEquals(null, DownloadProgress(2_250L, null).fraction)

            saver.gate?.complete(Unit)
            advanceUntilIdle()

            // Completion settles the button, not the byte count.
            assertEquals(null, viewModel.state.value.downloadProgress)
            assertEquals(OperationState.Succeeded, viewModel.state.value.saveOp)
        }

    @Test
    fun saveSuccessMarksTheWallpaperDownloaded() =
        runTest {
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.onSave()
            advanceUntilIdle()

            assertEquals(listOf(wallpaper), downloads.downloads.map { it.wallpaper })
            assertTrue(viewModel.state.value.isDownloaded)
        }

    @Test
    fun downloadedFlagFollowsTheRepository() =
        runTest {
            downloads.setDownloads(listOf(Downloaded(wallpaper, downloadedAtMillis = 1)))
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            advanceUntilIdle()

            assertTrue(viewModel.state.value.isDownloaded)
        }

    @Test
    fun alreadyDownloadedWallpaperSavesNothingOnAnotherTap() =
        runTest {
            downloads.setDownloads(listOf(Downloaded(wallpaper, downloadedAtMillis = 1)))
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))
            advanceUntilIdle()

            viewModel.onSave()
            advanceUntilIdle()

            assertTrue(saver.galleryCalls.isEmpty())
            assertEquals(OperationState.Idle, viewModel.state.value.saveOp)
            assertEquals(null, viewModel.state.value.downloadProgress)
        }

    @Test
    fun saveFailureClearsProgressSoTheButtonReturnsToIdle() =
        runTest {
            saver.galleryResult = SaveResult.Failure(SaveError.HTTP)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.events.test {
                viewModel.onSave()
                advanceUntilIdle()

                assertEquals(OperationState.Failed(DetailError.HTTP), viewModel.state.value.saveOp)
                assertEquals(null, viewModel.state.value.downloadProgress)
                assertEquals(DetailEvent.ActionFailed(DetailAction.SAVE, DetailError.HTTP), awaitItem())
            }

            // The retry is a fresh tap: the wall between failures resets.
            saver.galleryResult = SaveResult.Success(uri = "content://media/43", fileName = "retry.png")
            viewModel.onSave()
            advanceUntilIdle()
            assertEquals(OperationState.Succeeded, viewModel.state.value.saveOp)
        }

    @Test
    fun shareEmitsReadyEventWithFileAndMime() =
        runTest {
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.events.test {
                viewModel.onShare()
                advanceUntilIdle()

                assertEquals(OperationState.Succeeded, viewModel.state.value.shareOp)
                assertEquals(
                    DetailEvent.ShareReady(
                        filePath = "/cache/shared/wallhaven-e1abc2.jpg",
                        mimeType = "image/png",
                    ),
                    awaitItem(),
                )
                // Sharing is not history-worthy: only VIEWED is on record.
                assertEquals(listOf(HistoryAction.VIEWED), history.entries.map { it.action })
            }
        }

    @Test
    fun shareFailureEmitsMappedError() =
        runTest {
            saver.shareResult = SaveResult.Failure(SaveError.IO)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.events.test {
                viewModel.onShare()
                advanceUntilIdle()

                assertEquals(OperationState.Failed(DetailError.STORAGE), viewModel.state.value.shareOp)
                assertEquals(DetailEvent.ActionFailed(DetailAction.SHARE, DetailError.STORAGE), awaitItem())
            }
        }

    @Test
    fun pagingSwapsTheWallpaperAndRecordsEachViewedImage() =
        runTest {
            val next = wallpaper.copy(id = "next-one")
            val last = wallpaper.copy(id = "last-one")
            val session = ViewerSession()
            session.open(listOf(wallpaper, next, last), index = 0)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper), session)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.hasNext)
            assertFalse(viewModel.state.value.hasPrevious)

            viewModel.onNavigate(+1)
            advanceUntilIdle()

            assertEquals(next, viewModel.state.value.wallpaper)
            assertTrue(viewModel.state.value.hasPrevious)
            assertTrue(viewModel.state.value.hasNext)
            assertEquals(
                listOf(wallpaper, next),
                history.entries.map { it.wallpaper },
            )
            assertEquals(
                listOf(HistoryAction.VIEWED, HistoryAction.VIEWED),
                history.entries.map { it.action },
            )
        }

    @Test
    fun pagingPastEitherEndRefusesInsteadOfWrapping() =
        runTest {
            val next = wallpaper.copy(id = "next-one")
            val session = ViewerSession()
            session.open(listOf(wallpaper, next), index = 0)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper), session)
            advanceUntilIdle()

            viewModel.onNavigate(-1)
            advanceUntilIdle()
            assertEquals(wallpaper, viewModel.state.value.wallpaper)

            viewModel.onNavigate(+1)
            viewModel.onNavigate(+1)
            advanceUntilIdle()
            assertEquals(next, viewModel.state.value.wallpaper)
            assertEquals(
                listOf(HistoryAction.VIEWED, HistoryAction.VIEWED),
                history.entries.map { it.action },
            )
        }

    @Test
    fun withoutASessionTheLookalikesBecomeTheBrowsingList() =
        runTest {
            sources.setSources(capableSource)
            val lookalike = taggedWallpaper.copy(id = "zzz999")
            sources.enqueueSearch(
                NetworkResult.Success(Page(wallpapers = listOf(lookalike), nextPage = null)),
            )
            // The paged-in lookalike runs its own recommendation query; the
            // single-tag broadening step makes it two when the first is empty.
            sources.enqueueSearch(
                NetworkResult.Success(Page(wallpapers = emptyList(), nextPage = null)),
            )
            sources.enqueueSearch(
                NetworkResult.Success(Page(wallpapers = emptyList(), nextPage = null)),
            )
            val viewModel = createViewModel(DetailDestination.encode(taggedWallpaper))
            advanceUntilIdle()

            // Nothing was parked for this screen: the row itself stands in,
            // led by the wallpaper on screen.
            assertTrue(viewModel.state.value.hasNext)

            viewModel.onNavigate(+1)
            advanceUntilIdle()

            assertEquals(lookalike, viewModel.state.value.wallpaper)
        }

    @Test
    fun favoriteAndDownloadedFlagsFollowThePagedWallpaper() =
        runTest {
            val next = wallpaper.copy(id = "next-one")
            favorites.setFavorites(listOf(Favorite(next, addedAtMillis = 1L)))
            val session = ViewerSession()
            session.open(listOf(wallpaper, next), index = 0)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper), session)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isFavorite)

            viewModel.onNavigate(+1)
            advanceUntilIdle()

            assertEquals(next, viewModel.state.value.wallpaper)
            assertTrue(viewModel.state.value.isFavorite)
            assertFalse(viewModel.state.value.isDownloaded)
        }

    @Test
    fun peekNeighborSpotsThePageTargetWithoutMoving() =
        runTest {
            val next = wallpaper.copy(id = "next-one")
            val session = ViewerSession()
            session.open(listOf(wallpaper, next), index = 0)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper), session)
            advanceUntilIdle()

            assertEquals(next, viewModel.peekNeighbor(+1))
            assertNull(viewModel.peekNeighbor(-1))
            assertEquals(wallpaper, viewModel.state.value.wallpaper)

            viewModel.onNavigate(+1)
            advanceUntilIdle()

            assertEquals(wallpaper, viewModel.peekNeighbor(-1))
            assertNull(viewModel.peekNeighbor(+1))
        }

    @Test
    fun pagingResetsTheDetailsOfTheImageThatLeft() =
        runTest {
            sources.setSources(capableSource)
            val undimensioned = wallpaper.copy(width = null, height = null)
            val record =
                WallpaperDetails(
                    wallpaper = undimensioned,
                    resolution = "1920x1080",
                    fileSizeBytes = 10L,
                )
            sources.enqueueDetails(NetworkResult.Success(record))
            val dimensioned = wallpaper.copy(id = "dimensioned")
            val session = ViewerSession()
            session.open(listOf(undimensioned, dimensioned), index = 0)
            val viewModel = createViewModel(DetailDestination.encode(undimensioned), session)
            advanceUntilIdle()
            assertEquals(record, viewModel.state.value.details)

            viewModel.onNavigate(+1)
            advanceUntilIdle()

            // The image that lands states its own dimensions, so nothing is
            // fetched — and the one that left takes its record with it.
            assertNull(viewModel.state.value.details)
            assertEquals(listOf(undimensioned), sources.detailsCalls)
        }
}

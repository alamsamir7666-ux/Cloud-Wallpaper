package com.cloudimage.feature.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.SaveError
import com.cloudimage.core.data.repository.SaveResult
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.testing.FakeFavoritesRepository
import com.cloudimage.core.testing.FakeHistoryRepository
import com.cloudimage.core.testing.FakeWallpaperApplier
import com.cloudimage.core.testing.FakeWallpaperSaver
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private val wallpaper =
        Wallpaper(
            id = "e1abc2",
            providerId = "wallhaven",
            thumbUrl = "https://w.wallhaven.cc/full/e1abc2/large.jpg",
            fullUrl = "https://w.wallhaven.cc/full/e1abc2/original.png",
            width = 1920,
            height = 1080,
        )

    private fun createViewModel(encoded: String?): DetailViewModel =
        DetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(DetailDestination.arg to encoded)),
            applier = applier,
            saver = saver,
            favoritesRepository = favorites,
            historyRepository = history,
        )

    @Test
    fun decodesWallpaperFromTheRouteArgument() {
        val viewModel = createViewModel(DetailDestination.encode(wallpaper))

        assertEquals(wallpaper, viewModel.state.value.wallpaper)
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
    fun saveFailureEmitsMappedError() =
        runTest {
            saver.galleryResult = SaveResult.Failure(SaveError.HTTP)
            val viewModel = createViewModel(DetailDestination.encode(wallpaper))

            viewModel.events.test {
                viewModel.onSave()
                advanceUntilIdle()

                assertEquals(OperationState.Failed(DetailError.HTTP), viewModel.state.value.saveOp)
                assertEquals(DetailEvent.ActionFailed(DetailAction.SAVE, DetailError.HTTP), awaitItem())
            }
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
}

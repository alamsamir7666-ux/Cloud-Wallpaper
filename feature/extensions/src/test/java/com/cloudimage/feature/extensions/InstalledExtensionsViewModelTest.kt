package com.cloudimage.feature.extensions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The installed-extensions ViewModel (v1.0.20): per-source enablement
 * (and the browse-pin guard), API keys, uninstall, diagnostics, search.
 */
class InstalledExtensionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun TestScope.newPreferences() =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
            },
        )

    private fun TestScope.viewModel(
        engine: FakeExtensionRepository = FakeExtensionRepository(),
        sources: FakeSources = FakeSources(),
        preferences: UserPreferencesRepository = newPreferences(),
    ): InstalledExtensionsViewModel =
        InstalledExtensionsViewModel(
            repository = engine,
            preferences = preferences,
            sources = sources,
        )

    @Test
    fun stateReflectsInstalledRowsAndClearsLoading() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)
            assertTrue(viewModel.state.value.loading)

            fake.state.value = listOf(row())

            val state = viewModel.state.value
            assertFalse(state.loading)
            assertEquals(listOf(row()), state.extensions)
        }

    @Test
    fun uninstallForwardsIdToRepository() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)

            viewModel.uninstall(row())

            assertEquals(listOf("cloudimage.demo"), fake.uninstalledIds)
        }

    @Test
    fun savedKeysSurfaceInState() =
        runTest {
            val preferences = newPreferences()
            val viewModel = viewModel(preferences = preferences)

            viewModel.saveApiKey("cloudimage.unsplash", "secret")

            val state = viewModel.state.first { "cloudimage.unsplash" in it.keyedProviders }
            assertTrue("cloudimage.unsplash" in state.keyedProviders)
        }

    @Test
    fun sourcesFlowFeedsStateForKeyDialogs() =
        runTest {
            val sources = FakeSources()
            val viewModel = viewModel(sources = sources)

            sources.sourcesState.value =
                listOf(SourceInfo("cloudimage.unsplash", "Unsplash", requiresApiKey = true))

            assertEquals(
                listOf(SourceInfo("cloudimage.unsplash", "Unsplash", true)),
                viewModel.state.value.sources,
            )
        }

    @Test
    fun loadFailuresFeedStateForDiagnostics() =
        runTest {
            val sources = FakeSources()
            val viewModel = viewModel(sources = sources)

            sources.failuresState.value =
                mapOf("cloudimage.broken" to "entry class com.example.Broken is missing from the package")

            val state = viewModel.state.first { it.loadFailures.isNotEmpty() }

            assertEquals(
                "entry class com.example.Broken is missing from the package",
                state.loadFailures["cloudimage.broken"],
            )
        }

    @Test
    fun togglingASourcePersistsThroughPreferences() =
        runTest {
            val preferences = newPreferences()
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, preferences = preferences)
            val wallhaven = manifestRow("cloudimage.wallhaven")
            fake.state.value = listOf(wallhaven)

            viewModel.setSourceEnabled(wallhaven, enabled = false)

            val state = viewModel.state.first { "cloudimage.wallhaven" in it.disabledSources }
            assertEquals(setOf("cloudimage.wallhaven"), state.disabledSources)
        }

    @Test
    fun disablingThePinnedSourceClearsTheBrowsePin() =
        runTest {
            val preferences = newPreferences()
            preferences.setBrowseSourceId("cloudimage.wallhaven")
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, preferences = preferences)
            val wallhaven = manifestRow("cloudimage.wallhaven")
            fake.state.value = listOf(wallhaven)

            viewModel.setSourceEnabled(wallhaven, enabled = false)

            viewModel.state.first { "cloudimage.wallhaven" in it.disabledSources }
            assertEquals("", preferences.preferences.first().browseSourceId)
        }

    @Test
    fun disablingAnUnpinnedSourceKeepsThePin() =
        runTest {
            val preferences = newPreferences()
            preferences.setBrowseSourceId("cloudimage.other")
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, preferences = preferences)
            val wallhaven = manifestRow("cloudimage.wallhaven")
            fake.state.value = listOf(wallhaven)

            viewModel.setSourceEnabled(wallhaven, enabled = false)

            viewModel.state.first { "cloudimage.wallhaven" in it.disabledSources }
            assertEquals("cloudimage.other", preferences.preferences.first().browseSourceId)
        }

    @Test
    fun togglingRowsWithoutManifestIsIgnored() =
        runTest {
            val preferences = newPreferences()
            val viewModel = viewModel(preferences = preferences)
            val broken = row("cloudimage.broken") // manifest == null

            viewModel.setSourceEnabled(broken, enabled = false)

            advanceUntilIdle()
            val disabled = viewModel.state.value.disabledSources
            assertTrue(disabled.isEmpty())
        }

    @Test
    fun setQueryFiltersVisibleExtensionsByNameOrId() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)
            fake.state.value = listOf(manifestRow("cloudimage.wallhaven"), manifestRow("cloudimage.unsplash"))

            viewModel.setQuery("wallhaven")
            assertEquals(
                listOf("cloudimage.wallhaven"),
                viewModel.state.value
                    .visibleExtensions()
                    .map { it.id },
            )

            viewModel.setQuery("")
            assertEquals(
                2,
                viewModel.state.value
                    .visibleExtensions()
                    .size,
            )

            viewModel.setQuery("UNSPLASH")
            assertEquals(
                listOf("cloudimage.unsplash"),
                viewModel.state.value
                    .visibleExtensions()
                    .map { it.id },
            )
        }
}

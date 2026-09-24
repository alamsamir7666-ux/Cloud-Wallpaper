package com.cloudimage.core.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloudimage.core.data.platform.AndroidUpdateInstaller
import com.cloudimage.core.data.platform.InstallerStarter
import com.cloudimage.core.data.repository.UpdateInstallResult
import com.cloudimage.core.model.AppUpdate
import com.cloudimage.core.network.CloudimageHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidUpdateInstallerTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var installer: AndroidUpdateInstaller
    private val firedIntents = mutableListOf<Intent>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        // Robolectric hands every test a fresh data dir; FileProvider caches
        // its parsed roots per authority in a static map, so a provider
        // initialized by an earlier test would point at that test's cacheDir
        // and reject this test's file. Reset the cache to stay hermetic.
        runCatching {
            val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            cache.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (cache.get(null) as MutableMap<Any, Any>).clear()
        }
        installer =
            AndroidUpdateInstaller(
                context = context,
                client =
                    CloudimageHttpClient(
                        okHttpClient = OkHttpClient(),
                        json = Json { ignoreUnknownKeys = true },
                    ),
            )
        installer.starter =
            InstallerStarter { intent -> firedIntents.add(intent) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadStagesApkAndFiresTheSystemInstaller() =
        runTest {
            val apkBytes = ByteArray(1_000) { it.toByte() }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apkBytes)))
            val update = update(url = server.url("/app.apk").toString())

            val result = installer.downloadAndInstall(update)

            assertEquals(UpdateInstallResult.Started, result)
            assertEquals(1, firedIntents.size)
            val intent = firedIntents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
            val uri = intent.data
            assertNotNull(uri)
            assertEquals("${context.packageName}.updateprovider", uri?.authority)
            // The staged file carries exactly the downloaded bytes.
            val staged = File(context.cacheDir, "updates/cloudimage-v1.2.0.apk")
            assertTrue(staged.exists())
            assertTrue(staged.readBytes().contentEquals(apkBytes))
        }

    @Test
    fun tagCharactersThatDoNotBelongInFileNamesAreSanitized() =
        runTest {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(10))))
            val update = update(url = server.url("/app.apk").toString(), tagName = "v1.0.0/../evil")

            val result = installer.downloadAndInstall(update)

            assertEquals(UpdateInstallResult.Started, result)
            assertTrue(File(context.cacheDir, "updates/cloudimage-v1.0.0..evil.apk").exists())
        }

    @Test
    fun downloadFailureMapsToItsInstallError() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            val update = update(url = server.url("/app.apk").toString())

            val result = installer.downloadAndInstall(update)

            assertEquals(
                UpdateInstallResult.Failure(com.cloudimage.core.data.repository.UpdateInstallError.HTTP),
                result,
            )
        }

    private fun update(
        url: String,
        tagName: String = "v1.2.0",
    ): AppUpdate =
        AppUpdate(
            tagName = tagName,
            versionName = tagName.removePrefix("v"),
            apkUrl = url,
            apkSizeBytes = 1_000,
            releaseUrl = "https://github.com/release",
            title = null,
        )
}

package com.chizberg.rewind.colorization

import com.chizberg.rewind.core.util.Job
import com.chizberg.rewind.network.DownloadPerformer
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The model store's whole download, against a real HTTP server and a real archive: the manifest
 * names the archive, the archive is checked against it, unpacked and installed where the store
 * then finds it. iOS has no counterpart (its store runs on `URLSession` and the app's support
 * directory, out of reach of its unit tests).
 */
class ColorizationModelStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph = byteArrayOf(1, 2, 3, 5, 8, 13, 21)
    private val made = mutableListOf<Pair<ColorizationModelId, File>>()

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun aDownloadedArchiveIsInstalledAndHandedOutAsTheModel() =
        runBlocking {
            val archive = zip("DDColorLarge.onnx" to graph, "README" to byteArrayOf(0))
            val store = store(manifestFor(archive))
            server.enqueue(MockResponse().setBody(Buffer().write(archive)))

            val finished = finish(store.download(ColorizationModelId.DDColorLarge))

            assertTrue(finished.result.isSuccess)
            assertEquals(
                ColorizationFileState.Downloaded,
                store.fileState(ColorizationModelId.DDColorLarge),
            )
            assertEquals(
                ColorizationFileState.Available,
                store.fileState(ColorizationModelId.Eccv16),
            )
            assertNotNull(store.localModel(ColorizationModelId.DDColorLarge))
            // The model is built from the graph the archive carried.
            assertArrayEquals(graph, made.last().second.readBytes())
        }

    @Test
    fun anArchiveThatIsNotTheOneInTheManifestIsNotInstalled() =
        runBlocking {
            val archive = zip("DDColorLarge.onnx" to graph)
            val tampered = zip("DDColorLarge.onnx" to graph.reversedArray())
            val store = store(manifestFor(archive))
            server.enqueue(MockResponse().setBody(Buffer().write(tampered)))

            val finished = finish(store.download(ColorizationModelId.DDColorLarge))

            assertTrue(finished.result.isFailure)
            assertEquals(
                ColorizationFileState.Available,
                store.fileState(ColorizationModelId.DDColorLarge),
            )
            assertEquals(null, store.localModel(ColorizationModelId.DDColorLarge))
        }

    private suspend fun finish(job: DownloadJob): Job.State.Finished<Unit> =
        withTimeout(
            TIMEOUT,
        ) { job.state.first { it is Job.State.Finished } } as Job.State.Finished<Unit>

    private fun store(manifest: ColorizationManifest) =
        ColorizationModelStore(
            downloadPerformer = DownloadPerformer(OkHttpClient(), folder.newFolder(), scope),
            manifest = Remote { manifest },
            baseUrl = server.url("/models").toString().trimEnd('/'),
            modelsDirectory = File(folder.root, "models"),
            temporaryDirectory = folder.newFolder(),
            makeModel = { id, file ->
                made += id to file
                ColorlessModel(claheClip = 1.0)
            },
            scope = scope,
        )

    private fun manifestFor(archive: ByteArray) =
        ColorizationManifest(
            listOf(
                ColorizationManifest.Entry(
                    unpackedName = "DDColorLarge.onnx",
                    bytes = archive.size.toLong(),
                    sha256 =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(archive)
                            .joinToString("") { "%02x".format(it) },
                ),
            ),
        )

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}

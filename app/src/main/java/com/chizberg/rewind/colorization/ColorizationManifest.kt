package com.chizberg.rewind.colorization

import com.chizberg.rewind.network.DownloadRequest
import com.chizberg.rewind.network.Request
import com.chizberg.rewind.network.dto.networkJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import okhttp3.Request as OkHttpRequest

/**
 * What the model host serves: one archive per model, named after the file it unpacks to, with the
 * archive's size and checksum. Port of iOS `ColorizationManifest` — the same schema, so one tool
 * publishes both platforms' manifests.
 */
@Serializable
data class ColorizationManifest(
    val models: List<Entry>,
) {
    @Serializable
    data class Entry(
        val unpackedName: String,
        val bytes: Long,
        val sha256: String,
    )

    fun entry(id: ColorizationModelId): Entry =
        models.firstOrNull { it.unpackedName == id.packageName }
            ?: throw IOException("No ${id.packageName} in the manifest")
}

/**
 * The file a model's archive unpacks to. iOS ships Core ML packages (`DDColorLarge.mlpackage`); the
 * Android host serves ONNX graphs of the same two models under its own prefix.
 */
val ColorizationModelId.packageName: String
    get() =
        when (this) {
            ColorizationModelId.DDColorLarge -> "DDColorLarge.onnx"
            ColorizationModelId.Eccv16 -> "ECCV16.onnx"
        }

/** iOS `Network.Request.colorizationManifest()`: `manifest.json` at the model host. */
fun colorizationManifestRequest(baseUrl: String): Request<ColorizationManifest> =
    Request(
        makeRequest = { OkHttpRequest.Builder().url("$baseUrl/manifest.json").build() },
        parseResult = { bytes ->
            networkJson.decodeFromString(ColorizationManifest.serializer(), bytes.decodeToString())
        },
    )

/**
 * One model's archive: downloaded, checked against the manifest, unpacked into [temporaryDirectory].
 * Port of iOS `Network.DownloadRequest.colorizationModel(_:)`, minus the Core ML compile — an ONNX
 * graph is loaded as it is. The answer is the unpacked graph, for the store to move into place.
 */
fun colorizationModelDownload(
    entry: ColorizationManifest.Entry,
    baseUrl: String,
    temporaryDirectory: File,
): DownloadRequest<File> =
    DownloadRequest(
        makeRequest = {
            OkHttpRequest.Builder().url("$baseUrl/${entry.unpackedName}.zip").build()
        },
        processFile = { archive ->
            withContext(Dispatchers.IO) {
                verify(archive, entry)
                unarchive(archive, entry.unpackedName, temporaryDirectory)
            }
        },
    )

private suspend fun verify(
    archive: File,
    entry: ColorizationManifest.Entry,
) {
    val size = archive.length()
    if (size != entry.bytes) {
        throw IOException("Archive size $size differs from the manifest: ${entry.bytes}")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER_SIZE)
    archive.inputStream().use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val hex = digest.digest().joinToString("") { "%02x".format(it) }
    if (hex != entry.sha256) throw IOException("Archive checksum differs from the manifest")
}

/** The one file the archive is for; anything else in it is skipped. */
private suspend fun unarchive(
    archive: File,
    name: String,
    directory: File,
): File {
    directory.mkdirs()
    val destination = File(directory, UUID.randomUUID().toString())
    ZipInputStream(archive.inputStream().buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: throw IOException("No $name in the archive")
            if (entry.isDirectory || entry.name != name) continue
            var written = false
            try {
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                written = true
            } finally {
                // A cancelled or failed unpack leaves nothing half-written behind.
                if (!written) destination.delete()
            }
            return destination
        }
    }
}

private const val BUFFER_SIZE = 64 * 1024

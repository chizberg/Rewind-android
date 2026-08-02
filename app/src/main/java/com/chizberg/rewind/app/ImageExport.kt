package com.chizberg.rewind.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.details.ShareContent
import com.chizberg.rewind.network.ImageQuality
import com.chizberg.rewind.network.imageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** The album the saved photos land in, under the device's Pictures directory. */
private const val ALBUM = "Rewind"

/** JPEG quality for the re-encoded copy — high enough that the recompression is not visible. */
private const val JPEG_QUALITY = 95

/** Where a share copy is staged; matches the `<cache-path>` of `res/xml/file_paths.xml`. */
private const val SHARE_DIR = "shared"

/**
 * Saving a photo to the gallery and handing it to the share sheet — the platform half of the details
 * screen's two remaining actions. Wired into the reducer as `ImageSaver` / `ImageSharer`.
 *
 * Both go through the shared Coil [loader] rather than a second download: the screen has just
 * displayed the photo, so the bytes are in its disk cache. What travels is a *re-encoded* JPEG, not
 * the original file — the same thing iOS does (it hands `PHPhotoLibrary` a decoded `UIImage`), and
 * it is what the Coil divergence leaves us: the loader's product is a bitmap.
 */
class ImageExport(
    context: Context,
    private val loader: ImageLoader,
) {
    private val appContext = context.applicationContext

    /**
     * Inserts the photo into the gallery (`Pictures/Rewind`). Port of iOS `save(image:)`.
     *
     * The row is published in two steps — inserted `IS_PENDING`, cleared once the bytes are
     * written — so a failure halfway can't leave a zero-byte photo visible in the gallery. No
     * runtime permission is involved: MediaStore owns the row we created (minSdk 31).
     */
    suspend fun save(image: ModelImage) {
        save(load(image), fileName(image))
    }

    /**
     * The same insert for pixels that never came from the loader — the comparison screen's
     * composite (iOS hands `PHPhotoLibrary` its rendered `UIImage` in exactly the same way).
     */
    suspend fun save(
        bitmap: Bitmap,
        fileName: String,
    ) {
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val pending =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$ALBUM",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
                    ?: throw IOException("Gallery rejected the new image")
            runCatching {
                val stream =
                    resolver.openOutputStream(uri)
                        ?: throw IOException("Gallery gave no stream to write to")
                stream.use { bitmap.writeJpeg(it) }
            }.onFailure {
                // Drop the half-written row rather than leave a broken photo in the gallery.
                resolver.delete(uri, null, null)
            }.getOrThrow()
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }

    /**
     * Opens the system share sheet on the photo. Port of iOS `makeShareVC` + its `.sheet`.
     *
     * iOS passes four activity items and lets each target pick what it understands; an Android
     * intent carries one stream and one text, so the title, the (HTML-stripped) description and the
     * pastvu link are joined into that text. The copy is staged in the cache directory and exposed
     * through a `FileProvider` — a `file://` URI would have the target crash on a
     * `FileUriExposedException`.
     */
    suspend fun share(content: ShareContent) {
        share(
            bitmap = load(content.image),
            fileName = fileName(content.image),
            title = content.title,
            text = content.text(),
        )
    }

    /**
     * The same share sheet for pixels of our own making — the comparison composite. iOS builds its
     * activity controller from the rendered image, the photo's title and its pastvu link, with no
     * description at all (`makeShareVC(description: nil)`), and that is what [text] carries here.
     */
    suspend fun share(
        bitmap: Bitmap,
        fileName: String,
        title: String,
        text: String,
    ) {
        val uri =
            withContext(Dispatchers.IO) {
                val dir = File(appContext.cacheDir, SHARE_DIR).apply { mkdirs() }
                val file = File(dir, fileName)
                file.outputStream().use { bitmap.writeJpeg(it) }
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file,
                )
            }
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title)
                // The grant flag alone covers EXTRA_STREAM on modern targets, but the clip data is
                // what the chooser's own preview reads to show a thumbnail.
                clipData = ClipData.newUri(appContext.contentResolver, title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        appContext.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * The photo at full quality, from the cache when it is there. Hardware bitmaps are turned off:
     * their pixels live in graphics memory, and both paths here have to read them back.
     */
    private suspend fun load(image: ModelImage): Bitmap {
        val request =
            ImageRequest
                .Builder(appContext)
                .data(imageUrl(image.imagePath, ImageQuality.High))
                .size(Size.ORIGINAL)
                .allowHardware(false)
                .build()
        return when (val result = loader.execute(request)) {
            is SuccessResult -> result.image.toBitmap()
            is ErrorResult -> throw result.throwable
        }
    }
}

private fun Bitmap.writeJpeg(stream: OutputStream) {
    if (!compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
        throw IOException("Unable to encode the image")
    }
}

private fun fileName(image: ModelImage): String = "pastvu_${image.cid}.jpg"

/** The comparison composite of the same photo — a sibling name, so the two sit together in the
 *  album. A second shot of the same photo becomes "… (1).jpg", MediaStore's own doing (as on iOS,
 *  where a second save is a second asset). */
fun comparisonFileName(cid: Int): String = "pastvu_${cid}_comparison.jpg"

/**
 * Title, description and link as one blob — see [ImageExport.share]. The description is PastVu HTML
 * and iOS shares the plain text of it; `HtmlCompat` is the right tool *here* (this file is Android
 * through and through), unlike in the view, which keeps to Compose's parser to stay out of
 * `android.text`.
 */
private fun ShareContent.text(): String =
    listOfNotNull(
        title.takeIf { it.isNotBlank() },
        description
            ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT) }
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() },
        url,
    ).joinToString("\n\n")

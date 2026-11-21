package com.rameen.i230806.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.ImageView
import com.rameen.i230806.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import com.bumptech.glide.Glide
import java.util.concurrent.Executors

/**
 * Pictures and videos (Assignment 3: files on our PHP server, uploads/...).
 *
 * Saving: photos are shrunk first (compress: longest side 1080 px, JPEG quality 70, about
 * 100-200 KB) and uploaded with the post / message / story in one multipart request
 * (Api.filePart).
 *
 * Showing: posts, users and messages keep the file's URL. Images.load(imageView, url) uses
 * Glide, which caches in memory and on disk, so a picture seen once still shows offline.
 * A picture still on the phone (content:// or file://, waiting in the offline queue) shows the
 * same way. Videos are downloaded once into the cache folder for VideoView (loadVideo).
 */
object Images {

    private const val MAX_SIDE = 1080
    private const val JPEG_QUALITY = 70

    /** Biggest video we accept for a story (5 MB, about 15 seconds at 480p). */
    const val MAX_VIDEO_BYTES = 5L * 1024 * 1024

    private val background = Executors.newFixedThreadPool(2)
    private val mainThread = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    /** Size in bytes of the file at [uri] (0 if unknown). Used to refuse videos over 5 MB. */
    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: return 0).length()
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0
    }

    /**
     * Reads the picture, scales it down to [MAX_SIDE] and returns it as JPEG bytes.
     * Assignment 3: every photo is shrunk like this before it is uploaded to our server
     * (Api.filePart), so uploads are small and fast (about 100-200 KB instead of 5 MB).
     */
    fun compress(context: Context, uri: Uri): ByteArray? {
        val bitmap = decodeScaled(context, uri) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also turns photos the right way up (EXIF rotation).
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = MAX_SIDE.toFloat() / maxOf(width, height)
                if (scale < 1f) decoder.setTargetSize((width * scale).toInt(), (height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // Android 8 / 8.1: read the size first, then load a smaller copy.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // Showing
    // ---------------------------------------------------------------------------------------

    /**
     * Shows the picture at [mediaId] (a URL) in [view]. Safe in lists: Glide cancels the old
     * request when a row is reused for another item.
     * An empty [mediaId] clears the view (or shows [placeholder] if given).
     */
    fun load(view: ImageView, mediaId: String?, placeholder: Int = 0) {
        view.setTag(R.id.tag_media_id, mediaId)
        if (mediaId.isNullOrEmpty()) {
            if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
            return
        }
        // Assignment 3: pictures live on our server and the "id" is the file's URL: Glide loads
        // it (with its own memory and disk cache, so it still shows offline once seen).
        // A picture still on the phone (content:// or file://, e.g. picked but not uploaded
        // yet, or waiting in the offline queue) is shown the same way.
        if (mediaId.startsWith("http") || mediaId.startsWith("content:") || mediaId.startsWith("file:")) {
            val request = Glide.with(view.context.applicationContext).load(mediaId)
            (if (placeholder != 0) request.placeholder(placeholder) else request).into(view)
            return
        }
        // Anything else (an old Assignment 2 Firebase media id): not on this server.
        if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
    }

    /**
     * Copies the video at [mediaId] (a URL, or content:// for one still on the phone) into a
     * cache file that VideoView can play, and passes it to [onReady] (null if it failed).
     */
    fun loadVideo(context: Context, mediaId: String, onReady: (File?) -> Unit) {
        // Assignment 3: a video URL on our server is downloaded once into the cache folder,
        // so it plays smoothly and still plays offline later.
        // A video still on the phone (a story shared offline, not uploaded yet) is the same,
        // but copied from the phone's own storage.
        if (mediaId.startsWith("http") || mediaId.startsWith("content:") || mediaId.startsWith("file:")) {
            val cachedFile = File(context.cacheDir, "video_${mediaId.hashCode()}.mp4")
            if (cachedFile.exists() && cachedFile.length() > 0) {
                onReady(cachedFile)
                return
            }
            background.execute {
                val ok = try {
                    val input =
                        if (mediaId.startsWith("http")) URL(mediaId).openStream()
                        else context.contentResolver.openInputStream(android.net.Uri.parse(mediaId))
                    input!!.use { stream -> cachedFile.outputStream().use { stream.copyTo(it) } }
                    true
                } catch (e: Exception) {
                    cachedFile.delete()
                    false
                }
                mainThread.post { onReady(if (ok) cachedFile else null) }
            }
            return
        }
        onReady(null)   // not a file we can reach
    }
}

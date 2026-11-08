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
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.MediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Pictures and videos without Firebase Storage (which needs the paid plan).
 *
 * Saving: the picture is shrunk (longest side 1080 px, JPEG quality 70, about 100-200 KB),
 * turned into text with Base64 and written to media/{id} in the Realtime Database. Posts, users
 * and messages only keep that id.
 *
 * Showing: Images.load(imageView, mediaId) downloads the text once, turns it back into a
 * picture and keeps it in a memory cache, so scrolling back up doesn't download it again.
 *
 * The slow parts (shrinking, Base64) run on a background thread; results come back on the
 * main thread, so callers can touch views directly.
 */
object Images {

    private const val MAX_SIDE = 1080
    private const val JPEG_QUALITY = 70

    /** Biggest video we accept for a story (5 MB, about 15 seconds at 480p). */
    const val MAX_VIDEO_BYTES = 5L * 1024 * 1024

    private val background = Executors.newFixedThreadPool(2)
    private val mainThread = Handler(Looper.getMainLooper())

    /** Decoded pictures by media id, using at most 1/8 of the app's memory. */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    /**
     * Shrinks the picture at [uri] (gallery or camera), saves it under media/ and passes the
     * new media id to [onDone], or null if it failed.
     */
    fun uploadImage(context: Context, uri: Uri, onDone: (String?) -> Unit) {
        val appContext = context.applicationContext
        background.execute {
            val jpeg = compress(appContext, uri)
            if (jpeg == null) {
                mainThread.post { onDone(null) }
            } else {
                save(Base64.encodeToString(jpeg, Base64.NO_WRAP), MediaType.IMAGE, onDone)
            }
        }
    }

    /**
     * Saves the video at [uri] under media/ as it is (no re-encoding) and passes the media id
     * to [onDone], or null if it failed or is bigger than [MAX_VIDEO_BYTES].
     */
    fun uploadVideo(context: Context, uri: Uri, onDone: (String?) -> Unit) {
        val appContext = context.applicationContext
        background.execute {
            val bytes = try {
                if (sizeOf(appContext, uri) > MAX_VIDEO_BYTES) null
                else appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (bytes == null) {
                mainThread.post { onDone(null) }
            } else {
                save(Base64.encodeToString(bytes, Base64.NO_WRAP), MediaType.VIDEO, onDone)
            }
        }
    }

    /** Size in bytes of the file at [uri] (0 if unknown). Used to refuse videos over 5 MB. */
    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: return 0).length()
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0
    }

    /** Writes { data, type, ownerId } to a new media/{id}. Firebase calls back on the main thread. */
    private fun save(base64: String, type: String, onDone: (String?) -> Unit) {
        val ref = Db.root.child("media").push()
        val mediaId = ref.key
        ref.setValue(mapOf("data" to base64, "type" to type, "ownerId" to Db.uid))
            .addOnCompleteListener { task -> onDone(if (task.isSuccessful) mediaId else null) }
    }

    /** Reads the picture, scales it down to [MAX_SIDE] and returns it as JPEG bytes. */
    private fun compress(context: Context, uri: Uri): ByteArray? {
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
     * Shows media/[mediaId] in [view]. Safe in lists: if the row is reused for another item
     * before the download finishes, the old picture is not put into it.
     * An empty [mediaId] clears the view (or shows [placeholder] if given).
     */
    fun load(view: ImageView, mediaId: String?, placeholder: Int = 0) {
        view.setTag(R.id.tag_media_id, mediaId)
        if (mediaId.isNullOrEmpty()) {
            if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
            return
        }
        val cached = cache.get(mediaId)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
        getBitmap(mediaId) { bitmap ->
            // Only if this view still wants the same picture.
            if (bitmap != null && view.getTag(R.id.tag_media_id) == mediaId) view.setImageBitmap(bitmap)
        }
    }

    /** Downloads and decodes media/[mediaId] (or takes it from the cache). */
    fun getBitmap(mediaId: String, onLoaded: (Bitmap?) -> Unit) {
        cache.get(mediaId)?.let { onLoaded(it); return }
        readData(mediaId) { base64 ->
            if (base64 == null) {
                onLoaded(null)
                return@readData
            }
            background.execute {
                val bitmap = try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    null
                }
                mainThread.post {
                    if (bitmap != null) cache.put(mediaId, bitmap)
                    onLoaded(bitmap)
                }
            }
        }
    }

    /**
     * Downloads the video media/[mediaId] into a temporary file that VideoView can play, and
     * passes the file to [onReady] (null if it failed). Already-downloaded videos are reused.
     */
    fun loadVideo(context: Context, mediaId: String, onReady: (File?) -> Unit) {
        val file = File(context.cacheDir, "video_$mediaId.mp4")
        if (file.exists() && file.length() > 0) {
            onReady(file)
            return
        }
        readData(mediaId) { base64 ->
            if (base64 == null) {
                onReady(null)
                return@readData
            }
            background.execute {
                val ok = try {
                    file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
                    true
                } catch (e: Exception) {
                    false
                }
                mainThread.post { onReady(if (ok) file else null) }
            }
        }
    }

    /** Reads the Base64 text at media/{mediaId}/data once. */
    private fun readData(mediaId: String, onRead: (String?) -> Unit) {
        Db.media(mediaId).child("data").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onRead(snapshot.getValue(String::class.java))
            override fun onCancelled(error: DatabaseError) = onRead(null)
        })
    }
}
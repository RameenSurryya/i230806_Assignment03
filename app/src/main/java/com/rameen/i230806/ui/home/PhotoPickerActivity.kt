package com.rameen.i230806.ui.home

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.MediaType
import com.rameen.i230806.data.PickMode
import com.rameen.i230806.databinding.ActivityPhotoPickerBinding
import com.rameen.i230806.databinding.ItemPickerTileBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Images
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 08 Photo picker: the phone's own photos (and videos for stories), newest first.
 *
 * Who opens it, and what for (Extra.PICK_MODE):
 *  - PickMode.POST          several photos for a post (SELECT MULTIPLE numbers them 1, 2, 3 ...)
 *  - PickMode.SINGLE_IMAGE  one photo: profile picture, cover photo, comment, chat
 *  - PickMode.STORY         one photo or one video (max 5 MB) for a story
 *
 * How it works:
 *  1. Asks for the gallery permission (it differs by Android version, see permissions()).
 *  2. Reads the list of photos / videos from MediaStore, Android's index of all media files.
 *  3. Shows them in a RecyclerView grid. Thumbnails are made on a background thread and kept
 *     in a memory cache (LruCache) so scrolling back is instant.
 *  4. Tapping a tile shows it in the big preview and chooses it.
 *  5. Next returns the chosen files to the screen that opened the picker:
 *       Extra.PICKED_URIS = list of content:// addresses, Extra.MEDIA_TYPE = image or video.
 *
 * Cancel / Back return without choosing anything.
 */
class PhotoPickerActivity : BaseActivity() {

    /** One photo or video on the phone. */
    private data class Item(val uri: Uri, val isVideo: Boolean, val durationMs: Long, val added: Long)

    private enum class Filter { GALLERY, PHOTO, VIDEO }

    private lateinit var binding: ActivityPhotoPickerBinding

    private val mode: String by lazy { intent.getStringExtra(Extra.PICK_MODE) ?: PickMode.POST }
    private val allowsVideo: Boolean get() = mode == PickMode.STORY

    private var all: List<Item> = emptyList()        // everything found on the phone
    private var shown: List<Item> = emptyList()      // after the Gallery / Photo / Video filter
    private val chosen = mutableListOf<Item>()       // in the order they were tapped
    private var previewing: Item? = null
    private var multiSelect = false
    private var filter = Filter.GALLERY
    private var loaded = false

    private val adapter = TileAdapter()

    // Thumbnails: made on 3 background threads, kept in memory (1/16 of the app's memory).
    private val background = Executors.newFixedThreadPool(3)
    private val mainThread = Handler(Looper.getMainLooper())
    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Shows Android's "Allow Kinnect to access photos?" dialog; the answer comes back here. */
    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAccess()) loadMedia() else showNoPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackButton(R.id.tv_cancel)
        binding.tvNext.setOnClickListener { returnChosen() }

        binding.rvPhotos.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.rvPhotos.adapter = adapter

        // SELECT MULTIPLE only makes sense for a post.
        binding.llSelectMultiple.visibility = if (mode == PickMode.POST) View.VISIBLE else View.GONE
        binding.llSelectMultiple.setOnClickListener { toggleMultiSelect() }
        showMultiSelectState()

        // The expand button switches the preview between "fill the frame" and "whole photo".
        binding.ivExpand.setOnClickListener {
            binding.ivPreview.scaleType =
                if (binding.ivPreview.scaleType == ImageView.ScaleType.CENTER_CROP) ImageView.ScaleType.FIT_CENTER
                else ImageView.ScaleType.CENTER_CROP
        }

        // Filters. The Video filter is only there when videos are allowed (stories).
        binding.tvTabGallery.setOnClickListener { setFilter(Filter.GALLERY) }
        binding.tvTabPhoto.setOnClickListener { setFilter(Filter.PHOTO) }
        binding.tvTabVideo.setOnClickListener { setFilter(Filter.VIDEO) }
        val videoTab = if (allowsVideo) View.VISIBLE else View.GONE
        binding.tvTabVideo.visibility = videoTab
        binding.vTabVideo.visibility = videoTab

        if (hasAccess()) loadMedia() else askPermission.launch(permissions())
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the phone's Settings after allowing access.
        if (!loaded && hasAccess()) loadMedia()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // 1. Permission
    // ---------------------------------------------------------------------------------------

    /**
     * Android 14+: photos and videos, plus "Allow limited access" (only the files the user picks).
     * Android 13:  photos (and videos for stories).
     * Android 8-12: the older "read storage" permission.
     */
    private fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 ->
            if (allowsVideo) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True if any of the permissions above was given (limited access counts too). */
    private fun hasAccess(): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Refused: explain, and let a tap ask again. After "Don't allow" twice Android stops showing
     * the dialog, so the tap opens Kinnect's page in the phone's Settings instead.
     */
    private fun showNoPermission() {
        val canAskAgain = shouldShowRequestPermissionRationale(permissions().first())
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.setText(
            if (canAskAgain) R.string.picker_permission_needed else R.string.picker_permission_settings
        )
        binding.tvEmpty.setOnClickListener {
            if (canAskAgain) {
                askPermission.launch(permissions())
            } else {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 2. Reading the photos and videos from MediaStore
    // ---------------------------------------------------------------------------------------

    private fun loadMedia() {
        loaded = true
        binding.tvEmpty.visibility = View.GONE
        binding.tvEmpty.setOnClickListener(null)
        background.execute {
            val found = queryImages() + (if (allowsVideo) queryVideos() else emptyList())
            val newestFirst = found.sortedByDescending { it.added }
            mainThread.post {
                if (isDestroyed) return@post
                all = newestFirst
                // Start with the newest one chosen, so Next works straight away.
                newestFirst.firstOrNull()?.let { first ->
                    chosen.clear()
                    chosen.add(first)
                    preview(first)
                }
                setFilter(filter)
            }
        }
    }

    private fun queryImages(): List<Item> {
        val items = mutableListOf<Item>()
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && items.size < MAX_ITEMS) {
                val id = cursor.getLong(0)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                items.add(Item(uri, isVideo = false, durationMs = 0, added = cursor.getLong(1)))
            }
        }
        return items
    }

    private fun queryVideos(): List<Item> {
        val items = mutableListOf<Item>()
        val columns = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && items.size < MAX_ITEMS) {
                val id = cursor.getLong(0)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                items.add(Item(uri, isVideo = true, durationMs = cursor.getLong(2), added = cursor.getLong(1)))
            }
        }
        return items
    }

    // ---------------------------------------------------------------------------------------
    // 3. Thumbnails
    // ---------------------------------------------------------------------------------------

    /** Shows a [sizePx]-wide thumbnail of [item] in [view], made in the background. */
    private fun showThumb(view: ImageView, item: Item, sizePx: Int) {
        val key = "${item.uri}@$sizePx"
        view.tag = key
        val cached = thumbs.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        background.execute {
            val bitmap = makeThumb(item, sizePx) ?: return@execute
            mainThread.post {
                thumbs.put(key, bitmap)
                // Only if this view (a reused grid tile) still wants this picture.
                if (view.tag == key) view.setImageBitmap(bitmap)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun makeThumb(item: Item, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
        } else {
            // Android 8 / 9: the older thumbnail API (512 x 384).
            val id = ContentUris.parseId(item.uri)
            if (item.isVideo) {
                MediaStore.Video.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            }
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // 4. Choosing
    // ---------------------------------------------------------------------------------------

    private fun onTileTapped(item: Item) {
        // Stories: refuse videos over 5 MB (they are stored in the database as text).
        if (item.isVideo && Images.sizeOf(this, item.uri) > Images.MAX_VIDEO_BYTES) {
            toast(R.string.picker_video_too_big)
            return
        }
        if (multiSelect) {
            when {
                item in chosen -> chosen.remove(item)
                chosen.size >= MAX_POST_PHOTOS -> {
                    toast(getString(R.string.picker_max, MAX_POST_PHOTOS))
                    return
                }
                else -> chosen.add(item)
            }
        } else {
            chosen.clear()
            chosen.add(item)
        }
        preview(item)
        refresh()
    }

    private fun preview(item: Item) {
        previewing = item
        showThumb(binding.ivPreview, item, PREVIEW_PX)
    }

    private fun toggleMultiSelect() {
        multiSelect = !multiSelect
        if (!multiSelect) {
            // Back to one photo: keep the one in the preview.
            val keep = previewing
            chosen.clear()
            if (keep != null) chosen.add(keep)
        }
        showMultiSelectState()
        refresh()
    }

    /** Faded pill = off, full colour = on. */
    private fun showMultiSelectState() {
        binding.llSelectMultiple.alpha = if (multiSelect) 1f else 0.6f
        binding.llSelectMultiple.isSelected = multiSelect
    }

    private fun setFilter(newFilter: Filter) {
        filter = newFilter
        shown = when (filter) {
            Filter.GALLERY -> all
            Filter.PHOTO -> all.filter { !it.isVideo }
            Filter.VIDEO -> all.filter { it.isVideo }
        }
        // Teal text and line under the active filter, grey for the others.
        val teal = ContextCompat.getColor(this, R.color.teal)
        val grey = ContextCompat.getColor(this, R.color.text_grey)
        val tabs = listOf(
            Triple(Filter.GALLERY, binding.tvTabGallery, binding.vTabGallery),
            Triple(Filter.PHOTO, binding.tvTabPhoto, binding.vTabPhoto),
            Triple(Filter.VIDEO, binding.tvTabVideo, binding.vTabVideo),
        )
        for ((tabFilter, text, line) in tabs) {
            val active = tabFilter == filter
            text.setTextColor(if (active) teal else grey)
            line.setBackgroundColor(if (active) teal else 0)
        }

        binding.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty()) binding.tvEmpty.setText(R.string.picker_empty)
        refresh()
    }

    /** Redraws the tiles (rings, numbers, frame) and the Next label. */
    private fun refresh() {
        adapter.notifyDataSetChanged()
        binding.tvNext.text =
            if (multiSelect && chosen.isNotEmpty()) getString(R.string.picker_next_count, chosen.size)
            else getString(R.string.picker_next_plain)
    }

    // ---------------------------------------------------------------------------------------
    // 5. Returning the result
    // ---------------------------------------------------------------------------------------

    private fun returnChosen() {
        if (chosen.isEmpty()) {
            toast(R.string.picker_choose_first)
            return
        }
        val result = Intent()
            .putStringArrayListExtra(Extra.PICKED_URIS, ArrayList(chosen.map { it.uri.toString() }))
            .putExtra(Extra.MEDIA_TYPE, if (chosen.first().isVideo) MediaType.VIDEO else MediaType.IMAGE)
        setResult(RESULT_OK, result)
        finish()
    }

    // ---------------------------------------------------------------------------------------
    // The grid
    // ---------------------------------------------------------------------------------------

    private inner class TileHolder(val tile: ItemPickerTileBinding) : RecyclerView.ViewHolder(tile.root)

    private inner class TileAdapter : RecyclerView.Adapter<TileHolder>() {

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
            val tile = ItemPickerTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // Square tiles: as tall as a quarter of the grid's width.
            val width = parent.width / COLUMNS
            if (width > 0) tile.root.layoutParams.height = width
            return TileHolder(tile)
        }

        override fun onBindViewHolder(holder: TileHolder, position: Int) {
            val item = shown[position]
            val tile = holder.tile
            showThumb(tile.ivThumb, item, TILE_PX)

            val number = chosen.indexOf(item) + 1          // 0 = not chosen
            tile.vSelected.visibility = if (item == previewing) View.VISIBLE else View.GONE
            tile.vRing.visibility = if (multiSelect && number == 0) View.VISIBLE else View.GONE
            tile.tvNumber.visibility = if (multiSelect && number > 0) View.VISIBLE else View.GONE
            tile.tvNumber.text = number.toString()

            if (item.isVideo) {
                val seconds = item.durationMs / 1000
                tile.tvDuration.visibility = View.VISIBLE
                tile.tvDuration.text = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
            } else {
                tile.tvDuration.visibility = View.GONE
            }
            tile.root.contentDescription = getString(
                if (item.isVideo) R.string.cd_picker_video else R.string.cd_picker_tile, position + 1
            )
            tile.root.setOnClickListener { onTileTapped(item) }
        }
    }

    private companion object {
        const val COLUMNS = 4
        const val MAX_ITEMS = 300          // newest 300 photos (and 300 videos) is plenty
        const val MAX_POST_PHOTOS = 10     // each photo is ~150 KB in the database
        const val TILE_PX = 256
        const val PREVIEW_PX = 1080
    }
}
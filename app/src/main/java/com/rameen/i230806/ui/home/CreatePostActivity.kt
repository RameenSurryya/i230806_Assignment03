package com.rameen.i230806.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.PickMode
import com.rameen.i230806.data.PostRepo
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityCreatePostBinding
import com.rameen.i230806.databinding.ItemPickedPhotoBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Avatars
import java.util.concurrent.Executors

/**
 * 07 Create post.
 *  - Type the text; add photos with Photo/video (the Photo picker, several at once) or Camera
 *    (one photo). Chosen photos show as thumbnails with an X to remove them.
 *  - Post: PostRepo.create uploads each photo to media/ and then saves posts/{id}. The button
 *    is disabled while that runs, then the screen closes and Home shows the new post.
 *  - X / Back close without posting. The chips, swatches and other sheet rows are visual only.
 * Opened with Extra.PICK_MODE (the photo icon on Home) it goes straight to the Photo picker.
 */
class CreatePostActivity : BaseActivity() {

    private lateinit var binding: ActivityCreatePostBinding

    /** The photos chosen so far, in order. */
    private val pictures = mutableListOf<Uri>()

    private val background = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    /** Photo picker result: a list of content:// addresses. */
    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            result.data?.getStringArrayListExtra(Extra.PICKED_URIS)?.forEach { addPicture(it.toUri()) }
        }

    /** Camera result: one photo (Extra.MEDIA_URI). */
    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            result.data?.getStringExtra(Extra.MEDIA_URI)?.let { addPicture(it.toUri()) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatePostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackButton(R.id.iv_close)
        binding.btnPost.setOnClickListener { post() }
        binding.llPhotoVideo.setOnClickListener { openPicker() }
        binding.llCamera.setOnClickListener {
            takePhoto.launch(Intent(this, CameraActivity::class.java).putExtra(Extra.PICK_MODE, PickMode.POST))
        }

        Db.getUser(myUid) { user -> showAuthor(user) }

        // The photo icon on Home: straight to the picker (only the first time, not after rotation).
        if (savedInstanceState == null && intent.hasExtra(Extra.PICK_MODE)) openPicker()
        binding.etPostText.requestFocus()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun showAuthor(user: User?) {
        Avatars.bind(binding.tvAuthorAvatar, binding.ivAuthorPhoto, user)
        binding.tvAuthorName.text = user?.name.orEmpty()
    }

    private fun openPicker() {
        pickPhotos.launch(
            Intent(this, PhotoPickerActivity::class.java).putExtra(Extra.PICK_MODE, PickMode.POST)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Chosen photos
    // ---------------------------------------------------------------------------------------

    private fun addPicture(uri: Uri) {
        if (pictures.size >= MAX_PHOTOS) {
            toast(getString(R.string.picker_max, MAX_PHOTOS))
            return
        }
        if (uri in pictures) return
        pictures.add(uri)
        showPictures()
    }

    /** Rebuilds the thumbnail row (item_picked_photo.xml per photo). */
    private fun showPictures() {
        binding.llPicked.removeAllViews()
        for (uri in pictures) {
            val tile = ItemPickedPhotoBinding.inflate(layoutInflater, binding.llPicked, false)
            tile.ivPicked.clipToOutline = true   // rounded corners of bg_gallery_thumb
            showThumbnail(tile.ivPicked, uri)
            tile.ivRemove.setOnClickListener {
                pictures.remove(uri)
                showPictures()
            }
            binding.llPicked.addView(tile.root)
        }
        val hasPhotos = pictures.isNotEmpty()
        binding.hsvPicked.visibility = if (hasPhotos) View.VISIBLE else View.GONE
        binding.llSwatches.visibility = if (hasPhotos) View.GONE else View.VISIBLE
    }

    /** Decodes a small copy of the photo on a background thread (full photos are huge). */
    private fun showThumbnail(view: ImageView, uri: Uri) {
        view.tag = uri
        background.execute {
            val bitmap = smallBitmap(uri)
            mainThread.post { if (view.tag == uri && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    /** Reads only the size first, then decodes at 1/2, 1/4 ... so it is about 300 px. */
    private fun smallBitmap(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // Posting
    // ---------------------------------------------------------------------------------------

    private fun post() {
        val text = binding.etPostText.text.toString().trim()
        if (text.isEmpty() && pictures.isEmpty()) {
            toast(R.string.create_error_empty)
            return
        }
        setBusy(true)
        PostRepo.create(this, text, pictures.toList()) { saved ->
            if (isDestroyed) return@create
            if (saved) {
                toast(R.string.create_posted)
                finish()
            } else {
                setBusy(false)
                toast(R.string.create_error)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnPost.isEnabled = !busy
        binding.btnPost.alpha = if (busy) 0.6f else 1f
        binding.btnPost.setText(if (busy) R.string.create_posting else R.string.create_post_button)
    }

    private companion object {
        const val MAX_PHOTOS = 10
        const val THUMB_PX = 300
    }
}
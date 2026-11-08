package com.rameen.i230806.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.rameen.i230806.R
import com.rameen.i230806.data.Audience
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.MediaType
import com.rameen.i230806.data.StoryRepo
import com.rameen.i230806.databinding.ActivityStoryEditorBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import java.util.concurrent.Executors

/**
 * 10 Story editor: the photo or video from the Camera (Extra.MEDIA_URI + Extra.MEDIA_TYPE).
 *  - "Aa" (or tapping the sticker) types the text sticker shown on the story.
 *  - "Your story" / the arrow: share with everyone. "Close friends": share only with them.
 *    Sharing uploads the photo / video to media/ and saves stories/{me}/{id}
 *    (StoryRepo.add), then opens Your story.
 *  - X / Back: back to the camera without sharing.
 */
class StoryEditorActivity : BaseActivity() {
    override val darkSystemBars = true // white status bar icons on the black screen

    private lateinit var binding: ActivityStoryEditorBinding
    private lateinit var mediaUri: Uri
    private lateinit var mediaType: String
    private var label = ""
    private var sharing = false

    private val background = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoryEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intent.getStringExtra(Extra.MEDIA_URI)
        if (uri == null) {
            finish()
            return
        }
        mediaUri = Uri.parse(uri)
        mediaType = intent.getStringExtra(Extra.MEDIA_TYPE) ?: MediaType.IMAGE

        // Cut the picture to the rounded corners of its background shape (XML cannot do this).
        binding.flViewport.clipToOutline = true

        binding.ivClose.setOnClickListener { finish() }
        binding.tvAddText.setOnClickListener { editText() }
        binding.tvSticker.setOnClickListener { editText() }
        binding.llYourStory.setOnClickListener { share(Audience.PUBLIC) }
        binding.ivNext.setOnClickListener { share(Audience.PUBLIC) }
        binding.llCloseFriends.setOnClickListener { share(Audience.CLOSE_FRIENDS) }

        Db.getUser(myUid) { user -> Avatars.bind(binding.tvPillAvatar, null, user) }
        showMedia()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Showing the photo / video
    // ---------------------------------------------------------------------------------------

    private fun showMedia() {
        if (mediaType == MediaType.VIDEO) {
            binding.ivPhoto.visibility = View.GONE
            binding.vvVideo.visibility = View.VISIBLE
            binding.vvVideo.setVideoURI(mediaUri)
            binding.vvVideo.setOnPreparedListener { player ->
                player.isLooping = true
                player.setVolume(0f, 0f)   // silent preview; the story keeps its sound
                binding.vvVideo.start()
            }
        } else {
            // Camera photos are large: decode a screen-sized copy on a background thread.
            background.execute {
                val bitmap = screenSized(mediaUri)
                runOnUiThread { if (!isDestroyed && bitmap != null) binding.ivPhoto.setImageBitmap(bitmap) }
            }
        }
    }

    /** Reads the size first, then decodes at 1/2, 1/4 ... so the copy is about 1080 px. */
    private fun screenSized(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SCREEN_PX && bounds.outHeight / (sample * 2) >= SCREEN_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // Text sticker
    // ---------------------------------------------------------------------------------------

    /** A small dialog with a text field; Done shows the text as the sticker, Remove clears it. */
    private fun editText() {
        val field = EditText(this).apply {
            setText(label)
            setSelection(label.length)
            setHint(R.string.editor_text_hint)
            setSingleLine()
        }
        val margin = resources.getDimensionPixelSize(R.dimen.screen_margin)
        val box = FrameLayout(this).apply {
            setPadding(margin, margin / 2, margin, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_text_title)
            .setView(box)
            .setPositiveButton(R.string.editor_text_done) { _, _ -> setLabel(field.text.toString().trim()) }
            .setNegativeButton(R.string.editor_text_remove) { _, _ -> setLabel("") }
            .show()
    }

    private fun setLabel(text: String) {
        label = text.take(MAX_LABEL)
        binding.tvSticker.text = label
        binding.tvSticker.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------------------------------
    // Sharing
    // ---------------------------------------------------------------------------------------

    private fun share(audience: String) {
        if (sharing) return
        setSharing(true)
        toast(R.string.editor_sharing)

        val onUploaded: (String?) -> Unit = { mediaId ->
            if (mediaId == null) {
                failed()
            } else {
                StoryRepo.add(mediaId, mediaType, label, audience) { saved ->
                    if (!saved) {
                        failed()
                    } else {
                        toast(if (audience == Audience.CLOSE_FRIENDS) R.string.editor_shared_close else R.string.editor_shared)
                        // Your story's X / Back return to Home past the camera and this screen.
                        openScreen(YourStoryActivity::class.java)
                        finish()
                    }
                }
            }
        }
        if (mediaType == MediaType.VIDEO) Images.uploadVideo(this, mediaUri, onUploaded)
        else Images.uploadImage(this, mediaUri, onUploaded)
    }

    private fun failed() {
        if (isDestroyed) return
        setSharing(false)
        toast(R.string.editor_error)
    }

    /** While uploading, the share buttons are faded and can't be pressed again. */
    private fun setSharing(on: Boolean) {
        sharing = on
        val alpha = if (on) 0.5f else 1f
        binding.llYourStory.alpha = alpha
        binding.llCloseFriends.alpha = alpha
        binding.ivNext.alpha = alpha
    }

    private companion object {
        const val SCREEN_PX = 1080
        const val MAX_LABEL = 40
    }
}
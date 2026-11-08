package com.rameen.i230806.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.MediaType
import com.rameen.i230806.data.PickMode
import com.rameen.i230806.databinding.ActivityCameraBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Images
import java.io.File

/**
 * 09 Camera, with CameraX.
 *
 * CameraX "use cases" are bound to this screen's lifecycle, so the camera opens and closes by
 * itself with the screen:
 *  - Preview: the live picture in the PreviewView.
 *  - ImageCapture: tap the shutter for a photo (saved as a JPEG file in the app's cache).
 *  - VideoCapture: hold the shutter for a video, SD quality, at most 15 seconds and 5 MB
 *    (videos are stored in the database as text, so they must stay small).
 *
 * Two ways in (Extra.PICK_MODE):
 *  - Story (default, from Home / Profile): the photo or video goes to the Story editor.
 *    The gallery thumbnail picks a photo or video from the phone instead.
 *  - Post (from Create post): photos only; the photo is returned to Create post.
 *
 * Flip switches between the back and front camera, flash turns on / off for photos.
 * Brightness, effects and the mode labels are visual only.
 */
class CameraActivity : BaseActivity() {
    override val darkSystemBars = true // white status bar icons on the black screen

    private lateinit var binding: ActivityCameraBinding

    private val forPost: Boolean by lazy { intent.getStringExtra(Extra.PICK_MODE) == PickMode.POST }

    private var lens = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashOn = false
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    /** Camera (and microphone for story videos) permission dialog; the answer comes back here. */
    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (has(Manifest.permission.CAMERA)) startCamera() else showNoPermission()
        }

    /** Gallery thumbnail: one photo or video from the Photo picker. */
    private val pickFromGallery =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uri = data.getStringArrayListExtra(Extra.PICKED_URIS)?.firstOrNull() ?: return@registerForActivityResult
            done(Uri.parse(uri), data.getStringExtra(Extra.MEDIA_TYPE) ?: MediaType.IMAGE)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cut the picture to the rounded corners of its background shape (XML cannot do this).
        binding.flViewport.clipToOutline = true
        binding.ivGallery.clipToOutline = true

        binding.ivClose.setOnClickListener { finish() }
        binding.ivFlip.setOnClickListener { flip() }
        binding.ivFlash.setOnClickListener { toggleFlash() }
        showFlash()

        binding.tvCameraHint.setText(if (forPost) R.string.camera_hint_photo else R.string.camera_hint_story)
        setupShutter()

        // Posts have their own picker, so the gallery thumbnail is only for stories.
        binding.ivGallery.visibility = if (forPost) View.INVISIBLE else View.VISIBLE
        binding.ivGallery.setOnClickListener {
            pickFromGallery.launch(
                Intent(this, PhotoPickerActivity::class.java).putExtra(Extra.PICK_MODE, PickMode.STORY)
            )
        }

        if (has(Manifest.permission.CAMERA)) startCamera() else askPermission.launch(permissions())
    }

    override fun onStop() {
        // Leaving the screen while recording: stop and keep what was recorded.
        recording?.stop()
        super.onStop()
    }

    // ---------------------------------------------------------------------------------------
    // Permission
    // ---------------------------------------------------------------------------------------

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Stories can be videos with sound, so they also ask for the microphone. */
    private fun permissions(): Array<String> =
        if (forPost) arrayOf(Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private fun showNoPermission() {
        val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        binding.tvCameraMessage.visibility = View.VISIBLE
        binding.tvCameraMessage.setText(
            if (canAskAgain) R.string.camera_permission_needed else R.string.camera_permission_settings
        )
        binding.tvCameraMessage.setOnClickListener {
            if (canAskAgain) {
                askPermission.launch(permissions())
            } else {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Settings with the permission now allowed.
        if (binding.tvCameraMessage.visibility == View.VISIBLE && has(Manifest.permission.CAMERA)) {
            binding.tvCameraMessage.visibility = View.GONE
            startCamera()
        }
    }

    // ---------------------------------------------------------------------------------------
    // CameraX
    // ---------------------------------------------------------------------------------------

    /** Gets the camera provider, then binds Preview + ImageCapture (+ VideoCapture for stories). */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.pvCamera.surfaceProvider)

            val photo = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()
            imageCapture = photo

            val useCases = mutableListOf<UseCase>(preview, photo)
            if (!forPost) {
                // SD (480p) keeps 15 seconds well under 5 MB; a lower quality is used if needed.
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val video = VideoCapture.withOutput(recorder)
                videoCapture = video
                useCases.add(video)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, lens, *useCases.toTypedArray())
            } catch (e: Exception) {
                toast(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flip() {
        if (recording != null) return
        lens = if (lens == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
        if (has(Manifest.permission.CAMERA)) startCamera()
    }

    private fun toggleFlash() {
        flashOn = !flashOn
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        showFlash()
    }

    private fun showFlash() {
        binding.ivFlash.alpha = if (flashOn) 1f else 0.5f
    }

    // ---------------------------------------------------------------------------------------
    // Shutter: tap = photo, hold = video
    // ---------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")   // click and long click are still set below
    private fun setupShutter() {
        binding.ivShutter.setOnClickListener { takePhoto() }
        if (forPost) return
        binding.ivShutter.contentDescription = getString(R.string.cd_record_video)
        binding.ivShutter.setOnLongClickListener {
            startRecording()
            true
        }
        // Lifting the finger ends the video. Returning false lets click / long click still work.
        binding.ivShutter.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                recording?.stop()
            }
            false
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = done(Uri.fromFile(file), MediaType.IMAGE)

            override fun onError(exception: ImageCaptureException) = toast(R.string.camera_error)
        })
    }

    @SuppressLint("MissingPermission")   // withAudioEnabled is only called after checking RECORD_AUDIO
    private fun startRecording() {
        val capture = videoCapture ?: return
        if (recording != null) return
        val file = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(MAX_VIDEO_MS)
            .setFileSizeLimit(Images.MAX_VIDEO_BYTES)
            .build()

        var pending = capture.output.prepareRecording(this, options)
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        showRecording(true)
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                showRecording(false)
                // Reaching 15 s or 5 MB also ends the video normally.
                val ok = !event.hasError() ||
                        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                if (ok && file.length() > 0) {
                    done(Uri.fromFile(file), MediaType.VIDEO)
                } else {
                    file.delete()
                    toast(R.string.camera_error)
                }
            }
        }
    }

    /** The shutter turns red while recording. */
    private fun showRecording(on: Boolean) {
        binding.ivShutter.backgroundTintList =
            if (on) ColorStateList.valueOf(ContextCompat.getColor(this, R.color.call_end_red)) else null
    }

    // ---------------------------------------------------------------------------------------
    // Result
    // ---------------------------------------------------------------------------------------

    /** Post: return the photo to Create post. Story: open the Story editor with it. */
    private fun done(uri: Uri, mediaType: String) {
        if (forPost) {
            setResult(RESULT_OK, Intent().putExtra(Extra.MEDIA_URI, uri.toString()))
            finish()
        } else {
            openScreen(StoryEditorActivity::class.java) {
                putExtra(Extra.MEDIA_URI, uri.toString())
                putExtra(Extra.MEDIA_TYPE, mediaType)
            }
        }
    }

    private companion object {
        const val MAX_VIDEO_MS = 15_000L
    }
}
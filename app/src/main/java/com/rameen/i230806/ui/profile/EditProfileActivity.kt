package com.rameen.i230806.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.rameen.i230806.R
import com.rameen.i230806.data.AuthRepo
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.NetworkMonitor
import com.rameen.i230806.data.PickMode
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityEditProfileBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.home.HomeActivity
import com.rameen.i230806.ui.home.PhotoPickerActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import com.rameen.i230806.util.RecentLogin
import java.util.Locale

/**
 * 16 Edit profile, saved through our web service profile/update.php (Assignment 3).
 *
 * Two ways in:
 *  - From Profile ("Edit profile"): Cancel and Save return to Profile.
 *  - Right after sign up (Extra.FIRST_TIME, step 3 of 3): titled "Set up your profile", no
 *    Cancel. Save sets setup_done = 1 on the server and opens Home. Until then, Splash and
 *    Login keep sending the user back here.
 *
 * Loading: my profile from SQLite straight away, then fresh from the server (AuthRepo.me).
 *
 * Pictures: "Edit" (or tapping the avatar / cover) opens the Photo picker for one image. The
 * picture is shown at once from the phone; Save uploads it together with the text fields in
 * one multipart request, and the server stores the file in uploads/profile or uploads/cover.
 *
 * Usernames are unique: the server checks that nobody else has it (MySQL UNIQUE column) and
 * answers "That username is taken" otherwise.
 */
class EditProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityEditProfileBinding

    private val firstTime: Boolean by lazy { intent.getBooleanExtra(Extra.FIRST_TIME, false) }

    /** My profile as loaded (null until it arrives). */
    private var me: User? = null

    /** The pictures shown: server URLs of the saved ones, or content:// of newly picked ones. */
    private var photoId = ""
    private var coverId = ""

    /** Newly picked pictures, uploaded when Save is pressed (null = unchanged). */
    private var newPhoto: Uri? = null
    private var newCover: Uri? = null

    /** Which picture the Photo picker was opened for. */
    private var pickingCover = false

    /** Opens the Photo picker and receives the chosen picture when it closes. */
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.getStringArrayListExtra(Extra.PICKED_URIS)?.firstOrNull()
                ?: return@registerForActivityResult
            showPicked(uri.toUri())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cuts the cover photo to the frame's rounded corners (bg_photo_rounded);
        // XML only has this attribute from Android 12.
        binding.flCover.clipToOutline = true

        if (firstTime) {
            binding.tvTitle.setText(R.string.edit_title_first)
            binding.tvCancel.visibility = View.INVISIBLE   // keeps the title centred
        }
        binding.tvCancel.setOnClickListener { finish() }
        binding.tvSave.setOnClickListener { save() }

        // Profile picture: the Edit link and the avatar itself.
        binding.tvEditPhoto.setOnClickListener { openPicker(cover = false) }
        binding.flAvatar.setOnClickListener { openPicker(cover = false) }
        // Cover photo: the Edit link and the camera badge.
        binding.tvEditCover.setOnClickListener { openPicker(cover = true) }
        binding.ivCoverCamera.setOnClickListener { openPicker(cover = true) }

        setBusy(true)   // until the profile has loaded
        loadProfile()
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    /**
     * The cached copy fills the fields at once; the server's copy only if nothing was shown yet
     * (so it never overwrites what the user is typing).
     */
    private fun loadProfile() {
        AuthRepo.me { user ->
            if (me != null || user == null || isDestroyed) return@me
            me = user
            showProfile(user)
            setBusy(false)
        }
        if (me == null && !NetworkMonitor.isOnline) toast(R.string.error_network)
    }

    private fun showProfile(user: User) {
        binding.etName.setText(user.name)
        // First time: suggest a username made from the name ("Jacob West" -> "jacob.west").
        binding.etUsername.setText(user.username.ifEmpty { suggestUsername(user.name) })
        binding.etWebsite.setText(user.website)
        binding.etBio.setText(user.bio)
        binding.etWork.setText(user.work)
        binding.etStudy.setText(user.study)
        binding.etCity.setText(user.city)
        binding.etEmail.setText(user.email)
        binding.etPhone.setText(user.phone)
        binding.tvGender.text = user.gender

        photoId = user.photoId
        coverId = user.coverId
        showAvatar()
        showCover()
    }

    private fun suggestUsername(name: String): String =
        name.trim().lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), ".")
            .replace(Regex("[^a-z0-9._]"), "")
            .take(20)

    private fun showAvatar() {
        val name = binding.etName.text.toString()
        Avatars.bind(binding.tvAvatar, binding.ivAvatarPhoto, User(uid = myUid, name = name, photoId = photoId))
    }

    /** The cover, or the teal placeholder from the design when there is none. */
    private fun showCover() {
        Images.load(binding.ivCover, coverId, R.drawable.ph_teal)
    }

    // ---------------------------------------------------------------------------------------
    // Pictures
    // ---------------------------------------------------------------------------------------

    private fun openPicker(cover: Boolean) {
        pickingCover = cover
        pickImage.launch(
            Intent(this, PhotoPickerActivity::class.java).putExtra(Extra.PICK_MODE, PickMode.SINGLE_IMAGE)
        )
    }

    /** Shows the chosen picture straight away; Save uploads it. */
    private fun showPicked(uri: Uri) {
        if (pickingCover) {
            newCover = uri
            coverId = uri.toString()
            showCover()
        } else {
            newPhoto = uri
            photoId = uri.toString()
            showAvatar()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    private fun save() {
        if (me == null) return
        val name = binding.etName.text.toString().trim()
        val username = binding.etUsername.text.toString().trim().lowercase(Locale.getDefault())

        if (name.isEmpty()) {
            toast(R.string.edit_error_name)
            return
        }
        if (!USERNAME.matches(username)) {
            toast(R.string.edit_error_username)
            return
        }

        // Only the fields the server should change; email and gender are shown, not edited.
        val fields = mapOf(
            "name" to name,
            "username" to username,
            "website" to binding.etWebsite.text.toString().trim(),
            "bio" to binding.etBio.text.toString().trim(),
            "work" to binding.etWork.text.toString().trim(),
            "study" to binding.etStudy.text.toString().trim(),
            "city" to binding.etCity.text.toString().trim(),
            "phone" to binding.etPhone.text.toString().trim(),
        )

        setBusy(true)
        AuthRepo.updateProfile(this, fields, newPhoto, newCover) { user, error ->
            if (isDestroyed) return@updateProfile
            if (user == null) {
                setBusy(false)
                // "That username is taken", or "No connection to the server" when offline.
                toast(error ?: getString(R.string.error_generic))
                return@updateProfile
            }
            RecentLogin.save(this, user)
            toast(R.string.edit_saved)
            if (firstTime) {
                // Setup finished: Home becomes the only screen.
                openScreen(HomeActivity::class.java, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            finish()
        }
    }

    /** While loading or saving, Save is faded and can't be pressed twice. */
    private fun setBusy(busy: Boolean) {
        binding.tvSave.isEnabled = !busy
        binding.tvSave.alpha = if (busy) 0.4f else 1f
    }

    private companion object {
        /** 3 to 20 small letters, numbers, dots or underscores: "jacob.west", "sara_99". */
        val USERNAME = Regex("^[a-z0-9._]{3,20}$")
    }
}

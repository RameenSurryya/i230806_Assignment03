package com.rameen.i230806.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
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
 * 16 Edit profile, saved to users/{uid} in the Realtime Database.
 *
 * Two ways in:
 *  - From Profile ("Edit profile"): Cancel and Save return to Profile.
 *  - Right after sign up (Extra.FIRST_TIME, step 3 of 3): titled "Set up your profile", no
 *    Cancel. Save sets setupDone = true and opens Home. Until then, Splash and Login keep
 *    sending the user back here.
 *
 * Pictures: "Edit" (or tapping the avatar / cover) opens the Photo picker for one image. The
 * picture is shrunk and uploaded straight away (Images.uploadImage) and shown; its media id is
 * written to the profile when Save is pressed.
 *
 * Usernames are unique: usernames/{username} holds the uid of its owner, claimed with a
 * transaction (see claimUsername), so two people can't take the same name at the same moment.
 */
class EditProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityEditProfileBinding

    private val firstTime: Boolean by lazy { intent.getBooleanExtra(Extra.FIRST_TIME, false) }

    /** The profile as loaded from the database (null until it arrives). */
    private var me: User? = null

    /** Media ids of the pictures in use: the saved ones, or newly uploaded ones. */
    private var photoId = ""
    private var coverId = ""

    /** Which picture the Photo picker was opened for. */
    private var pickingCover = false

    /** Opens the Photo picker and receives the chosen picture when it closes. */
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.getStringArrayListExtra(Extra.PICKED_URIS)?.firstOrNull()
                ?: return@registerForActivityResult
            uploadPicture(uri)
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

    /** Reads users/{my uid} once and fills in the fields. */
    private fun loadProfile() {
        Db.user(myUid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val user = snapshot.getValue(User::class.java) ?: User(uid = myUid)
                me = user
                showProfile(user)
                setBusy(false)
            }

            override fun onCancelled(error: DatabaseError) {
                setBusy(false)
                toast(R.string.error_network)
            }
        })
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

    /** Shrinks and uploads the chosen picture, then shows it. Save writes its id to the profile. */
    private fun uploadPicture(uri: String) {
        setBusy(true)
        Images.uploadImage(this, uri.toUri()) { mediaId ->
            if (isDestroyed) return@uploadImage
            setBusy(false)
            if (mediaId == null) {
                toast(R.string.edit_error_upload)
                return@uploadImage
            }
            if (pickingCover) {
                coverId = mediaId
                showCover()
            } else {
                photoId = mediaId
                showAvatar()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    private fun save() {
        val old = me ?: return
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

        val updated = old.copy(
            name = name,
            nameLower = name.lowercase(Locale.getDefault()),
            username = username,
            usernameLower = username,
            website = binding.etWebsite.text.toString().trim(),
            bio = binding.etBio.text.toString().trim(),
            work = binding.etWork.text.toString().trim(),
            study = binding.etStudy.text.toString().trim(),
            city = binding.etCity.text.toString().trim(),
            phone = binding.etPhone.text.toString().trim(),
            photoId = photoId,
            coverId = coverId,
            setupDone = true,
        )

        setBusy(true)
        claimUsername(username, old.username) { claimed ->
            if (!claimed) {
                setBusy(false)
                toast(R.string.edit_error_username_taken)
                return@claimUsername
            }
            // updateChildren changes only these keys; online, fcmToken etc. stay as they are.
            Db.user(myUid).updateChildren(
                mapOf(
                    "name" to updated.name,
                    "nameLower" to updated.nameLower,
                    "username" to updated.username,
                    "usernameLower" to updated.usernameLower,
                    "website" to updated.website,
                    "bio" to updated.bio,
                    "work" to updated.work,
                    "study" to updated.study,
                    "city" to updated.city,
                    "phone" to updated.phone,
                    "photoId" to updated.photoId,
                    "coverId" to updated.coverId,
                    "setupDone" to true,
                )
            ).addOnSuccessListener {
                Db.remember(updated)
                RecentLogin.save(this, updated)
                toast(R.string.edit_saved)
                if (firstTime) {
                    // Setup finished: Home becomes the only screen.
                    openScreen(HomeActivity::class.java, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                finish()
            }.addOnFailureListener {
                setBusy(false)
                toast(R.string.error_generic)
            }
        }
    }

    /**
     * Makes usernames/{username} = my uid, but only if nobody else owns it.
     *
     * A transaction reads the current value and writes the new one in one step on the server:
     * if two people try to take "sara" at the same moment, only one of them succeeds.
     * When it works, the old username is released so someone else can use it.
     */
    private fun claimUsername(username: String, oldUsername: String, onDone: (Boolean) -> Unit) {
        if (username == oldUsername) {
            onDone(true)
            return
        }
        Db.usernames().child(username).runTransaction(object : Transaction.Handler {
            override fun doTransaction(current: MutableData): Transaction.Result {
                val owner = current.getValue(String::class.java)
                if (owner != null && owner != myUid) return Transaction.abort()   // taken
                current.value = myUid
                return Transaction.success(current)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (committed && oldUsername.isNotEmpty()) Db.usernames().child(oldUsername).removeValue()
                onDone(committed)
            }
        })
    }

    /** While loading, uploading or saving, Save is faded and can't be pressed twice. */
    private fun setBusy(busy: Boolean) {
        binding.tvSave.isEnabled = !busy
        binding.tvSave.alpha = if (busy) 0.4f else 1f
    }

    private companion object {
        /** 3 to 20 small letters, numbers, dots or underscores: "jacob.west", "sara_99". */
        val USERNAME = Regex("^[a-z0-9._]{3,20}$")
    }
}
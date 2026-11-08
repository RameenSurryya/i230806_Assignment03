package com.rameen.i230806.util

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.User
import java.util.Locale

/**
 * Avatars look exactly as in Assignment 1: a coloured disc with white initials (a TextView with
 * style Kinnect.Avatar). When the person has a profile picture, an ImageView of the same size
 * is stacked on top of the disc (FrameLayout) and shows the picture instead.
 *
 * Usage in an adapter or screen:
 *   Avatars.bind(binding.tvAvatar, binding.ivAvatarPhoto, user)
 */
object Avatars {

    /** The eight disc colours from the design. Each person always gets the same one. */
    private val palette = intArrayOf(
        R.color.teal, R.color.purple, R.color.maroon, R.color.green,
        R.color.indigo, R.color.brown, R.color.steel_blue, R.color.olive,
    )

    /** Picks a colour from the uid, so a person has the same colour on every screen and phone. */
    fun colorFor(uid: String): Int = palette[(uid.hashCode() and Int.MAX_VALUE) % palette.size]

    /** "Jacob West" -> "JW", "aisha" -> "A". */
    fun initialsOf(name: String): String =
        name.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().toString().uppercase(Locale.getDefault()) }

    /**
     * Shows [user] in an avatar. [initials] is the coloured disc; [photo] (optional) is the
     * ImageView on top of it. Its background (bg_avatar_circle or bg_avatar_square) gives the
     * shape the picture is cut to.
     */
    fun bind(initials: TextView, photo: ImageView?, user: User?) {
        val uid = user?.uid.orEmpty()
        initials.text = initialsOf(user?.name.orEmpty())
        initials.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(initials.context, colorFor(uid)))

        if (photo == null) return
        val photoId = user?.photoId.orEmpty()
        if (photoId.isEmpty()) {
            photo.visibility = View.GONE
            Images.load(photo, null)
        } else {
            photo.visibility = View.VISIBLE
            photo.clipToOutline = true          // cut the picture to the circle / rounded square
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            Images.load(photo, photoId)
        }
    }
}
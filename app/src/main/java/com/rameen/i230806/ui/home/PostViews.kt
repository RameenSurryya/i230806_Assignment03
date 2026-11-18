package com.rameen.i230806.ui.home

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Post
import com.rameen.i230806.data.PostRepo
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.databinding.ItemPostBinding
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import com.rameen.i230806.util.Times
import java.util.Locale

/**
 * Fills one post card (item_post.xml). Shared by the Home feed and the Reaction picker, so a
 * post looks the same everywhere.
 *
 * What each button does is decided by the screen, through a [Listener]. Every method has an
 * empty default, so a screen only writes the ones it needs.
 */
object PostViews {

    interface Listener {
        fun onAuthor(post: Post) {}
        fun onLike(post: Post) {}
        fun onLikeLongPress(post: Post) {}
        fun onComment(post: Post) {}
        fun onShare(post: Post) {}
        fun onMore(post: Post, anchor: View) {}
        fun onHide(post: Post) {}
    }

    fun bind(v: ItemPostBinding, post: Post, listener: Listener) {
        val context = v.root.context
        v.root.tag = post.id   // async results below only fill the card if it still shows this post

        // ----- Header -----
        v.tvAuthor.text = ""
        Avatars.bind(v.tvAuthorAvatar, v.ivAuthorPhoto, null)
        Db.getUser(post.authorId) { user ->
            if (v.root.tag != post.id || user == null) return@getUser
            v.tvAuthor.text = user.name
            Avatars.bind(v.tvAuthorAvatar, v.ivAuthorPhoto, user)
            v.flAuthorAvatar.contentDescription = context.getString(R.string.cd_post_author, user.name)
        }
        v.tvTime.text =
            if (post.pending) context.getString(R.string.pending_waiting) else Times.ago(context, post.timestamp)

        // ----- Text -----
        v.tvPostText.text = post.text
        v.tvPostText.visibility = if (post.text.isEmpty()) View.GONE else View.VISIBLE

        // ----- Photos -----
        showPhotos(v, post.imageIds)

        // ----- Reaction summary and counts -----
        showSummary(v, post)

        // ----- Like button: shows my reaction -----
        val mine = PostRepo.myReaction(post)
        val grey = ContextCompat.getColor(context, R.color.text_grey)
        when (mine) {
            null -> {
                v.ivLike.setImageResource(R.drawable.ic_like)
                v.ivLike.imageTintList = ColorStateList.valueOf(grey)
                v.tvLike.setText(R.string.action_like)
                v.tvLike.setTextColor(grey)
            }
            Reaction.LIKE -> {
                val teal = ContextCompat.getColor(context, R.color.teal)
                v.ivLike.setImageResource(R.drawable.ic_like)
                v.ivLike.imageTintList = ColorStateList.valueOf(teal)
                v.tvLike.setText(R.string.action_like)
                v.tvLike.setTextColor(teal)
            }
            else -> {
                // The coloured reaction disc itself, no tint.
                v.ivLike.setImageResource(mine.icon)
                v.ivLike.imageTintList = null
                v.tvLike.setText(mine.label)
                v.tvLike.setTextColor(ContextCompat.getColor(context, mine.color))
            }
        }

        // ----- Clicks -----
        v.flAuthorAvatar.setOnClickListener { listener.onAuthor(post) }
        v.tvAuthor.setOnClickListener { listener.onAuthor(post) }
        v.llLike.setOnClickListener { listener.onLike(post) }
        v.llLike.setOnLongClickListener {
            listener.onLikeLongPress(post)
            true   // the long press is handled, so no normal click follows
        }
        v.llComment.setOnClickListener { listener.onComment(post) }
        v.llPhotos.setOnClickListener { listener.onComment(post) }
        v.llSummary.setOnClickListener { listener.onComment(post) }
        v.llShare.setOnClickListener { listener.onShare(post) }
        v.ivPostMore.setOnClickListener { listener.onMore(post, it) }
        v.ivPostClose.setOnClickListener { listener.onHide(post) }
    }

    /** 0 photos: no collage. 1: one wide photo. 2: side by side. 3: one tall + two stacked.
     *  4 or more: like 3, with "+N" over the last. */
    private fun showPhotos(v: ItemPostBinding, ids: List<String>) {
        val count = ids.size
        v.llPhotos.visibility = if (count == 0) View.GONE else View.VISIBLE
        if (count == 0) return
        v.llPhotos.contentDescription = v.root.context.getString(R.string.cd_post_photo_count, count)

        v.llPhotosRight.visibility = if (count >= 2) View.VISIBLE else View.GONE
        v.flPhoto3.visibility = if (count >= 3) View.VISIBLE else View.GONE
        Images.load(v.ivPhoto1, ids.getOrNull(0))
        Images.load(v.ivPhoto2, ids.getOrNull(1))
        Images.load(v.ivPhoto3, ids.getOrNull(2))

        val extra = count - 3
        v.vPhotoDim.visibility = if (extra > 0) View.VISIBLE else View.GONE
        v.tvMorePhotos.visibility = if (extra > 0) View.VISIBLE else View.GONE
        v.tvMorePhotos.text = v.root.context.getString(R.string.post_more_photos, extra)
    }

    /** The three most used reactions, "You and 12 others", "86 comments · 12 shares". */
    private fun showSummary(v: ItemPostBinding, post: Post) {
        val context = v.root.context
        val total = post.reactions.size

        // Reaction kinds, most used first.
        val kinds = post.reactions.values
            .mapNotNull { Reaction.of(it) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
        listOf(v.ivReaction1, v.ivReaction2, v.ivReaction3).forEachIndexed { index, icon ->
            val kind = kinds.getOrNull(index)
            icon.visibility = if (kind == null) View.GONE else View.VISIBLE
            if (kind != null) icon.setImageResource(kind.icon)
        }

        // Names: "You", "You and 3 others", "Aisha and 3 others".
        val me = Db.uid
        v.tvReactionNames.text = ""
        when {
            total == 0 -> Unit
            me != null && post.reactions.containsKey(me) ->
                v.tvReactionNames.text =
                    if (total == 1) context.getString(R.string.post_reactions_you)
                    else context.getString(R.string.post_reactions_you_and, compact(total - 1L))
            else -> {
                val firstUid = post.reactions.keys.first()
                Db.getUser(firstUid) { user ->
                    if (v.root.tag != post.id) return@getUser
                    val first = user?.name?.substringBefore(' ').orEmpty()
                    v.tvReactionNames.text =
                        if (total == 1) first
                        else context.getString(R.string.post_reactions_name_and, first, compact(total - 1L))
                }
            }
        }

        // "86 comments · 12 shares" (parts with 0 are left out).
        val parts = mutableListOf<String>()
        if (post.commentCount > 0) parts.add(context.getString(R.string.post_comments_count, compact(post.commentCount)))
        if (post.shareCount > 0) parts.add(context.getString(R.string.post_shares_count, compact(post.shareCount)))
        v.tvCommentsShares.text = parts.joinToString(context.getString(R.string.post_dot_joiner))

        v.llSummary.visibility = if (total == 0 && parts.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 950 -> "950", 1234 -> "1.2K", 2500000 -> "2.5M". */
    fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0).replace(".0K", "K")
        else -> n.toString()
    }
}

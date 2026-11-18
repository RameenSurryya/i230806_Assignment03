package com.rameen.i230806.ui.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.Post
import com.rameen.i230806.data.PostRepo
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.databinding.ActivityReactionPickerBinding
import com.rameen.i230806.ui.BaseActivity

/**
 * 05 Reaction picker, opened by long-pressing Like on a post (Extra.POST_ID).
 *
 * The post is shown underneath (the same card as on Home, filled by PostViews) and dimmed.
 * The white pill with the six reactions is moved just above the post's Like button. Your
 * current reaction (or Love, as in the design, when you haven't reacted) is raised, with its
 * name in the tooltip above it.
 *
 * Tapping a reaction saves it (PostRepo.react: choosing your current one removes it) and closes
 * the picker. The back arrow or a tap on the dim area closes it without changes.
 */
class ReactionPickerActivity : BaseActivity() {

    private lateinit var binding: ActivityReactionPickerBinding
    private var post: Post? = null
    private var positioned = false

    /** The six icons in the pill, in the same order as the Reaction enum. */
    private val icons: List<ImageView> by lazy {
        listOf(
            binding.ivReactionLike, binding.ivReactionLove, binding.ivReactionHaha,
            binding.ivReactionWow, binding.ivReactionSad, binding.ivReactionAngry,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReactionPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val postId = intent.getStringExtra(Extra.POST_ID).orEmpty()
        if (postId.isEmpty()) {
            finish()
            return
        }

        binding.ivBack.setOnClickListener { finish() }
        binding.vDim.setOnClickListener { finish() }
        // The pill and tooltip start hidden until they are placed above the Like button.
        binding.llReactions.visibility = View.INVISIBLE
        binding.tvTooltip.visibility = View.INVISIBLE

        Reaction.entries.forEachIndexed { index, reaction ->
            icons[index].setOnClickListener { choose(reaction) }
        }

        // The saved copy at once, then the server's.
        PostRepo.cachedPost(postId)?.let { showPost(it) }
        PostRepo.refreshPost(postId) { fresh, gone ->
            if (isDestroyed) return@refreshPost
            if (gone) finish()   // the post was deleted
            else if (fresh != null) showPost(fresh)
        }
    }

    private fun showPost(loaded: Post) {
        post = loaded
        PostViews.bind(binding.post, loaded, object : PostViews.Listener {})
        Db.getUser(loaded.authorId) { user ->
            binding.tvTitle.text = getString(R.string.post_title_format, user?.name.orEmpty())
        }
        if (!positioned) {
            positioned = true
            raise(PostRepo.myReaction(loaded) ?: Reaction.LOVE)
            // Wait until the post has been measured, then place the pill.
            binding.post.root.post { placeAboveLike() }
        }
    }

    private fun choose(reaction: Reaction) {
        post?.let { PostRepo.react(this, it, reaction) }
        finish()
    }

    /** Enlarges and lifts one reaction, with extra room on both sides, and names it. */
    private fun raise(reaction: Reaction) {
        val icon = icons[reaction.ordinal]
        val scale = ResourcesCompat.getFloat(resources, R.dimen.reaction_raised_scale)
        icon.scaleX = scale
        icon.scaleY = scale
        icon.translationY = resources.getDimension(R.dimen.reaction_raised_shift)
        val extra = resources.getDimensionPixelSize(R.dimen.reaction_raised_margin)
        (icon.layoutParams as ViewGroup.MarginLayoutParams).apply {
            marginStart += extra
            marginEnd += extra
        }
        icon.requestLayout()
        binding.tvTooltip.setText(reaction.label)
    }

    /**
     * Scrolls so the Like row is on screen, then puts the pill just above it and the tooltip
     * above the raised reaction. Positions come from getLocationInWindow (pixels on screen).
     */
    private fun placeAboveLike() {
        val likeRow = binding.post.llLike
        val scroll = binding.svPost
        val overflow = likeRow.bottom + binding.post.root.top - scroll.height
        if (overflow > 0) scroll.scrollTo(0, overflow + resources.getDimensionPixelSize(R.dimen.space_16))

        scroll.post {
            val root = IntArray(2).also { binding.flRoot.getLocationInWindow(it) }
            val like = IntArray(2).also { likeRow.getLocationInWindow(it) }
            val gap = resources.getDimensionPixelSize(R.dimen.space_8)

            val tray = binding.llReactions
            tray.y = (like[1] - root[1] - tray.height - gap).toFloat()

            val raised = icons.first { it.scaleX > 1f }
            val tooltip = binding.tvTooltip
            tooltip.x = tray.x + raised.x + raised.width / 2f - tooltip.width / 2f
            tooltip.y = tray.y + raised.y - raised.height * (raised.scaleY - 1) / 2 - tooltip.height - gap

            tray.visibility = View.VISIBLE
            tooltip.visibility = View.VISIBLE
        }
    }
}

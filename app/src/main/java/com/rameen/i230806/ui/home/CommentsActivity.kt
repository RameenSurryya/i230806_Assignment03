package com.rameen.i230806.ui.home

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.Comment
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.PickMode
import com.rameen.i230806.data.Post
import com.rameen.i230806.data.PostRepo
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.databinding.ActivityCommentsBinding
import com.rameen.i230806.databinding.ItemCommentBinding
import com.rameen.i230806.databinding.ItemCommentMoreBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import com.rameen.i230806.util.Times

/**
 * 06 Comments of one post (Extra.POST_ID), live from comments/{postId}.
 *
 *  - The list is a RecyclerView with two kinds of rows: a comment or reply (item_comment.xml)
 *    and "View 3 more replies" (item_comment_more.xml). Replies are one level deep and sit
 *    under their comment; the first two show, the rest open with "View more".
 *  - Sort pill: Most relevant (most liked first) or Newest.
 *  - Like on a comment: like / undo (PostRepo.toggleCommentLike). Reply: the bar shows
 *    "Replying to <name>" with Cancel, and the next comment is saved as a reply.
 *  - Camera button: add one photo to the comment (Photo picker, uploaded straight away).
 *  - Long-press your own comment to delete it. Tap a name or avatar for their profile.
 *  - The reaction summary at the top opens the Reaction picker for the post.
 */
class CommentsActivity : BaseActivity() {

    /** One row of the list: a comment / reply, or the "View more replies" row of a thread. */
    private data class Row(
        val comment: Comment? = null,
        val isReply: Boolean = false,
        val threadId: String = "",      // for the "more" row: the comment whose replies it opens
        val hiddenCount: Int = 0,       // for the "more" row: 0 means "Hide replies"
    )

    private lateinit var binding: ActivityCommentsBinding
    private lateinit var postId: String

    private var post: Post? = null
    private var comments: List<Comment> = emptyList()
    private val rows = mutableListOf<Row>()
    private val adapter = CommentAdapter()

    private var newestFirst = false
    private val openThreads = mutableSetOf<String>()

    private var replyingTo: Comment? = null
    private var attachedId = ""

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.getStringArrayListExtra(Extra.PICKED_URIS)?.firstOrNull()
                ?: return@registerForActivityResult
            attachPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        postId = intent.getStringExtra(Extra.POST_ID).orEmpty()
        if (postId.isEmpty()) {
            finish()
            return
        }

        setupBackButton(R.id.iv_back)
        binding.rvComments.layoutManager = LinearLayoutManager(this)
        binding.rvComments.adapter = adapter
        binding.ivAttached.clipToOutline = true

        binding.llSort.setOnClickListener {
            newestFirst = !newestFirst
            binding.tvSort.setText(if (newestFirst) R.string.comments_newest else R.string.comments_most_relevant)
            showComments()
        }
        binding.llSummary.setOnClickListener {
            openScreen(ReactionPickerActivity::class.java) { putExtra(Extra.POST_ID, postId) }
        }
        binding.tvCancelReply.setOnClickListener { setReplyingTo(null) }
        binding.ivReplyCamera.setOnClickListener {
            pickImage.launch(
                Intent(this, PhotoPickerActivity::class.java).putExtra(Extra.PICK_MODE, PickMode.SINGLE_IMAGE)
            )
        }
        binding.ivRemoveAttached.setOnClickListener { clearPhoto() }
        binding.ivSend.setOnClickListener { send() }

        watch(Db.post(postId)) { snapshot ->
            val loaded = snapshot.getValue(Post::class.java)?.apply { id = snapshot.key ?: id }
            if (loaded == null) {
                toast(R.string.comment_post_gone)
                finish()
                return@watch
            }
            post = loaded
            showSummary(loaded)
        }
        watch(Db.comments(postId)) { snapshot ->
            comments = snapshot.children.mapNotNull { it.getValue(Comment::class.java)?.apply { id = it.key ?: id } }
            showComments()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Post reactions at the top
    // ---------------------------------------------------------------------------------------

    private fun showSummary(post: Post) {
        val kinds = post.reactions.values.mapNotNull { Reaction.of(it) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        listOf(binding.ivSummary1, binding.ivSummary2, binding.ivSummary3).forEachIndexed { index, icon ->
            val kind = kinds.getOrNull(index)
            icon.visibility = if (kind == null) View.GONE else View.VISIBLE
            if (kind != null) icon.setImageResource(kind.icon)
        }
        binding.tvSummaryTotal.text = PostViews.compact(post.reactions.size.toLong())
        binding.llSummary.visibility = if (post.reactions.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------------------------------
    // Building the list
    // ---------------------------------------------------------------------------------------

    private fun showComments() {
        val top = comments.filter { it.parentId.isEmpty() }
        val sortedTop =
            if (newestFirst) top.sortedByDescending { it.timestamp }
            else top.sortedWith(compareByDescending<Comment> { it.likes.size }.thenBy { it.timestamp })
        val repliesOf = comments.filter { it.parentId.isNotEmpty() }
            .sortedBy { it.timestamp }
            .groupBy { it.parentId }

        rows.clear()
        for (comment in sortedTop) {
            rows.add(Row(comment))
            val replies = repliesOf[comment.id].orEmpty()
            val open = comment.id in openThreads
            val shown = if (open) replies else replies.take(REPLIES_SHOWN)
            shown.forEach { rows.add(Row(it, isReply = true)) }
            if (replies.size > REPLIES_SHOWN) {
                rows.add(Row(threadId = comment.id, hiddenCount = if (open) 0 else replies.size - REPLIES_SHOWN))
            }
        }
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    private fun setReplyingTo(comment: Comment?) {
        replyingTo = comment
        binding.llReplying.visibility = if (comment == null) View.GONE else View.VISIBLE
        if (comment == null) return

        Db.getUser(comment.authorId) { user ->
            val name = user?.name.orEmpty()
            binding.tvReplyingName.text = name
            // Replying to a reply: start with "@Name " so it's clear who is meant.
            if (comment.parentId.isNotEmpty() && binding.etReply.text.isEmpty()) {
                binding.etReply.setText(getString(R.string.comment_mention, name))
                binding.etReply.setSelection(binding.etReply.text.length)
            }
        }
        openThreads.add(comment.parentId.ifEmpty { comment.id })
        showComments()
        binding.etReply.requestFocus()
        getSystemService(InputMethodManager::class.java)
            .showSoftInput(binding.etReply, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun attachPhoto(uri: String) {
        toast(R.string.comment_uploading)
        binding.ivSend.isEnabled = false
        Images.uploadImage(this, uri.toUri()) { mediaId ->
            if (isDestroyed) return@uploadImage
            binding.ivSend.isEnabled = true
            if (mediaId == null) {
                toast(R.string.edit_error_upload)
                return@uploadImage
            }
            attachedId = mediaId
            binding.flAttached.visibility = View.VISIBLE
            Images.load(binding.ivAttached, mediaId)
            toast(R.string.comment_photo_added)
        }
    }

    private fun clearPhoto() {
        attachedId = ""
        binding.flAttached.visibility = View.GONE
        Images.load(binding.ivAttached, null)
    }

    private fun send() {
        val current = post ?: return
        val text = binding.etReply.text.toString().trim()
        if (text.isEmpty() && attachedId.isEmpty()) {
            toast(R.string.comment_error_empty)
            return
        }
        PostRepo.addComment(current, text, attachedId, replyingTo)
        binding.etReply.setText("")
        attachedId = ""
        binding.flAttached.visibility = View.GONE
        setReplyingTo(null)
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.etReply.windowToken, 0)
    }

    private fun showDeleteMenu(anchor: View, comment: Comment) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(R.string.comment_delete)
        popup.setOnMenuItemClickListener {
            PostRepo.deleteComment(comment)
            toast(R.string.comment_deleted)
            true
        }
        popup.show()
    }

    // ---------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------

    private inner class CommentHolder(val row: ItemCommentBinding) : RecyclerView.ViewHolder(row.root)
    private inner class MoreHolder(val row: ItemCommentMoreBinding) : RecyclerView.ViewHolder(row.root)

    private inner class CommentAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position].comment == null) TYPE_MORE else TYPE_COMMENT

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_MORE) MoreHolder(ItemCommentMoreBinding.inflate(inflater, parent, false))
            else CommentHolder(ItemCommentBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = rows[position]
            if (holder is MoreHolder) {
                holder.row.tvMore.text =
                    if (item.hiddenCount > 0) getString(R.string.comments_view_more_count, item.hiddenCount)
                    else getString(R.string.comments_hide_replies)
                holder.row.tvMore.setOnClickListener {
                    if (item.hiddenCount > 0) openThreads.add(item.threadId) else openThreads.remove(item.threadId)
                    showComments()
                }
            } else if (holder is CommentHolder) {
                bindComment(holder.row, item.comment!!, item.isReply)
            }
        }
    }

    private fun bindComment(v: ItemCommentBinding, comment: Comment, isReply: Boolean) {
        v.root.tag = comment.id

        // Comment or reply look: indent, avatar size, thread line.
        v.vThreadLine.visibility = if (isReply) View.VISIBLE else View.GONE
        v.llRow.setPaddingRelative(
            resources.getDimensionPixelSize(if (isReply) R.dimen.reply_indent else R.dimen.screen_margin), 0, 0, 0
        )
        val size = resources.getDimensionPixelSize(if (isReply) R.dimen.avatar_26 else R.dimen.avatar_33)
        v.flAvatar.layoutParams = v.flAvatar.layoutParams.apply { width = size; height = size }
        v.tvAvatar.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(if (isReply) R.dimen.text_avatar_26 else R.dimen.text_avatar_33)
        )

        // Author
        v.tvName.text = ""
        Avatars.bind(v.tvAvatar, v.ivAvatarPhoto, null)
        Db.getUser(comment.authorId) { user ->
            if (v.root.tag != comment.id) return@getUser
            v.tvName.text = user?.name.orEmpty()
            Avatars.bind(v.tvAvatar, v.ivAvatarPhoto, user)
        }
        v.flAvatar.setOnClickListener { openProfile(comment.authorId) }
        v.tvName.setOnClickListener { openProfile(comment.authorId) }

        // Text and photo
        v.tvText.text = comment.text
        v.tvText.visibility = if (comment.text.isEmpty()) View.GONE else View.VISIBLE
        v.ivPhoto.visibility = if (comment.imageId.isEmpty()) View.GONE else View.VISIBLE
        v.ivPhoto.clipToOutline = true
        Images.load(v.ivPhoto, comment.imageId.ifEmpty { null })

        // Likes: chip with the count, "Like" teal when I liked it.
        val likes = comment.likes.size
        v.llChip.visibility = if (likes > 0) View.VISIBLE else View.GONE
        v.tvChipCount.text = getString(R.string.comments_like_count, likes)
        val liked = comment.likes.containsKey(myUid)
        v.tvLike.setTextColor(ContextCompat.getColor(this, if (liked) R.color.teal else R.color.text_grey))

        v.tvTime.text = Times.ago(this, comment.timestamp)
        v.tvLike.setOnClickListener { PostRepo.toggleCommentLike(comment) }
        v.tvReply.setOnClickListener { setReplyingTo(comment) }

        // Long-press your own comment: delete.
        v.llBubble.setOnLongClickListener {
            if (comment.authorId == myUid) showDeleteMenu(it, comment)
            true
        }
    }

    private companion object {
        const val TYPE_COMMENT = 0
        const val TYPE_MORE = 1
        const val REPLIES_SHOWN = 2
    }
}
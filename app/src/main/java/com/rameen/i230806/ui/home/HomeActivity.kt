package com.rameen.i230806.ui.home

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.AuthRepo
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.MediaType
import com.rameen.i230806.data.Outbox
import com.rameen.i230806.data.PickMode
import com.rameen.i230806.data.Post
import com.rameen.i230806.data.PostRepo
import com.rameen.i230806.data.Story
import com.rameen.i230806.data.StoryRepo
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityHomeBinding
import com.rameen.i230806.databinding.ItemPostBinding
import com.rameen.i230806.databinding.ItemStoryCardBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.chat.ShareToChatActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images

/**
 * 04 Home: the composer, the stories row and the feed.
 *
 *  - Feed (Assignment 3): the newest 50 posts of mine and my friends' from our server
 *    (posts/feed.php), saved in SQLite. The screen shows the SQLite copy at once (so it works
 *    offline), refreshes from the server every time it shows, and again after the sync worker
 *    has sent queued actions. Posts written offline appear with "Waiting to send...".
 *    A post card is item_post.xml, filled by PostViews.
 *  - Stories row (Assignment 3, stories/feed.php + SQLite): me and my friends with a story
 *    that hasn't expired (24 hours). The server only sends close-friends stories to people in
 *    the author's close friends. A teal ring means there is a story I haven't watched yet;
 *    those people come first.
 *
 * Clicks: composer -> Create post (the image icon goes straight to the photo picker), your
 * avatar -> Profile, Create story -> Camera, a story card -> Story viewer (yours -> Your story),
 * Like -> like / undo, long-press Like -> Reaction picker, Comment / photos -> Comments,
 * Share -> send to a friend, "..." -> Delete (yours) or Hide, X -> Hide.
 */
class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding

    private var me: User? = null
    private var allPosts: List<Post> = emptyList()

    private val feed = mutableListOf<Post>()
    private val feedAdapter = FeedAdapter()

    /** Posts hidden with X or "Hide post" (until the app is closed). */
    private val hiddenPosts = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHomeTopBar()
        setupTopTabs(Tab.HOME)

        // Composer row
        binding.flComposerAvatar.setOnClickListener { openProfile(myUid) }
        binding.tvComposer.setOnClickListener { openScreen(CreatePostActivity::class.java) }
        binding.ivComposerPhoto.setOnClickListener {
            openScreen(CreatePostActivity::class.java) { putExtra(Extra.PICK_MODE, PickMode.POST) }
        }

        // Stories row. clipToOutline cuts the cards to their rounded corners (bg_story_card);
        // XML only has this attribute from Android 12.
        binding.flCreateStory.clipToOutline = true
        binding.flCreateStory.setOnClickListener { openScreen(CameraActivity::class.java) }

        // Feed: the page's ScrollView does the scrolling.
        binding.rvFeed.layoutManager = LinearLayoutManager(this)
        binding.rvFeed.isNestedScrollingEnabled = false
        binding.rvFeed.adapter = feedAdapter

        // Offline actions that were sent later: reload the feed.
        var lastSync = Outbox.synced.value
        Outbox.synced.observe(this) { time ->
            if (time != lastSync) {
                lastSync = time
                reloadFeed()
                refreshFeed()
                refreshStories()
            }
        }

    }

    override fun onStart() {
        super.onStart()
        AuthRepo.me { user -> if (user != null && !isDestroyed) showMe(user) }
        reloadFeed()
        refreshFeed()
        showStories()
        refreshStories()
    }

    /** New stories from the server; the row is redrawn from SQLite. */
    private fun refreshStories() {
        StoryRepo.refreshAll { ok -> if (ok && !isDestroyed) showStories() }
    }

    /** Draws the feed from SQLite (instant, also offline). */
    private fun reloadFeed() {
        allPosts = PostRepo.cachedFeed(FEED_SIZE)
        showFeed()
    }

    /** Asks the server for the newest posts; redraws when the answer is saved. */
    private fun refreshFeed() {
        PostRepo.refreshFeed { ok -> if (ok && !isDestroyed) reloadFeed() }
    }

    /** Your avatar in the composer and the Create story card, and "What's on your mind, Jacob?". */
    private fun showMe(user: User) {
        me = user
        Avatars.bind(binding.tvComposerAvatar, binding.ivComposerPhotoAvatar, user)
        Avatars.bind(binding.tvCreateAvatar, binding.ivCreateAvatarPhoto, user)
        binding.tvComposer.text = getString(R.string.home_composer_hint_name, user.name.substringBefore(' '))
    }

    // ---------------------------------------------------------------------------------------
    // Feed
    // ---------------------------------------------------------------------------------------

    private fun showFeed() {
        // The server already sends only mine and my friends' posts.
        feed.clear()
        feed.addAll(allPosts.filter { it.id !in hiddenPosts }.sortedByDescending { it.timestamp })
        feedAdapter.notifyDataSetChanged()
        binding.tvFeedEmpty.visibility = if (feed.isEmpty()) View.VISIBLE else View.GONE
    }

    /** What the buttons on a post card do on Home. */
    private val postListener = object : PostViews.Listener {
        override fun onAuthor(post: Post) = openProfile(post.authorId)

        override fun onLike(post: Post) = PostRepo.toggleLike(this@HomeActivity, post) { reloadFeed() }

        override fun onLikeLongPress(post: Post) =
            openScreen(ReactionPickerActivity::class.java) { putExtra(Extra.POST_ID, post.id) }

        override fun onComment(post: Post) {
            if (post.pending) toast(R.string.comment_error_post_pending)
            else openScreen(CommentsActivity::class.java) { putExtra(Extra.POST_ID, post.id) }
        }

        override fun onShare(post: Post) =
            openScreen(ShareToChatActivity::class.java) { putExtra(Extra.POST_ID, post.id) }

        override fun onHide(post: Post) {
            hiddenPosts.add(post.id)
            showFeed()
            toast(R.string.post_hidden)
        }

        override fun onMore(post: Post, anchor: View) {
            val popup = PopupMenu(this@HomeActivity, anchor)
            val mine = post.authorId == myUid
            popup.menu.add(if (mine) R.string.post_delete else R.string.post_hide)
            popup.setOnMenuItemClickListener {
                if (mine) {
                    PostRepo.delete(this@HomeActivity, post)
                    reloadFeed()
                    toast(R.string.post_deleted)
                } else {
                    onHide(post)
                }
                true
            }
            popup.show()
        }
    }

    private inner class PostHolder(val card: ItemPostBinding) : RecyclerView.ViewHolder(card.root)

    private inner class FeedAdapter : RecyclerView.Adapter<PostHolder>() {
        override fun getItemCount() = feed.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PostHolder(ItemPostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PostHolder, position: Int) =
            PostViews.bind(holder.card, feed[position], postListener)
    }

    // ---------------------------------------------------------------------------------------
    // Stories row
    // ---------------------------------------------------------------------------------------

    private fun showStories() {
        // Not expired, already filtered by the server (close friends), grouped by author.
        val byAuthor = StoryRepo.cachedAll().groupBy { it.authorId }

        // Mine first, then people with unwatched stories, then newest.
        val rows = byAuthor.entries.map { it.key to it.value }
            .sortedWith(
                compareByDescending<Pair<String, List<Story>>> { it.first == myUid }
                    .thenByDescending { (_, stories) -> stories.any { !it.viewers.containsKey(myUid) } }
                    .thenByDescending { (_, stories) -> stories.maxOf { it.timestamp } }
            )

        // Keep the Create story card (child 0), rebuild the rest.
        binding.llStories.removeViews(1, binding.llStories.childCount - 1)
        for ((author, stories) in rows) {
            binding.llStories.addView(storyCard(author, stories))
        }
    }

    /** One item_story_card.xml for [author], showing their newest story. */
    private fun storyCard(author: String, stories: List<Story>): View {
        val card = ItemStoryCardBinding.inflate(layoutInflater, binding.llStories, false)
        card.root.clipToOutline = true
        val newest = stories.maxBy { it.timestamp }
        val unseen = stories.any { !it.viewers.containsKey(myUid) }

        // Teal ring while something is unwatched (your own story always has the teal ring).
        card.flStoryRing.setBackgroundResource(
            if (unseen || author == myUid) R.drawable.bg_avatar_ring else R.drawable.bg_avatar_circle
        )
        if (newest.mediaType == MediaType.VIDEO) showVideoFrame(card.ivStoryPhoto, newest.mediaId)
        else Images.load(card.ivStoryPhoto, newest.mediaId, R.drawable.ph_night)

        Db.getUser(author) { user ->
            Avatars.bind(card.tvStoryAvatar, card.ivStoryAvatarPhoto, user)
            val name = if (author == myUid) getString(R.string.home_your_story) else user?.name.orEmpty()
            card.tvStoryName.text = name
            card.root.contentDescription = getString(R.string.cd_story_of, user?.name.orEmpty())
        }
        card.root.setOnClickListener {
            if (author == myUid) openScreen(YourStoryActivity::class.java)
            else openScreen(StoryViewerActivity::class.java) { putExtra(Extra.UID, author) }
        }
        return card.root
    }

    /** A video story's card shows its first frame (MediaMetadataRetriever reads it from the file). */
    private fun showVideoFrame(view: ImageView, mediaId: String) {
        view.setImageResource(R.drawable.ph_night)
        Images.loadVideo(this, mediaId) { file ->
            if (file == null || isDestroyed) return@loadVideo
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.frameAtTime?.let { view.setImageBitmap(it) }
            } catch (e: Exception) {
                // keep the placeholder
            } finally {
                retriever.release()
            }
        }
    }

    private companion object {
        const val FEED_SIZE = 50
    }
}

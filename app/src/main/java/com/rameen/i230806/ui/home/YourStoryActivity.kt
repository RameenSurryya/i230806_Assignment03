package com.rameen.i230806.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import com.rameen.i230806.R
import com.rameen.i230806.data.Audience
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.data.Story
import com.rameen.i230806.data.StoryRepo
import com.rameen.i230806.databinding.ActivityYourStoryBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Times

/**
 * 12 Your story: your own stories from the last 24 hours, played by StoryPlayer.
 *  - Live from stories/{me}: "Seen by" and the floating reactions update while you watch.
 *  - "Seen by N": the newest viewers' avatars; tap for the full list (with their reactions).
 *  - Create: another story (Camera). More: delete this story.
 *  - X / Back / the end of the last story: back to Home, skipping the camera and the editor.
 */
class YourStoryActivity : BaseActivity() {
    override val darkSystemBars = true // white status bar icons on the black screen

    private lateinit var binding: ActivityYourStoryBinding
    private lateinit var player: StoryPlayer

    /** The newest copy of each story (viewers and reactions change while watching). */
    private val latest = HashMap<String, Story>()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYourStoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cut the picture to the rounded corners of its background shape (XML cannot do this).
        binding.flViewport.clipToOutline = true

        binding.ivClose.setOnClickListener { closeToHome() }
        onBackPressedDispatcher.addCallback(this) { closeToHome() }
        binding.llCreate.setOnClickListener { openScreen(CameraActivity::class.java) }
        binding.llMore.setOnClickListener { showMoreMenu(it) }
        binding.llSeenBy.setOnClickListener { showViewers() }

        Db.getUser(myUid) { user -> Avatars.bind(binding.tvAvatar, binding.ivAvatarPhoto, user) }

        player = StoryPlayer(
            activity = this,
            progress = binding.llProgress,
            photo = binding.ivPhoto,
            video = binding.vvVideo,
            touchArea = binding.flViewport,
            onShow = { story -> showDetails(latest[story.id] ?: story) },
            onFinished = { closeToHome() },
        )

        watch(Db.stories(myUid)) { snapshot ->
            val now = Db.now()
            val stories = snapshot.children
                .mapNotNull { it.getValue(Story::class.java)?.apply { id = it.key ?: id; authorId = myUid } }
                .filter { it.expiresAt > now }
                .sortedBy { it.timestamp }
            latest.clear()
            stories.forEach { latest[it.id] = it }

            if (!started) {
                if (stories.isEmpty()) {
                    toast(R.string.story_none)
                    closeToHome()
                    return@watch
                }
                started = true
                player.start(stories)
            } else {
                // Same story still playing: just refresh "Seen by" and the reactions.
                player.current?.let { current -> latest[current.id]?.let { showDetails(it) } }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onResume() {
        super.onResume()
        player.resume()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // What is shown for the current story
    // ---------------------------------------------------------------------------------------

    private fun showDetails(story: Story) {
        // "11h · Close friends"
        val audience = getString(
            if (story.audience == Audience.CLOSE_FRIENDS) R.string.story_audience_close else R.string.story_audience_public
        )
        binding.tvTime.text = getString(R.string.story_time_audience, Times.ago(this, story.timestamp), audience)

        binding.tvSticker.text = story.label
        binding.tvSticker.visibility = if (story.label.isEmpty()) View.GONE else View.VISIBLE

        // Floating reactions: one icon per reaction, at most five.
        val reactions = story.reactions.values.mapNotNull { Reaction.of(it) }.take(5)
        listOf(binding.ivHeart1, binding.ivHeart2, binding.ivHeart3, binding.ivHeart4, binding.ivHeart5)
            .forEachIndexed { index, icon ->
                val reaction = reactions.getOrNull(index)
                icon.visibility = if (reaction == null) View.GONE else View.VISIBLE
                if (reaction != null) icon.setImageResource(reaction.icon)
            }

        // Seen by: the three newest viewers.
        val viewers = story.viewers.entries.sortedByDescending { it.value }.map { it.key }
        binding.tvSeenBy.text = getString(R.string.story_seen_by, viewers.size)
        listOf(binding.flSeen1 to binding.tvSeen1, binding.flSeen2 to binding.tvSeen2, binding.flSeen3 to binding.tvSeen3)
            .forEachIndexed { index, (frame, initials) ->
                val uid = viewers.getOrNull(index)
                frame.visibility = if (uid == null) View.GONE else View.VISIBLE
                if (uid != null) Db.getUser(uid) { Avatars.bind(initials, null, it) }
            }
    }

    /** A simple list of everyone who watched the current story, with their reaction. */
    private fun showViewers() {
        val story = player.current?.let { latest[it.id] ?: it } ?: return
        val viewers = story.viewers.entries.sortedByDescending { it.value }.map { it.key }
        if (viewers.isEmpty()) {
            toast(R.string.story_viewers_none)
            return
        }
        player.pause()
        val names = arrayOfNulls<String>(viewers.size)
        var waiting = viewers.size
        viewers.forEachIndexed { index, uid ->
            Db.getUser(uid) { user ->
                val name = user?.name.orEmpty()
                val reaction = Reaction.of(story.reactions[uid])
                names[index] =
                    if (reaction == null) name
                    else getString(R.string.story_viewer_reacted, name, getString(reaction.label))
                waiting--
                if (waiting == 0 && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.story_viewers_title)
                        .setItems(names.map { it.orEmpty() }.toTypedArray(), null)
                        .setPositiveButton(R.string.story_close, null)
                        .setOnDismissListener { player.resume() }
                        .show()
                }
            }
        }
    }

    private fun showMoreMenu(anchor: View) {
        val story = player.current ?: return
        player.pause()
        val popup = PopupMenu(this, anchor)
        popup.menu.add(R.string.story_delete)
        popup.setOnMenuItemClickListener {
            StoryRepo.delete(story)
            toast(R.string.story_deleted)
            player.removeCurrent()
            true
        }
        popup.setOnDismissListener { player.resume() }
        popup.show()
    }

    /**
     * Closing returns to Home. CLEAR_TOP closes every screen above Home (camera, story
     * editor); SINGLE_TOP reuses the Home that is already open.
     */
    private fun closeToHome() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
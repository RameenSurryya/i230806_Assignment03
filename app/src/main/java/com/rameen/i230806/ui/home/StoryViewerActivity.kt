package com.rameen.i230806.ui.home

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.rameen.i230806.R
import com.rameen.i230806.data.Audience
import com.rameen.i230806.data.ChatRepo
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.data.Story
import com.rameen.i230806.data.StoryRepo
import com.rameen.i230806.databinding.ActivityStoryViewerBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Times

/**
 * 11 Story viewer: someone's stories (Extra.UID), oldest first, played by StoryPlayer.
 *  - Each story shown is marked as seen (StoryRepo.markViewed), so it moves to "seen" on Home
 *    and the author sees you in "Seen by".
 *  - Like / Love / Haha react to the story (the author gets a notification).
 *  - The message box sends a reply to the author's chat. While typing, the story pauses.
 *  - X closes; after the last story the screen closes by itself.
 */
class StoryViewerActivity : BaseActivity() {
    override val darkSystemBars = true // white status bar icons on the black screen

    private lateinit var binding: ActivityStoryViewerBinding
    private lateinit var author: String
    private lateinit var player: StoryPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoryViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        author = intent.getStringExtra(Extra.UID).orEmpty()
        if (author.isEmpty()) {
            finish()
            return
        }

        // Cut the picture to the rounded corners of its background shape (XML cannot do this).
        binding.flViewport.clipToOutline = true
        binding.ivClose.setOnClickListener { finish() }
        binding.flAvatar.setOnClickListener { openProfile(author) }

        player = StoryPlayer(
            activity = this,
            progress = binding.llProgress,
            photo = binding.ivPhoto,
            video = binding.vvVideo,
            touchArea = binding.flViewport,
            onShow = { story -> showStory(story) },
            onFinished = { finish() },
        )

        Db.getUser(author) { user ->
            binding.tvName.text = user?.name.orEmpty()
            Avatars.bind(binding.tvAvatar, binding.ivAvatarPhoto, user)
        }

        // Reactions
        binding.ivReactLike.setOnClickListener { react(Reaction.LIKE) }
        binding.ivReactLove.setOnClickListener { react(Reaction.LOVE) }
        binding.ivReactHaha.setOnClickListener { react(Reaction.HAHA) }

        // Reply: pause while typing, send with the keyboard's send key.
        binding.etMessage.setOnFocusChangeListener { _, focused -> if (focused) player.pause() else player.resume() }
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) sendReply()
            true
        }

        StoryRepo.loadVisible(author) { stories ->
            if (isDestroyed) return@loadVisible
            if (stories.isEmpty()) {
                toast(R.string.story_none)
                finish()
                return@loadVisible
            }
            // Start at the first story I haven't seen yet.
            val firstUnseen = stories.indexOfFirst { !it.viewers.containsKey(myUid) }
            player.start(stories, if (firstUnseen >= 0) firstUnseen else 0)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) player.pause()
    }

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized && !binding.etMessage.hasFocus()) player.resume()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun showStory(story: Story) {
        StoryRepo.markViewed(story)
        binding.tvTime.text = Times.ago(this, story.timestamp)
        val close = story.audience == Audience.CLOSE_FRIENDS
        binding.ivAudience.setImageResource(if (close) R.drawable.ic_star else R.drawable.ic_globe)
        binding.ivAudience.contentDescription =
            getString(if (close) R.string.cd_close_friends_story else R.string.cd_public)
        binding.tvSticker.text = story.label
        binding.tvSticker.visibility = if (story.label.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun react(reaction: Reaction) {
        val story = player.current ?: return
        StoryRepo.react(story, reaction)
        toast(R.string.story_reacted)
    }

    private fun sendReply() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        ChatRepo.sendText(this, author, getString(R.string.story_reply_prefix, text))
        toast(R.string.story_reply_sent)
        binding.etMessage.setText("")
        binding.etMessage.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }
}
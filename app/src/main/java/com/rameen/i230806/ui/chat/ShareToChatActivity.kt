package com.rameen.i230806.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.ChatRepo
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.PostRepo
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityShareToChatBinding
import com.rameen.i230806.databinding.ItemSearchPersonBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Avatars

/**
 * Share to chat: sends the post (Extra.POST_ID) to friends as a chat message.
 * Each friend row has a send button; after sending it fades and the row says "Sent", so the
 * same post isn't sent twice by accident. The optional message goes with the post.
 * Every send also counts one share on the post (PostRepo.countShare).
 */
class ShareToChatActivity : BaseActivity() {

    private lateinit var binding: ActivityShareToChatBinding
    private lateinit var postId: String

    private val friends = mutableListOf<User>()
    private val sentTo = mutableSetOf<String>()
    private val adapter = FriendAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareToChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        postId = intent.getStringExtra(Extra.POST_ID).orEmpty()
        if (postId.isEmpty()) {
            finish()
            return
        }
        setupBackButton(R.id.iv_back)
        binding.rvFriends.layoutManager = LinearLayoutManager(this)
        binding.rvFriends.adapter = adapter

        watch(Db.friends(myUid)) { snapshot ->
            val uids = snapshot.children.mapNotNull { it.key }
            binding.tvEmpty.visibility = if (uids.isEmpty()) View.VISIBLE else View.GONE
            loadFriends(uids)
        }
    }

    /** Loads every friend's profile, then shows them A to Z. */
    private fun loadFriends(uids: List<String>) {
        val loaded = mutableListOf<User>()
        var waiting = uids.size
        if (waiting == 0) {
            friends.clear()
            adapter.notifyDataSetChanged()
            return
        }
        for (uid in uids) {
            Db.getUser(uid) { user ->
                if (user != null) loaded.add(user)
                waiting--
                if (waiting == 0) {
                    friends.clear()
                    friends.addAll(loaded.sortedBy { it.nameLower })
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun sendTo(friend: User) {
        if (!sentTo.add(friend.uid)) return
        ChatRepo.sendPost(this, friend.uid, postId, binding.etMessage.text.toString().trim())
        PostRepo.countShare(postId)
        toast(R.string.share_sent)
        adapter.notifyDataSetChanged()
    }

    private inner class Holder(val row: ItemSearchPersonBinding) : RecyclerView.ViewHolder(row.root)

    private inner class FriendAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = friends.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSearchPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val friend = friends[position]
            val row = holder.row
            val sent = friend.uid in sentTo
            Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, friend)
            row.tvName.text = friend.name
            row.tvSubtitle.text = if (sent) getString(R.string.share_sent) else ""
            row.tvSubtitle.visibility = if (sent) View.VISIBLE else View.GONE

            // The add-friend circle becomes a send button.
            row.ivAdd.setImageResource(R.drawable.ic_send)
            row.ivAdd.contentDescription = getString(R.string.cd_send_to, friend.name)
            row.ivAdd.alpha = if (sent) 0.4f else 1f
            row.ivAdd.isEnabled = !sent
            row.ivAdd.setOnClickListener { sendTo(friend) }
            row.root.setOnClickListener { sendTo(friend) }
        }
    }
}
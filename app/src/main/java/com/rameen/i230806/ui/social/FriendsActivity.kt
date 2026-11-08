package com.rameen.i230806.ui.social

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.databinding.ActivityFriendsBinding
import com.rameen.i230806.databinding.ItemFriendCardBinding
import com.rameen.i230806.databinding.ItemSearchPersonBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Times
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 14 Friends. Two chips switch between two sections:
 *
 * Suggestions
 *  - Friend requests: live from friendRequests/{me}. Confirm = FriendRepo.accept, Delete =
 *    FriendRepo.decline. The orange number is how many are waiting.
 *  - People you may know: friends of my friends (more mutual friends = higher up), plus the
 *    newest Kinnect users so a new account still sees someone. People I'm already friends
 *    with, who asked me, or whom I asked are left out. Add friend sends a request; Remove
 *    hides the person until the screen is opened again.
 *
 * Your friends: live from friends/{me}, newest first, with "Friends since ...".
 *
 * Every card and row opens that person's profile. All three lists are RecyclerViews inside the
 * page's ScrollView, so their own scrolling is turned off and the page scrolls as one.
 */
class FriendsActivity : BaseActivity() {

    /** One card: who, and when they sent the request (0 for suggestions). */
    private data class Card(val uid: String, val time: Long = 0)

    private lateinit var binding: ActivityFriendsBinding

    private var myFriends: Map<String, Long> = emptyMap()     // uid -> friends since
    private val requests = mutableListOf<Card>()
    private val suggestions = mutableListOf<Card>()
    private val friendRows = mutableListOf<Card>()

    private val requestAdapter = CardAdapter(forRequests = true, cards = requests)
    private val suggestionAdapter = CardAdapter(forRequests = false, cards = suggestions)
    private val friendAdapter = FriendRowAdapter()

    /** People hidden with Remove, and people I just sent a request to. */
    private val hidden = mutableSetOf<String>()

    /** friends/{uid} of other people, read once each (for mutual friends and suggestions). */
    private val friendsOfCache = HashMap<String, Set<String>>()

    /** Increases every time suggestions are rebuilt, so an older, slower run is ignored. */
    private var suggestionRun = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar(R.string.title_friends)
        setupTopTabs(Tab.FRIENDS)

        setupList(binding.rvRequests, requestAdapter)
        setupList(binding.rvSuggestions, suggestionAdapter)
        setupList(binding.rvFriends, friendAdapter)

        binding.tvChipSuggestions.setOnClickListener { showSection(friends = false) }
        binding.tvChipFriends.setOnClickListener { showSection(friends = true) }
        showSection(friends = false)

        watch(Db.friends(myUid)) { snapshot ->
            myFriends = snapshot.children.associate { (it.key ?: "") to ((it.value as? Number)?.toLong() ?: 0L) }
            friendRows.clear()
            friendRows.addAll(myFriends.map { (uid, since) -> Card(uid, since) }.sortedByDescending { it.time })
            friendAdapter.notifyDataSetChanged()
            binding.tvFriendCount.text = getString(R.string.friends_count_title, friendRows.size)
            binding.tvNoFriends.visibility = if (friendRows.isEmpty()) View.VISIBLE else View.GONE
            requestAdapter.notifyDataSetChanged()   // mutual friend counts changed
            refreshSuggestions()
        }
        watch(Db.friendRequests(myUid)) { snapshot ->
            requests.clear()
            requests.addAll(
                snapshot.children
                    .map { Card(it.key ?: "", (it.value as? Number)?.toLong() ?: 0L) }
                    .sortedByDescending { it.time }
            )
            requestAdapter.notifyDataSetChanged()
            binding.tvRequestCount.text = if (requests.isEmpty()) "" else requests.size.toString()
            binding.tvNoRequests.visibility = if (requests.isEmpty()) View.VISIBLE else View.GONE
            refreshSuggestions()
        }
    }

    private fun setupList(list: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        list.layoutManager = LinearLayoutManager(this)
        list.isNestedScrollingEnabled = false   // the page's ScrollView does the scrolling
        list.adapter = adapter
    }

    /** Colours the chosen chip like Kinnect.Chip.Selected and shows its section. */
    private fun showSection(friends: Boolean) {
        styleChip(binding.tvChipSuggestions, selected = !friends)
        styleChip(binding.tvChipFriends, selected = friends)
        binding.llSuggestionsSection.visibility = if (friends) View.GONE else View.VISIBLE
        binding.llFriendsSection.visibility = if (friends) View.VISIBLE else View.GONE
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip.setTextColor(ContextCompat.getColor(this, if (selected) R.color.teal else R.color.text_dark))
    }

    // ---------------------------------------------------------------------------------------
    // Reading other people's friend lists
    // ---------------------------------------------------------------------------------------

    /** Reads a path once (not live) and passes the result, or null if it failed. */
    private fun readOnce(query: Query, onRead: (DataSnapshot?) -> Unit) {
        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onRead(snapshot)
            override fun onCancelled(error: DatabaseError) = onRead(null)
        })
    }

    private fun friendsOf(uid: String, onLoaded: (Set<String>) -> Unit) {
        friendsOfCache[uid]?.let { onLoaded(it); return }
        readOnce(Db.friends(uid)) { snapshot ->
            val set = snapshot?.children?.mapNotNull { it.key }?.toSet() ?: emptySet()
            friendsOfCache[uid] = set
            onLoaded(set)
        }
    }

    // ---------------------------------------------------------------------------------------
    // People you may know
    // ---------------------------------------------------------------------------------------

    /** Someone who could be suggested: not me, not a friend, no request from them, not hidden. */
    private fun isCandidate(uid: String) =
        uid.isNotEmpty() && uid != myUid && uid !in myFriends && requests.none { it.uid == uid } && uid !in hidden

    private fun refreshSuggestions() {
        val run = ++suggestionRun
        val mutualCount = HashMap<String, Int>()
        val friendList = myFriends.keys.toList()
        var waiting = friendList.size + 1     // every friend's list + the newest users

        val oneDone = {
            waiting--
            if (waiting == 0 && run == suggestionRun) dropAlreadyAsked(run, mutualCount)
        }
        // Friends of friends: each time someone appears in a friend's list, +1 mutual friend.
        for (friend in friendList) {
            friendsOf(friend) { theirFriends ->
                theirFriends.filter { isCandidate(it) }.forEach { mutualCount[it] = (mutualCount[it] ?: 0) + 1 }
                oneDone()
            }
        }
        // The newest users, so a new account with no friends still sees people.
        readOnce(Db.users().orderByChild("createdAt").limitToLast(NEWEST_USERS)) { snapshot ->
            snapshot?.children?.mapNotNull { it.key }?.filter { isCandidate(it) }
                ?.forEach { if (it !in mutualCount) mutualCount[it] = 0 }
            oneDone()
        }
    }

    /** Requests I sent are stored under the other person, so each candidate is checked once. */
    private fun dropAlreadyAsked(run: Int, mutualCount: Map<String, Int>) {
        val candidates = mutualCount.keys.toList()
        if (candidates.isEmpty()) {
            showSuggestions(emptyList())
            return
        }
        val keep = mutableListOf<String>()
        var waiting = candidates.size
        for (uid in candidates) {
            readOnce(Db.friendRequests(uid).child(myUid)) { snapshot ->
                if (snapshot?.exists() != true) keep.add(uid)
                waiting--
                if (waiting == 0 && run == suggestionRun) {
                    showSuggestions(keep.sortedByDescending { mutualCount[it] ?: 0 }.take(MAX_SUGGESTIONS))
                }
            }
        }
    }

    private fun showSuggestions(uids: List<String>) {
        suggestions.clear()
        suggestions.addAll(uids.map { Card(it) })
        suggestionAdapter.notifyDataSetChanged()
        binding.tvNoSuggestions.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Takes one card out of a list straight away (the database catches up a moment later). */
    private fun removeCard(cards: MutableList<Card>, adapter: CardAdapter, uid: String) {
        val index = cards.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            cards.removeAt(index)
            adapter.notifyItemRemoved(index)
        }
        if (cards === suggestions) {
            binding.tvNoSuggestions.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cards: friend requests and people you may know (item_friend_card.xml)
    // ---------------------------------------------------------------------------------------

    private inner class CardHolder(val card: ItemFriendCardBinding) : RecyclerView.ViewHolder(card.root)

    private inner class CardAdapter(
        private val forRequests: Boolean,
        private val cards: MutableList<Card>,
    ) : RecyclerView.Adapter<CardHolder>() {

        override fun getItemCount() = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CardHolder(ItemFriendCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val item = cards[position]
            val card = holder.card
            card.root.tag = item.uid   // async results below only fill the card if it is still this person

            card.tvName.text = ""
            Db.getUser(item.uid) { user ->
                if (card.root.tag != item.uid) return@getUser
                card.tvName.text = user?.name.orEmpty()
                Avatars.bind(card.tvAvatar, card.ivAvatarPhoto, user)
            }
            card.tvTime.text = if (item.time > 0) Times.ago(this@FriendsActivity, item.time) else ""

            // Mutual friends: their friend list ∩ mine.
            card.tvMutual.text = ""
            card.vMutual1.visibility = View.GONE
            card.vMutual2.visibility = View.GONE
            friendsOf(item.uid) { theirFriends ->
                if (card.root.tag != item.uid) return@friendsOf
                showMutual(card, theirFriends.filter { it in myFriends })
            }

            card.root.setOnClickListener { openProfile(item.uid) }

            if (forRequests) {
                card.btnPrimary.setText(R.string.friends_confirm)
                card.btnSecondary.setText(R.string.friends_delete)
                card.btnPrimary.setOnClickListener {
                    FriendRepo.accept(item.uid)
                    toast(R.string.friends_request_confirmed)
                }
                card.btnSecondary.setOnClickListener {
                    FriendRepo.decline(item.uid)
                    toast(R.string.request_deleted)
                }
            } else {
                card.btnPrimary.setText(R.string.friends_add)
                card.btnSecondary.setText(R.string.friends_remove)
                card.btnPrimary.setOnClickListener {
                    FriendRepo.sendRequest(item.uid)
                    hidden.add(item.uid)
                    removeCard(suggestions, this, item.uid)
                    toast(R.string.friend_request_sent)
                }
                card.btnSecondary.setOnClickListener {
                    hidden.add(item.uid)
                    removeCard(suggestions, this, item.uid)
                    toast(R.string.friends_suggestion_removed)
                }
            }
        }
    }

    /** "8 mutual friends" with two small discs in the first two mutual friends' colours. */
    private fun showMutual(card: ItemFriendCardBinding, mutual: List<String>) {
        card.tvMutual.text = when (mutual.size) {
            0 -> getString(R.string.friends_no_mutual)
            1 -> getString(R.string.friends_mutual_one)
            else -> getString(R.string.friends_mutual_count, mutual.size)
        }
        listOf(card.vMutual1, card.vMutual2).forEachIndexed { index, dot ->
            val uid = mutual.getOrNull(index)
            dot.visibility = if (uid == null) View.GONE else View.VISIBLE
            if (uid != null) {
                dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, Avatars.colorFor(uid)))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Your friends (item_search_person.xml)
    // ---------------------------------------------------------------------------------------

    private inner class RowHolder(val row: ItemSearchPersonBinding) : RecyclerView.ViewHolder(row.root)

    private inner class FriendRowAdapter : RecyclerView.Adapter<RowHolder>() {

        private val monthYear = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        override fun getItemCount() = friendRows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(ItemSearchPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = friendRows[position]
            val row = holder.row
            row.root.tag = item.uid
            row.ivAdd.visibility = View.GONE
            row.tvName.text = ""
            row.tvSubtitle.text = getString(R.string.friends_since, monthYear.format(Date(item.time)))
            Db.getUser(item.uid) { user ->
                if (row.root.tag != item.uid) return@getUser
                row.tvName.text = user?.name.orEmpty()
                Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, user)
            }
            row.root.setOnClickListener { openProfile(item.uid) }
        }
    }

    private companion object {
        const val NEWEST_USERS = 20
        const val MAX_SUGGESTIONS = 15
    }
}
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
import com.rameen.i230806.R
import com.rameen.i230806.data.Cached
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.data.Json
import com.rameen.i230806.data.LocalDb
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityFriendsBinding
import com.rameen.i230806.databinding.ItemFriendCardBinding
import com.rameen.i230806.databinding.ItemSearchPersonBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Times
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 14 Friends. Two chips switch between two sections. Everything comes from one request,
 * friends/lists.php (Assignment 3), loaded every time the screen shows; the saved copy
 * (Cached) shows first, so the screen also works offline.
 *
 * Suggestions
 *  - Friend requests: Confirm = accept, Delete = decline. The orange number is how many wait.
 *  - People you may know: worked out by the server (friends of my friends, most mutual friends
 *    first, then the newest users). Add friend sends a request; Remove hides the person until
 *    the screen is opened again.
 *
 * Your friends: newest first, with "Friends since ...".
 *
 * A tap removes the card at once (also offline: the action waits in the queue); the server's
 * answer then reloads the lists. Every card and row opens that person's profile. The lists
 * are RecyclerViews inside the page's ScrollView, so the page scrolls as one.
 */
class FriendsActivity : BaseActivity() {

    /** One card or row: who, when (request sent / friends since), and mutual friends. */
    private data class Card(
        val user: User,
        val time: Long = 0,
        val mutualCount: Int = 0,
        val mutualIds: List<String> = emptyList(),
    )

    private lateinit var binding: ActivityFriendsBinding

    private val requests = mutableListOf<Card>()
    private val suggestions = mutableListOf<Card>()
    private val friendRows = mutableListOf<Card>()

    private val requestAdapter = CardAdapter(forRequests = true, cards = requests)
    private val suggestionAdapter = CardAdapter(forRequests = false, cards = suggestions)
    private val friendAdapter = FriendRowAdapter()

    /** People hidden with Remove, or just handled, so a reload doesn't bring them back early. */
    private val hidden = mutableSetOf<String>()

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
    }

    override fun onStart() {
        super.onStart()
        load()
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
    // Loading
    // ---------------------------------------------------------------------------------------

    private fun load() {
        Cached.get("friends/lists.php") { json ->
            if (isDestroyed) return@get
            fill(requests, json, "requests", "time")
            fill(suggestions, json, "suggestions", "time")
            fill(friendRows, json, "friends", "since")
            saveFriends(json)
            showLists()
        }
    }

    /** My friends and requests in SQLite's friends table (user id -> friends / received). */
    private fun saveFriends(json: JSONObject) {
        LocalDb.replaceAll(LocalDb.FRIENDS, null, null) {
            for ((list, status) in listOf("friends" to "friends", "requests" to "received")) {
                for (item in Json.objects(json.optJSONArray(list))) {
                    val user = Json.saveUser(item.getJSONObject("user"))
                    LocalDb.put(LocalDb.FRIENDS, "user_id", user.uid, item, mapOf("status" to status))
                }
            }
        }
    }

    /** Turns one JSON list into cards (people in [hidden] are left out of suggestions). */
    private fun fill(cards: MutableList<Card>, json: JSONObject, listName: String, timeKey: String) {
        cards.clear()
        for (item in Json.objects(json.optJSONArray(listName))) {
            val user = Json.user(item.getJSONObject("user"))
            if (cards === suggestions && user.uid in hidden) continue
            cards.add(
                Card(
                    user = user,
                    time = item.optLong(timeKey),
                    mutualCount = item.optInt("mutual_count"),
                    mutualIds = Json.strings(item.optJSONArray("mutual_ids")),
                )
            )
        }
    }

    private fun showLists() {
        requestAdapter.notifyDataSetChanged()
        suggestionAdapter.notifyDataSetChanged()
        friendAdapter.notifyDataSetChanged()
        binding.tvRequestCount.text = if (requests.isEmpty()) "" else requests.size.toString()
        binding.tvNoRequests.visibility = if (requests.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNoSuggestions.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.GONE
        binding.tvFriendCount.text = getString(R.string.friends_count_title, friendRows.size)
        binding.tvNoFriends.visibility = if (friendRows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Takes one card out of its list straight away; the server's answer reloads everything. */
    private fun removeCard(cards: MutableList<Card>, uid: String) {
        cards.removeAll { it.user.uid == uid }
        showLists()
    }

    /** Sends [action] for [uid], then reloads (the counts and lists change on the server). */
    private fun act(action: String, uid: String) {
        FriendRepo.act(this, action, uid) { answer ->
            if (answer != null && !isDestroyed) load()
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
            val uid = item.user.uid

            card.tvName.text = item.user.name
            Avatars.bind(card.tvAvatar, card.ivAvatarPhoto, item.user)
            card.tvTime.text = if (item.time > 0) Times.ago(this@FriendsActivity, item.time) else ""
            showMutual(card, item.mutualCount, item.mutualIds)
            card.root.setOnClickListener { openProfile(uid) }

            if (forRequests) {
                card.btnPrimary.setText(R.string.friends_confirm)
                card.btnSecondary.setText(R.string.friends_delete)
                card.btnPrimary.setOnClickListener {
                    removeCard(requests, uid)
                    act(FriendRepo.ACCEPT, uid)
                    toast(R.string.friends_request_confirmed)
                }
                card.btnSecondary.setOnClickListener {
                    removeCard(requests, uid)
                    act(FriendRepo.DECLINE, uid)
                    toast(R.string.request_deleted)
                }
            } else {
                card.btnPrimary.setText(R.string.friends_add)
                card.btnSecondary.setText(R.string.friends_remove)
                card.btnPrimary.setOnClickListener {
                    hidden.add(uid)
                    removeCard(suggestions, uid)
                    act(FriendRepo.REQUEST, uid)
                    toast(R.string.friend_request_sent)
                }
                card.btnSecondary.setOnClickListener {
                    hidden.add(uid)
                    removeCard(suggestions, uid)
                    toast(R.string.friends_suggestion_removed)
                }
            }
        }
    }

    /** "8 mutual friends" with two small discs in the first two mutual friends' colours. */
    private fun showMutual(card: ItemFriendCardBinding, count: Int, firstIds: List<String>) {
        card.tvMutual.text = when (count) {
            0 -> getString(R.string.friends_no_mutual)
            1 -> getString(R.string.friends_mutual_one)
            else -> getString(R.string.friends_mutual_count, count)
        }
        listOf(card.vMutual1, card.vMutual2).forEachIndexed { index, dot ->
            val uid = firstIds.getOrNull(index)
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
            row.ivAdd.visibility = View.GONE
            row.tvName.text = item.user.name
            row.tvSubtitle.text = getString(R.string.friends_since, monthYear.format(Date(item.time)))
            Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, item.user)
            row.root.setOnClickListener { openProfile(item.user.uid) }
        }
    }
}

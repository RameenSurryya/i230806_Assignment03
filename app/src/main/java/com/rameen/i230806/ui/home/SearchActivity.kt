package com.rameen.i230806.ui.home

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.Api
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.data.Json
import com.rameen.i230806.data.LocalDb
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivitySearchBinding
import com.rameen.i230806.databinding.ItemRecentSearchBinding
import com.rameen.i230806.databinding.ItemSearchPersonBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars

/**
 * 13 Search for people by name or username, as you type (Assignment 3: search.php).
 *
 * The server searches MySQL with LIKE '%text%', so any part of a name or username matches
 * ("west" finds Jacob West). The three filter chips narrow it down:
 *   Everyone  every user (the list stays empty until something is typed)
 *   Friends   only my friends (all of them while the field is empty)
 *   Requests  people with a friend request waiting either way (all of them while empty)
 * The search waits 300 ms after the last key press so it doesn't run for every letter.
 * Offline, people already saved on this phone (SQLite) are searched instead.
 *
 * Each row shows the friendship state and, for strangers, an add-friend button.
 * Tapping a row opens the profile and saves the text in Recent searches (on this phone),
 * which are shown while the field is empty on Everyone.
 */
class SearchActivity : BaseActivity() {

    /** One result: the person and how I'm related to them. */
    private data class Person(val user: User, var status: FriendRepo.Status)

    private lateinit var binding: ActivitySearchBinding

    private val people = mutableListOf<Person>()
    private val adapter = PeopleAdapter()

    private var filter = FILTER_EVERYONE

    private val handler = Handler(Looper.getMainLooper())
    private val runSearch = Runnable { search() }

    /** Increases with every search, so answers to an older search are ignored. */
    private var searchNumber = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackButton(R.id.iv_back)
        binding.rvPeople.layoutManager = LinearLayoutManager(this)
        binding.rvPeople.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.ivClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                handler.removeCallbacks(runSearch)
                handler.postDelayed(runSearch, TYPING_DELAY_MS)
            }
        })
        // The keyboard's search key: remember the text.
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) saveRecent(binding.etSearch.text.toString())
            false
        }
        binding.ivClear.setOnClickListener { binding.etSearch.setText("") }

        // Filter chips.
        binding.tvFilterEveryone.setOnClickListener { setFilter(FILTER_EVERYONE) }
        binding.tvFilterFriends.setOnClickListener { setFilter(FILTER_FRIENDS) }
        binding.tvFilterRequests.setOnClickListener { setFilter(FILTER_REQUESTS) }
        setFilter(FILTER_EVERYONE)

        binding.etSearch.requestFocus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(runSearch)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Filter chips
    // ---------------------------------------------------------------------------------------

    private fun setFilter(newFilter: String) {
        filter = newFilter
        styleChip(binding.tvFilterEveryone, filter == FILTER_EVERYONE)
        styleChip(binding.tvFilterFriends, filter == FILTER_FRIENDS)
        styleChip(binding.tvFilterRequests, filter == FILTER_REQUESTS)
        search()
    }

    /** Selected chip: light teal with teal text (Kinnect.Chip.Selected); others grey. */
    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip.setTextColor(ContextCompat.getColor(this, if (selected) R.color.teal else R.color.text_dark))
    }

    // ---------------------------------------------------------------------------------------
    // Searching
    // ---------------------------------------------------------------------------------------

    private fun search() {
        val text = binding.etSearch.text.toString().trim().removePrefix("@")
        val number = ++searchNumber

        // Nothing typed on Everyone: Recent searches instead of results.
        if (text.isEmpty() && filter == FILTER_EVERYONE) {
            binding.llResults.visibility = View.GONE
            binding.svRecent.visibility = View.VISIBLE
            showRecent()
            return
        }
        binding.llResults.visibility = View.VISIBLE
        binding.svRecent.visibility = View.GONE

        Api.get("search.php", mapOf("q" to text, "filter" to filter)) { result ->
            if (number != searchNumber || isDestroyed) return@get   // an older search
            val json = result.json
            if (result.ok && json != null) {
                val found = Json.objects(json.optJSONArray("users")).map { item ->
                    Person(Json.saveUser(item), FriendRepo.statusOf(item.optString("status")))
                }
                showResults(text, found)
            } else if (result.offline) {
                toast(R.string.search_offline)
                showResults(text, searchOnPhone(text))
            } else {
                toast(result.error ?: getString(R.string.error_generic))
            }
        }
    }

    /** Offline: people saved in SQLite whose name or username contains [text]. */
    private fun searchOnPhone(text: String): List<Person> {
        if (filter != FILTER_EVERYONE) return emptyList()
        val like = "%$text%"
        return LocalDb.list(LocalDb.USERS, "(name LIKE ? OR username LIKE ?) AND id <> ?", arrayOf(like, like, myUid), "name", 30)
            .map { Person(Json.user(it), FriendRepo.Status.NONE) }
    }

    private fun showResults(text: String, results: List<Person>) {
        people.clear()
        people.addAll(results)
        adapter.notifyDataSetChanged()
        binding.tvNoResults.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNoResults.text = when {
            filter == FILTER_REQUESTS && text.isEmpty() -> getString(R.string.search_no_requests)
            filter == FILTER_FRIENDS -> getString(R.string.search_no_friends_match, text)
            else -> getString(R.string.search_no_results, text)
        }
    }

    /** "Friend · Lives in Karachi", "@sara.iqbal · Lives in Lahore" ... */
    private fun subtitleOf(person: Person): String {
        val user = person.user
        val parts = mutableListOf<String>()
        when (person.status) {
            FriendRepo.Status.FRIENDS -> parts.add(getString(R.string.search_friend))
            FriendRepo.Status.REQUEST_RECEIVED -> parts.add(getString(R.string.search_sent_you_request))
            FriendRepo.Status.REQUEST_SENT -> parts.add(getString(R.string.search_requested))
            FriendRepo.Status.NONE ->
                if (user.username.isNotEmpty()) parts.add(getString(R.string.search_username, user.username))
        }
        if (user.city.isNotEmpty()) parts.add(getString(R.string.details_city_prefix) + user.city)
        return parts.joinToString(getString(R.string.search_joiner))
    }

    // ---------------------------------------------------------------------------------------
    // Recent searches (SharedPreferences: one text per line, newest first)
    // ---------------------------------------------------------------------------------------

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun loadRecent(): List<String> =
        prefs.getString(KEY_RECENT, "").orEmpty().split("\n").filter { it.isNotBlank() }

    private fun storeRecent(list: List<String>) {
        prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    private fun saveRecent(typed: String) {
        val text = typed.trim()
        if (text.isEmpty()) return
        storeRecent((listOf(text) + loadRecent().filter { it != text }).take(MAX_RECENT))
    }

    /** Rebuilds the Recent searches rows from item_recent_search.xml. */
    private fun showRecent() {
        val recent = loadRecent()
        binding.tvNoRecent.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        binding.llRecent.removeAllViews()
        for (text in recent) {
            val row = ItemRecentSearchBinding.inflate(layoutInflater, binding.llRecent, false)
            row.tvRecentText.text = text
            row.root.setOnClickListener {
                binding.etSearch.setText(text)
                binding.etSearch.setSelection(text.length)
            }
            row.ivRemove.setOnClickListener {
                storeRecent(loadRecent().filter { it != text })
                showRecent()
            }
            binding.llRecent.addView(row.root)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Result rows
    // ---------------------------------------------------------------------------------------

    private inner class PersonHolder(val row: ItemSearchPersonBinding) : RecyclerView.ViewHolder(row.root)

    private inner class PeopleAdapter : RecyclerView.Adapter<PersonHolder>() {

        override fun getItemCount() = people.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PersonHolder(ItemSearchPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PersonHolder, position: Int) {
            val person = people[position]
            val user = person.user
            val row = holder.row
            Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, user)
            row.tvName.text = user.name
            row.tvSubtitle.text = subtitleOf(person)

            row.root.setOnClickListener {
                saveRecent(binding.etSearch.text.toString())
                openProfile(user.uid)
            }

            // Add-friend circle only for people with no friendship or request yet.
            row.ivAdd.visibility = if (person.status == FriendRepo.Status.NONE) View.VISIBLE else View.GONE
            row.ivAdd.contentDescription = getString(R.string.cd_add_friend_name, user.name)
            row.ivAdd.setOnClickListener {
                FriendRepo.act(this@SearchActivity, FriendRepo.REQUEST, user.uid)
                person.status = FriendRepo.Status.REQUEST_SENT
                toast(R.string.friend_request_sent)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    private companion object {
        const val TYPING_DELAY_MS = 300L
        const val MAX_RECENT = 8
        const val PREFS = "recent_searches"
        const val KEY_RECENT = "list"

        // The filter values search.php understands.
        const val FILTER_EVERYONE = "everyone"
        const val FILTER_FRIENDS = "friends"
        const val FILTER_REQUESTS = "requests"
    }
}

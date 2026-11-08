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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivitySearchBinding
import com.rameen.i230806.databinding.ItemRecentSearchBinding
import com.rameen.i230806.databinding.ItemSearchPersonBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import java.util.Locale

/**
 * 13 Search for people by name or username, as you type.
 *
 * How the search works: Firebase can't do "contains", but it can do "starts with". Every user
 * has nameLower ("jacob west") and usernameLower ("jacob.west"), so typing "jac" asks for
 * values from "jac" up to "jac\uf8ff" (\uf8ff sorts after every normal letter). Both lists
 * are merged, so "west" finds nobody but "jacob" and "jacob.w" both find Jacob West.
 * The search waits 300 ms after the last key press so it doesn't run for every letter.
 *
 * Each row shows the friendship state and, for strangers, an add-friend button.
 * Tapping a row opens the profile and saves the text in Recent searches (on this phone),
 * which are shown while the field is empty.
 */
class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding

    private val people = mutableListOf<User>()
    private val adapter = PeopleAdapter()

    // Friendship facts for the rows.
    private var friendUids: Set<String> = emptySet()
    private var askedMe: Set<String> = emptySet()
    private val iAsked = mutableSetOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val runSearch = Runnable { search(binding.etSearch.text.toString()) }

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

        watch(Db.friends(myUid)) { snapshot ->
            friendUids = snapshot.children.mapNotNull { it.key }.toSet()
            adapter.notifyDataSetChanged()
        }
        watch(Db.friendRequests(myUid)) { snapshot ->
            askedMe = snapshot.children.mapNotNull { it.key }.toSet()
            adapter.notifyDataSetChanged()
        }

        showRecent()
        binding.etSearch.requestFocus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(runSearch)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Searching
    // ---------------------------------------------------------------------------------------

    private fun search(typed: String) {
        val text = typed.trim().lowercase(Locale.getDefault()).removePrefix("@")
        val number = ++searchNumber
        if (text.isEmpty()) {
            binding.llResults.visibility = View.GONE
            binding.svRecent.visibility = View.VISIBLE
            showRecent()
            return
        }
        binding.llResults.visibility = View.VISIBLE
        binding.svRecent.visibility = View.GONE

        // Two "starts with" queries; results are merged by uid (a LinkedHashMap keeps the order).
        val found = LinkedHashMap<String, User>()
        var answers = 0
        val onAnswer = { snapshot: DataSnapshot? ->
            snapshot?.children?.forEach { child ->
                child.getValue(User::class.java)?.let { user ->
                    if (user.uid.isNotEmpty() && user.uid != myUid) found[user.uid] = user
                }
            }
            answers++
            if (answers == 2 && number == searchNumber) showResults(text, found.values.toList())
        }
        for (field in listOf("nameLower", "usernameLower")) {
            Db.users().orderByChild(field).startAt(text).endAt(text + "\uf8ff").limitToFirst(MAX_RESULTS)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) = onAnswer(snapshot)
                    override fun onCancelled(error: DatabaseError) = onAnswer(null)
                })
        }
    }

    private fun showResults(text: String, results: List<User>) {
        people.clear()
        people.addAll(results.sortedBy { it.nameLower })
        adapter.notifyDataSetChanged()
        binding.tvNoResults.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
        binding.tvNoResults.text = getString(R.string.search_no_results, text)

        // Requests I already sent are stored under the other person, so check each result once.
        for (user in results) {
            Db.friendRequests(user.uid).child(myUid).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && iAsked.add(user.uid)) adapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    private fun statusOf(uid: String) =
        FriendRepo.statusOf(uid in friendUids, uid in iAsked, uid in askedMe)

    /** "Friend · Lives in Karachi", "@sara.iqbal · Lives in Lahore" ... */
    private fun subtitleOf(user: User): String {
        val parts = mutableListOf<String>()
        when (statusOf(user.uid)) {
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
            val user = people[position]
            val row = holder.row
            Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, user)
            row.tvName.text = user.name
            row.tvSubtitle.text = subtitleOf(user)

            row.root.setOnClickListener {
                saveRecent(binding.etSearch.text.toString())
                openProfile(user.uid)
            }

            // Add-friend circle only for people with no friendship or request yet.
            val canAdd = statusOf(user.uid) == FriendRepo.Status.NONE
            row.ivAdd.visibility = if (canAdd) View.VISIBLE else View.GONE
            row.ivAdd.contentDescription = getString(R.string.cd_add_friend_name, user.name)
            row.ivAdd.setOnClickListener {
                FriendRepo.sendRequest(user.uid)
                iAsked.add(user.uid)
                toast(R.string.friend_request_sent)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    private companion object {
        const val TYPING_DELAY_MS = 300L
        const val MAX_RESULTS = 20
        const val MAX_RECENT = 8
        const val PREFS = "recent_searches"
        const val KEY_RECENT = "list"
    }
}
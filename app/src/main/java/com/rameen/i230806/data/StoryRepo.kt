package com.rameen.i230806.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

/**
 * Everything about stories (used by the Story editor, Story viewer and Your story). Stored as
 *
 *   stories/{authorUid}/{storyId}     Story, with viewers/{uid} = time and reactions/{uid} = key
 *
 * A story lasts 24 hours: expiresAt = timestamp + 24 h, and every screen hides expired ones.
 * (Without the paid plan there is no server job to delete them; the app ignores them instead.)
 * Close-friends stories are shown only to people in closeFriends/{author}.
 */
object StoryRepo {

    const val LIFETIME_MS = 24 * 60 * 60 * 1000L

    private val me: String get() = Db.uid ?: ""

    /** Saves a new story for me. [onDone] gets true when it was saved. */
    fun add(mediaId: String, mediaType: String, label: String, audience: String, onDone: (Boolean) -> Unit) {
        val ref = Db.stories(me).push()
        val now = Db.now()
        val story = Story(
            id = ref.key ?: "",
            authorId = me,
            mediaId = mediaId,
            mediaType = mediaType,
            label = label,
            audience = audience,
            timestamp = now,
            expiresAt = now + LIFETIME_MS,
        )
        ref.setValue(story).addOnCompleteListener { onDone(it.isSuccessful) }
    }

    /** Deletes my story and its picture / video. */
    fun delete(story: Story) {
        Db.root.updateChildren(
            mapOf(
                "stories/${story.authorId}/${story.id}" to null,
                "media/${story.mediaId}" to null,
            )
        )
    }

    /** "Seen": adds me to the story's viewers (not for my own stories). */
    fun markViewed(story: Story) {
        if (story.authorId == me || story.viewers.containsKey(me)) return
        Db.stories(story.authorId).child(story.id).child("viewers").child(me).setValue(Db.now())
    }

    /** Reacts to someone's story; the author gets a notification with the reaction. */
    fun react(story: Story, reaction: Reaction) {
        Db.stories(story.authorId).child(story.id).child("reactions").child(me).setValue(reaction.key)
        Db.notify(story.authorId, NotifType.STORY_REACTION, text = reaction.key)
    }

    /**
     * Reads [author]'s stories that I may see right now, oldest first (the order they play in):
     * not expired, and close-friends ones only if [author] added me to their close friends.
     */
    fun loadVisible(author: String, onLoaded: (List<Story>) -> Unit) {
        Db.stories(author).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = Db.now()
                val stories = snapshot.children
                    .mapNotNull { it.getValue(Story::class.java)?.apply { id = it.key ?: id; authorId = author } }
                    .filter { it.expiresAt > now }
                    .sortedBy { it.timestamp }
                if (author == me || stories.none { it.audience == Audience.CLOSE_FRIENDS }) {
                    onLoaded(stories)
                    return
                }
                // Some are for close friends only: am I one of theirs?
                Db.closeFriends(author).child(me).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(close: DataSnapshot) {
                        onLoaded(stories.filter { it.audience != Audience.CLOSE_FRIENDS || close.exists() })
                    }

                    override fun onCancelled(error: DatabaseError) = onLoaded(stories.filter { it.audience != Audience.CLOSE_FRIENDS })
                })
            }

            override fun onCancelled(error: DatabaseError) = onLoaded(emptyList())
        })
    }
}
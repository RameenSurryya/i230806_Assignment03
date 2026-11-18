package com.rameen.i230806.data

import org.json.JSONObject

/**
 * Reading from the server with an offline copy (Assignment 3).
 *
 *   Cached.get("friends/lists.php") { json -> showLists(json) }
 *
 *  1. If this request was answered before, the saved answer (SQLite, table api_cache) is
 *     shown straight away: the screen opens instantly, and works offline.
 *  2. Then the server is asked; a fresh answer is saved and [onData] runs again with it.
 * So [onData] can run twice (old copy, then fresh), or only once.
 * [onFail] runs when the server couldn't be reached or refused (offline, 401, 404 ...).
 */
object Cached {

    fun get(
        path: String,
        params: Map<String, Any?> = emptyMap(),
        onFail: (Api.Result) -> Unit = {},
        onData: (JSONObject) -> Unit,
    ) {
        val name = nameOf(path, params)
        LocalDb.get(LocalDb.CACHE, "name", name)?.let(onData)
        Api.get(path, params) { result ->
            val json = result.json
            if (result.ok && json != null) {
                LocalDb.put(LocalDb.CACHE, "name", name, json)
                onData(json)
            } else {
                onFail(result)
            }
        }
    }

    /** "users/profile.php?id=7": the same request always gets the same name. */
    private fun nameOf(path: String, params: Map<String, Any?>): String =
        if (params.isEmpty()) path
        else path + "?" + params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
}

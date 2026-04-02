package com.dd3boh.outertune.playback

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model and JSON serialization for the Jam WebSocket protocol.
 */

// ─── Data Classes ────────────────────────────────────────────────────────────

data class JamParticipant(
    val id: String,
    val displayName: String,
    val isHost: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject) = JamParticipant(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            isHost = json.optBoolean("isHost", false),
        )
    }
}

data class JamPlaybackState(
    val songId: String,
    val title: String,
    val artists: String,
    val thumbnailUrl: String?,
    val duration: Int,
    val position: Long,
    val isPlaying: Boolean,
    val timestamp: Long,
) {
    companion object {
        fun fromJson(json: JSONObject) = JamPlaybackState(
            songId = json.getString("songId"),
            title = json.getString("title"),
            artists = json.getString("artists"),
            thumbnailUrl = json.optString("thumbnailUrl", null),
            duration = json.optInt("duration", 0),
            position = json.optLong("position", 0),
            isPlaying = json.optBoolean("isPlaying", true),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        )
    }
}

data class JamQueueItem(
    val id: String,
    val songId: String,
    val title: String,
    val artists: String,
    val thumbnailUrl: String?,
    val duration: Int,
    val addedBy: String,
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("songId", songId)
        put("title", title)
        put("artists", artists)
        put("thumbnailUrl", thumbnailUrl)
        put("duration", duration)
        put("addedBy", addedBy)
    }

    companion object {
        fun fromJson(json: JSONObject) = JamQueueItem(
            id = json.getString("id"),
            songId = json.getString("songId"),
            title = json.getString("title"),
            artists = json.getString("artists"),
            thumbnailUrl = json.optString("thumbnailUrl", null),
            duration = json.optInt("duration", 0),
            addedBy = json.optString("addedBy", ""),
        )
    }
}

enum class JamState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    IN_SESSION,
    ERROR,
}

// ─── Outgoing Messages (Client → Server) ────────────────────────────────────

object JamMessages {
    fun createSession(displayName: String) = JSONObject().apply {
        put("type", "CREATE")
        put("displayName", displayName)
    }.toString()

    fun joinSession(code: String, displayName: String) = JSONObject().apply {
        put("type", "JOIN")
        put("code", code)
        put("displayName", displayName)
    }.toString()

    fun leaveSession() = JSONObject().apply {
        put("type", "LEAVE")
    }.toString()

    fun playbackState(
        songId: String,
        title: String,
        artists: String,
        thumbnailUrl: String?,
        duration: Int,
        position: Long,
        isPlaying: Boolean,
    ) = JSONObject().apply {
        put("type", "PLAYBACK")
        put("songId", songId)
        put("title", title)
        put("artists", artists)
        put("thumbnailUrl", thumbnailUrl)
        put("duration", duration)
        put("position", position)
        put("isPlaying", isPlaying)
    }.toString()

    fun queueAdd(
        songId: String,
        title: String,
        artists: String,
        thumbnailUrl: String?,
        duration: Int,
    ) = JSONObject().apply {
        put("type", "QUEUE_ADD")
        put("songId", songId)
        put("title", title)
        put("artists", artists)
        put("thumbnailUrl", thumbnailUrl)
        put("duration", duration)
    }.toString()

    fun queueRemove(index: Int) = JSONObject().apply {
        put("type", "QUEUE_REMOVE")
        put("index", index)
    }.toString()

    fun ping() = JSONObject().apply {
        put("type", "PING")
    }.toString()
}

// ─── Incoming Message Parsing ────────────────────────────────────────────────

fun parseParticipantList(json: JSONObject): List<JamParticipant> {
    val arr = json.optJSONArray("participants") ?: return emptyList()
    return (0 until arr.length()).map { JamParticipant.fromJson(arr.getJSONObject(it)) }
}

fun parseQueueList(json: JSONObject): List<JamQueueItem> {
    val arr = json.optJSONArray("queue") ?: return emptyList()
    return (0 until arr.length()).map { JamQueueItem.fromJson(arr.getJSONObject(it)) }
}

package com.dd3boh.outertune.playback

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.constants.JamServerUrlKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WebSocket connection and state for Jam (collaborative listening) sessions.
 *
 * Host mode: Broadcasts local playback state to all guests via the server.
 * Guest mode: Receives playback state from host and syncs the local player.
 */
@Singleton
class JamManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "JamManager"
        private const val DEFAULT_SERVER_URL = "ws://10.0.2.2:8080" // emulator localhost
        private const val HEARTBEAT_INTERVAL = 30_000L
        private const val RECONNECT_DELAY = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0

    // ─── Public State ────────────────────────────────────────────────────────

    private val _jamState = MutableStateFlow(JamState.DISCONNECTED)
    val jamState: StateFlow<JamState> = _jamState.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _joinCode = MutableStateFlow<String?>(null)
    val joinCode: StateFlow<String?> = _joinCode.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _participants = MutableStateFlow<List<JamParticipant>>(emptyList())
    val participants: StateFlow<List<JamParticipant>> = _participants.asStateFlow()

    private val _jamQueue = MutableStateFlow<List<JamQueueItem>>(emptyList())
    val jamQueue: StateFlow<List<JamQueueItem>> = _jamQueue.asStateFlow()

    private val _remotePlaybackState = MutableStateFlow<JamPlaybackState?>(null)
    val remotePlaybackState: StateFlow<JamPlaybackState?> = _remotePlaybackState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var displayName: String = "User"

    val isInSession: Boolean
        get() = _jamState.value == JamState.IN_SESSION

    // ─── Actions ─────────────────────────────────────────────────────────────

    fun startSession(name: String) {
        displayName = name
        _isHost.value = true
        connect {
            sendMessage(JamMessages.createSession(name))
        }
    }

    fun joinSession(code: String, name: String) {
        displayName = name
        _isHost.value = false
        connect {
            sendMessage(JamMessages.joinSession(code, name))
        }
    }

    fun leaveSession() {
        sendMessage(JamMessages.leaveSession())
        disconnect()
    }

    fun sendPlaybackUpdate(
        songId: String,
        title: String,
        artists: String,
        thumbnailUrl: String?,
        duration: Int,
        position: Long,
        isPlaying: Boolean,
    ) {
        if (!_isHost.value || !isInSession) return
        sendMessage(
            JamMessages.playbackState(
                songId = songId,
                title = title,
                artists = artists,
                thumbnailUrl = thumbnailUrl,
                duration = duration,
                position = position,
                isPlaying = isPlaying,
            )
        )
    }

    fun addToJamQueue(
        songId: String,
        title: String,
        artists: String,
        thumbnailUrl: String?,
        duration: Int,
    ) {
        if (!isInSession) return
        sendMessage(
            JamMessages.queueAdd(
                songId = songId,
                title = title,
                artists = artists,
                thumbnailUrl = thumbnailUrl,
                duration = duration,
            )
        )
    }

    fun removeFromJamQueue(index: Int) {
        if (!_isHost.value || !isInSession) return
        sendMessage(JamMessages.queueRemove(index))
    }

    fun clearError() {
        _error.value = null
    }

    // ─── WebSocket Connection ────────────────────────────────────────────────

    private fun getServerUrl(): String {
        return context.dataStore[JamServerUrlKey] ?: DEFAULT_SERVER_URL
    }

    private fun connect(onConnected: (() -> Unit)? = null) {
        _jamState.value = JamState.CONNECTING
        reconnectAttempts = 0

        val serverUrl = getServerUrl()
        val wsUrl = if (serverUrl.endsWith("/ws")) serverUrl else "$serverUrl/ws"

        Log.i(TAG, "Connecting to jam server: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                _jamState.value = JamState.CONNECTED
                reconnectAttempts = 0
                startHeartbeat()
                onConnected?.invoke()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                _error.value = t.message ?: "Connection failed"
                handleDisconnect()

                // Auto-reconnect if was in a session
                if (_sessionId.value != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    scope.launch {
                        reconnectAttempts++
                        delay(RECONNECT_DELAY * reconnectAttempts)
                        Log.i(TAG, "Reconnecting (attempt $reconnectAttempts)...")
                        connect {
                            // Re-join the session after reconnect
                            val code = _joinCode.value
                            if (code != null && !_isHost.value) {
                                sendMessage(JamMessages.joinSession(code, displayName))
                            }
                        }
                    }
                }
            }
        })
    }

    private fun disconnect() {
        heartbeatJob?.cancel()
        webSocket?.close(1000, "User left")
        webSocket = null
        resetState()
    }

    private fun handleDisconnect() {
        heartbeatJob?.cancel()
        if (_jamState.value != JamState.CONNECTING) {
            _jamState.value = JamState.DISCONNECTED
        }
    }

    private fun resetState() {
        _jamState.value = JamState.DISCONNECTED
        _isHost.value = false
        _joinCode.value = null
        _sessionId.value = null
        _participants.value = emptyList()
        _jamQueue.value = emptyList()
        _remotePlaybackState.value = null
        _error.value = null
    }

    private fun sendMessage(message: String) {
        webSocket?.send(message) ?: run {
            Log.w(TAG, "Cannot send: WebSocket not connected")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL)
                sendMessage(JamMessages.ping())
            }
        }
    }

    // ─── Message Handling ────────────────────────────────────────────────────

    private fun handleMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.getString("type")

            when (type) {
                "SESSION_CREATED" -> {
                    _sessionId.value = json.getString("sessionId")
                    _joinCode.value = json.getString("code")
                    _participants.value = parseParticipantList(json)
                    _jamState.value = JamState.IN_SESSION
                    Log.i(TAG, "Session created with code: ${_joinCode.value}")
                }

                "SESSION_JOINED" -> {
                    _sessionId.value = json.getString("sessionId")
                    _joinCode.value = json.getString("code")
                    _isHost.value = json.optBoolean("isHost", false)
                    _participants.value = parseParticipantList(json)
                    _jamQueue.value = parseQueueList(json)
                    _jamState.value = JamState.IN_SESSION

                    // Parse initial playback state if present
                    if (!json.isNull("playbackState")) {
                        _remotePlaybackState.value = JamPlaybackState.fromJson(json.getJSONObject("playbackState"))
                    }

                    Log.i(TAG, "Joined session: ${_joinCode.value}")
                }

                "PLAYBACK" -> {
                    _remotePlaybackState.value = JamPlaybackState.fromJson(json)
                }

                "QUEUE_UPDATED" -> {
                    _jamQueue.value = parseQueueList(json)
                }

                "PARTICIPANT_JOINED" -> {
                    _participants.value = parseParticipantList(json)
                }

                "PARTICIPANT_LEFT" -> {
                    _participants.value = parseParticipantList(json)
                }

                "SESSION_ENDED" -> {
                    val reason = json.optString("reason", "unknown")
                    Log.i(TAG, "Session ended: $reason")
                    _error.value = when (reason) {
                        "host_left" -> "Host ended the session"
                        "expired" -> "Session expired"
                        else -> "Session ended"
                    }
                    resetState()
                    _error.value = _error.value // keep error after reset
                }

                "LEFT" -> {
                    resetState()
                }

                "ERROR" -> {
                    val message = json.optString("message", "Unknown error")
                    Log.e(TAG, "Server error: $message")
                    _error.value = message
                    if (_jamState.value == JamState.CONNECTING || _jamState.value == JamState.CONNECTED) {
                        _jamState.value = JamState.ERROR
                    }
                }

                "PONG" -> {
                    // heartbeat response, no action needed
                }

                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $raw", e)
        }
    }
}

package com.dd3boh.outertune.playback

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.JamClientIdKey
import com.dd3boh.outertune.constants.JamServerUrlKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.Queue
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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Holds an intercepted play request when a guest tries to play something during a Jam session
data class InterceptedPlayAction(
    val queue: Queue,
    val items: List<MediaMetadata>, // pre-fetched first page of songs for queue display
    val shouldResume: Boolean = false,
    val replace: Boolean = true,
    val isRadio: Boolean = false,
    val title: String? = null
)

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
        private const val DEFAULT_SERVER_URL = "ws://files.cipherscore.xyz"
        private const val HEARTBEAT_INTERVAL = 30_000L
        private const val RECONNECT_DELAY = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val stableClientId = getOrCreateClientId()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var reconnectSuppressed = false
    private var displayName: String = "User"
    private var resumeToken: String? = null
    private var lastRequestedJoinCode: String? = null

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

    private val _guestControlEnabled = MutableStateFlow(false)
    val guestControlEnabled: StateFlow<Boolean> = _guestControlEnabled.asStateFlow()

    private val _pendingInterceptedAction = MutableStateFlow<InterceptedPlayAction?>(null)
    val pendingInterceptedAction: StateFlow<InterceptedPlayAction?> = _pendingInterceptedAction.asStateFlow()

    private val _selfParticipantId = MutableStateFlow<String?>(null)
    val selfParticipantId: StateFlow<String?> = _selfParticipantId.asStateFlow()

    private val _jamEndReason = MutableStateFlow<JamEndReason?>(null)
    val jamEndReason: StateFlow<JamEndReason?> = _jamEndReason.asStateFlow()

    private val _jamEndMessage = MutableStateFlow<String?>(null)
    val jamEndMessage: StateFlow<String?> = _jamEndMessage.asStateFlow()

    val isInSession: Boolean
        get() = _jamState.value == JamState.IN_SESSION

    fun startSession(name: String) {
        displayName = name
        lastRequestedJoinCode = null
        clearTerminalState()
        clearTransientState()
        _isHost.value = true
        connect(JamState.CONNECTING) {
            sendMessage(JamMessages.createSession(name, stableClientId))
        }
    }

    fun joinSession(code: String, name: String) {
        displayName = name
        _isHost.value = false
        lastRequestedJoinCode = code.uppercase()
        clearTerminalState()
        clearTransientState(clearJoinCode = false)
        connect(JamState.CONNECTING) {
            sendMessage(
                JamMessages.joinSession(
                    code = lastRequestedJoinCode!!,
                    displayName = name,
                    clientId = stableClientId,
                )
            )
        }
    }

    fun leaveSession() {
        reconnectSuppressed = true
        reconnectJob?.cancel()
        sendMessage(JamMessages.leaveSession())
        disconnect("User left")
    }

    fun returnToSetup() {
        reconnectJob?.cancel()
        clearTerminalState()
        clearTransientState()
        _error.value = null
    }

    fun toggleGuestControl() {
        if (!_isHost.value || !isInSession) return
        val newValue = !_guestControlEnabled.value
        sendMessage(
            JSONObject().apply {
                put("type", "UPDATE_SETTINGS")
                put("guestControlEnabled", newValue)
            }.toString()
        )
        _guestControlEnabled.value = newValue
    }

    fun requestInterceptPlay(action: InterceptedPlayAction) {
        _pendingInterceptedAction.value = action
    }

    fun clearPendingIntercept() {
        _pendingInterceptedAction.value = null
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
        if (!isInSession) return
        if (!_isHost.value && !_guestControlEnabled.value) return
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

    private fun getServerUrl(): String {
        return context.dataStore[JamServerUrlKey] ?: DEFAULT_SERVER_URL
    }

    private fun connect(initialState: JamState, onConnected: (() -> Unit)? = null) {
        _jamState.value = initialState

        val serverUrl = getServerUrl()
        val wsUrl = if (serverUrl.endsWith("/ws")) serverUrl else "$serverUrl/ws"

        Log.i(TAG, "Connecting to jam server: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                if (_jamState.value != JamState.RECONNECTING) {
                    _jamState.value = JamState.CONNECTED
                }
                _error.value = null
                startHeartbeat()
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                handleSocketTermination("Socket closed ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                handleSocketTermination(t.message ?: "Connection failed")
            }
        })
    }

    private fun disconnect(reason: String) {
        heartbeatJob?.cancel()
        webSocket?.close(1000, reason)
        webSocket = null
        clearTransientState()
        reconnectSuppressed = false
    }

    private fun handleSocketTermination(message: String) {
        heartbeatJob?.cancel()
        webSocket = null

        if (reconnectSuppressed) {
            reconnectSuppressed = false
            return
        }

        if (canResumeSession()) {
            scheduleReconnect()
            return
        }

        if (_sessionId.value == null) {
            _error.value = message
            if (_jamState.value != JamState.ENDED) {
                _jamState.value = JamState.DISCONNECTED
            }
            return
        }

        moveToEnded(
            reason = JamEndReason.CONNECTION_FAILED,
            message = "Couldn't reconnect to the jam.",
        )
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            moveToEnded(
                reason = JamEndReason.CONNECTION_FAILED,
                message = "Couldn't reconnect to the jam.",
            )
            return
        }

        reconnectAttempts += 1
        _jamState.value = JamState.RECONNECTING

        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY * reconnectAttempts)
            reconnectJob = null
            Log.i(TAG, "Reconnecting (attempt $reconnectAttempts)")
            connect(JamState.RECONNECTING) {
                sendResumeJoin()
            }
        }
    }

    private fun sendResumeJoin() {
        val code = _joinCode.value ?: lastRequestedJoinCode
        val token = resumeToken

        if (code.isNullOrBlank() || token.isNullOrBlank()) {
            moveToEnded(
                reason = JamEndReason.CONNECTION_FAILED,
                message = "Couldn't reconnect to the jam.",
            )
            return
        }

        sendMessage(
            JamMessages.joinSession(
                code = code,
                displayName = displayName,
                clientId = stableClientId,
                resumeToken = token,
            )
        )
    }

    private fun clearTransientState(clearJoinCode: Boolean = true) {
        _jamState.value = JamState.DISCONNECTED
        _isHost.value = false
        if (clearJoinCode) {
            _joinCode.value = null
            lastRequestedJoinCode = null
        }
        _sessionId.value = null
        _participants.value = emptyList()
        _jamQueue.value = emptyList()
        _remotePlaybackState.value = null
        _guestControlEnabled.value = false
        _pendingInterceptedAction.value = null
        _selfParticipantId.value = null
        resumeToken = null
        reconnectAttempts = 0
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket = null
    }

    private fun clearTerminalState() {
        _jamEndReason.value = null
        _jamEndMessage.value = null
    }

    private fun moveToEnded(reason: JamEndReason, message: String) {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        webSocket = null
        val currentCode = _joinCode.value
        clearTransientState(clearJoinCode = true)
        _joinCode.value = currentCode
        _jamEndReason.value = reason
        _jamEndMessage.value = message
        _error.value = null
        _jamState.value = JamState.ENDED
    }

    private fun canResumeSession(): Boolean {
        return !_sessionId.value.isNullOrBlank() &&
            !(_joinCode.value ?: lastRequestedJoinCode).isNullOrBlank() &&
            !resumeToken.isNullOrBlank()
    }

    private fun sendMessage(message: String) {
        webSocket?.send(message) ?: Log.w(TAG, "Cannot send: WebSocket not connected")
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

    private fun handleMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            when (val type = json.getString("type")) {
                "SESSION_CREATED" -> {
                    reconnectAttempts = 0
                    _sessionId.value = json.getString("sessionId")
                    _joinCode.value = json.getString("code")
                    lastRequestedJoinCode = _joinCode.value
                    _participants.value = parseParticipantList(json)
                    _selfParticipantId.value = json.optString("selfParticipantId").takeIf { it.isNotBlank() }
                    resumeToken = json.optString("resumeToken").takeIf { it.isNotBlank() }
                    clearTerminalState()
                    _error.value = null
                    _jamState.value = JamState.IN_SESSION
                    Log.i(TAG, "Session created with code: ${_joinCode.value}")
                }

                "SESSION_JOINED" -> {
                    reconnectAttempts = 0
                    _sessionId.value = json.getString("sessionId")
                    _joinCode.value = json.getString("code")
                    lastRequestedJoinCode = _joinCode.value
                    _isHost.value = json.optBoolean("isHost", false)
                    _participants.value = parseParticipantList(json)
                    _jamQueue.value = parseQueueList(json)
                    _guestControlEnabled.value = json.optBoolean("guestControlEnabled", false)
                    _selfParticipantId.value = json.optString("selfParticipantId").takeIf { it.isNotBlank() }
                    resumeToken = json.optString("resumeToken").takeIf { it.isNotBlank() } ?: resumeToken
                    if (json.isNull("playbackState")) {
                        _remotePlaybackState.value = null
                    } else {
                        _remotePlaybackState.value = JamPlaybackState.fromJson(json.getJSONObject("playbackState"))
                    }
                    clearTerminalState()
                    _error.value = null
                    _jamState.value = JamState.IN_SESSION
                    Log.i(TAG, "Joined session: ${_joinCode.value}")
                }

                "PLAYBACK" -> {
                    _remotePlaybackState.value = JamPlaybackState.fromJson(json)
                }

                "QUEUE_UPDATED" -> {
                    _jamQueue.value = parseQueueList(json)
                }

                "PARTICIPANT_JOINED", "PARTICIPANT_LEFT", "PARTICIPANTS_UPDATED" -> {
                    _participants.value = parseParticipantList(json)
                }

                "SESSION_ENDED" -> {
                    val mapped = mapSessionEnd(json.optString("reason", "unknown"))
                    moveToEnded(mapped.first, mapped.second)
                }

                "LEFT" -> {
                    clearTerminalState()
                    clearTransientState()
                    _error.value = null
                }

                "ERROR" -> {
                    val message = json.optString("message", "Unknown error")
                    Log.e(TAG, "Server error: $message")
                    if (_jamState.value == JamState.RECONNECTING || _sessionId.value != null) {
                        val mappedReason = when {
                            message.contains("Session not found", ignoreCase = true) -> JamEndReason.SESSION_NOT_FOUND
                            message.contains("Resume rejected", ignoreCase = true) -> JamEndReason.RESUME_REJECTED
                            else -> JamEndReason.UNKNOWN
                        }
                        moveToEnded(mappedReason, message)
                    } else {
                        reconnectSuppressed = true
                        webSocket?.close(1000, "Jam error")
                        webSocket = null
                        _error.value = message
                        _jamState.value = JamState.DISCONNECTED
                    }
                }

                "PONG" -> Unit

                "SETTINGS_UPDATED" -> {
                    _guestControlEnabled.value = json.optBoolean("guestControlEnabled", false)
                }

                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $raw", e)
        }
    }

    private fun mapSessionEnd(reason: String): Pair<JamEndReason, String> {
        return when (reason) {
            "host_left" -> JamEndReason.HOST_ENDED to "The host ended the jam."
            "host_disconnected_timeout" -> JamEndReason.HOST_DISCONNECTED_TIMEOUT to "The host did not reconnect in time."
            "expired" -> JamEndReason.SESSION_EXPIRED to "This jam has expired."
            "session_not_found" -> JamEndReason.SESSION_NOT_FOUND to "This jam is no longer available."
            else -> JamEndReason.UNKNOWN to "This jam has ended."
        }
    }

    private fun getOrCreateClientId(): String {
        val existing = context.dataStore[JamClientIdKey]
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val generated = UUID.randomUUID().toString()
        runBlocking(Dispatchers.IO) {
            context.dataStore.edit { preferences ->
                preferences[JamClientIdKey] = generated
            }
        }
        return generated
    }
}

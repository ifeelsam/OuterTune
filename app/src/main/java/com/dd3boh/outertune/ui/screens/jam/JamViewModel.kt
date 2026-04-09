package com.dd3boh.outertune.ui.screens.jam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.playback.JamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamManager: JamManager
) : ViewModel() {

    val error = jamManager.error
    val isHost = jamManager.isHost
    val participants = jamManager.participants
    val sessionCode = jamManager.joinCode
    val isReady = jamManager.jamState.map { it == com.dd3boh.outertune.playback.JamState.CONNECTED || it == com.dd3boh.outertune.playback.JamState.IN_SESSION }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val inSession = combine(isHost, sessionCode) { host, code ->
        host || code != null
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun startJam(nickname: String) {
        jamManager.startSession(nickname)
    }

    fun joinJam(code: String, nickname: String) {
        jamManager.joinSession(code, nickname)
    }

    fun leaveJam() {
        jamManager.leaveSession()
    }
}

package com.dd3boh.outertune.ui.screens.jam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.playback.JamManager
import com.dd3boh.outertune.playback.JamState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamManager: JamManager
) : ViewModel() {

    val error = jamManager.error
    val endMessage = jamManager.jamEndMessage
    val isHost = jamManager.isHost
    val participants = jamManager.participants
    val sessionCode = jamManager.joinCode
    val jamState = jamManager.jamState
    val isLoading = jamManager.jamState.map { it == JamState.CONNECTING || it == JamState.RECONNECTING }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)
    val inSession = jamManager.jamState.map { it == JamState.IN_SESSION }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun startJam(nickname: String) {
        jamManager.startSession(nickname)
    }

    fun joinJam(code: String, nickname: String) {
        jamManager.joinSession(code, nickname)
    }

    fun leaveJam() {
        jamManager.leaveSession()
    }

    fun returnToSetup() {
        jamManager.returnToSetup()
    }
}

package com.dd3boh.outertune.ui.screens.jam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.playback.JamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamManager: JamManager
) : ViewModel() {

    val url = jamManager.url
    val isConnected = jamManager.isConnected
    val error = jamManager.error
    val isHost = jamManager.isHost
    val participants = jamManager.participants
    val sessionCode = jamManager.sessionCode
    val isReady = jamManager.isReady

    val inSession = combine(isHost, sessionCode) { host, code ->
        host || code != null
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun startJam() {
        jamManager.startJam(viewModelScope)
    }

    fun joinJam(code: String, nickname: String) {
        jamManager.joinJam(code, nickname, viewModelScope)
    }

    fun leaveJam() {
        jamManager.leaveJam()
    }
}

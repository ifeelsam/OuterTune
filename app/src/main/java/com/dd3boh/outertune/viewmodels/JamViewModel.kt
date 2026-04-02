package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.playback.JamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class JamViewModel @Inject constructor(
    val jamManager: JamManager
) : ViewModel() {

    private val _joinCodeInput = MutableStateFlow("")
    val joinCodeInput = _joinCodeInput.asStateFlow()

    private val _displayNameInput = MutableStateFlow("")
    val displayNameInput = _displayNameInput.asStateFlow()

    fun updateJoinCode(code: String) {
        _joinCodeInput.value = code.uppercase()
    }

    fun updateDisplayName(name: String) {
        _displayNameInput.value = name
    }

    fun startSession() {
        if (_displayNameInput.value.isNotBlank()) {
            jamManager.startSession(_displayNameInput.value)
        }
    }

    fun joinSession() {
        if (_joinCodeInput.value.isNotBlank() && _displayNameInput.value.isNotBlank()) {
            jamManager.joinSession(_joinCodeInput.value, _displayNameInput.value)
        }
    }

    fun leaveSession() {
        jamManager.leaveSession()
    }

    fun clearError() {
        jamManager.clearError()
    }
}

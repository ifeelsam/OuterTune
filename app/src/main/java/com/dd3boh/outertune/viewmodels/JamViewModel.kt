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

    fun updateJoinCode(code: String) {
        _joinCodeInput.value = code.uppercase()
    }

    fun startSession(displayName: String) {
        if (displayName.isNotBlank()) {
            jamManager.startSession(displayName)
        }
    }

    fun joinSession(displayName: String) {
        if (_joinCodeInput.value.isNotBlank() && displayName.isNotBlank()) {
            jamManager.joinSession(_joinCodeInput.value, displayName)
        }
    }

    fun leaveSession() {
        jamManager.leaveSession()
    }

    fun toggleGuestControl() {
        jamManager.toggleGuestControl()
    }

    val guestControlEnabled = jamManager.guestControlEnabled

    fun clearError() {
        jamManager.clearError()
    }
}

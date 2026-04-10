package com.dd3boh.outertune.ui.jam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.JamDisplayNameKey
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.utils.parseCookieString

data class JamIdentityState(
    val isSignedIn: Boolean,
    val accountName: String,
    val savedName: String,
    val resolvedName: String?,
) {
    val requiresManualName: Boolean
        get() = !isSignedIn && resolvedName.isNullOrBlank()

    val avatarLabel: String
        get() = initialFor(resolvedName ?: savedName ?: accountName)

    fun resolveDisplayName(input: String = ""): String? {
        return resolvedName ?: input.trim().takeIf { it.isNotEmpty() }
    }

    fun shouldPersistInput(input: String): Boolean {
        return !isSignedIn && savedName.isBlank() && input.trim().isNotEmpty()
    }

    companion object {
        fun initialFor(name: String?): String {
            val initial = name
                ?.trim()
                ?.firstOrNull()
                ?.uppercaseChar()
                ?: '?'
            return initial.toString()
        }
    }
}

@Composable
fun rememberJamIdentityState(): Pair<JamIdentityState, (String) -> Unit> {
    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val accountName by rememberPreference(AccountNameKey, "")
    val (savedName, onSavedNameChange) = rememberPreference(JamDisplayNameKey, "")

    val isSignedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    val normalizedAccountName = accountName.trim()
    val normalizedSavedName = savedName.trim()
    val resolvedName = when {
        isSignedIn && normalizedAccountName.isNotEmpty() -> normalizedAccountName
        isSignedIn -> "You"
        normalizedSavedName.isNotEmpty() -> normalizedSavedName
        else -> null
    }

    return JamIdentityState(
        isSignedIn = isSignedIn,
        accountName = normalizedAccountName,
        savedName = normalizedSavedName,
        resolvedName = resolvedName,
    ) to { newName ->
        onSavedNameChange(newName.trim())
    }
}

@Composable
fun JamIdentityAvatar(
    name: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = JamIdentityState.initialFor(name),
            color = contentColor,
            fontWeight = FontWeight.Bold
        )
    }
}

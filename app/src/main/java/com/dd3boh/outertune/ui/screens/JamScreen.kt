package com.dd3boh.outertune.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.playback.JamEndReason
import com.dd3boh.outertune.playback.JamState
import com.dd3boh.outertune.ui.jam.JamIdentityAvatar
import com.dd3boh.outertune.ui.jam.JamIdentityState
import com.dd3boh.outertune.ui.jam.buildJamInviteUri
import com.dd3boh.outertune.ui.jam.createJamInviteQrBitmap
import com.dd3boh.outertune.ui.jam.rememberJamQrScannerLauncher
import com.dd3boh.outertune.ui.jam.rememberJamIdentityState
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.viewmodels.JamViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamScreen(
    viewModel: JamViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit,
    onOpenJoinCode: (String) -> Unit
) {
    val jamState by viewModel.jamManager.jamState.collectAsState()
    val isHost by viewModel.jamManager.isHost.collectAsState()
    val joinCode by viewModel.jamManager.joinCode.collectAsState()
    val participants by viewModel.jamManager.participants.collectAsState()
    val jamEndReason by viewModel.jamManager.jamEndReason.collectAsState()
    val jamEndMessage by viewModel.jamManager.jamEndMessage.collectAsState()
    val guestControlEnabled by viewModel.guestControlEnabled.collectAsState()
    val selfParticipantId by viewModel.jamManager.selfParticipantId.collectAsState()
    val (jamIdentity, onSavedNameChange) = rememberJamIdentityState()
    var showProfileDialog by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jam Session") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showProfileDialog = true }) {
                        JamIdentityAvatar(
                            name = jamIdentity.resolvedName ?: jamIdentity.savedName,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    if (jamState == JamState.IN_SESSION) {
                        TextButton(onClick = { viewModel.leaveSession(); onNavigateUp() }) {
                            Text(if (isHost) "End Jam" else "Leave", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (jamState) {
                JamState.DISCONNECTED, JamState.ERROR -> JamSetupView(
                    viewModel = viewModel,
                    jamIdentity = jamIdentity,
                    onSaveDisplayName = onSavedNameChange,
                    onOpenJoinCode = onOpenJoinCode
                )
                JamState.CONNECTING, JamState.CONNECTED -> ConnectingView("Connecting to Jam Server...")
                JamState.RECONNECTING -> ConnectingView("Reconnecting to Jam...")
                JamState.IN_SESSION -> JamSessionView(
                    isHost = isHost,
                    joinCode = joinCode,
                    participants = participants,
                    selfParticipantId = selfParticipantId,
                    guestControlEnabled = guestControlEnabled,
                    onCopyCode = { code -> clipboardManager.setText(AnnotatedString(code)) },
                    onToggleGuestControl = viewModel::toggleGuestControl
                )
                JamState.ENDED -> JamEndedView(
                    reason = jamEndReason,
                    message = jamEndMessage ?: "This jam has ended.",
                    onBack = onNavigateUp,
                    onStartNewJam = viewModel::returnToSetup,
                    onJoinAnotherJam = viewModel::returnToSetup
                )
            }
        }

        if (showProfileDialog) {
            JamProfileDialog(
                jamIdentity = jamIdentity,
                onDismiss = { showProfileDialog = false },
                onSaveDisplayName = onSavedNameChange
            )
        }
    }
}

@Composable
private fun JamSetupView(
    viewModel: JamViewModel,
    jamIdentity: JamIdentityState,
    onSaveDisplayName: (String) -> Unit,
    onOpenJoinCode: (String) -> Unit,
) {
    val joinCode by viewModel.joinCodeInput.collectAsState()
    val snackbarHostState = LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()
    var pendingDisplayName by rememberSaveable(jamIdentity.savedName) {
        mutableStateOf(jamIdentity.savedName)
    }
    val scanJamQr = rememberJamQrScannerLauncher(
        onInviteCodeScanned = onOpenJoinCode,
        onInvalidQr = {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("That QR code is not a valid jam invite.")
            }
        }
    )

    val effectiveDisplayName = jamIdentity.resolveDisplayName(pendingDisplayName)
    val canStartOrJoin = !effectiveDisplayName.isNullOrBlank()

    val actionInsets = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Bottom)
        .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.Group,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Listen Together",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Start a Jam session to listen in sync with friends.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            if (jamIdentity.requiresManualName) {
                OutlinedTextField(
                    value = pendingDisplayName,
                    onValueChange = { newValue -> pendingDisplayName = newValue.take(20) },
                    label = { Text("Your Jam Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
            }

            OutlinedTextField(
                value = joinCode,
                onValueChange = {
                    viewModel.updateJoinCode(
                        it.filter(Char::isLetterOrDigit).take(6)
                    )
                },
                label = { Text("Join Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Use the profile icon in the top-right to change your jam name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            TextButton(
                onClick = scanJamQr,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan QR Instead")
            }
        }

        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(actionInsets)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = {
                        val displayName = effectiveDisplayName ?: return@Button
                        if (jamIdentity.shouldPersistInput(pendingDisplayName)) {
                            onSaveDisplayName(displayName)
                        }
                        viewModel.startSession(displayName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = canStartOrJoin
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start a Jam Session")
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        val displayName = effectiveDisplayName ?: return@OutlinedButton
                        if (jamIdentity.shouldPersistInput(pendingDisplayName)) {
                            onSaveDisplayName(displayName)
                        }
                        viewModel.joinSession(displayName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = canStartOrJoin && joinCode.length == 6
                ) {
                    Text("Join Session")
                }
            }
        }
    }
}

@Composable
private fun ConnectingView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JamEndedView(
    reason: JamEndReason?,
    message: String,
    onBack: () -> Unit,
    onStartNewJam: () -> Unit,
    onJoinAnotherJam: () -> Unit,
) {
    val title = when (reason) {
        JamEndReason.HOST_DISCONNECTED_TIMEOUT -> "Host disconnected"
        JamEndReason.SESSION_EXPIRED -> "Jam expired"
        JamEndReason.SESSION_NOT_FOUND -> "Jam unavailable"
        JamEndReason.RESUME_REJECTED -> "Couldn't rejoin"
        else -> "Jam ended"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.SignalWifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStartNewJam,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start New Jam")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onJoinAnotherJam,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join Another Jam")
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Composable
private fun JamSessionView(
    isHost: Boolean,
    joinCode: String?,
    participants: List<com.dd3boh.outertune.playback.JamParticipant>,
    selfParticipantId: String?,
    guestControlEnabled: Boolean,
    onCopyCode: (String) -> Unit,
    onToggleGuestControl: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var showInviteQr by remember { mutableStateOf(false) }
    val inviteUri = remember(joinCode) {
        joinCode?.let { code ->
            runCatching { buildJamInviteUri(code) }.getOrNull()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header card
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Join Code",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        joinCode ?: "------",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        letterSpacing = 4.sp
                    )
                    IconButton(onClick = { joinCode?.let { onCopyCode(it) } }) {
                        Icon(Icons.Rounded.ContentCopy, "Copy Code")
                    }
                }
                if (inviteUri != null) {
                    Spacer(Modifier.height(16.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilledTonalButton(
                                onClick = {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, inviteUri.toString())
                                            },
                                            null
                                        )
                                    )
                                }
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Share Invite")
                            }
                        }
                        item {
                            OutlinedButton(onClick = { showInviteQr = true }) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Show QR")
                            }
                        }
                    }
                }
                Text(
                    if (isHost) "You are hosting this session" else "You are listening as a guest",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Guest control toggle / indicator
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Group,
                    contentDescription = null,
                    tint = if (guestControlEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Guest Control",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (guestControlEnabled) "Guests can change songs & seek"
                        else "Only the host can control playback",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isHost) {
                    Switch(
                        checked = guestControlEnabled,
                        onCheckedChange = { onToggleGuestControl() }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Participants (${participants.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(participants, key = { it.id }) { p ->
                val isCurrentUser = p.id == selfParticipantId
                ListItem(
                    colors = if (isCurrentUser) {
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                    headlineContent = {
                        Text(
                            if (isCurrentUser) "${p.displayName} (You)" else p.displayName,
                            fontWeight = if (p.isHost || isCurrentUser) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    supportingContent = {
                        val subtitle = buildList {
                            if (p.isHost) add("Host")
                            if (isCurrentUser) add("In this jam")
                        }.joinToString(" • ")
                        if (subtitle.isNotEmpty()) Text(subtitle)
                    },
                    leadingContent = {
                        JamIdentityAvatar(
                            name = p.displayName,
                            modifier = Modifier.size(40.dp),
                            containerColor = if (isCurrentUser) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            contentColor = if (isCurrentUser) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                )
            }
        }
    }

    if (showInviteQr && inviteUri != null && joinCode != null) {
        JamInviteQrDialog(
            joinCode = joinCode,
            inviteLink = inviteUri.toString(),
            onCopyLink = {
                clipboardManager.setText(AnnotatedString(inviteUri.toString()))
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Copied jam invite link.")
                }
            },
            onShareLink = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, inviteUri.toString())
                        },
                        null
                    )
                )
            },
            onDismiss = { showInviteQr = false }
        )
    }
}

@Composable
private fun JamInviteQrDialog(
    joinCode: String,
    inviteLink: String,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val qrImage = remember(inviteLink) { createJamInviteQrBitmap(inviteLink) }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text("Jam Invite") },
        buttons = {
            TextButton(onClick = onCopyLink) {
                Text("Copy Link")
            }
            TextButton(onClick = onShareLink) {
                Text("Share")
            }
        }
    ) {
        androidx.compose.foundation.Image(
            bitmap = qrImage,
            contentDescription = "Jam invite QR code",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(280.dp)
                .padding(bottom = 16.dp)
        )
        Text(
            text = "Scan this QR code or use the link below to join.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Join code: $joinCode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = inviteLink,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun JamProfileDialog(
    jamIdentity: JamIdentityState,
    onDismiss: () -> Unit,
    onSaveDisplayName: (String) -> Unit,
) {
    if (jamIdentity.isSignedIn) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                JamIdentityAvatar(
                    name = jamIdentity.resolvedName,
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            },
            title = { Text("Jam Profile") },
            text = {
                Text(
                    "You're joining jams as ${jamIdentity.resolvedName.orEmpty()} from your signed-in account."
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        )
        return
    }

    var pendingName by rememberSaveable(jamIdentity.savedName) {
        mutableStateOf(jamIdentity.savedName)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            JamIdentityAvatar(
                name = pendingName.ifBlank { jamIdentity.resolvedName.orEmpty() },
                modifier = Modifier.size(44.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        title = { Text("Jam Profile") },
        text = {
            Column {
                Text(
                    "Set the name you'll use when joining a jam."
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pendingName,
                    onValueChange = { newValue -> pendingName = newValue.take(20) },
                    label = { Text("Your Jam Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveDisplayName(pendingName)
                    onDismiss()
                },
                enabled = pendingName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

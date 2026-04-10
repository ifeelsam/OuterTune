package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dd3boh.outertune.constants.JamDisplayNameKey
import com.dd3boh.outertune.playback.JamState
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.JamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamScreen(
    viewModel: JamViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit
) {
    val jamState by viewModel.jamManager.jamState.collectAsState()
    val isHost by viewModel.jamManager.isHost.collectAsState()
    val joinCode by viewModel.jamManager.joinCode.collectAsState()
    val participants by viewModel.jamManager.participants.collectAsState()
    val error by viewModel.jamManager.error.collectAsState()
    val guestControlEnabled by viewModel.guestControlEnabled.collectAsState()
    
    val clipboardManager = LocalClipboardManager.current
    
    LaunchedEffect(error) {
        if (error != null) {
            // handle error toast
            viewModel.clearError()
        }
    }

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
                JamState.DISCONNECTED, JamState.ERROR -> JamSetupView(viewModel)
                JamState.CONNECTING -> ConnectingView()
                JamState.CONNECTED -> ConnectingView() // Wait for session state
                JamState.IN_SESSION -> JamSessionView(
                    isHost = isHost,
                    joinCode = joinCode,
                    participants = participants,
                    guestControlEnabled = guestControlEnabled,
                    onCopyCode = { code -> clipboardManager.setText(AnnotatedString(code)) },
                    onToggleGuestControl = viewModel::toggleGuestControl
                )
            }
        }
    }
}

@Composable
private fun JamSetupView(viewModel: JamViewModel) {
    val (displayName, onDisplayNameChange) = rememberPreference(
        key = JamDisplayNameKey,
        defaultValue = "Guest"
    )
    val joinCode by viewModel.joinCodeInput.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.Group,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
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
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )

        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Joining As") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(Modifier.height(32.dp))
        
        Button(
            onClick = { viewModel.startSession(displayName) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = displayName.isNotBlank()
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start a Jam Session")
        }

        Row(
            modifier = Modifier.padding(vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Divider(Modifier.weight(1f))
            Text("OR", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Divider(Modifier.weight(1f))
        }

        OutlinedTextField(
            value = joinCode,
            onValueChange = { viewModel.updateJoinCode(it.take(6)) },
            label = { Text("Join Code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = { viewModel.joinSession(displayName) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = displayName.isNotBlank() && joinCode.length == 6
        ) {
            Text("Join Session")
        }
    }
}

@Composable
private fun ConnectingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Connecting to Jam Server...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JamSessionView(
    isHost: Boolean,
    joinCode: String?,
    participants: List<com.dd3boh.outertune.playback.JamParticipant>,
    guestControlEnabled: Boolean,
    onCopyCode: (String) -> Unit,
    onToggleGuestControl: () -> Unit
) {
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
                ListItem(
                    headlineContent = { Text(p.displayName, fontWeight = if (p.isHost) FontWeight.Bold else FontWeight.Normal) },
                    supportingContent = { if (p.isHost) Text("Host") },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                p.displayName.take(1).uppercase(),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                )
            }
        }
    }
}

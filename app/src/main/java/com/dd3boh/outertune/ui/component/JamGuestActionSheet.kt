package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.playback.JamManager
import com.dd3boh.outertune.playback.PlayerConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamGuestActionSheet(
    jamManager: JamManager,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier
) {
    val pendingAction by jamManager.pendingInterceptedAction.collectAsState()
    val guestControlEnabled by jamManager.guestControlEnabled.collectAsState()

    if (pendingAction != null) {
        ModalBottomSheet(
            onDismissRequest = { jamManager.clearPendingIntercept() },
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Active Jam Session",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "You are listening as a guest. What would you like to do?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                // "Play Now" — only shown when the host has granted guest control
                if (guestControlEnabled) {
                    FilledTonalButton(
                        onClick = {
                            val action = pendingAction
                            jamManager.clearPendingIntercept()
                            if (action != null) {
                                playerConnection.playQueue(
                                    queue = action.queue,
                                    shouldResume = action.shouldResume,
                                    replace = action.replace,
                                    isRadio = action.isRadio,
                                    title = action.title,
                                    force = true
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play Now")
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // "Add to Jam Queue" — uses pre-fetched items stored in the action
                OutlinedButton(
                    onClick = {
                        val action = pendingAction
                        jamManager.clearPendingIntercept()
                        action?.items?.forEach { song ->
                            jamManager.addToJamQueue(
                                songId = song.id,
                                title = song.title,
                                artists = song.artists.joinToString { it.name },
                                thumbnailUrl = song.thumbnailUrl,
                                duration = song.duration
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add to Jam Queue")
                }

                Spacer(Modifier.height(12.dp))

                TextButton(
                    onClick = { jamManager.clearPendingIntercept() },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

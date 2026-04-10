package com.dd3boh.outertune.ui.screens.jam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.jam.rememberJamIdentityState
import com.dd3boh.outertune.ui.jam.rememberJamQrScannerLauncher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinJamScreen(
    navController: NavController,
    initialCode: String?,
    viewModel: JamViewModel = hiltViewModel()
) {
    var code by remember { mutableStateOf(initialCode ?: "") }
    val (jamIdentity, onSavedNameChange) = rememberJamIdentityState()
    var nickname by rememberSaveable(jamIdentity.savedName) { mutableStateOf(jamIdentity.savedName) }
    val snackbarHostState = LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()
    val scanJamQr = rememberJamQrScannerLauncher(
        onInviteCodeScanned = { scannedCode -> code = scannedCode },
        onInvalidQr = {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("That QR code is not a valid jam invite.")
            }
        }
    )

    val inSession by viewModel.inSession.collectAsState()
    val error by viewModel.error.collectAsState()
    val endMessage by viewModel.endMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val effectiveDisplayName = jamIdentity.resolveDisplayName(nickname)
    val actionInsets = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Bottom)
        .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))
    val statusMessage = endMessage ?: error

    // Auto navigate back or to Jam screen once connected
    LaunchedEffect(inSession) {
        if (inSession) {
            navController.popBackStack("jam", false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_jam_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.join_jam_desc),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                if (isLoading) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Connecting to jam...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (statusMessage != null) {
                    Text(
                        text = statusMessage,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    TextButton(
                        onClick = { viewModel.returnToSetup() },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("Reset and try again")
                    }
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.uppercase().filter { char -> char.isLetterOrDigit() }.take(6)
                    },
                    label = { Text(stringResource(R.string.jam_code_prompt)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = if (jamIdentity.requiresManualName) ImeAction.Next else ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                if (jamIdentity.requiresManualName) {
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(20) },
                        label = { Text(stringResource(R.string.jam_nickname_prompt)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (code.length == 6 && !effectiveDisplayName.isNullOrBlank()) {
                                    if (jamIdentity.shouldPersistInput(nickname)) {
                                        onSavedNameChange(effectiveDisplayName)
                                    }
                                    viewModel.joinJam(code, effectiveDisplayName)
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Use the profile icon on the jam screen to change your jam name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = scanJamQr,
                    modifier = Modifier.align(Alignment.End),
                    enabled = !isLoading
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan QR")
                }
            }

            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 4.dp
            ) {
                Button(
                    onClick = {
                        val displayName = effectiveDisplayName ?: return@Button
                        if (jamIdentity.shouldPersistInput(nickname)) {
                            onSavedNameChange(displayName)
                        }
                        viewModel.joinJam(code, displayName)
                    },
                    enabled = !isLoading && code.length == 6 && !effectiveDisplayName.isNullOrBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(actionInsets)
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .height(56.dp)
                ) {
                    Text(
                        text = if (isLoading) "Joining..." else stringResource(R.string.join),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

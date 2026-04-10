package com.dd3boh.outertune.ui.jam

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun rememberJamQrScannerLauncher(
    onInviteCodeScanned: (String) -> Unit,
    onInvalidQr: () -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents?.trim().orEmpty()
        if (content.isBlank()) return@rememberLauncherForActivityResult

        val code = parseJamInviteCode(content.toUri()) ?: normalizeJamCode(content)
        if (code != null) {
            onInviteCodeScanned(code)
        } else {
            onInvalidQr()
        }
    }

    return remember(launcher) {
        {
            launcher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Scan a jam QR code")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }
            )
        }
    }
}

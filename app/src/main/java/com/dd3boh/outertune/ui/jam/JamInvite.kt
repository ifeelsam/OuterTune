package com.dd3boh.outertune.ui.jam

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private const val JAM_INVITE_HOST = "outertune.app"

fun normalizeJamCode(value: String?): String? {
    val normalized = value
        ?.uppercase()
        ?.filter(Char::isLetterOrDigit)
        ?.take(6)
        .orEmpty()

    return normalized.takeIf { it.length == 6 }
}

fun buildJamInviteUri(joinCode: String): Uri {
    val normalizedCode = normalizeJamCode(joinCode)
        ?: error("Join code must be a 6-character jam code.")

    return Uri.Builder()
        .scheme("https")
        .authority(JAM_INVITE_HOST)
        .appendPath("jam")
        .appendPath("join")
        .appendPath(normalizedCode)
        .build()
}

fun parseJamInviteCode(uri: Uri): String? {
    if (uri.scheme != "https") return null
    if (uri.host !in setOf(JAM_INVITE_HOST, "www.$JAM_INVITE_HOST")) return null

    val segments = uri.pathSegments
    if (segments.size < 3 || segments[0] != "jam" || segments[1] != "join") return null

    return normalizeJamCode(segments[2])
}

fun createJamInviteQrBitmap(content: String, size: Int = 768): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)

    for (y in 0 until size) {
        val rowOffset = y * size
        for (x in 0 until size) {
            pixels[rowOffset + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

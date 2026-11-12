package com.vknxworks.mailctrl.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.str
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.theme.LocalCtrlColors
import java.io.File
import java.util.Calendar
import kotlin.math.abs

// ---------- Time and size ----------

/** "14:05" today, "Tue" this week, "12 Mar" this year, "12/03/2024" before. */
fun shortTime(context: Context, millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    return when {
        DateUtils.isToday(millis) -> DateFormat.getTimeFormat(context).format(millis)
        abs(now.timeInMillis - millis) < 6 * DateUtils.DAY_IN_MILLIS && millis < now.timeInMillis ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
    }
}

fun fullTime(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY)

fun fileSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** IMAP reports attachment sizes as sent (base64, about a third more): roughly what the file will be. */
fun attachmentSize(encoded: Long): String = if (encoded < 0) "" else fileSize(encoded * 3 / 4)

// ---------- Avatars ----------

private val avatarColors = listOf(
    Color(0xFFB48CFF), Color(0xFF7FC8F8), Color(0xFF8BD8C6), Color(0xFFF4C76B),
    Color(0xFFF2A0A0), Color(0xFFA6D68A), Color(0xFFF2B880), Color(0xFF9FB4F2),
)

/** A steady colour per sender (or account), so the same one always looks the same. */
fun avatarColor(key: String): Color = avatarColors[abs(key.lowercase().hashCode()) % avatarColors.size]

/** [color] as text or an icon: as is in dark, darkened in light so it reads on a pale background. */
@Composable
fun avatarInk(color: Color): Color = if (LocalCtrlColors.current.isDark) color else lerp(color, Color.Black, 0.5f)

/** The sender's initial on their colour. No pictures are fetched for senders. */
@Composable
fun Avatar(name: String, key: String, size: Dp = 44.dp) {
    val tone = avatarColor(key)
    val ink = avatarInk(tone)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tone.copy(alpha = if (LocalCtrlColors.current.isDark) 0.18f else 0.30f)),
        contentAlignment = Alignment.Center,
    ) {
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        if (letter != null) {
            Text(letter.toString(), color = ink, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), fontSize = (size.value * 0.40f).sp)
        } else {
            Icon(Icons.Filled.Person, null, Modifier.size(size * 0.55f), tint = ink)
        }
    }
}

// ---------- Files ----------

/** Opens a downloaded attachment in another app, for that one view. */
fun openFile(context: Context, file: File, mimeType: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        AppLock.expectReturn()
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure { Toast.makeText(context, str(R.string.attachment_open_failed), Toast.LENGTH_SHORT).show() }
}

/** Shares a downloaded attachment with another app. */
fun shareFile(context: Context, file: File, mimeType: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        AppLock.expectReturn()
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure { Toast.makeText(context, str(R.string.attachment_open_failed), Toast.LENGTH_SHORT).show() }
}

/** Opens a web link in the browser. */
fun openLink(context: Context, url: String) {
    runCatching {
        AppLock.expectReturn()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }.onFailure { Toast.makeText(context, str(R.string.link_open_failed), Toast.LENGTH_SHORT).show() }
}

package com.vknxworks.mailctrl.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.data.pgp.KeyOffer
import com.vknxworks.mailctrl.data.pgp.PgpView
import com.vknxworks.mailctrl.mail.Address
import com.vknxworks.mailctrl.pgp.PgpEngine
import com.vknxworks.mailctrl.pgp.SigCheck
import com.vknxworks.mailctrl.pgp.SigStatus
import com.vknxworks.mailctrl.ui.components.CtrlCardShape
import com.vknxworks.mailctrl.ui.pgp.shortFingerprint
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.Gutter

/** One OpenPGP status line on a message: an icon, what it means, and an action when there's one. */
@Composable
private fun Banner(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String?,
    action: String? = null,
    border: Color = CtrlColors.Outline,
    onAction: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = 4.dp)
            .clip(CtrlCardShape)
            .background(CtrlColors.Surface)
            .border(1.dp, border, CtrlCardShape)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) } else Spacer(Modifier.width(8.dp))
    }
}

/** Shown while an encrypted or signed message is being decrypted or checked. */
@Composable
fun PgpWorking() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.pgp_working), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
    }
}

/**
 * Whether the message was encrypted, who signed it and whether that checks out, and a key in it to import.
 * [onLookup]: look the signer's key up (when Web Key Directory lookups are on).
 */
@Composable
fun PgpBanners(
    view: PgpView,
    from: Address?,
    onUnlock: () -> Unit,
    onLookup: (() -> Unit)?,
) {
    if (view.encrypted) {
        val locked = view.locked
        when {
            locked != null -> Banner(
                Icons.Outlined.Lock,
                CtrlColors.AccentText,
                stringResource(R.string.pgp_encrypted),
                stringResource(R.string.pgp_encrypted_locked, locked.primaryUserId),
                action = stringResource(R.string.pgp_unlock),
                onAction = onUnlock,
            )
            view.error != null -> Banner(
                Icons.Outlined.Lock,
                CtrlColors.Error,
                stringResource(R.string.pgp_encrypted),
                view.error,
                border = CtrlColors.Error,
            )
            else -> Banner(Icons.Outlined.Lock, CtrlColors.AccentText, stringResource(R.string.pgp_encrypted), stringResource(R.string.pgp_encrypted_ok))
        }
    } else if (view.error != null) {
        Banner(Icons.Outlined.ErrorOutline, CtrlColors.Error, stringResource(R.string.pgp_problem), view.error, border = CtrlColors.Error)
    }
    view.signature?.let { SignatureBanner(it, from, onLookup) }
}

@Composable
private fun SignatureBanner(sig: SigCheck, from: Address?, onLookup: (() -> Unit)?) {
    when (sig.status) {
        SigStatus.VALID -> {
            val signer = sig.signerUserIds.firstOrNull().orEmpty()
            val fromEmail = from?.email?.trim()?.lowercase()
            // A good signature by someone else's key isn't proof the sender wrote it.
            val otherAddress = fromEmail != null && sig.signerEmails.isNotEmpty() && fromEmail !in sig.signerEmails
            val warn = otherAddress || sig.keyProblem
            val detail = listOfNotNull(
                sig.signerFingerprint?.let(::shortFingerprint),
                stringResource(R.string.pgp_sig_other_address, sig.signerEmails.joinToString(", ")).takeIf { otherAddress },
                stringResource(R.string.pgp_sig_key_problem).takeIf { sig.keyProblem },
            ).joinToString("\n")
            Banner(
                if (warn) Icons.Outlined.WarningAmber else Icons.Outlined.Verified,
                if (warn) CtrlColors.Error else CtrlColors.AccentText,
                stringResource(R.string.pgp_sig_valid, signer),
                detail,
                border = if (warn) CtrlColors.Error else CtrlColors.Outline,
            )
        }
        SigStatus.UNKNOWN_KEY -> Banner(
            Icons.AutoMirrored.Outlined.HelpOutline,
            CtrlColors.Muted,
            stringResource(R.string.pgp_sig_unknown),
            stringResource(R.string.pgp_sig_unknown_body, PgpEngine.keyIdText(sig.keyId)),
            action = if (onLookup != null) stringResource(R.string.pgp_wkd_look_up) else null,
            onAction = { onLookup?.invoke() },
        )
        SigStatus.BAD -> Banner(
            Icons.Outlined.ErrorOutline,
            CtrlColors.Error,
            stringResource(R.string.pgp_sig_bad),
            stringResource(R.string.pgp_sig_bad_body),
            border = CtrlColors.Error,
        )
    }
}

/** The sender's key, found in their Autocrypt header, offered for import. */
@Composable
fun KeyOfferBanner(offer: KeyOffer, onImport: () -> Unit) {
    Banner(
        Icons.Outlined.Key,
        CtrlColors.AccentText,
        stringResource(R.string.pgp_offer_title),
        listOfNotNull(
            offer.info.primaryUserId,
            shortFingerprint(offer.info.fingerprint),
            stringResource(R.string.pgp_offer_replaces).takeIf { offer.replaces },
        ).joinToString("\n"),
        action = stringResource(R.string.pgp_import),
        onAction = onImport,
    )
}

/** Whether an attachment may hold an OpenPGP key (by its type or file name), so it gets an Import key button. */
fun looksLikeKey(name: String, mimeType: String): Boolean {
    val n = name.lowercase()
    if (n == "signature.asc" || n == "encrypted.asc") return false
    return mimeType.equals("application/pgp-keys", ignoreCase = true) ||
        n.endsWith(".asc") || n.endsWith(".key") || n.endsWith(".pub") || n.endsWith(".gpg") || n.endsWith(".pgp")
}

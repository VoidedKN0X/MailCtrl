package com.vknxworks.mailctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.Gutter
import com.vknxworks.mailctrl.ui.theme.LocalCtrlColors

// ---------- Top bars ----------

/** Top bar for pushed screens: the square back button and a title. */
@Composable
fun ScreenTopBar(
    title: String,
    showBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalNavigator.current
    CtrlSubTopBar(title, onBack = if (showBack) ({ nav.back() }) else null, actions = actions)
}

/**
 * The main tabs: the TEXT/CTRL wordmark with the profile picture, then the tab's big title.
 * [trailing] actions sit next to the profile picture.
 */
@Composable
fun TabTitle(
    title: String,
    modifier: Modifier = Modifier,
    showProfile: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        CtrlTopBar(showProfile = showProfile, actions = trailing)
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = CtrlColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 12.dp),
        )
    }
}

// ---------- Grouped lists (settings, profile) ----------

/** A card of related rows with 1dp lines between them, under an optional mono section label. */
@Composable
fun Group(
    title: String? = null,
    modifier: Modifier = Modifier,
    footer: String? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = if (padded) Gutter else 0.dp, vertical = 8.dp)) {
        if (title != null) SectionLabel(title, Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CtrlCardShape)
                .background(CtrlColors.Surface)
                .border(1.dp, CtrlColors.Outline, CtrlCardShape),
        ) {
            CompositionLocalProvider(LocalInGroup provides true) { Column(content = content) }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Muted,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

/** True inside a [Group]: rows there draw a 1dp line on top (the first one lands on the card's own border). */
private val LocalInGroup = compositionLocalOf { false }

@Composable
private fun GroupRowFrame(
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val divided = LocalInGroup.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (divided) Modifier.topDivider(0.dp, CtrlColors.Outline) else Modifier)
            .heightIn(min = 62.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun GroupRowContent(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconTint: Color,
    titleColor: Color,
    trailing: (@Composable () -> Unit)?,
    showChevron: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(CtrlColors.Raised),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(20.dp), tint = iconTint) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CtrlColors.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (showChevron) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = CtrlColors.Muted)
        }
    }
}

@Composable
fun GroupRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = CtrlColors.AccentText,
    titleColor: Color = CtrlColors.Text,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    GroupRowFrame(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        GroupRowContent(title, subtitle, icon, iconTint, titleColor, trailing, showChevron)
    }
}

/** A row with a [CtrlSwitch]; the whole row toggles it and reads as one switch to TalkBack. */
@Composable
fun GroupSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    GroupRowFrame(Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        GroupRowContent(
            title,
            subtitle,
            icon,
            CtrlColors.AccentText,
            if (enabled) CtrlColors.Text else CtrlColors.Inactive,
            trailing = { CtrlSwitch(checked, onCheckedChange = null, enabled = enabled) },
            showChevron = false,
        )
    }
}

/** A row showing the current value; tapping opens a sheet to pick another. */
@Composable
fun <T> GroupChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    icon: ImageVector? = null,
    fallbackLabel: String = "",
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: fallbackLabel
    GroupRow(title, current, icon, showChevron = true, onClick = { open = true })
    if (open) {
        ChoiceSheet(title, options, selected, onDismiss = { open = false }) { onPick(it); open = false }
    }
}

@Composable
fun <T> ChoiceSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = CtrlSheetShape,
        containerColor = CtrlColors.Surface,
        contentColor = CtrlColors.Text,
        scrimColor = Color.Black.copy(alpha = if (LocalCtrlColors.current.isDark) 0.6f else 0.32f),
    ) {
        SectionLabel(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            // Keyed by position: labels can repeat (two chats with the same name), and values can be null.
            itemsIndexed(options, key = { i, _ -> i }) { i, (value, label) ->
                val isSel = value == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i > 0) Modifier.topDivider(24.dp) else Modifier)
                        .clickable { onPick(value) }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSel) CtrlColors.AccentText else CtrlColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSel) Icon(Icons.Outlined.Check, stringResource(R.string.selected), Modifier.size(20.dp), tint = CtrlColors.AccentText)
                }
            }
        }
    }
}

// ---------- Buttons ----------

/** The one main action on a screen: accent fill, dark text. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = CtrlColors.Accent,
    content: Color = CtrlColors.OnAccent,
) {
    CtrlButton(text, onClick, modifier, icon, enabled, container, content)
}

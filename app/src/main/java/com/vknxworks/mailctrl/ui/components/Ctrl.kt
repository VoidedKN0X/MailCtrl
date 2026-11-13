package com.vknxworks.mailctrl.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.nav.YouRoute
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter

// Ctrl / Signal building blocks shared by every screen.

/**
 * Space taken at the bottom of the screen by the floating tab bar. Scrolling screens add it to their
 * bottom content padding, so their last items can scroll clear of the bar.
 */
val LocalBottomClearance = compositionLocalOf { 0.dp }

// ---------- Shapes ----------

val CtrlCardShape = RoundedCornerShape(22.dp)
val CtrlHeroShape = RoundedCornerShape(26.dp)
val CtrlControlShape = RoundedCornerShape(14.dp)
val CtrlButtonShape = RoundedCornerShape(18.dp)
val CtrlFieldShape = RoundedCornerShape(16.dp)
val CtrlChipShape = RoundedCornerShape(10.dp)
val CtrlDialogShape = RoundedCornerShape(26.dp)
val CtrlSheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

// ---------- Text ----------

/** Mono, upper-case section header in the muted colour. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = CtrlColors.Muted) {
    Text(text.uppercase(), style = CtrlType.Label, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "MAIL/CTRL", with the slash in the accent colour. */
@Composable
fun CtrlWordmark(name: String = "MAIL", modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append(name)
            withStyle(SpanStyle(color = CtrlColors.AccentText)) { append("/") }
            append("CTRL")
        },
        style = CtrlType.Wordmark,
        color = CtrlColors.Text,
        modifier = modifier,
    )
}

/** An 8dp square status light. */
@Composable
fun StatusDot(color: Color = CtrlColors.Accent, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

// ---------- Top bars ----------

/** A 44dp square button (back, close, profile) in the Ctrl style: surface fill and a hairline border. */
@Composable
fun CtrlSquareButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CtrlControlShape)
            .background(CtrlColors.Surface)
            .border(1.dp, CtrlColors.ControlOutline, CtrlControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = if (enabled) CtrlColors.Text else CtrlColors.Inactive)
    }
}

/** The back button on pushed screens. */
@Composable
fun CtrlBackButton(onClick: () -> Unit, contentDescription: String = stringResource(R.string.back)) {
    CtrlSquareButton(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription, onClick)
}

/**
 * The profile picture at the end of the main top bar. Tapping it opens the You page (the only way into
 * settings). A small accent square shows while instant notifications through ntfy are on.
 */
@Composable
fun CtrlProfileButton() {
    val nav = LocalNavigator.current
    val c = LocalContext.current.container
    val s by c.settings.settings.collectAsState()
    val push = s.notifyEnabled && s.pushEnabled && s.pushEndpoint != null
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CtrlSquareButton(Icons.Outlined.Person, stringResource(R.string.you_title), onClick = { nav.navigate(YouRoute) })
        if (push) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-1).dp, y = (-1).dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CtrlColors.Background)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CtrlColors.Accent),
            )
        }
    }
}

/** Main top bar: the wordmark on the left, actions and the profile picture on the right. No colour fill. */
@Composable
fun CtrlTopBar(
    modifier: Modifier = Modifier,
    showProfile: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Gutter, end = Gutter - 2.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtrlWordmark(modifier = Modifier.weight(1f))
        actions()
        if (showProfile) {
            Spacer(Modifier.width(4.dp))
            CtrlProfileButton()
        }
    }
}

/** Top bar for pushed screens: the square back button and a title. Handles the status bar itself. */
@Composable
fun CtrlSubTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 68.dp).padding(start = Gutter - 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CtrlBackButton(onBack)
            Spacer(Modifier.width(14.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = CtrlColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

// ---------- Cards ----------

/** Secondary card: surface fill, hairline border, 22dp corners. */
@Composable
fun CtrlCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(CtrlCardShape).background(CtrlColors.Surface).border(1.dp, CtrlColors.Outline, CtrlCardShape).padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** The one most important thing on a main screen: accent fill, a mono label row and a giant number. */
@Composable
fun CtrlHeroCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    unit: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.clip(CtrlHeroShape).background(CtrlColors.Accent).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = CtrlType.Label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = CtrlColors.OnAccent, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailingLabel != null) {
                Text(trailingLabel.uppercase(), style = CtrlType.Status.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = CtrlColors.OnAccent, maxLines = 1)
            }
        }
        Text(
            buildAnnotatedString {
                append(value)
                if (unit != null) withStyle(SpanStyle(fontSize = CtrlType.Readout.fontSize * 0.44f)) { append(unit) }
            },
            style = CtrlType.Readout,
            color = CtrlColors.OnAccent,
        )
        content()
    }
}

/** A list row's 1dp top divider, inset by the page margin. */
@Composable
fun Modifier.topDivider(inset: Dp = Gutter, color: Color = CtrlColors.Divider): Modifier = drawBehind {
    val x = inset.toPx()
    drawLine(color, Offset(x, 0f), Offset(size.width - x, 0f), strokeWidth = 1.dp.toPx())
}

// ---------- Controls ----------

/**
 * Rounded-rect switch: 52x32dp track (10dp corners) with a 24dp knob (7dp corners).
 * On: accent track, dark knob. Off: dark track, grey knob.
 */
@Composable
fun CtrlSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val knobX by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    val track by animateColorAsState(if (checked) CtrlColors.Accent else CtrlColors.SwitchOff, label = "track")
    val knob by animateColorAsState(if (checked) CtrlColors.OnAccent else CtrlColors.Inactive, label = "knob-colour")
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .background(track)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else {
                    Modifier
                },
            )
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = knobX).size(24.dp).clip(RoundedCornerShape(7.dp)).background(knob))
    }
}

/** Primary button: accent fill, dark text, 56dp tall. */
@Composable
fun CtrlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = CtrlColors.Accent,
    content: Color = CtrlColors.OnAccent,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = CtrlButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = CtrlColors.Raised,
            disabledContentColor = CtrlColors.Inactive,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Secondary button: surface fill with a hairline border. */
@Composable
fun CtrlSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CtrlControlShape,
        border = BorderStroke(1.dp, CtrlColors.ControlOutline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CtrlColors.Surface, contentColor = CtrlColors.Text),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Text field colours: surface fill, hairline border, accent border and cursor when focused. Use with [CtrlFieldShape]. */
@Composable
fun ctrlFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CtrlColors.Text,
    unfocusedTextColor = CtrlColors.Text,
    focusedContainerColor = CtrlColors.Surface,
    unfocusedContainerColor = CtrlColors.Surface,
    disabledContainerColor = CtrlColors.Surface,
    errorContainerColor = CtrlColors.Surface,
    cursorColor = CtrlColors.AccentText,
    focusedBorderColor = CtrlColors.AccentText,
    unfocusedBorderColor = CtrlColors.ControlOutline,
    disabledBorderColor = CtrlColors.Outline,
    errorBorderColor = CtrlColors.Error,
    focusedLeadingIconColor = CtrlColors.Muted,
    unfocusedLeadingIconColor = CtrlColors.Muted,
    focusedTrailingIconColor = CtrlColors.Muted,
    unfocusedTrailingIconColor = CtrlColors.Muted,
    focusedLabelColor = CtrlColors.AccentText,
    unfocusedLabelColor = CtrlColors.Muted,
    focusedPlaceholderColor = CtrlColors.Muted,
    unfocusedPlaceholderColor = CtrlColors.Muted,
)

/** Filter chip in the Ctrl style: 10dp corners, accent fill when selected. */
@Composable
fun CtrlChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    /** A small colour square before the label (an account's colour). */
    dot: Color? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        leadingIcon = when {
            leadingIcon != null -> { { Icon(leadingIcon, null, Modifier.size(18.dp)) } }
            dot != null -> { { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(dot)) } }
            else -> null
        },
        trailingIcon = trailingIcon,
        shape = CtrlChipShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = CtrlColors.Surface,
            labelColor = CtrlColors.Text,
            selectedContainerColor = CtrlColors.Accent,
            selectedLabelColor = CtrlColors.OnAccent,
            selectedLeadingIconColor = CtrlColors.OnAccent,
            selectedTrailingIconColor = CtrlColors.OnAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = CtrlColors.ControlOutline,
            selectedBorderColor = CtrlColors.Accent,
        ),
    )
}

// ---------- Dialogs ----------

/** Alert dialog in the Ctrl style: surface fill, 26dp corners, hairline border. */
@Composable
fun CtrlAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        Surface(shape = CtrlDialogShape, color = CtrlColors.Surface, border = BorderStroke(1.dp, CtrlColors.Outline)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
                if (title != null) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                        CompositionLocalProvider(LocalContentColor provides CtrlColors.Text) {
                            Box(Modifier.padding(bottom = 12.dp)) { title() }
                        }
                    }
                }
                if (text != null) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        CompositionLocalProvider(LocalContentColor provides CtrlColors.Muted) {
                            Box(Modifier.weight(1f, fill = false).padding(bottom = 16.dp)) { text() }
                        }
                    }
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

// ---------- Bottom navigation ----------

/** One tab of [CtrlBottomBar]. */
data class CtrlNavItem(@StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/**
 * Floating bottom bar: 64dp tall, 22dp corners, translucent surface with a hairline border.
 * The selected tab is an accent block with its icon and label; the others show only their icon.
 */
@Composable
fun CtrlBottomBar(
    items: List<CtrlNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val shape: Shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(CtrlColors.Surface.copy(alpha = 0.92f))
            .border(1.dp, CtrlColors.ControlOutline, shape)
            .padding(horizontal = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            val label = stringResource(item.label)
            Row(
                Modifier
                    .animateContentSize()
                    .height(48.dp)
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) CtrlColors.Accent else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = {
                            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        },
                    )
                    .padding(horizontal = if (selected) 18.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (selected) item.selectedIcon else item.icon,
                    contentDescription = if (selected) null else label,
                    tint = if (selected) CtrlColors.OnAccent else CtrlColors.Muted,
                    modifier = Modifier.size(20.dp),
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = CtrlColors.OnAccent, maxLines = 1)
                }
            }
        }
    }
}

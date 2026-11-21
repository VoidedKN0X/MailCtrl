package com.vknxworks.mailctrl.ui.components

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.data.local.LaterTimes
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Picks a moment in the future, for "Send later" and "Snooze": a few ready-made times, or a date and time of
 * your own. [onPick] gets the time in epoch millis.
 */
@Composable
fun WhenPicker(title: String, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    val now = remember { ZonedDateTime.now() }
    val presets = remember(now) { LaterTimes.presets(now) }
    // 0: the ready-made times, 1: the date, 2: the time of day.
    var step by remember { mutableStateOf(0) }
    var day by remember { mutableStateOf<LocalDate?>(null) }

    when (step) {
        0 -> ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = CtrlSheetShape,
            containerColor = CtrlColors.Surface,
            contentColor = CtrlColors.Text,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
                SectionLabel(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                presets.forEach { (preset, time) ->
                    val label = stringResource(
                        when (preset) {
                            LaterTimes.Preset.LATER_TODAY -> R.string.when_later_today
                            LaterTimes.Preset.THIS_EVENING -> R.string.when_this_evening
                            LaterTimes.Preset.TOMORROW_MORNING -> R.string.when_tomorrow
                            LaterTimes.Preset.NEXT_WEEK -> R.string.when_next_week
                        },
                    )
                    val millis = time.toInstant().toEpochMilli()
                    ActionItem(Icons.Outlined.Schedule, label + "  ·  " + shortWhen(context, millis)) { onPick(millis) }
                }
                ActionItem(Icons.Outlined.EditCalendar, stringResource(R.string.when_pick)) { step = 1 }
            }
        }
        1 -> {
            val today = remember { now.toLocalDate() }
            val todayUtc = remember { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            val state = rememberDatePickerState(
                initialSelectedDateMillis = todayUtc,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayUtc
                    override fun isSelectableYear(year: Int): Boolean = year >= today.year
                },
            )
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        onClick = {
                            // The picker gives the chosen day as midnight UTC.
                            day = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                            if (day != null) step = 2
                        },
                        enabled = state.selectedDateMillis != null,
                    ) { Text(stringResource(R.string.when_next)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            ) {
                DatePicker(state = state)
            }
        }
        else -> {
            val state = rememberTimePickerState(
                initialHour = (now.hour + 1) % 24,
                initialMinute = 0,
                is24Hour = DateFormat.is24HourFormat(context),
            )
            val soon = stringResource(R.string.when_past)
            CtrlAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = { TimeInput(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        val chosen = day?.atTime(state.hour, state.minute)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                        if (chosen == null || chosen < System.currentTimeMillis() + 60_000L) {
                            Toast.makeText(context, soon, Toast.LENGTH_LONG).show()
                        } else {
                            onPick(chosen)
                        }
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** A day and time, short: "Mon 23 Nov, 08:00". */
fun shortWhen(context: android.content.Context, millis: Long): String =
    android.text.format.DateUtils.formatDateTime(
        context,
        millis,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
            android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
    )

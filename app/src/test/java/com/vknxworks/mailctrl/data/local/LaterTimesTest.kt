package com.vknxworks.mailctrl.data.local

import com.vknxworks.mailctrl.data.local.LaterTimes.Preset
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LaterTimesTest {
    private val zone = ZoneId.of("Europe/Brussels")

    @Test
    fun aSaturdayMorning() {
        // Saturday 21 November 2026, 09:30.
        val now = ZonedDateTime.of(2026, 11, 21, 9, 30, 15, 0, zone)
        val presets = LaterTimes.presets(now)
        assertEquals(listOf(Preset.LATER_TODAY, Preset.THIS_EVENING, Preset.TOMORROW_MORNING, Preset.NEXT_WEEK), presets.map { it.first })
        assertEquals(ZonedDateTime.of(2026, 11, 21, 12, 30, 0, 0, zone), presets[0].second)
        assertEquals(ZonedDateTime.of(2026, 11, 21, 18, 0, 0, 0, zone), presets[1].second)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 8, 0, 0, 0, zone), presets[2].second)
        // Next Monday.
        assertEquals(ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, zone), presets[3].second)
    }

    @Test
    fun lateAtNightOnlyTomorrowAndNextWeekAreOffered() {
        val now = ZonedDateTime.of(2026, 11, 23, 22, 10, 0, 0, zone)
        val presets = LaterTimes.presets(now)
        assertEquals(listOf(Preset.TOMORROW_MORNING, Preset.NEXT_WEEK), presets.map { it.first })
        // On a Monday, "next week" is the Monday after.
        assertEquals(ZonedDateTime.of(2026, 11, 30, 8, 0, 0, 0, zone), presets[1].second)
    }
}

package dev.leash.personal.core

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaFormatTest {
    private val utc = ZoneId.of("UTC")
    private val us = Locale.US
    private fun plain(s: String) = s.replace(Regex("\\p{Z}"), " ")

    @Test fun tintFollowsWhatIsLeft() {
        assertEquals(QuotaFormat.Level.Unknown, QuotaFormat.level(null))
        assertEquals(QuotaFormat.Level.Danger, QuotaFormat.level(0.149))
        assertEquals(QuotaFormat.Level.Attention, QuotaFormat.level(0.15))
        assertEquals(QuotaFormat.Level.Attention, QuotaFormat.level(0.399))
        assertEquals(QuotaFormat.Level.Normal, QuotaFormat.level(0.4))
        assertEquals(true, QuotaFormat.isTight(0.39))
        assertEquals(false, QuotaFormat.isTight(0.4))
        assertEquals(false, QuotaFormat.isTight(null))
    }

    @Test fun percentRoundsAndClamps() {
        assertEquals(62, QuotaFormat.percent(0.615))
        assertEquals(100, QuotaFormat.percent(1.2))
        assertEquals(0, QuotaFormat.percent(0.001))
    }

    @Test fun aResetTodayIsJustItsTimeAndLaterOnesNameTheWeekday() {
        val now = java.time.ZonedDateTime.of(2026, 11, 26, 9, 0, 0, 0, utc).toInstant().toEpochMilli()
        val today = QuotaFormat.reset(now + 2 * 3_600_000L + 10 * 60_000L, now, utc, us)!!
        assertEquals("11:10 AM", plain(today.day))
        assertEquals(listOf(DurationPart(DurationUnit.Hour, 2), DurationPart(DurationUnit.Minute, 10)), today.left)
        val later = QuotaFormat.reset(now + 3 * 86_400_000L + 4 * 3_600_000L, now, utc, us)!!
        assertEquals("Sun 1:00 PM", plain(later.day))
        assertEquals(listOf(DurationPart(DurationUnit.Day, 3), DurationPart(DurationUnit.Hour, 4)), later.left)
        // the weekday follows the locale it is given
        assertTrue(QuotaFormat.reset(now + 3 * 86_400_000L + 4 * 3_600_000L, now, utc, java.util.Locale.SIMPLIFIED_CHINESE)!!.day.startsWith("周日"))
        assertEquals(listOf(DurationPart(DurationUnit.Day, 6), DurationPart(DurationUnit.Hour, 22)), QuotaFormat.left(166 * 3600.0 + 17 * 60))
        // whole days leave the zero hours out
        assertEquals(listOf(DurationPart(DurationUnit.Day, 3)), QuotaFormat.left(72 * 3600.0))
        assertEquals(listOf(DurationPart(DurationUnit.Hour, 47), DurationPart(DurationUnit.Minute, 59)), QuotaFormat.left(47 * 3600.0 + 59 * 60))
    }

    @Test fun aWindowThatHasResetSaysSo() {
        assertNull(QuotaFormat.reset(1_000, 2_000, utc, us))
        assertNull(QuotaFormat.reset(2_000, 2_000, utc, us))
    }

    @Test fun agoIsCoarse() {
        val now = 10_000_000_000L
        assertEquals(QuotaFormat.Ago.JustNow, QuotaFormat.ago(now - 30_000, now))
        assertEquals(QuotaFormat.Ago.Minutes(5), QuotaFormat.ago(now - 5 * 60_000, now))
        assertEquals(QuotaFormat.Ago.Hours(3), QuotaFormat.ago(now - 3 * 3_600_000, now))
        assertEquals(QuotaFormat.Ago.Days(2), QuotaFormat.ago(now - 2 * 86_400_000, now))
    }

    @Test fun providerHints() {
        assertEquals("claude", QuotaFormat.poolHint("anthropic"))
        assertEquals("gpt", QuotaFormat.poolHint("openai"))
        assertEquals("gemini", QuotaFormat.poolHint("gemini"))
        assertEquals("gemini", QuotaFormat.poolHint("other"))
    }
}

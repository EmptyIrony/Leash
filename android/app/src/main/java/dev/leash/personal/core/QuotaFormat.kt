package dev.leash.personal.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How quota is read out (ios QuotaViews.swift): the tint by what is left, "62% left", and when a window resets. */
object QuotaFormat {
    enum class Level { Unknown, Danger, Attention, Normal }

    /** Under 15% left is danger, under 40% attention, unknown (no data) is its own look. */
    fun level(fraction: Double?): Level = when {
        fraction == null -> Level.Unknown
        fraction < 0.15 -> Level.Danger
        fraction < 0.40 -> Level.Attention
        else -> Level.Normal
    }

    /** True when a ring or bar should be tinted (the tightest 40%); above that it keeps its normal colour. */
    fun isTight(fraction: Double?): Boolean = fraction != null && fraction < 0.4

    fun percent(fraction: Double): Int = Math.round(fraction * 100).toInt().coerceIn(0, 100)

    /** The day part ("5:30 PM", or "Tue 5:30 PM" past today) and the time left of a window's reset. */
    class Reset(val day: String, val left: List<DurationPart>)

    /** Null when the window has already reset ("Resetting now"). */
    fun reset(resetAtMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): Reset? {
        val seconds = (resetAtMs - nowMs) / 1000.0
        if (seconds <= 0) return null
        val at = Instant.ofEpochMilli(resetAtMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(at)
        val day = if (at.toLocalDate() == today) time else DateTimeFormatter.ofPattern("EEE", locale).format(at) + " " + time
        return Reset(day, left(seconds))
    }

    /** "2h 10m" up to two days out, then "6d 22h" (a weekly window would otherwise read "166h 17m"). */
    fun left(seconds: Double): List<DurationPart> {
        val hours = (seconds / 3600).toLong()
        if (hours < 48) return durationParts(seconds)
        return listOfNotNull(DurationPart(DurationUnit.Day, hours / 24), (hours % 24).takeIf { it > 0 }?.let { DurationPart(DurationUnit.Hour, it) })
    }

    /** How long ago the numbers were read, for the UI to word ("just now", "5 min ago", "3 h ago", "2 d ago"). */
    sealed interface Ago {
        data object JustNow : Ago
        data class Minutes(val count: Int) : Ago
        data class Hours(val count: Int) : Ago
        data class Days(val count: Int) : Ago
    }

    fun ago(fetchedAtMs: Long, nowMs: Long): Ago {
        val minutes = (nowMs - fetchedAtMs) / 60_000
        return when {
            minutes < 1 -> Ago.JustNow
            minutes < 60 -> Ago.Minutes(minutes.toInt())
            minutes < 24 * 60 -> Ago.Hours((minutes / 60).toInt())
            else -> Ago.Days((minutes / (24 * 60)).toInt())
        }
    }

    /** The agy quota pool family a provider's models draw from, for the model picker's group headers. */
    fun poolHint(provider: String): String = when (provider) { "anthropic" -> "claude"; "openai" -> "gpt"; else -> "gemini" }
}

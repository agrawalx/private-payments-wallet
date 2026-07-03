package com.privatepayments.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared formatting helpers — small, pure functions pulled out of the screens
 * that were each hand-rolling the same stroops→XLM / address-truncation /
 * timestamp-label logic.
 */

/** Stroops → "1,234.5678" (no unit suffix, comma-grouped). */
internal fun xlm(stroops: Long): String = "%,.4f".format(stroops / 1e7)

/** Stroops → "−1.5000" / "+1.5000" (U+2212 minus, matching the Horizon client's sign). */
internal fun signedXlm(stroops: Long, negative: Boolean): String =
    "%s%.4f".format(if (negative) "−" else "+", stroops / 1e7)

/** "G...ABCD" style truncation. Returns `a` unchanged if it's already short. */
internal fun shortAddress(a: String, head: Int = 6, tail: Int = 4): String =
    if (a.length <= head + tail) a else "${a.take(head)}…${a.takeLast(tail)}"

/** Tx-hash truncation (wider than [shortAddress] — used for the Success screen). */
internal fun shortHash(h: String): String =
    if (h.length <= 20) h else "${h.take(12)}…${h.takeLast(8)}"

/** "YYYY-MM-DD" → "Today" / "Yesterday" / "Mon, Jan 5". Falls back to the raw string. */
internal fun dayLabel(iso: String): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}

/** First 10 chars of an ISO instant/date — the "YYYY-MM-DD" part. */
internal fun dateOf(iso: String): String = if (iso.isBlank()) iso else iso.take(10)

/** ISO instant → "HH:mm" (device-local time). Empty string if it doesn't parse. */
internal fun timeLabel(iso: String): String =
    runCatching {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
    }.getOrDefault("")

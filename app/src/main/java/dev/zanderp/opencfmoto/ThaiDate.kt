// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

/**
 * User-visible dates with Buddhist-era years (พ.ศ. = ค.ศ. + 543) when the app runs in Thai.
 * Other languages are untouched. The year comes from a Gregorian calendar in the formatter's
 * time zone, so 31 Dec / 1 Jan flip exactly when the shown day does.
 */
object ThaiDate {
    fun isThai(locale: Locale = Locale.getDefault()): Boolean = locale.language == "th"

    /** A bare year as shown to the rider (e.g. "year in review 2569"). */
    fun year(gregorianYear: Int, locale: Locale = Locale.getDefault()): Int =
        if (isThai(locale)) gregorianYear + 543 else gregorianYear

    fun format(pattern: String, millis: Long, locale: Locale = Locale.getDefault()): String =
        format(SimpleDateFormat(pattern, locale), millis, locale)

    /** Drop-in for `fmt.format(Date(millis))`; swaps the year (and era) for พ.ศ. in Thai. */
    fun format(fmt: DateFormat, millis: Long, locale: Locale = Locale.getDefault()): String {
        val sdf = fmt as? SimpleDateFormat
        if (!isThai(locale) || sdf == null) return fmt.format(Date(millis))
        val gy = GregorianCalendar(sdf.timeZone).apply { timeInMillis = millis }.get(Calendar.YEAR)
        return SimpleDateFormat(bePattern(sdf.toPattern(), gy + 543), locale)
            .apply { timeZone = sdf.timeZone }
            .format(Date(millis))
    }

    /**
     * Replace unquoted y-runs with [beYear] ("yy" → last two digits) and G-runs with "พ.ศ.".
     * Digits, Thai letters and dots aren't pattern letters, so they go in unquoted.
     */
    fun bePattern(pattern: String, beYear: Int): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') { quoted = !quoted; out.append(c); i++; continue }
            if (quoted || (c != 'y' && c != 'G')) { out.append(c); i++; continue }
            var j = i
            while (j < pattern.length && pattern[j] == c) j++
            val text = when {
                c == 'G' -> "พ.ศ."
                j - i == 2 -> String.format(Locale.ROOT, "%02d", beYear % 100)
                else -> beYear.toString()
            }
            out.append(text)
            i = j
        }
        return out.toString()
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

class ThaiDateTest {
    private val th = Locale("th", "TH")
    private val bkk = TimeZone.getTimeZone("Asia/Bangkok")

    private fun at(y: Int, m: Int, d: Int, hh: Int, mm: Int): Long =
        GregorianCalendar(bkk).apply { clear(); set(y, m, d, hh, mm) }.timeInMillis

    private fun fmt(pattern: String, locale: Locale) = SimpleDateFormat(pattern, locale).apply { timeZone = bkk }

    @Test
    fun buddhistYearFlipsExactlyAtLocalMidnight() {
        val nye = at(2025, Calendar.DECEMBER, 31, 23, 59)
        val ny = at(2026, Calendar.JANUARY, 1, 0, 1)
        assertEquals("31/12/2568", ThaiDate.format(fmt("d/M/yyyy", th), nye, th))
        assertEquals("1/1/2569", ThaiDate.format(fmt("d/M/yyyy", th), ny, th))
        assertEquals("31/12/68", ThaiDate.format(fmt("d/M/yy", th), nye, th))
        // Same instant is still 2025 in UTC: the year follows the formatter's zone, not UTC.
        val utc = SimpleDateFormat("d/M/yyyy", th).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("1/1/2569", ThaiDate.format(fmt("d/M/yyyy", th), at(2026, Calendar.JANUARY, 1, 3, 0), th))
        assertEquals("31/12/2568", ThaiDate.format(utc, at(2026, Calendar.JANUARY, 1, 3, 0), th))
    }

    @Test
    fun otherLanguagesKeepGregorian() {
        val nye = at(2025, Calendar.DECEMBER, 31, 23, 59)
        assertEquals("31/12/2025", ThaiDate.format(fmt("d/M/yyyy", Locale.US), nye, Locale.US))
        assertEquals(2026, ThaiDate.year(2026, Locale.US))
        assertEquals(2569, ThaiDate.year(2026, th))
    }

    @Test
    fun patternRewriteHandlesEraAndQuotes() {
        assertEquals("EEEEที่ d MMMM พ.ศ. 2569", ThaiDate.bePattern("EEEEที่ d MMMM G y", 2569))
        assertEquals("d MMM 69", ThaiDate.bePattern("d MMM yy", 2569))
        assertEquals("'year y' 2569", ThaiDate.bePattern("'year y' yyyy", 2569))
    }
}

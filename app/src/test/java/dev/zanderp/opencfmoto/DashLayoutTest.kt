// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import dev.zanderp.opencfmoto.DashLayout.Widget
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rider's dash screen: stored layout round-trip and the widget formatters. */
class DashLayoutTest {
    @Test fun nothing_saved_gives_default() {
        assertEquals(DashLayout.DEFAULT, DashLayout.parse(null, null))
    }

    @Test fun round_trip_keeps_order() {
        val l = DashLayout.Layout(listOf(Widget.CLOCK, Widget.SPEED, Widget.PHONE), DashLayout.ACCENTS[2])
        assertEquals(l, DashLayout.parse(DashLayout.format(l), l.accent))
    }

    @Test fun unknown_duplicate_and_excess_widgets_are_dropped() {
        val l = DashLayout.parse("SPEED,FUEL_LEVEL,SPEED,CLOCK,TRIP,RIDE_TIME,HEADING,ALTITUDE,MAX_SPEED,PHONE", null)
        assertEquals(DashLayout.MAX_WIDGETS, l.widgets.size)
        assertEquals(listOf(Widget.SPEED, Widget.CLOCK), l.widgets.take(2))
        assertEquals(DashLayout.DEFAULT.widgets, DashLayout.parse("NOT_A_WIDGET", null).widgets)
    }

    @Test fun compass_points() {
        assertEquals("N 0°", CustomDashUi.compass(0f))
        assertEquals("N 0°", CustomDashUi.compass(360f))
        assertEquals("NE 48°", CustomDashUi.compass(48f))
        assertEquals("NW 330°", CustomDashUi.compass(330f))
        assertEquals("N 338°", CustomDashUi.compass(338f))  // 22° from N, 23° from NW
        assertEquals("W 270°", CustomDashUi.compass(-90f))
    }

    @Test fun ride_time() {
        assertEquals("0:38", CustomDashUi.hm(38 * 60_000L))
        assertEquals("2:05", CustomDashUi.hm((2 * 60 + 5) * 60_000L + 59_000L))
    }
}

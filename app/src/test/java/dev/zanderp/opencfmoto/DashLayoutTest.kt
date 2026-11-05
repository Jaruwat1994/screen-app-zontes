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

    @Test fun pro_style_and_tank_round_trip() {
        val l = DashLayout.parse("SPEED", null, "PRO", 12.5f)
        assertEquals(DashLayout.Style.PRO, l.style)
        assertEquals(12.5f, l.tankLiters)
        assertEquals(DashLayout.Style.GRID, DashLayout.parse(null, null, "NOPE", null).style)
        assertEquals(DashLayout.Style.NEON, DashLayout.parse(null, null, "NEON", null).style)
        assertEquals(true, DashLayout.Style.RACING.isPro)
        assertEquals(60f, DashLayout.parse(null, null, null, 500f).tankLiters)
    }

    @Test fun gradient_and_custom_colour_round_trip() {
        val l = DashLayout.parse("SPEED", 0xFFFF2E88.toInt(), "CYBER", 0f, 0xFF22E5FF.toInt(), customColor = true)
        assertEquals(0xFF22E5FF.toInt(), l.accent2)
        assertEquals(true, l.customColor)
        assertEquals(null, DashLayout.parse("SPEED", null).accent2)
        assertEquals(true, DashLayout.Style.CYBER.themed)
        assertEquals(false, DashLayout.Style.PRO.themed)
    }

    @Test fun hex_parsing() {
        assertEquals(0xFFFDC052.toInt(), ColorPicker.parseHex("#FDC052"))
        assertEquals(0xFFFDC052.toInt(), ColorPicker.parseHex("fdc052"))
        assertEquals(null, ColorPicker.parseHex("#FDC05"))
        assertEquals(null, ColorPicker.parseHex("#GGC052"))
        assertEquals("#FDC052", ColorPicker.hex(0xFFFDC052.toInt()))
    }

    @Test fun fuel_estimate() {
        assertEquals(null, ProDashView.fuelLeft(0f, 3.0, 100.0))       // tank unknown
        assertEquals(null, ProDashView.fuelLeft(12f, 3.0, null))       // never refuelled
        assertEquals(9.0, ProDashView.fuelLeft(12f, 3.0, 100.0)!!, 1e-9)
        assertEquals(0.0, ProDashView.fuelLeft(12f, 3.0, 1000.0)!!, 1e-9)  // never negative
        assertEquals(300, ProDashView.rangeKm(9.0, 3.0))
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

    @Test fun gps_badge_tells_off_searching_stale_network_and_good_apart() {
        val sat = "\uD83D\uDEF0"
        assertEquals("GPS OFF" to 2, CustomDashUi.gpsBadge(false, 100L, 4, true, 9))
        assertEquals("GPS ${sat}3" to 2, CustomDashUi.gpsBadge(true, null, null, false, 3))
        assertEquals("GPS 12s" to 1, CustomDashUi.gpsBadge(true, 12_400L, 4, true, 9))
        assertEquals("GPS 45s" to 2, CustomDashUi.gpsBadge(true, 45_000L, 4, true, 9))
        assertEquals("NET ±999m" to 1, CustomDashUi.gpsBadge(true, 1_000L, 1500, false, 0))
        assertEquals("±4m ${sat}11" to 0, CustomDashUi.gpsBadge(true, 1_000L, 4, true, 11))
        assertEquals("±40m ${sat}4" to 1, CustomDashUi.gpsBadge(true, 1_000L, 40, true, 4))
    }

    @Test fun hidden_parts_round_trip_and_skip_unknown() {
        val p = setOf(DashLayout.Part.TYRE, DashLayout.Part.GPS)
        assertEquals(p, DashLayout.parseSet<DashLayout.Part>(DashLayout.formatSet(p)))
        assertEquals(setOf(DashLayout.Part.ODO), DashLayout.parseSet<DashLayout.Part>("ODO, NOT_A_PART"))
        assertEquals(emptySet<DashLayout.Part>(), DashLayout.parseSet<DashLayout.Part>(null))
        assertEquals(setOf(DashLayout.Fx.GLOW), DashLayout.parseSet<DashLayout.Fx>("GLOW"))
        assertEquals(emptySet<DashLayout.Fx>(), DashLayout.parseSet<DashLayout.Fx>(""))  // all switched off stays off
        assertEquals(p, DashLayout.parse("SPEED", null, "CYBER", hidden = "GPS,TYRE").hidden)
    }

    @Test fun needle_eases_toward_the_speed_and_stops() {
        val v = ProDashView.ease(0f, 100f, 16L)
        assert(v > 0f && v < 100f) { "moves part way: $v" }
        assertEquals(100f, ProDashView.ease(99.7f, 100f, 16L))   // snaps when close: animation ends
        assertEquals(100f, ProDashView.ease(0f, 100f, 2_000L))   // long gap: arrives
        assert(ProDashView.ease(100f, 40f, 16L) in 40f..100f)     // also eases down
    }

    @Test fun lean_angle_from_speed_and_turn_rate() {
        // 60 km/h round a 50 m radius: tan(lean) = v²/(r·g) ≈ 0.566 → ≈ 29.5°, to the left.
        val v = 60f / 3.6f
        assertEquals(29.5f, CustomDashUi.leanDeg(v, v / 50f), 0.5f)
        assertEquals(-29.5f, CustomDashUi.leanDeg(v, -v / 50f), 0.5f)   // right turn
        assertEquals(0f, CustomDashUi.leanDeg(1f, 2f))                   // walking a U-turn: no lean
        assertEquals(65f, CustomDashUi.leanDeg(40f, 5f))                 // gyro spike: capped
        assertEquals("◀ 30°", CustomDashUi.leanText(29.6f))
        assertEquals("12° ▶", CustomDashUi.leanText(-12f))
        assertEquals("0°", CustomDashUi.leanText(2.4f))
    }

    @Test fun arrow_relative_to_heading() {
        assertEquals("↑", CustomDashUi.arrow(10f))
        assertEquals("→", CustomDashUi.arrow(95f))
        assertEquals("↖", CustomDashUi.arrow(-40f))
        assertEquals("↓", CustomDashUi.arrow(540f))
    }

    @Test fun every_template_kind_has_templates() {
        for (c in DashLayout.Category.entries) assert(DashLayout.Style.entries.any { it.category == c }) { "empty kind $c" }
        assertEquals(DashLayout.Category.BASIC, DashLayout.Style.GRID.category)
    }

    @Test fun every_zontes_model_has_a_tank_size() {
        for (m in BikePicker.Model.entries) if (m != BikePicker.Model.OTHER) assert(m.tankL in 8f..25f) { "$m tank ${m.tankL}" }
        assertEquals(17.5f, BikePicker.Model.Z368G.tankL)
    }

    @Test fun speedo_drops_to_zero_when_stopped_instead_of_freezing() {
        assertEquals(62, CustomDashUi.displaySpeed(62, 800L))
        assertEquals(0, CustomDashUi.displaySpeed(3, 800L))        // GPS drift at a standstill
        assertEquals(0, CustomDashUi.displaySpeed(14, 5_000L))     // fixes stopped while slowing down
        assertEquals(null, CustomDashUi.displaySpeed(80, 5_000L))  // fast and no fixes: "—", not a frozen 80
        assertEquals(null, CustomDashUi.displaySpeed(null, null))
    }

    @Test fun now_playing_line() {
        assertEquals("⏸ Song — Artist", NowPlaying.line(NowPlaying.Track("Song", "Artist", true)))
        assertEquals("▶ Song", NowPlaying.line(NowPlaying.Track("Song", " ", false)))
        assertEquals("⏸ ", NowPlaying.line(NowPlaying.Track(null, null, true)))  // playing, title unknown (no access)
    }

    @Test fun nav_distance_and_arrows() {
        assertEquals("250 m", DashNav.distance(254.0))
        assertEquals("1.2 km", DashNav.distance(1234.0))
        assertEquals("↱", DashNav.arrow(GpxNav.TurnDir.RIGHT))
        assertEquals("↰", DashNav.arrow(GpxNav.TurnDir.SHARP_LEFT))
    }

    @Test fun phone_nav_notification_split() {
        assertEquals("250 m" to "Turn right onto Vibhavadi Rd", PhoneNav.split("250 m", "Turn right onto Vibhavadi Rd"))
        assertEquals("300 ม." to "เลี้ยวขวา", PhoneNav.split("300 ม.", "เลี้ยวขวา"))
        assertEquals("1.2 กม." to "ตรงไป", PhoneNav.split("1.2 กม.", "ตรงไป"))
        assertEquals("" to "Head north on Main St · toward Oak Ave", PhoneNav.split("Head north on Main St", "toward Oak Ave"))
    }
}

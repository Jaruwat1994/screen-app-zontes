package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThaiFuelTest {
    @Test fun picks_gasohol_95_from_bangchak_board() {
        val list = """[{"OilName":"แก๊สโซฮอล์ E20 S EVO","PriceToday":33.50},{"OilName":"แก๊สโซฮอล์ 95 S EVO","PriceToday":41.54},{"OilName":"ไฮ พรีเมียม 98 พลัส","PriceToday":48.55}]"""
        val body = """[{"OilPriceDate":"28/10/2569","OilList":${org.json.JSONObject.quote(list)}}]"""
        val p = ThaiFuel.parsePrice(body)!!
        assertEquals(41.54, p.baht, 0.001)
        assertEquals("28/10/2569", p.date)
        assertNull(ThaiFuel.parsePrice("""[{"OilList":"[]"}]"""))
    }

    @Test fun stations_from_overpass_nodes_and_ways() {
        val body = """{"elements":[
            {"type":"node","lat":13.75,"lon":100.50,"tags":{"amenity":"fuel","brand":"PTT","opening_hours":"24/7"}},
            {"type":"way","center":{"lat":13.76,"lon":100.51},"tags":{"amenity":"fuel","name":"Bangchak","addr:district":"Pathum Wan"}},
            {"type":"node","tags":{"amenity":"fuel"}}]}"""
        val s = ThaiFuel.parseStations(body, null)
        assertEquals(2, s.size)
        assertEquals("PTT", s[0].brand)
        assertEquals("24 ชม.", s[0].hours)
        assertEquals("Bangchak", s[1].brand)
        assertEquals("Pathum Wan", s[1].town)
    }

    @Test fun thailand_box() {
        assertEquals(true, ThaiFuel.covers(13.7563, 100.5018))   // Bangkok
        assertEquals(true, ThaiFuel.covers(18.79, 98.98))        // Chiang Mai
        assertEquals(false, ThaiFuel.covers(41.3874, 2.1686))    // Barcelona
    }
}

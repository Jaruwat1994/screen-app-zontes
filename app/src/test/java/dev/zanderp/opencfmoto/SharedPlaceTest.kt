package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPlaceTest {
    @Test
    fun googleMapsShareTextGivesNameAndLink() {
        val p = SharedPlace.parse("วัดอรุณราชวราราม\nhttps://maps.app.goo.gl/AbC123xyz")
        assertEquals("วัดอรุณราชวราราม", p.name)
        assertEquals("https://maps.app.goo.gl/AbC123xyz", p.link)
        assertNull(p.lat)  // the short link must be expanded first
    }

    @Test
    fun coordinatesFromLongUrlsAndGeo() {
        // The place's own !3d!4d beats the viewport's @centre.
        assertEquals(13.7437 to 100.4888, SharedPlace.coords("https://www.google.com/maps/place/Wat+Arun/@13.70,100.40,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d13.7437!4d100.4888"))
        assertEquals(13.75 to 100.5, SharedPlace.coords("https://maps.google.com/?q=13.75,100.5"))
        assertEquals(13.75 to 100.5, SharedPlace.coords("https://www.google.com/maps/search/?api=1&query=13.75%2C100.5"))
        assertEquals(13.75 to 100.5, SharedPlace.coords("geo:13.75,100.5?z=16"))
        assertEquals(13.75 to 100.5, SharedPlace.coords("geo:0,0?q=13.75,100.5(Home)"))
        assertEquals(14.0123 to 100.7123, SharedPlace.coords("https://www.google.com/maps/search/14.0123,+100.7123?entry=tts"))
        // A shop's link: Google's place id only, no position.
        assertNull(SharedPlace.coords("https://www.google.com/maps/place/Shop/data=!4m2!3m1!1s0x311d79f731f04329:0x8ec2cb693c1bc0b6!18m1!1e1?entry=gps"))
        assertNull(SharedPlace.coords("geo:0,0?q=Central World"))
        assertNull(SharedPlace.coords("https://maps.google.com/?q=95.1,200.0"))  // out of range
    }

    @Test
    fun namesFromGeoAndPlaceUrls() {
        assertEquals("Central World", SharedPlace.parse("geo:0,0?q=Central%20World").name)
        assertEquals("Home", SharedPlace.parse("geo:0,0?q=13.75,100.5(Home)").name)
        assertEquals("Wat Arun", SharedPlace.placeName("https://www.google.com/maps/place/Wat+Arun/@13.7,100.4,17z"))
    }
}

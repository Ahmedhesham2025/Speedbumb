package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** The map file (KML) for Google Earth / My Maps: valid XML, one pin per spot, the right style per kind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharingKmlTest {
    private val cfg = EngineConfig()

    private fun parse(kml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(kml.byteInputStream(Charsets.UTF_8))

    private fun Element.child(name: String): String = getElementsByTagName(name).item(0).textContent

    private fun placemarks(doc: Document): List<Element> {
        val nodes = doc.getElementsByTagName("Placemark")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun bump(id: Long, lat: Double, kindScore: Double = 0.0, kindVotes: Int = 0, sideScore: Double = 0.0,
                     sideVotes: Int = 0, peak: Double = 0.0, userMuted: Boolean = false) =
        Bump(id, lat, 31.2357, 90.0, 3, 3, 0, 3, 0, 0, userMuted, kindScore, kindVotes, sideScore, sideVotes, peak)

    @Test fun eachKindGetsItsOwnStyleAndName() {
        val bumps = listOf(
            bump(1, 30.001, kindScore = -0.8, kindVotes = 3),                                         // speed bump
            bump(2, 30.002, kindScore = 0.8, kindVotes = 3, sideScore = 1.0, sideVotes = 2, peak = 3.0), // pothole, right
            bump(3, 30.003, kindScore = 0.8, kindVotes = 3, peak = cfg.harshPotholeMs2 + 1),            // harsh pothole
            bump(4, 30.004),                                                                             // no votes yet
            bump(5, 30.005, kindScore = 0.8, kindVotes = 3, peak = 9.0, userMuted = true),               // muted wins
        )
        val doc = parse(Sharing.kml(bumps, cfg))
        assertEquals("kml", doc.documentElement.localName)
        assertEquals("http://www.opengis.net/kml/2.2", doc.documentElement.namespaceURI)

        val pins = placemarks(doc)
        assertEquals(5, pins.size)
        assertEquals(listOf("#bump", "#pothole", "#harsh", "#unsure", "#muted"), pins.map { it.child("styleUrl") })
        assertEquals(
            listOf("Speed bump #1", "Pothole #2", "Harsh pothole #3", "Bump (unsure) #4", "Muted spot #5"),
            pins.map { it.child("name") },
        )
        assertTrue(pins[1].child("description").startsWith("Pothole, right side."))
        assertTrue(pins[0].child("description").contains("Felt 3 of 3 passes"))
        assertTrue("non-ASCII survives the UTF-8 round trip", pins[0].child("description").endsWith("m/s²."))

        // Every pin points at a style the file defines.
        val styles = doc.getElementsByTagName("Style")
        val ids = (0 until styles.length).map { (styles.item(it) as Element).getAttribute("id") }.toSet()
        assertEquals(setOf("bump", "pothole", "harsh", "unsure", "muted"), ids)
    }

    @Test fun coordinatesAreLonLatInUsDigitsEvenOnAnArabicPhone() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val doc = parse(Sharing.kml(listOf(bump(9, 30.0444123)), cfg))
            val pin = placemarks(doc).single()
            assertEquals("31.2357000,30.0444123,0", pin.child("coordinates"))
            assertTrue(pin.child("description").contains("Direction of travel 90°"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun emptyMapIsStillAValidFile() {
        val doc = parse(Sharing.kml(emptyList(), cfg))
        assertEquals(0, placemarks(doc).size)
        assertEquals("Bump Beeper map", (doc.getElementsByTagName("Document").item(0) as Element).child("name"))
    }
}

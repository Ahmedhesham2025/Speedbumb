package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Properties

class RealDriveChecksTest {
    private val header = TraceReader.HEADER.joinToString(",")

    /** A row in the standard columns: only t_s, type, lat, lon (and event for event rows) filled in. */
    private fun row(t: String, type: String, lat: String, lon: String, event: String = ""): String {
        val p = MutableList(TraceReader.HEADER.size) { "" }
        p[0] = t; p[1] = type; p[9] = lat; p[10] = lon; p[14] = event
        return p.joinToString(",")
    }

    private fun clean() = listOf(
        "# app_version=1.4.0", "# android=15", "# placement=pocket", "# gyro=yes", header,
        row("0.000", "gps", "0.5000000", "0.5000000"),
        row("1.000", "gps", "0.5000900", "0.5000000"),
        row("1.500", "event", "0.5000900", "0.5000000", "beep"),
    )

    @Test fun cleanFilePasses() = assertEquals(emptyList<String>(), RealDriveChecks.anonymizationProblems(clean()))

    @Test fun rawCoordinatesOnlyOnAnEventRowFail() {
        val lines = clean() + row("2.000", "event", "30.0444000", "31.2357000", "new_bump")
        val problems = RealDriveChecks.anonymizationProblems(lines)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].startsWith("position not near the fake origin"))
        assertFalse("must not print coordinates", problems[0].contains("30.04"))
    }

    @Test fun rawGpsRowFails() =
        assertTrue(RealDriveChecks.anonymizationProblems(clean() + row("2.000", "gps", "30.0444000", "0.5")).isNotEmpty())

    @Test fun clockNotAtZeroAndDeviceMetadataFail() {
        val lines = listOf("# device=x", header, row("5.000", "gps", "0.5", "0.5"))
        val problems = RealDriveChecks.anonymizationProblems(lines)
        assertTrue(problems.any { it.startsWith("line 1: metadata 'device'") })
        assertTrue(problems.contains("clock does not start at 0"))
    }

    @Test fun expectedFileKeepsDotsInTheName() {
        assertEquals("drive.v2.expected.properties", RealDriveChecks.expectedFile(File("x", "drive.v2.csv.gz")).name)
        assertEquals("drive01_pocket.expected.properties", RealDriveChecks.expectedFile(File("x", "drive01_pocket.csv")).name)
    }

    private fun summary(warnings: Int, km: Double = 10.0) =
        DriveSummary(km, 5, warnings, 0, 0, emptyMap(), 0, 0, 0, 0, 0, 0)

    @Test fun zeroFailsWhereMoreIsExpected() {
        val want = Properties().apply { setProperty("warnings", "2"); setProperty("distance_km", "10.0") }
        assertEquals(emptyList<String>(), RealDriveChecks.outOfRange(want, summary(warnings = 4)))
        assertEquals(1, RealDriveChecks.outOfRange(want, summary(warnings = 0)).size)   // within ±3, but 0
        assertEquals(1, RealDriveChecks.outOfRange(want, summary(warnings = 2, km = 0.0)).size)
        val zero = Properties().apply { setProperty("warnings", "0") }
        assertEquals(emptyList<String>(), RealDriveChecks.outOfRange(zero, summary(warnings = 0)))
    }
}

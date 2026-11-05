package dev.wallupus.log

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class LogExportTest {
    private fun e(uid: Int, name: String, blocked: Boolean, count: Int, first: Long, last: Long, vararg qt: Int) =
        QueryGroups.Entry(uid, name, blocked, count, first, last, qt.toList())

    @Test fun sameDomainAcrossAppsFoldsIntoOneRow() {
        val rows = foldByDomain(
            listOf(
                e(10_001, "ads.example.com", true, 5, 2_000, 9_000, 1),
                e(10_002, "ads.example.com", true, 312, 1_000, 5_000, 28, 1),
                e(10_003, "ads.example.com", true, 40, 3_000, 4_000, 65),
            ),
        )
        val r = rows.single()
        assertEquals(357, r.times)
        assertEquals(1_000L, r.firstMillis)
        assertEquals(9_000L, r.lastMillis)
        assertEquals(listOf(1, 28, 65), r.qtypes) // union, first-seen order
        assertEquals(listOf(10_002 to 312, 10_003 to 40, 10_001 to 5), r.byApp) // busiest first
    }

    @Test fun blockedAndAllowedStaySeparate() {
        val rows = foldByDomain(
            listOf(
                e(1, "x.example.com", true, 3, 0, 0, 1),
                e(1, "x.example.com", false, 7, 0, 0, 1),
            ),
        )
        assertEquals(listOf(true, false), rows.map { it.blocked })
    }

    @Test fun blockedFirstThenByTimes() {
        val rows = foldByDomain(
            listOf(
                e(1, "allowed-big.com", false, 900, 0, 0),
                e(1, "small.com", true, 2, 0, 0),
                e(1, "big.com", true, 50, 0, 0),
            ),
        )
        assertEquals(listOf("big.com", "small.com", "allowed-big.com"), rows.map { it.name })
    }

    @Test fun csvHeaderRowAndTimestamps() {
        val totals = foldByDomain(listOf(e(7, "ads.example.com", true, 3, 0, 61_000, 1, 28)))
        val csv = exportCsv(totals, { "Chrome" }, ZoneOffset.UTC)
        assertEquals(
            EXPORT_HEADER + "\r\n" +
                "ads.example.com,blocked,3,Chrome ×3,1970-01-01T00:00:00Z,1970-01-01T00:01:01Z,A|AAAA\r\n",
            csv,
        )
    }

    @Test fun multipleAppsAreOneQuotedFieldOnlyWhenNeeded() {
        val totals = foldByDomain(
            listOf(e(1, "t.com", true, 2, 0, 0), e(2, "t.com", true, 1, 0, 0)),
        )
        val line = exportCsv(totals, { if (it == 1) "Maps, by Google" else "Say \"hi\"" }, ZoneOffset.UTC).lines()[1]
        assertEquals(true, line.startsWith("t.com,blocked,3,\"Maps, by Google ×2; Say \"\"hi\"\" ×1\","))
    }

    @Test fun quotingRules() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"a\"\"b\"", csvField("a\"b"))
        assertEquals("\"a\nb\"", csvField("a\nb"))
    }

    @Test fun formulaInjectionIsDefused() {
        assertEquals("'=cmd|' /C calc'!A0", csvField("=cmd|' /C calc'!A0"))
        assertEquals("'+1", csvField("+1"))
        assertEquals("'-x", csvField("-x"))
        assertEquals("'@a", csvField("@a"))
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", csvField("=HYPERLINK(\"http://x\",\"y\")"))
        // A hostile app label, as it lands in the apps column.
        val totals = foldByDomain(listOf(e(1, "t.com", true, 1, 0, 0)))
        val line = exportCsv(totals, { "=evil()" }, ZoneOffset.UTC).lines()[1]
        assertEquals(true, line.contains(",'=evil() ×1,"))
    }

    @Test fun unknownQtypeFallsBackToNumber() {
        val totals = foldByDomain(listOf(e(1, "t.com", false, 1, 0, 0, 99)))
        assertEquals(true, exportCsv(totals, { "x" }, ZoneOffset.UTC).lines()[1].endsWith(",99"))
    }

    @Test fun fileName() {
        assertEquals("wallupus-log-1970-01-01_0001.csv", exportFileName(61_000, ZoneOffset.UTC))
    }
}

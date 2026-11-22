package dev.wallupus.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpansionTest {
    @Test fun tappedSectionsFlipAwayFromTheDefault() {
        val e = Expansion(openByDefault = false).toggle(1).toggle(2)
        assertTrue(e.isOpen(1))
        assertTrue(e.isOpen(2))
        assertFalse(e.isOpen(3)) // never touched: follows the default
        assertFalse(e.toggle(1).isOpen(1)) // tapping again closes it
    }

    @Test fun allOpenDrivesTheButtonLabel() {
        val apps = listOf(1, 2, 3)
        assertFalse(Expansion(false).toggle(1).toggle(2).allOpen(apps)) // 2 of 3 open: "Expand all"
        assertTrue(Expansion(false).toggle(1).toggle(2).toggle(3).allOpen(apps)) // "Collapse all"
        assertTrue(Expansion(true).allOpen(apps))
        assertFalse(Expansion(true).toggle(2).allOpen(apps))
        assertFalse(Expansion(true).allOpen(emptyList()))
    }

    @Test fun setAllResetsPerAppChoicesAndBecomesTheDefault() {
        val e = Expansion(false).toggle(1).setAll(true)
        assertEquals(Expansion(true), e)
        assertTrue(e.isOpen(99)) // an app that shows up later opens too
        val closed = e.toggle(5).setAll(false)
        assertFalse(closed.isOpen(5))
        assertFalse(closed.isOpen(1))
    }

    @Test fun filterSplitsByOutcome() {
        val blocked = QueryGroups.Entry(1, "ads.example", true, 3, 0L, 1L, listOf(1))
        val allowed = QueryGroups.Entry(1, "example.com", false, 2, 0L, 1L, listOf(1))
        val rows = listOf(blocked, allowed)
        assertEquals(rows, rows.filter(LogFilter.ALL::matches))
        assertEquals(listOf(blocked), rows.filter(LogFilter.BLOCKED::matches))
        assertEquals(listOf(allowed), rows.filter(LogFilter.ALLOWED::matches))
        // Header counts follow the filter: the Allowed view shows no blocked lookups.
        assertEquals(0, groupByApp(rows.filter(LogFilter.ALLOWED::matches), byCount = false).single().blocked)
        assertEquals(3, groupByApp(rows.filter(LogFilter.BLOCKED::matches), byCount = false).single().total)
    }
}

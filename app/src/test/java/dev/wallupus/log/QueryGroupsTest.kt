package dev.wallupus.log

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryGroupsTest {
    private var now = 1_000L
    private fun groups(capacity: Int = 10) = QueryGroups(capacity) { now }

    @Test fun repeatsFoldIntoOneRowWithCountAndTimes() {
        val g = groups()
        g.record("ads.example.com", 1, blocked = true, uid = 10_100)
        now = 2_000
        g.record("ads.example.com", 28, blocked = true, uid = 10_100)
        now = 3_000
        g.record("ads.example.com", 1, blocked = true, uid = 10_100)
        val e = g.snapshot().single()
        assertEquals(3, e.count)
        assertEquals(1_000L, e.firstMillis)
        assertEquals(3_000L, e.lastMillis)
        assertEquals(listOf(1, 28), e.qtypes)
        assertEquals(10_100, e.uid)
    }

    @Test fun blockedAndAllowedAreSeparateRows() {
        val g = groups()
        g.record("x.example.com", 1, blocked = true, uid = 1)
        g.record("x.example.com", 1, blocked = false, uid = 1)
        assertEquals(listOf(false, true), g.snapshot().map { it.blocked }) // newest first
        assertEquals(2, g.snapshot().map { it.key }.toSet().size)
    }

    @Test fun eachAppCountsItsOwnLookups() {
        val g = groups()
        g.record("cdn.com", 1, false, 1)
        g.record("cdn.com", 1, false, 2)
        g.record("cdn.com", 1, false, 1)
        val byUid = g.snapshot().associate { it.uid to it.count }
        assertEquals(mapOf(1 to 2, 2 to 1), byUid)
    }

    @Test fun mostRecentlySeenFirst() {
        val g = groups()
        g.record("a.com", 1, false, 1)
        g.record("b.com", 1, false, 1)
        g.record("a.com", 1, false, 1)
        assertEquals(listOf("a.com", "b.com"), g.snapshot().map { it.name })
    }

    @Test fun evictsTheStalestGroupWhenFull() {
        val g = groups(capacity = 2)
        g.record("old.com", 1, false, 1)
        g.record("kept.com", 1, false, 1)
        g.record("old.com", 1, false, 1) // touched: now kept.com is the stalest
        g.record("new.com", 1, false, 1)
        assertEquals(listOf("new.com", "old.com"), g.snapshot().map { it.name })
    }
}

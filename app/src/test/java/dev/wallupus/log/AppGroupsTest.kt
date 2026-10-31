package dev.wallupus.log

import org.junit.Assert.assertEquals
import org.junit.Test

class AppGroupsTest {
    private fun e(uid: Int, name: String, blocked: Boolean, count: Int, last: Long) =
        QueryGroups.Entry(uid, name, blocked, count, 0L, last, listOf(1))

    private val rows = listOf(
        e(1, "a.com", true, 2, last = 50),
        e(2, "b.com", false, 9, last = 40),
        e(1, "c.com", false, 5, last = 10),
        e(1, "d.com", true, 5, last = 30),
    )

    @Test fun groupsPerAppWithTotalsAndBusiestDomainsFirst() {
        val app1 = groupByApp(rows, byCount = false).first { it.uid == 1 }
        assertEquals(12, app1.total)
        assertEquals(7, app1.blocked)
        assertEquals(50L, app1.lastMillis)
        // count desc, ties by recency: d.com (5, t30) before c.com (5, t10)
        assertEquals(listOf("d.com", "c.com", "a.com"), app1.entries.map { it.name })
    }

    @Test fun appsOrderedByRecencyOrByCount() {
        assertEquals(listOf(1, 2), groupByApp(rows, byCount = false).map { it.uid })
        assertEquals(listOf(1, 2), groupByApp(rows, byCount = true).map { it.uid }) // 12 > 9
        val busier2 = rows + e(2, "z.com", false, 10, last = 1)
        assertEquals(listOf(2, 1), groupByApp(busier2, byCount = true).map { it.uid })
    }

    @Test fun monogramUsesTheSiteLabel() {
        assertEquals('D', monogram("ads.doubleclick.net", 6).first)
        assertEquals(monogram("a.doubleclick.net", 6), monogram("b.doubleclick.net", 6))
        assertEquals('L', monogram("localhost", 6).first)
        assertEquals('?', monogram("", 6).first)
    }
}

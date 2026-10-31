package dev.wallupus.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTagTest {

    // CI tags v0.1.<count>; the count is the versionCode the updater compares against.
    @Test fun readsVersionCodeFromOurTags() {
        assertEquals(42, versionCodeOf("v0.1.42"))
        assertEquals(7, versionCodeOf("0.1.7"))
        assertEquals(1234, versionCodeOf("v2.0.1234"))
    }

    // A hand-made or foreign tag must never look like an update.
    @Test fun rejectsAnythingElse() {
        for (tag in listOf("", "v", "latest", "v0.1.0", "v0.1.x", "v0.1.42-beta", "v0..42", "v0.1.", "vv0.1.2")) {
            assertNull("\"$tag\"", versionCodeOf(tag))
        }
    }
}

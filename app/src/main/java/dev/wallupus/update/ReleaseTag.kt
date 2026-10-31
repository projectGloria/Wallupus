package dev.wallupus.update

/**
 * versionCode from a release tag. CI tags each build `v0.1.<count>` where count is both the last
 * version component and the versionCode (see app/build.gradle.kts), so the tag alone tells us
 * whether a release is newer: no extra metadata file to publish and keep in sync.
 * Null for anything that isn't shaped like one of our tags.
 */
internal fun versionCodeOf(tag: String): Int? {
    val name = tag.removePrefix("v")
    if (name.isEmpty() || name.split('.').any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    return name.substringAfterLast('.').toIntOrNull()?.takeIf { it > 0 }
}

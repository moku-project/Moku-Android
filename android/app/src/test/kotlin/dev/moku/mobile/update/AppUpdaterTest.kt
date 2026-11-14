package dev.moku.mobile.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun newerVersions() {
        assertTrue(AppUpdater.isNewer("0.1.1", "0.1.0"))
        assertTrue(AppUpdater.isNewer("0.2.0", "0.1.9"))
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.0"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.99.99"))
        assertTrue(AppUpdater.isNewer("0.1.1", "0.1.0-debug"))
    }

    @Test
    fun sameOrOlderVersions() {
        assertFalse(AppUpdater.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppUpdater.isNewer("0.1.0", "0.1.1"))
        assertFalse(AppUpdater.isNewer("0.9.0", "0.10.0"))
        assertFalse(AppUpdater.isNewer("0.1", "0.1.0"))
    }
}

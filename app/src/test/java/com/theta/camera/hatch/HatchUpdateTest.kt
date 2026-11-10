package com.theta.camera.hatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HatchUpdateTest {
    @Test fun newerVersions() {
        assertTrue(HatchUpdate.isNewer("0.3.0", "0.2.0"))
        assertTrue(HatchUpdate.isNewer("0.10.0", "0.9.1"))
        assertTrue(HatchUpdate.isNewer("1.0", "0.99.99"))
        assertTrue(HatchUpdate.isNewer("0.2.1", "0.2"))
    }
    @Test fun sameOrOlder() {
        assertFalse(HatchUpdate.isNewer("0.2.0", "0.2.0"))
        assertFalse(HatchUpdate.isNewer("0.2", "0.2.0"))
        assertFalse(HatchUpdate.isNewer("0.1.9", "0.2.0"))
    }
}

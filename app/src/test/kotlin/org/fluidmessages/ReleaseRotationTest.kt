package org.fluidmessages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app updater accepts the same key, or a new key the old one handed over to (APK key rotation). */
class ReleaseRotationTest {
    @Test fun sameKeyOrHandedOver() {
        assertTrue(ReleaseUpdates.sameOrRotated(setOf("old"), setOf("old"), emptySet()))
        // Rotated: the update is signed by the new key and its history says old → new.
        assertTrue(ReleaseUpdates.sameOrRotated(setOf("old"), setOf("new"), setOf("old", "new")))
        // After the switch, updates signed by the new key keep working.
        assertTrue(ReleaseUpdates.sameOrRotated(setOf("new"), setOf("new"), setOf("old", "new")))
        // A stranger's key, or a history that doesn't include this copy's key, is refused.
        assertFalse(ReleaseUpdates.sameOrRotated(setOf("old"), setOf("evil"), emptySet()))
        assertFalse(ReleaseUpdates.sameOrRotated(setOf("old"), setOf("evil"), setOf("other", "evil")))
        assertFalse(ReleaseUpdates.sameOrRotated(emptySet<String>(), setOf("new"), setOf("new")))
    }
}

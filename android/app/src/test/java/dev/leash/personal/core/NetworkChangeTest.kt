package dev.leash.personal.core

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeTest {
    @Test fun theFirstNetworkIsNotAChange() {
        assertFalse(NetworkChange().available("wifi"))
    }

    @Test fun anotherNetworkIsAChangeAndTheSameOneAgainIsNot() {
        val n = NetworkChange()
        n.available("wifi")
        assertFalse(n.available("wifi"))
        assertTrue(n.available("cell"))
        assertTrue(n.available("wifi"))
    }

    @Test fun theSameNetworkComingBackAfterALossIsAChange() {
        val n = NetworkChange()
        n.available("wifi")
        n.lost("wifi")
        assertTrue(n.available("wifi"))
        assertFalse(n.available("wifi"))
    }

    @Test fun aLossOfSomethingElseChangesNothing() {
        val n = NetworkChange()
        n.available("cell")
        n.lost("wifi")
        assertFalse(n.available("cell"))
    }

    /** The manifest names these as plain strings: they must be what the platform calls them (POST_PROMOTED_NOTIFICATIONS is not in compileSdk 36.0's android.jar). */
    @Test fun platformNamesUsedInTheManifestAndTheLiveUpdate() {
        assertEquals("android.permission.FOREGROUND_SERVICE_SPECIAL_USE", Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
        assertEquals("android.permission.POST_NOTIFICATIONS", Manifest.permission.POST_NOTIFICATIONS)
    }
}

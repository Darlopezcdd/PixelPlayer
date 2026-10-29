package com.theveloper.pixelplay.data.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateManagerTest {

    @Test
    fun `isRemoteVersionNewer returns true when remote minor or patch is higher`() {
        assertTrue(AppUpdateManager.isRemoteVersionNewer("v0.7.6-beta", "0.7.5-beta"))
        assertTrue(AppUpdateManager.isRemoteVersionNewer("v0.8.0", "0.7.5-beta"))
        assertTrue(AppUpdateManager.isRemoteVersionNewer("0.7.5-darlopc.2", "0.7.5-darlopc.1"))
    }

    @Test
    fun `isRemoteVersionNewer returns false when remote matches or is older`() {
        assertFalse(AppUpdateManager.isRemoteVersionNewer("v0.7.5-beta", "0.7.5-beta"))
        assertFalse(AppUpdateManager.isRemoteVersionNewer("v0.7.4", "0.7.5-beta"))
        assertFalse(AppUpdateManager.isRemoteVersionNewer("0.5.0-beta", "0.7.5-beta"))
    }

    @Test
    fun `isRemoteVersionNewer treats stable release as newer than beta of same numeric version`() {
        assertTrue(AppUpdateManager.isRemoteVersionNewer("v0.7.5", "0.7.5-beta"))
        assertFalse(AppUpdateManager.isRemoteVersionNewer("v0.7.5-beta", "0.7.5"))
    }
}

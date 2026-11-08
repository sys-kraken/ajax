package com.ajax.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MemoryTest {
    private var clock = 1_000_000L

    @Before fun setUp() {
        Memory.now = { clock }
        Memory.forget()
    }

    @After fun tearDown() {
        Memory.now = { System.currentTimeMillis() }
        Memory.forget()
    }

    @Test fun switches() {
        Memory.rememberToggle("wifi")
        assertEquals("turn on wifi", Memory.resolve("turn it back on"))
        assertEquals("turn off wifi", Memory.resolve("switch it off"))
        assertEquals("turn on wifi", Memory.resolve("enable it"))
        Memory.rememberToggle("flashlight")
        assertEquals("flashlight off", Memory.resolve("turn it off"))
    }

    @Test fun apps() {
        Memory.rememberApp("instagram")
        assertEquals("open instagram", Memory.resolve("open it again"))
        assertEquals("force stop instagram", Memory.resolve("close it"))
        assertEquals("app info for instagram", Memory.resolve("info for it"))
        // Right after a list of apps, "close it" means that list (another command).
        assertNull(Memory.resolve("close it", appsJustListed = true))
    }

    @Test fun people() {
        Memory.rememberContact("mom")
        assertEquals("call mom", Memory.resolve("call her back"))
        assertEquals("text mom saying on my way", Memory.resolve("text her saying on my way"))
    }

    @Test fun nothingRememberedOrTooOld() {
        assertNull(Memory.resolve("turn it on"))
        Memory.rememberApp("telegram")
        clock += 11 * 60_000L
        assertNull(Memory.resolve("open it"))
    }

    @Test fun routingUsesMemory() {
        Memory.rememberToggle("bluetooth")
        assertEquals("toggle:Bluetooth:on", CommandRouter.route("turn it back on")?.id)
        Memory.rememberContact("dad")
        assertEquals("call", CommandRouter.route("call him back")?.id)
        Memory.forget()
        assertEquals("force-stop-which", CommandRouter.route("close it")?.id)
    }
}

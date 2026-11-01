package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandRouterTest {
    private fun id(s: String) = CommandRouter.route(s)?.id

    @Test fun apps() {
        assertEquals("open-app", id("Open YouTube"))
        assertEquals("open-app", id("please launch the camera app"))
    }

    @Test fun settings() {
        assertEquals("settings:wifi", id("open Wi-Fi settings"))
        assertEquals("settings:wifi", id("wi fi settings"))
        assertEquals("settings:battery", id("battery settings"))
        assertEquals("settings:main", id("open settings"))
        assertEquals("open-app", id("open camera settings")) // unknown page falls through to app lookup
    }

    @Test fun torch() {
        assertEquals("torch-on", id("flashlight on"))
        assertEquals("torch-on", id("could you flip the torch on"))
        assertEquals("torch-on", id("turn on the flashlight"))
        assertEquals("torch-off", id("turn off the torch"))
        assertEquals("torch-unclear", id("flashlight"))
    }

    @Test fun volume() {
        assertEquals("volume-up", id("volume up"))
        assertEquals("volume-down", id("please turn the volume down"))
        assertEquals("mute", id("mute"))
        assertEquals("unmute", id("unmute"))
    }

    @Test fun call() {
        assertEquals("call", id("call Mom"))
        assertEquals("call-number", id("call 911"))
    }

    @Test fun unknown() {
        assertNull(id("what's the weather"))
        assertNull(id(""))
    }
}

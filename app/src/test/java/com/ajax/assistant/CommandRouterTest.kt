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
        assertEquals("torch-toggle", id("flashlight"))
        assertEquals("torch-toggle", id("toggle the torch"))
        assertEquals("torch-unclear", id("is the flashlight bright"))
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

    @Test fun toggles() {
        assertEquals("toggle:Wi-Fi:on", id("turn on wifi"))
        assertEquals("toggle:Bluetooth:off", id("Turn Bluetooth off"))
        assertEquals("toggle:Mobile data:on", id("mobile data on"))
        assertEquals("toggle:Airplane mode:on", id("enable airplane mode"))
    }

    @Test fun phoneSettings() {
        assertEquals("brightness", id("set brightness to 50 percent"))
        assertEquals("brightness", id("brightness 80"))
        assertEquals("brightness-invalid", id("brightness 500"))
        assertEquals("dnd-on", id("turn on do not disturb"))
        assertEquals("dnd-off", id("do not disturb off"))
        assertEquals("ringer-vibrate", id("vibrate mode"))
        assertEquals("ringer-silent", id("silent mode"))
    }

    @Test fun navigation() {
        assertEquals("nav-back", id("go back"))
        assertEquals("nav-home", id("home"))
        assertEquals("nav-recents", id("recent apps"))
        assertEquals("nav-notifications", id("show notifications"))
        assertEquals("nav-quick-settings", id("quick settings"))
        assertEquals("nav-screenshot", id("take a screenshot"))
        assertEquals("nav-lock", id("lock the phone"))
        assertEquals("nav-scroll-down", id("scroll down"))
        assertEquals("nav-scroll-up", id("scroll up"))
    }

    @Test fun info() {
        assertEquals("info-battery", id("what's my battery"))
        assertEquals("info-battery", id("battery level"))
        assertEquals("info-time", id("what time is it"))
        assertEquals("info-date", id("what's the date"))
        assertEquals("info-notifications", id("read my notifications"))
    }

    @Test fun alarmsAndTimers() {
        assertEquals("alarm", id("set an alarm for 7"))
        assertEquals("alarm", id("set an alarm for 7:30 a.m."))
        assertEquals("alarm", id("wake me up at 6 45 pm"))
        assertEquals("timer", id("timer 5 minutes"))
        assertEquals("timer", id("set a 10 minute timer"))
    }

    @Test fun texting() {
        assertEquals("text", id("text Mom saying I'm on my way"))
        assertEquals("text", id("send a message to John Smith that I will be late"))
        assertEquals("open-app", id("open messages")) // no 'saying': not a text command
    }

    @Test fun appInfoPage() {
        assertEquals("app-info", id("app info for YouTube"))
        assertEquals("app-info", id("YouTube app info"))
    }

    @Test fun normalizeKeepsMeaning() {
        assertEquals("7 30 am", CommandRouter.normalize("7:30 a.m."))
        assertEquals("whats my battery", CommandRouter.normalize("What's my battery?"))
    }

    @Test fun unknown() {
        assertNull(id("what's the weather"))
        assertNull(id(""))
    }
}

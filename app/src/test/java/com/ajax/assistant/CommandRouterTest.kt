package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun trailingFiller() {
        assertEquals("info-time", id("What is the time right now?"))
        assertEquals("info-time", id("what time is it now"))
        assertEquals("info-battery", id("whats my battery now"))
        assertEquals("toggle:Wi-Fi:off", id("turn off wifi now"))
    }

    @Test fun media() {
        assertEquals("media-playing", id("play music"))
        assertEquals("media-paused", id("pause"))
        assertEquals("media-next-track", id("skip this song"))
        assertEquals("media-previous-track", id("previous track"))
        assertEquals("volume-level", id("set volume to 40 percent"))
        assertEquals("volume-level", id("max volume"))
        assertEquals("volume-invalid", id("volume 400"))
    }

    @Test fun newToggles() {
        assertEquals("toggle:Location:off", id("turn off location"))
        assertEquals("toggle:Battery saver:on", id("turn on battery saver"))
        assertEquals("toggle:NFC:on", id("nfc on"))
        assertEquals("rotate-on", id("auto rotate on"))
        assertEquals("rotate-off", id("lock rotation"))
    }

    @Test fun webMapsCamera() {
        assertEquals("search", id("search for pizza near me"))
        assertEquals("search", id("google how tall is everest"))
        assertEquals("navigate", id("navigate to the airport"))
        assertEquals("navigate", id("take me to the beach"))
        assertEquals("settings:wifi", id("take me to wifi settings"))
        assertEquals("camera-photo", id("take a selfie"))
        assertEquals("camera-video", id("record a video"))
        assertEquals("info-storage", id("how much storage do i have"))
    }

    @Test fun screenControl() {
        assertEquals("tap", id("tap send"))
        assertEquals("tap", id("click on the accept button"))
        assertEquals("nav-back", id("press back"))
        assertEquals("type", id("type hello world"))
        assertEquals("text", id("text mom saying hi"))
        assertEquals("toggle:Airplane mode:on", id("turn on airplane mode"))
    }

    @Test fun everydayPhrasing() {
        assertEquals("torch-off", id("kill the light"))
        assertEquals("torch-on", id("i need some light"))
        assertEquals("volume-down", id("make it quieter"))
        assertEquals("mute", id("turn the sound off"))
        assertEquals("media-playing", id("put some music on"))
        assertEquals("media-next-track", id("skip this one"))
        assertEquals("toggle:Wi-Fi:off", id("disconnect from the wifi"))
        assertEquals("toggle:Wi-Fi:on", id("turn wifi back on"))
        assertEquals("toggle:Battery saver:on", id("my battery is dying save power"))
        assertEquals("info-battery", id("is my phone charged"))
        assertEquals("info-time", id("do you have the time"))
        assertEquals("info-date", id("which day is it"))
        assertEquals("info-storage", id("am i running out of space"))
        assertEquals("info-notifications", id("any new messages"))
        assertEquals("rotate-off", id("stop the screen from rotating"))
        assertEquals("navigate", id("how do i get to the airport"))
        assertEquals("settings:main", id("open phone settings"))
    }

    @Test fun powerAndScreen() {
        assertEquals("nav-restart", id("Restart the phone"))
        assertEquals("nav-restart", id("reboot"))
        assertEquals("nav-power-off", id("turn off the phone"))
        assertEquals("nav-power-off", id("shut down"))
        assertEquals("nav-lock", id("Turn off the screen"))
        assertFalse(CommandRouter.route("restart the phone")!!.hintOnly)
        assertEquals("nav-lock", id("turn the display off"))
        assertEquals("settings:screen", id("open screen settings"))
    }

    @Test fun drafting() {
        assertEquals("draft", id("Draft a message to Franklin saying hi"))
        assertEquals("draft", id("write a text to Franklin that I'm late"))
        assertEquals("draft", id("compose a message to Franklin"))
        assertEquals("text", id("text Franklin saying hi")) // sending still goes through yes/no
        assertEquals("text", id("text mom saying turn on the light")) // the message is not a command
    }

    @Test fun spokenNumbers() {
        assertEquals("brightness", id("Change brightness to zero percent"))
        assertEquals("brightness", id("set the brightness to 0"))
        assertEquals("brightness", id("brightness fifty"))
        assertEquals("brightness", id("brightness a hundred percent"))
        assertEquals("brightness-invalid", id("brightness 101"))
        assertEquals("volume-level", id("change the volume to thirty five percent"))
        assertEquals("timer", id("timer for five minutes"))
        assertEquals("alarm", id("set an alarm for six thirty am"))
        assertEquals("search", id("search for one direction"))
    }

    @Test fun followUpListening() {
        for (keeps in listOf("flashlight on", "volume up", "whats the time", "turn on wifi", "scroll down", "pause")) {
            assertFalse(keeps, CommandRouter.route(keeps)!!.endsConversation)
        }
        for (ends in listOf("open youtube", "wifi settings", "take a photo", "call mom", "navigate to the airport",
            "search for pizza", "play music", "lock the phone", "restart the phone", "draft a message to mom")) {
            assertTrue(ends, CommandRouter.route(ends)!!.endsConversation)
        }
    }

    @Test fun lookalikesDoNotMisfire() {
        assertNull(CommandRouter.route("turn on light mode")?.id?.takeIf { it.startsWith("torch") })
        assertEquals("toggle:Battery saver:on", id("turn on battery saver"))
        assertNull(CommandRouter.route("how do i save battery")?.id?.takeIf { it == "info-battery" })
        assertNull(CommandRouter.route("is it going to rain today")?.id?.takeIf { it == "info-date" })
        assertEquals("timer", id("set a timer for 5 minutes"))
    }
}

package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoRoutingTest {
    private fun check(id: String, vararg phrases: String) {
        for (p in phrases) assertEquals(p, id, CommandRouter.route(p)?.id)
    }

    @Test fun systemReport() = check(
        "info-system-report",
        "system report", "System report", "phone status", "device status", "give me a system report",
        "status report", "diagnostics", "how is my phone doing", "health check", "phone health check",
    )

    @Test fun memory() = check("info-memory", "how much ram do i have", "memory usage", "whats my memory", "ram")

    @Test fun temperature() = check("info-temperature", "phone temperature", "is my phone hot", "is it overheating", "cpu temperature")

    @Test fun uptime() = check("info-uptime", "uptime", "how long has my phone been on", "when was the last restart")

    @Test fun device() = check("info-device", "android version", "what phone is this", "security patch", "what model is my phone")

    @Test fun network() = check("info-network", "am i connected to the internet", "am i online", "network status", "is my vpn on")

    @Test fun backgroundApps() = check("info-running-apps", "background apps", "Background apps", "what apps are open")

    @Test fun stoppingTheListedApps() = check(
        "force-stop-listed",
        "terminate them", "kill all background apps", "close all the apps", "force stop everything",
    )

    @Test fun stoppingItWithoutKnowingWhichApp() = check("force-stop-which", "can you terminate it")

    @Test fun stoppingOneApp() {
        check("force-stop-app", "force stop instagram", "close telegram", "kill the voice recorder app")
        // These belong to other commands.
        check("nav-power-off", "shut down the phone")
        assertEquals(null, CommandRouter.route("close it")?.id?.takeIf { it == "force-stop-app" })
    }

    @Test fun existingCommandsStillWin() {
        check("info-battery", "battery percent", "whats my battery")
        check("info-storage", "how much storage do i have")
        check("toggle:Wi-Fi:on", "turn on wifi")
        check("settings:main", "open settings")
        check("info-time", "what time is it")
    }
}

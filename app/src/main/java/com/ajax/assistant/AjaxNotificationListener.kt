package com.ajax.assistant

import android.service.notification.NotificationListenerService

/** Lets Ajax read the active notifications when asked. Nothing is stored or logged. */
class AjaxNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: AjaxNotificationListener? = null
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }
}

package com.bingwa.mobile

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build

internal fun Service.startForegroundCompat(
    notificationId: Int,
    notification: Notification,
    foregroundServiceType: Int
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(notificationId, notification, foregroundServiceType)
    } else {
        @Suppress("DEPRECATION")
        startForeground(notificationId, notification)
    }
}

internal object ForegroundServiceTypes {
    val dataSync: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
}

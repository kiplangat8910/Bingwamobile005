package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val allowBootForegroundRestart = context.applicationInfo.targetSdkVersion < 35
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            if (
                allowBootForegroundRestart &&
                prefs.safeGetBoolean("automation_enabled", true)
            ) {
                ServiceLauncher.startBalanceChecker(context)
            }
            if (prefs.safeGetBoolean("auto_retry", false)) {
                UnderMaintenanceRetryReceiver.schedule(context)
            }
            ScheduledOfferDispatchStore.rescheduleAll(context)
            val cfg = RelayManager.load(context)
            if (allowBootForegroundRestart && cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") {
                RelayManager.startRelayHotspotService(context)
            }
        }
    }
}

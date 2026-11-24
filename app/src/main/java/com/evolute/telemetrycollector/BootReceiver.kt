package com.evolute.telemetrycollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.evolute.telemetrycollector.data.KeystoreTokenStore

/**
 * Resume telemetry collection after the device finishes booting.
 * BOOT_COMPLETED is delivered after the user has unlocked the device,
 * so the Keystore-backed token store is available.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            if (KeystoreTokenStore(context).get() == null) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CollectorService::class.java)
            )
        } catch (_: Exception) {
            // The app will retry starting collection on the next manual launch.
        }
    }
}

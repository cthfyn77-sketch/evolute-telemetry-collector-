package com.evolute.telemetrycollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.evolute.telemetrycollector.data.ApiClient
import com.evolute.telemetrycollector.data.KeystoreTokenStore
import com.evolute.telemetrycollector.data.SnapshotDb
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            pollJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (pollJob?.isActive == true) return START_STICKY

        pollJob = scope.launch {
            while (isActive) {
                try {
                    val tokenStore = KeystoreTokenStore(this@CollectorService)
                    // Initialize Python and resolve the bridge inside the retry loop.
                    // A transient startup/import failure must not permanently stop polling.
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(applicationContext))
                    }
                    val bridge = Python.getInstance().getModule("evaconnect_bridge")
                    val saved = tokenStore.get()
                        ?: throw IOException("Сначала выполните вход в аккаунт")
                    val raw = bridge.callAttr(
                        "fetch_telemetry",
                        saved.access,
                        saved.refresh
                    ).toString()
                    val result = JSONObject(raw)

                    // evaconnect rotates refresh tokens on HTTP 401. Persist the
                    // returned pair immediately, still encrypted by Android Keystore.
                    val access = result.optString("access_token", saved.access).ifBlank { saved.access }
                    val refresh = result.optString("refresh_token", saved.refresh).ifBlank { saved.refresh }
                    if (access != saved.access || refresh != saved.refresh) {
                        tokenStore.save(saved.copy(access = access, refresh = refresh))
                    }

                    if (!result.optBoolean("ok", false)) {
                        throw IOException(result.optString("error", "evaconnect не вернул телеметрию"))
                    }

                    val telemetry = ApiClient.Telemetry(
                        soc = result.getDouble("soc"),
                        odometer = result.getDouble("odometer"),
                        stockRange = if (result.isNull("stock_range")) null else result.getDouble("stock_range"),
                        online = result.optBoolean("online", false),
                        ready = result.optBoolean("ready", false),
                        updatedAt = result.optString("updated_at").takeIf { it.isNotBlank() },
                        sensorsJson = result.optString("sensors_json", "{}")
                    )
                    val validationError = ApiClient.validateTelemetry(telemetry)
                    if (validationError != null) {
                        throw IOException(validationError)
                    }
                    when (SnapshotDb.get(this@CollectorService).insert(telemetry)) {
                        com.evolute.telemetrycollector.data.TelemetryFreshness.ACCEPT ->
                            updateNotification("evaconnect: получены данные. SOC ${telemetry.soc}%")
                        com.evolute.telemetrycollector.data.TelemetryFreshness.DUPLICATE ->
                            updateNotification("evaconnect: повторный снимок пропущен")
                        com.evolute.telemetrycollector.data.TelemetryFreshness.STALE ->
                            updateNotification("evaconnect: устаревший снимок пропущен")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the foreground service alive and retry on the next poll.
                    updateNotification(
                        "Ошибка evaconnect: ${e.message?.take(100) ?: e.javaClass.simpleName}"
                    )
                }
                delay(30_000)
            }
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Сбор телеметрии", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return buildNotification("Ожидание первого опроса через evaconnect")
    }

    private fun buildNotification(text: String) = Notification.Builder(this, CHANNEL)
        .setContentTitle("Evolute Telemetry Collector")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        getSharedPreferences("collector_status", MODE_PRIVATE).edit()
            .putString("last_status", text)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "collector"
        private const val NOTIFICATION_ID = 401
    }
}

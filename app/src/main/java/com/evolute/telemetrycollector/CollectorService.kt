package com.evolute.telemetrycollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.evolute.telemetrycollector.data.ApiClient
import com.evolute.telemetrycollector.data.KeystoreTokenStore
import com.evolute.telemetrycollector.data.SnapshotDb
import kotlinx.coroutines.*

class CollectorService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onBind(intent:Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); startForeground(NOTIFICATION_ID,notification()) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if(scope.coroutineContext[Job]?.isActive==true) return START_STICKY
        scope.launch {
            val api=ApiClient(KeystoreTokenStore(this@CollectorService))
            while(isActive) {
                try {
                    val tel=api.fetchTelemetry()
                    if(tel.online && tel.ready) SnapshotDb.get(this@CollectorService).insert(tel)
                    updateNotification("Последний опрос: SOC ${tel.soc}%")
                } catch(e:Exception) { updateNotification("Ошибка опроса: ${e.message?.take(90) ?: "API недоступен"}") }
                delay(30_000)
            }
        }
        return START_STICKY
    }
    private fun notification():Notification {
        val nm=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel(CHANNEL,"Сбор телеметрии",NotificationManager.IMPORTANCE_LOW))
        return buildNotification("Сбор данных каждые 30 секунд")
    }
    private fun buildNotification(s:String)=if(Build.VERSION.SDK_INT>=26)
        Notification.Builder(this,CHANNEL).setContentTitle("Evolute Telemetry Collector").setContentText(s).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
        else @Suppress("DEPRECATION") Notification.Builder(this).setContentTitle("Evolute Telemetry Collector").setContentText(s).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    private fun updateNotification(s:String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,buildNotification(s)) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private const val CHANNEL="collector"; private const val NOTIFICATION_ID=401 }
}

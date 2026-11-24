package com.evolute.telemetrycollector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.evolute.telemetrycollector.data.ApiClient
import com.evolute.telemetrycollector.data.KeystoreTokenStore
import com.evolute.telemetrycollector.data.SnapshotDb
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var statusJob: Job? = null
    private lateinit var phone:EditText; private lateinit var country:EditText; private lateinit var code:EditText
    private lateinit var status:TextView; private lateinit var actionStatus:TextView
    private lateinit var store:KeystoreTokenStore
    private val csvRequest=72
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); store=KeystoreTokenStore(this); buildUi() }
    private fun buildUi() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,28,28,28) }
        fun label(s:String) { root.addView(TextView(this).apply { text=s; textSize=16f; setPadding(0,12,0,4) }) }
        phone=EditText(this).apply { hint="Телефон с кодом страны, без +"; inputType=3 }; country=EditText(this).apply { hint="Страна (две буквы), например RU"; setText("RU") }
        code=EditText(this).apply { hint="Код из SMS"; inputType=2 }
        root.addView(TextView(this).apply { text="Evolute Telemetry Collector"; textSize=23f })
        label("Авторизация Evolute"); root.addView(phone); root.addView(country); root.addView(code)
        root.addView(btn("Запросить SMS-код") { runTask { ApiClient(store).requestOtp(phone.text.toString(),country.text.toString()); "Запрос кода отправлен" } })
        root.addView(btn("Войти") { runTask { ApiClient(store).signIn(phone.text.toString(),code.text.toString()); "Авторизация сохранена в Android Keystore" } })
        label("Сбор телеметрии"); root.addView(btn("Начать сбор") { if(store.get()==null) show("Сначала войдите в аккаунт") else { requestNotificationPermission(); ContextCompat.startForegroundService(this,Intent(this,CollectorService::class.java)); show("Сбор запущен. Интервал 30 секунд.") } })
        root.addView(btn("Остановить сбор") { startService(Intent(this,CollectorService::class.java).setAction("STOP")); show("Остановка отправлена") })
        root.addView(btn("Обновить статус / число записей") { refreshStatus() })
        root.addView(btn("Экспорт CSV") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"evolute-telemetry.csv"),csvRequest) })
        label("Состояние сбора (обновляется автоматически)"); status=TextView(this).apply { textSize=14f }; root.addView(status)
        label("Результат последнего действия"); actionStatus=TextView(this).apply { textSize=14f }; root.addView(actionStatus)
        root.addView(TextView(this).apply { text="Только чтение API. Команды автомобилю не отправляются. Расчёта запаса хода нет."; textSize=12f; setPadding(0,18,0,0) })
        setContentView(ScrollView(this).apply { addView(root,ViewGroup.LayoutParams(-1,-2)) }); refreshStatus()
    }
    private fun btn(t:String,action:()->Unit)=Button(this).apply { text=t; setOnClickListener { action() } }
    private fun runTask(block:suspend ()->String) { scope.launch { try { val result=withContext(Dispatchers.IO) { block() }; show(result) } catch(e:Exception) { show("Ошибка: ${e.message ?: e.javaClass.simpleName}") } } }
    private fun show(s:String) { actionStatus.text=s }
    private fun statusText(): String {
        val db=SnapshotDb.get(this)
        return CollectorStatusFormatter.format(db.count(), db.latest(), store.get()!=null)
    }
    private suspend fun updateStatus() {
        try {
            status.text=withContext(Dispatchers.IO) { statusText() }
        } catch(e:Exception) {
            status.text="Ошибка чтения статуса: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    private fun refreshStatus() { scope.launch { updateStatus() } }
    override fun onResume() {
        super.onResume()
        statusJob?.cancel()
        statusJob=scope.launch {
            while(isActive) {
                updateStatus()
                delay(3_000)
            }
        }
    }
    override fun onPause() {
        statusJob?.cancel()
        statusJob=null
        super.onPause()
    }
    private fun requestNotificationPermission() { if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),55) }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==csvRequest && resultCode==RESULT_OK) { val uri=data?.data ?: return; scope.launch(Dispatchers.IO) { try { contentResolver.openOutputStream(uri)?.use { SnapshotDb.get(this@MainActivity).exportCsv(it) }; withContext(Dispatchers.Main) { show("CSV экспортирован") } } catch(e:Exception) { withContext(Dispatchers.Main) { show("Ошибка экспорта: ${e.message}") } } } } }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

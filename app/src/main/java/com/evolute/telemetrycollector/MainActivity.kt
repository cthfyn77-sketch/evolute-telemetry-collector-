package com.evolute.telemetrycollector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.IOException
import com.evolute.telemetrycollector.data.KeystoreTokenStore
import com.evolute.telemetrycollector.data.SnapshotDb
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private lateinit var phone: EditText
    private lateinit var country: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView
    private lateinit var actionStatus: TextView
    private lateinit var authFeedback: TextView
    private lateinit var socValue: TextView
    private lateinit var socBar: View
    private lateinit var rangeValue: TextView
    private lateinit var calculatedRangeValue: TextView
    private lateinit var calculatedRangeHint: TextView
    private lateinit var consumptionValue: TextView
    private lateinit var odometerValue: TextView
    private lateinit var apiFieldsValue: TextView
    private lateinit var recordValue: TextView
    private lateinit var connectionValue: TextView
    private lateinit var chart: TelemetryChartView
    private lateinit var chartCard: LinearLayout
    private lateinit var store: KeystoreTokenStore
    private val csvRequest = 72

    private val bg = Color.rgb(12, 19, 24)
    private val surface = Color.rgb(23, 34, 42)
    private val surface2 = Color.rgb(29, 43, 52)
    private val accent = Color.rgb(91, 218, 193)
    private val muted = Color.rgb(151, 172, 181)
    private val white = Color.rgb(241, 247, 248)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KeystoreTokenStore(this)
        buildUi()
        if (store.get() != null) autoStartCollectorIfAuthorized()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(): LinearLayout = column().apply {
        background = rounded(surface)
        setPadding(dp(16), dp(15), dp(16), dp(15))
    }

    private fun addGap(parent: LinearLayout, height: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    /** First-run sign-in screen. The dashboard is shown only after successful authentication. */
    private fun buildAuthUi() {
        val content = column().apply { setPadding(dp(22), dp(30), dp(22), dp(28)); gravity = Gravity.CENTER_HORIZONTAL }
        addGap(content, 34)
        content.addView(text("EV", 38f, accent, true).apply { gravity = Gravity.CENTER })
        addGap(content, 6)
        content.addView(text("EVOLUTE TELEMETRY", 20f, white, true).apply { gravity = Gravity.CENTER })
        content.addView(text("COLLECTOR  /  PRO", 10f, muted, true).apply { gravity = Gravity.CENTER })
        addGap(content, 30)
        val auth = card()
        auth.addView(text("АВТОРИЗАЦИЯ EVACONNECT", 14f, white, true))
        addGap(auth, 8)
        auth.addView(text("Войдите в аккаунт, чтобы открыть панель телеметрии и включить автоматический сбор.", 12f, muted))
        addGap(auth, 16)
        phone = edit("Телефон с кодом страны, без +", 3)
        country = edit("Страна (две буквы)", 0).apply { setText("RU") }
        code = edit("Код из SMS", 2)
        auth.addView(phone); addGap(auth, 8)
        auth.addView(country); addGap(auth, 8)
        auth.addView(code); addGap(auth, 12)
        auth.addView(actionButton("ЗАПРОСИТЬ SMS-КОД", surface2, white) {
            runTask { evaconnectAuth("request_otp", phone.text.toString(), country.text.toString()); "SMS-код отправлен" }
        })
        addGap(auth, 8)
        auth.addView(actionButton("ВОЙТИ И ОТКРЫТЬ ПРИЛОЖЕНИЕ", accent, bg) {
            runTask {
                evaconnectAuth("sign_in", phone.text.toString(), code.text.toString())
                withContext(Dispatchers.Main) {
                    buildUi()
                    requestNotificationPermission()
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, CollectorService::class.java))
                    show("Авторизация успешна · автоматический сбор запущен")
                }
                "Авторизация успешна"
            }
        })
        addGap(auth, 12)
        authFeedback = text("Ожидание авторизации", 11f, muted)
        auth.addView(authFeedback)
        addGap(auth, 12)
        auth.addView(text("Токены сохраняются в защищённом Android Keystore. Команды автомобилю не отправляются.", 10f, muted))
        content.addView(auth, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(content, ViewGroup.LayoutParams(-1, -2)) }
        window.statusBarColor = bg; window.navigationBarColor = bg; window.decorView.systemUiVisibility = 0
        setContentView(scroll)
    }

    private fun buildUi() {
        val content = column().apply { setPadding(dp(18), dp(22), dp(18), dp(28)) }
        content.addView(row().apply {
            addView(text("EV", 24f, accent, true))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, 0, 0)
                addView(text("EVOLUTE", 19f, white, true))
                addView(text("TELEMETRY  /  PRO", 10f, muted, true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("● ONLINE", 12f, accent, true))
        })
        addGap(content, 20)

        // Keep sign-in on the dashboard until a valid session is saved.
        if (store.get() == null) {
            val auth = card()
            auth.addView(text("АВТОРИЗАЦИЯ EVACONNECT", 14f, white, true))
            addGap(auth, 8)
            auth.addView(text("Войдите в аккаунт, чтобы включить автоматический сбор телеметрии.", 12f, muted))
            addGap(auth, 14)
            phone = edit("Телефон с кодом страны, без +", 3)
            country = edit("Страна (две буквы)", 0).apply { setText("RU") }
            code = edit("Код из SMS", 2)
            auth.addView(phone); addGap(auth, 8)
            auth.addView(country); addGap(auth, 8)
            auth.addView(code); addGap(auth, 12)
            auth.addView(actionButton("ЗАПРОСИТЬ SMS-КОД", surface2, white) {
                runTask { evaconnectAuth("request_otp", phone.text.toString(), country.text.toString()); "SMS-код отправлен" }
            })
            addGap(auth, 8)
            auth.addView(actionButton("ВОЙТИ", accent, bg) {
                runTask {
                    evaconnectAuth("sign_in", phone.text.toString(), code.text.toString())
                    withContext(Dispatchers.Main) {
                        buildUi()
                        requestNotificationPermission()
                        ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, CollectorService::class.java))
                    }
                    "Авторизация успешна · автоматический сбор запущен"
                }
            })
            addGap(auth, 10)
            authFeedback = text("Ожидание авторизации", 11f, muted)
            auth.addView(authFeedback)
            addGap(auth, 10)
            auth.addView(text("Токены хранятся в защищённом Android Keystore. Команды автомобилю не отправляются.", 10f, muted))
            content.addView(auth)
            addGap(content, 12)
        }

        val hero = card()
        hero.addView(text("УРОВЕНЬ ЗАРЯДА", 11f, muted, true))
        addGap(hero, 4)
        val socRow = row()
        socValue = text("—%", 43f, white, true)
        socRow.addView(socValue, LinearLayout.LayoutParams(0, -2, 1f))
        socRow.addView(text("SOC", 12f, accent, true))
        hero.addView(socRow)
        addGap(hero, 10)
        socBar = View(this).apply { background = rounded(accent, 8) }
        val track = FrameLayout(this).apply {
            background = rounded(Color.rgb(49, 65, 73), 8)
            addView(socBar, FrameLayout.LayoutParams(0, dp(8)))
        }
        hero.addView(track, LinearLayout.LayoutParams(-1, dp(8)))
        addGap(hero, 9)
        connectionValue = text("Ожидание телеметрии", 12f, muted)
        hero.addView(connectionValue)
        content.addView(hero)

        addGap(content, 12)
        val rangeCard = card()
        rangeCard.addView(text("ЗАПАС ХОДА", 10f, muted, true))
        addGap(rangeCard, 10)
        val rangeColumns = row()
        val stockColumn = column()
        stockColumn.addView(text("ШТАТНЫЙ", 9f, muted, true))
        rangeValue = text("— км", 24f, white, true)
        stockColumn.addView(rangeValue)
        stockColumn.addView(text("По данным авто", 9f, muted))
        val calcColumn = column()
        calcColumn.addView(text("НАШ РАСЧЁТ", 9f, accent, true))
        calculatedRangeValue = text("— км", 24f, accent, true)
        calcColumn.addView(calculatedRangeValue)
        calculatedRangeHint = text("Нужно проехать 10 км", 9f, muted)
        calcColumn.addView(calculatedRangeHint)
        rangeColumns.addView(stockColumn, LinearLayout.LayoutParams(0, -2, 1f))
        rangeColumns.addView(View(this).apply { setBackgroundColor(Color.rgb(54, 70, 78)) },
            LinearLayout.LayoutParams(dp(1), dp(48)))
        rangeColumns.addView(calcColumn, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(14)
        })
        rangeCard.addView(rangeColumns)
        content.addView(rangeCard)

        addGap(content, 10)
        val consumptionCard = card()
        consumptionCard.addView(text("СРЕДНИЙ РАСХОД БАТАРЕИ", 10f, muted, true))
        addGap(consumptionCard, 5)
        consumptionValue = text("— % / 100 км", 27f, white, true)
        consumptionCard.addView(consumptionValue)
        consumptionCard.addView(text("По фактическому расходу SOC за последние 10 км", 9f, muted))
        content.addView(consumptionCard)

        addGap(content, 10)
        val odoCard = card()
        odoCard.addView(text("ОДОМЕТР", 10f, muted, true))
        odometerValue = text("— км", 22f, white, true)
        odoCard.addView(odometerValue)
        content.addView(odoCard)

        addGap(content, 10)
        val apiFieldsCard = card()
        apiFieldsCard.addView(text("ДОСТУПНЫЕ ПОЛЯ API", 11f, white, true))
        addGap(apiFieldsCard, 5)
        apiFieldsCard.addView(text("Полный ответ evaconnect · идентификаторы и токены скрыты", 9f, muted))
        addGap(apiFieldsCard, 8)
        apiFieldsValue = text("Ожидание первой телеметрии", 10f, white)
        apiFieldsValue.setLineSpacing(dp(2).toFloat(), 1f)
        apiFieldsCard.addView(apiFieldsValue)
        content.addView(apiFieldsCard)

        addGap(content, 12)
        chartCard = card()
        chartCard.addView(text("ДИНАМИКА ЗАРЯДА", 12f, white, true))
        addGap(chartCard, 10)
        chartCard.addView(row().apply {
            addView(text("●  SOC / ПРОБЕГ", 9f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("●  SOC / ВРЕМЯ", 9f, Color.rgb(96, 165, 250), true))
        })
        addGap(chartCard, 8)
        chart = TelemetryChartView(this)
        chartCard.addView(chart, LinearLayout.LayoutParams(-1, dp(176)))
        content.addView(chartCard)

        addGap(content, 12)
        val collection = card()
        collection.addView(row().apply {
            addView(text("СБОР ТЕЛЕМЕТРИИ", 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("● 30 СЕК", 10f, accent, true))
        })
        addGap(collection, 10)
        recordValue = text("Записей в базе: 0", 14f, white, true)
        collection.addView(recordValue)
        addGap(collection, 5)
        status = text("Ожидание первого опроса", 11f, muted)
        collection.addView(status)
        addGap(collection, 12)
        collection.addView(actionButton("ОБНОВИТЬ СТАТУС", surface2, white) {
            refreshStatus()
        })
        addGap(collection, 8)
        collection.addView(actionButton("ЭКСПОРТ CSV", surface2, accent) {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "evolute-telemetry.csv"), csvRequest
            )
        })
        content.addView(collection)

        addGap(content, 12)
        val result = card()
        result.addView(text("ПОСЛЕДНЕЕ ДЕЙСТВИЕ", 10f, muted, true))
        actionStatus = text("Готово к работе", 12f, white)
        result.addView(actionStatus)
        content.addView(result)
        addGap(content, 18)
        content.addView(text("EVOLUTE TELEMETRY COLLECTOR  ·  LOCAL DATA ONLY", 9f, muted).apply {
            gravity = Gravity.CENTER
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        setContentView(scroll)
        refreshStatus()
    }

    private fun edit(hintText: String, input: Int) = EditText(this).apply {
        hint = hintText; inputType = input
        textSize = 14f; setTextColor(white); setHintTextColor(muted)
        background = rounded(surface2, 12)
        setPadding(dp(13), dp(10), dp(13), dp(10))
        minHeight = dp(48)
    }

    private fun actionButton(label: String, fill: Int, foreground: Int, action: () -> Unit) =
        TextView(this).apply {
            text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(foreground)
            background = rounded(fill, 13)
            setPadding(dp(12), dp(13), dp(12), dp(13))
            isClickable = true; isFocusable = true
            setOnClickListener { action() }
            minHeight = dp(48)
        }


    /** All account authentication is delegated to the vendored EVACONNECT Python client. */
    private fun evaconnectAuth(action: String, phoneNumber: String, value: String) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val module = Python.getInstance().getModule("evaconnect_bridge")
        val raw = when (action) {
            "request_otp" -> module.callAttr("request_otp", phoneNumber, value).toString()
            "sign_in" -> module.callAttr("sign_in", phoneNumber, value).toString()
            else -> throw IOException("Неизвестная операция авторизации")
        }
        val result = JSONObject(raw)
        if (!result.optBoolean("ok", false)) {
            throw IOException(result.optString("error", "EVACONNECT: ошибка авторизации"))
        }
        if (action == "sign_in") {
            val access = result.optString("access_token")
            val refresh = result.optString("refresh_token")
            if (access.isBlank() || refresh.isBlank()) throw IOException("EVACONNECT не вернул полную пару токенов")
            store.save(ApiClient.Tokens(access, refresh,
                result.optString("user_id").takeIf { it.isNotBlank() },
                result.optString("user_token").takeIf { it.isNotBlank() },
                result.optString("widget_id").takeIf { it.isNotBlank() }))
        }
    }

    private fun runTask(block: suspend () -> String) {
        scope.launch {
            try { show(withContext(Dispatchers.IO) { block() }) }
            catch (e: Exception) { show("Ошибка: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun show(value: String) {
        if (::actionStatus.isInitialized) actionStatus.text = value
        else if (::authFeedback.isInitialized) authFeedback.text = value
    }

    /** Battery level palette shared by the SOC indicator and chart. */
    private fun socLevelColor(soc: Double): Int = when {
        soc >= 80.0 -> Color.rgb(34, 197, 94)
        soc >= 60.0 -> Color.rgb(132, 204, 22)
        soc >= 40.0 -> Color.rgb(234, 179, 8)
        soc >= 20.0 -> Color.rgb(249, 115, 22)
        else -> Color.rgb(239, 68, 68)
    }

    private fun updateDashboard() {
        val db = SnapshotDb.get(this)
        val latest = db.latestSnapshot()
        recordValue.text = "Записей в базе: ${db.count()}"
        if (latest == null) {
            socValue.text = "—%"
            socValue.setTextColor(white)
            socBar.background = rounded(Color.rgb(73, 88, 96), 8)
            rangeValue.text = "— км"
            consumptionValue.text = "— % / 100 км"
            odometerValue.text = "— км"
            connectionValue.text = "Ожидание телеметрии"
        } else {
            val currentSoc = latest.soc.coerceIn(0.0, 100.0)
            val socColor = socLevelColor(currentSoc)
            socValue.text = "${latest.soc.toInt()}%"
            socValue.setTextColor(socColor)
            socBar.background = rounded(socColor, 8)
            rangeValue.text = latest.stockRange?.let { "${it.toInt()} км" } ?: "— км"
            odometerValue.text = "${latest.odometer.toInt()} км"
            connectionValue.text = if (latest.online) "● АВТОМОБИЛЬ В СЕТИ" else "○ АВТОМОБИЛЬ НЕ В СЕТИ"
            connectionValue.setTextColor(if (latest.online) accent else muted)
            val trackWidth = (socBar.parent as? View)?.width
                ?: (resources.displayMetrics.widthPixels - dp(68)).coerceAtLeast(dp(100))
            val params = socBar.layoutParams
            params.width = (trackWidth * (currentSoc / 100.0)).toInt()
            socBar.layoutParams = params
        }
        apiFieldsValue.text = latest?.let { formatApiFields(it.sensorsJson) } ?: "Ожидание первой телеметрии"
        val averageConsumption = db.averageConsumptionPer100Km()
        consumptionValue.text = averageConsumption?.let { String.format(java.util.Locale.US, "%.1f %% / 100 км", it) } ?: "— % / 100 км"
        val session = if (store.get() != null) "Авторизация: сохранена" else "Авторизация: не выполнена"
        val last = getSharedPreferences("collector_status", MODE_PRIVATE)
            .getString("last_status", "Опрос ещё не выполнялся") ?: "Опрос ещё не выполнялся"
        val updated = getSharedPreferences("collector_status", MODE_PRIVATE).getLong("updated_at", 0L)
        status.text = "$session\n$last" + if (updated > 0) "\nОбновлено: ${java.time.Instant.ofEpochMilli(updated)}" else ""
        val snapshots = db.recentSnapshots(240)
        val mileageValues = snapshots.map { it.odometer }
        val hasChartHistory = snapshots.size >= 2
        chartCard.visibility = if (hasChartHistory) View.VISIBLE else View.GONE
        chart.setSnapshots(snapshots)
    }

    private fun formatApiFields(raw: String): String {
        return try {
            val root = JSONObject(raw)
            val lines = mutableListOf<String>()
            fun walk(value: Any?, path: String) {
                if (lines.size >= 80) return
                when (value) {
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext() && lines.size < 80) {
                            val key = keys.next()
                            walk(value.opt(key), if (path.isEmpty()) key else path + "." + key)
                        }
                    }
                    is JSONArray -> {
                        lines.add(path + ": [" + value.length() + " элементов]")
                        for (i in 0 until minOf(value.length(), 10)) walk(value.opt(i), path + "[" + i + "]")
                    }
                    JSONObject.NULL -> lines.add(path + ": null")
                    else -> lines.add(path + ": " + value.toString().take(180))
                }
            }
            walk(root, "")
            if (lines.isEmpty()) "API не вернул дополнительных полей"
            else lines.joinToString("\n") + if (lines.size >= 80) "\n… список ограничен первыми 80 полями" else ""
        } catch (_: Exception) {
            "Не удалось разобрать JSON телеметрии"
        }
    }

    private fun refreshStatus() {
        scope.launch {
            try { withContext(Dispatchers.IO) { SnapshotDb.get(this@MainActivity).count() }; updateDashboard() }
            catch (e: Exception) { status.text = "Ошибка чтения базы: ${e.message}" }
        }
    }

    override fun onResume() {
        super.onResume()
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) { refreshStatus(); delay(3000) }
        }
    }

    override fun onPause() {
        statusJob?.cancel(); statusJob = null
        super.onPause()
    }

    /**
     * Start the foreground collector automatically whenever the user opens the app.
     * First-time SMS sign-in remains a one-time manual action; tokens are reused
     * from Android Keystore on all subsequent launches.
     */
    private fun autoStartCollectorIfAuthorized() {
        try {
            if (store.get() == null) {
                show("Для автоматического сбора сначала выполните вход один раз")
                return
            }
            requestNotificationPermission()
            ContextCompat.startForegroundService(
                this,
                Intent(this, CollectorService::class.java)
            )
            show("Автоматический сбор запущен · опрос каждые 30 секунд")
        } catch (e: Exception) {
            show("Не удалось автоматически запустить сбор: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 55)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == csvRequest && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            scope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { SnapshotDb.get(this@MainActivity).exportCsv(it) }
                    withContext(Dispatchers.Main) { show("CSV экспортирован") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { show("Ошибка экспорта: ${e.message}") }
                }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

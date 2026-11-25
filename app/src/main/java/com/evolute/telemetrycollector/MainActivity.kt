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
import com.evolute.telemetrycollector.data.ApiClient
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.IOException
import com.evolute.telemetrycollector.data.KeystoreTokenStore
import com.evolute.telemetrycollector.data.SnapshotDb
import com.evolute.telemetrycollector.data.BatteryHistoryAnalyzer
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
    private lateinit var batteryMetricsValue: TextView
    private lateinit var batteryHistoryValue: TextView
    private lateinit var apiFieldsValue: TextView
    private lateinit var apiFieldsCard: LinearLayout
    private lateinit var sensorReadingsContent: LinearLayout
    private lateinit var sensorToggleButton: TextView
    private var sensorReadingsVisible = true
    private lateinit var recordValue: TextView
    private lateinit var connectionValue: TextView
    private lateinit var dashboardBadge: TextView
    private lateinit var vehicleStateValue: TextView
    private lateinit var dataFreshnessValue: TextView
    private lateinit var distanceChart: TelemetryChartView
    private lateinit var timeChart: TelemetryChartView
    private lateinit var distanceChartCard: LinearLayout
    private lateinit var timeChartCard: LinearLayout
    private lateinit var tripHistoryCard: LinearLayout
    private lateinit var tripHistoryValue: TextView
    private var lastTripHistoryRecordCount = -1L
    private var lastBatteryHistoryRecordCount = -1L
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
        sensorReadingsVisible = getSharedPreferences("collector_ui", MODE_PRIVATE)
            .getBoolean("sensor_readings_visible", true)
        val content = column().apply { setPadding(dp(18), dp(22), dp(18), dp(28)) }
        content.addView(row().apply {
            addView(text("EV", 24f, accent, true))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, 0, 0)
                addView(text("EVOLUTE", 19f, white, true))
                addView(text("TELEMETRY  /  PRO", 10f, muted, true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            dashboardBadge = text("○ ОЖИДАНИЕ", 11f, muted, true)
            addView(dashboardBadge)
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
        val vehicleStateCard = card()
        vehicleStateCard.addView(text("СОСТОЯНИЕ АВТОМОБИЛЯ", 10f, muted, true))
        addGap(vehicleStateCard, 8)
        vehicleStateValue = text("Ожидание данных автомобиля", 16f, white, true)
        vehicleStateCard.addView(vehicleStateValue)
        addGap(vehicleStateCard, 5)
        dataFreshnessValue = text("Данные ещё не получены", 10f, muted)
        vehicleStateCard.addView(dataFreshnessValue)
        content.addView(vehicleStateCard)

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
        calculatedRangeHint = text("Штатный прогноз · сбор истории 5 км", 9f, muted)
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
        consumptionCard.addView(text("По фактическому расходу SOC за последние 5 км", 9f, muted))
        content.addView(consumptionCard)

        addGap(content, 10)
        val odoCard = card()
        odoCard.addView(text("ОДОМЕТР", 10f, muted, true))
        odometerValue = text("— км", 22f, white, true)
        odoCard.addView(odometerValue)
        content.addView(odoCard)

        addGap(content, 10)
        val batteryCard = card()
        batteryCard.addView(text("ПАРАМЕТРЫ БАТАРЕИ", 11f, white, true))
        addGap(batteryCard, 6)
        batteryCard.addView(text(
            "Только поля, фактически полученные от EVACONNECT · значения и единицы без пересчёта",
            9f, muted
        ))
        addGap(batteryCard, 8)
        batteryMetricsValue = text("Ожидание данных батареи", 11f, white)
        batteryMetricsValue.setLineSpacing(dp(2).toFloat(), 1f)
        batteryCard.addView(batteryMetricsValue)
        content.addView(batteryCard)

        addGap(content, 10)
        val batteryHistoryCard = card()
        batteryHistoryCard.addView(text("ИСТОРИЯ БАТАРЕИ · 4 ЧАСА", 11f, white, true))
        addGap(batteryHistoryCard, 6)
        batteryHistoryCard.addView(text("История датчиков из локальной базы · события показывают возможные сбои телеметрии, а не неисправность автомобиля", 9f, muted))
        addGap(batteryHistoryCard, 8)
        batteryHistoryValue = text("Ожидание истории батареи", 10f, white)
        batteryHistoryValue.setLineSpacing(dp(2).toFloat(), 1f)
        batteryHistoryCard.addView(batteryHistoryValue)
        content.addView(batteryHistoryCard)

        addGap(content, 10)
        apiFieldsCard = card()
        apiFieldsCard.addView(row().apply {
            addView(text("ТЕХНИЧЕСКИЕ ДАТЧИКИ", 11f, white, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            sensorToggleButton = actionButton(
                if (sensorReadingsVisible) "СКРЫТЬ" else "ПОКАЗАТЬ",
                surface2, accent
            ) { toggleSensorReadings() }
            addView(sensorToggleButton, LinearLayout.LayoutParams(-2, -2))
        })
        sensorReadingsContent = column()
        sensorReadingsContent.visibility = if (sensorReadingsVisible) View.VISIBLE else View.GONE
        addGap(sensorReadingsContent, 5)
        sensorReadingsContent.addView(text("Полный ответ evaconnect · идентификаторы и токены скрыты", 9f, muted))
        addGap(sensorReadingsContent, 8)
        apiFieldsValue = text("Ожидание первой телеметрии", 10f, white)
        apiFieldsValue.setLineSpacing(dp(2).toFloat(), 1f)
        sensorReadingsContent.addView(apiFieldsValue)
        apiFieldsCard.addView(sensorReadingsContent)
        content.addView(apiFieldsCard)

        addGap(content, 12)
        distanceChartCard = card()
        distanceChartCard.addView(text("SOC · ЗАВИСИМОСТЬ ОТ ПРОБЕГА", 11f, accent, true))
        addGap(distanceChartCard, 5)
        distanceChartCard.addView(text("Изменение процента заряда по пройденным километрам", 9f, muted))
        addGap(distanceChartCard, 8)
        distanceChart = TelemetryChartView(this, TelemetryChartView.AxisMode.DISTANCE)
        distanceChartCard.addView(distanceChart, LinearLayout.LayoutParams(-1, dp(176)))
        content.addView(distanceChartCard)

        addGap(content, 10)
        timeChartCard = card()
        timeChartCard.addView(text("SOC · ЗАВИСИМОСТЬ ОТ ВРЕМЕНИ", 11f, Color.rgb(96, 165, 250), true))
        addGap(timeChartCard, 5)
        timeChartCard.addView(text("Изменение заряда во времени · стоянки и зарядка", 9f, muted))
        addGap(timeChartCard, 8)
        timeChart = TelemetryChartView(this, TelemetryChartView.AxisMode.TIME)
        timeChartCard.addView(timeChart, LinearLayout.LayoutParams(-1, dp(176)))
        content.addView(timeChartCard)

        addGap(content, 12)
        tripHistoryCard = card()
        tripHistoryCard.addView(text("ИСТОРИЯ ПОЕЗДОК", 12f, white, true))
        addGap(tripHistoryCard, 6)
        tripHistoryCard.addView(text(
            "По фактическим изменениям одометра и SOC · поездка завершается после 15 минут без движения",
            9f, muted
        ))
        addGap(tripHistoryCard, 8)
        tripHistoryValue = text("Пока нет завершённых поездок", 11f, white)
        tripHistoryValue.setLineSpacing(dp(3).toFloat(), 1f)
        tripHistoryCard.addView(tripHistoryValue)
        content.addView(tripHistoryCard)

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

    private fun toggleSensorReadings() {
        sensorReadingsVisible = !sensorReadingsVisible
        sensorReadingsContent.visibility = if (sensorReadingsVisible) View.VISIBLE else View.GONE
        sensorToggleButton.text = if (sensorReadingsVisible) "СКРЫТЬ" else "ПОКАЗАТЬ"
        getSharedPreferences("collector_ui", MODE_PRIVATE).edit()
            .putBoolean("sensor_readings_visible", sensorReadingsVisible)
            .apply()
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
        val snapshotCount = db.count()
        recordValue.text = "Записей в базе: $snapshotCount"
        if (latest == null) {
            socValue.text = "—%"
            socValue.setTextColor(white)
            socBar.background = rounded(Color.rgb(73, 88, 96), 8)
            rangeValue.text = "— км"
            calculatedRangeValue.text = "— км"
            calculatedRangeHint.text = "Ожидание телеметрии"
            consumptionValue.text = "— % / 100 км"
            odometerValue.text = "— км"
            connectionValue.text = "Ожидание телеметрии"
            connectionValue.setTextColor(muted)
            dashboardBadge.text = "○ ОЖИДАНИЕ"
            dashboardBadge.setTextColor(muted)
            vehicleStateValue.text = "Ожидание данных автомобиля"
            vehicleStateValue.setTextColor(white)
            dataFreshnessValue.text = "Данные ещё не получены"
        } else {
            val currentSoc = latest.soc.coerceIn(0.0, 100.0)
            val socColor = socLevelColor(currentSoc)
            socValue.text = "${latest.soc.toInt()}%"
            socValue.setTextColor(socColor)
            socBar.background = rounded(socColor, 8)
            rangeValue.text = latest.stockRange?.let { "${it.toInt()} км" } ?: "— км"
            val calculatedRange = db.calculatedRangeKm()
            calculatedRangeValue.text = (calculatedRange ?: latest.stockRange)?.let { "${it.toInt()} км" } ?: "— км"
            calculatedRangeHint.text = if (calculatedRange != null) {
                "По фактическому расходу SOC · окно 5 км"
            } else {
                "Штатный прогноз · сбор истории 5 км"
            }
            odometerValue.text = "${latest.odometer.toInt()} км"
            connectionValue.text = if (latest.online) "● АВТОМОБИЛЬ В СЕТИ" else "○ АВТОМОБИЛЬ НЕ В СЕТИ"
            connectionValue.setTextColor(if (latest.online) accent else muted)
            dashboardBadge.text = if (latest.online) "● АВТО НА СВЯЗИ" else "○ АВТО НЕ В СЕТИ"
            dashboardBadge.setTextColor(if (latest.online) accent else muted)
            vehicleStateValue.text = if (latest.ready) "Готов к движению" else "Не готов к движению"
            vehicleStateValue.setTextColor(if (latest.ready) accent else muted)
            dataFreshnessValue.text = formatDataFreshness(db, latest)
            val trackWidth = (socBar.parent as? View)?.width
                ?: (resources.displayMetrics.widthPixels - dp(68)).coerceAtLeast(dp(100))
            val params = socBar.layoutParams
            params.width = (trackWidth * (currentSoc / 100.0)).toInt()
            socBar.layoutParams = params
        }
        apiFieldsValue.text = latest?.let { formatApiFields(it.sensorsJson) } ?: "Ожидание первой телеметрии"
        if (snapshotCount != lastBatteryHistoryRecordCount) {
            val batteryHistory = BatteryHistoryAnalyzer.analyze(db.recentSnapshots(480))
            batteryHistoryValue.text = BatteryHistoryAnalyzer.render(batteryHistory)
            lastBatteryHistoryRecordCount = snapshotCount
        }
        batteryMetricsValue.text = if (latest != null) {
            BatteryTelemetryFormatter.format(db.recentSnapshots(20))
        } else "Ожидание данных батареи"
        val averageConsumption = db.averageConsumptionPer100Km()
        consumptionValue.text = averageConsumption?.let { String.format(java.util.Locale.US, "%.1f %% / 100 км", it) } ?: "— % / 100 км"
        val session = if (store.get() != null) "Авторизация: сохранена" else "Авторизация: не выполнена"
        val last = getSharedPreferences("collector_status", MODE_PRIVATE)
            .getString("last_status", "Опрос ещё не выполнялся") ?: "Опрос ещё не выполнялся"
        val updated = getSharedPreferences("collector_status", MODE_PRIVATE).getLong("updated_at", 0L)
        status.text = "$session\n$last" + if (updated > 0) "\nОбновлено: ${java.time.Instant.ofEpochMilli(updated)}" else ""
        if (snapshotCount != lastTripHistoryRecordCount) {
            renderTripHistory(db.allSnapshots())
            lastTripHistoryRecordCount = snapshotCount
        }
        val snapshots = db.recentSnapshots(240)
        val hasTimeHistory = snapshots.size >= 2
        val hasDistanceHistory = hasTimeHistory &&
            (snapshots.maxOf { it.odometer } - snapshots.minOf { it.odometer }) > 0.001
        distanceChartCard.visibility = if (hasDistanceHistory) View.VISIBLE else View.GONE
        timeChartCard.visibility = if (hasTimeHistory) View.VISIBLE else View.GONE
        distanceChart.setSnapshots(snapshots)
        timeChart.setSnapshots(snapshots)
    }

    private fun renderTripHistory(history: List<com.evolute.telemetrycollector.data.TelemetrySnapshot>) {
        val trips = com.evolute.telemetrycollector.data.TripHistoryAnalyzer.analyze(history).take(8)
        if (trips.isEmpty()) {
            tripHistoryValue.text = "Пока нет завершённых поездок. Данные появятся после движения и завершения стоянки."
            return
        }
        val formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
        tripHistoryValue.text = trips.joinToString("\n\n") { trip ->
            val date = formatter.format(java.time.Instant.parse(trip.startedAt))
            val durationMinutes = (trip.durationSeconds / 60).coerceAtLeast(1)
            val soc = trip.socUsedPercent?.let { " · расход SOC ${String.format(java.util.Locale.US, "%.1f", it)}%" } ?: ""
            "$date · ${String.format(java.util.Locale.US, "%.1f", trip.distanceKm)} км · ${durationMinutes} мин$soc"
        }
    }

    private fun formatDataFreshness(
        db: SnapshotDb,
        latest: com.evolute.telemetrycollector.data.TelemetrySnapshot
    ): String {
        val localLabel = "Последний снимок: ${formatSnapshotAge(latest.capturedAt)}"
        val serverTime = try { latest.apiUpdatedAt?.let { java.time.Instant.parse(it) } } catch (_: Exception) { null }
            ?: return localLabel
        val capturedTime = try { java.time.Instant.parse(latest.capturedAt) } catch (_: Exception) { return localLabel }
        val serverAgeSeconds = java.time.Duration.between(serverTime, capturedTime).seconds
        if (serverAgeSeconds > 180) {
            return "$localLabel · данные сервера устарели (${serverAgeSeconds / 60} мин.)"
        }
        val recent = db.recentSnapshots(2)
        if (recent.size == 2) {
            val previousTime = try { java.time.Instant.parse(recent[0].capturedAt) } catch (_: Exception) { null }
            val currentTime = try { java.time.Instant.parse(recent[1].capturedAt) } catch (_: Exception) { null }
            if (previousTime != null && currentTime != null &&
                java.time.Duration.between(previousTime, currentTime).seconds > 180 &&
                serverAgeSeconds in -60..180) {
                return "Связь восстановлена · данные синхронизированы\n$localLabel"
            }
        }
        return localLabel
    }

    private fun formatSnapshotAge(timestamp: String): String {
        return try {
            val captured = java.time.Instant.parse(timestamp)
            val seconds = java.time.Duration.between(captured, java.time.Instant.now()).seconds.coerceAtLeast(0)
            val age = when {
                seconds < 60 -> "$seconds сек. назад"
                seconds < 3600 -> "${seconds / 60} мин. назад"
                else -> "${seconds / 3600} ч. ${(seconds % 3600) / 60} мин. назад"
            }
            val localTime = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault()).format(captured)
            "$localTime · $age"
        } catch (_: Exception) {
            timestamp
        }
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

package com.evolute.telemetrydisplay

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 12, 19)
    private val panel = Color.rgb(19, 27, 39)
    private val muted = Color.rgb(145, 160, 180)
    private val white = Color.WHITE
    private val accent = Color.rgb(62, 214, 151)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var updated: TextView
    private lateinit var soc: TextView
    private lateinit var stockRange: TextView
    private lateinit var estimatedRange: TextView
    private lateinit var consumption: TextView
    private lateinit var odometer: TextView
    private var endpoint = ""
    private var pairingCode = ""

    private val poll = object : Runnable {
        override fun run() {
            if (endpoint.isNotBlank()) fetchTelemetry()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        endpoint = getPreferences(0).getString("endpoint", "") ?: ""
        pairingCode = getPreferences(0).getString("pairing_code", "") ?: ""
        buildDashboard()
        handler.post(poll)
    }

    private fun label(text: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

    private fun card(title: String, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(14, 12, 14, 12)
            setBackgroundColor(panel)
            addView(label(title.uppercase(Locale.getDefault()), 12f, muted, true))
            addView(value, LinearLayout.LayoutParams(-1, 0, 1f))
        }

    private fun buildDashboard() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 14, 22, 14)
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(label("EVOLUTE", 19f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        status = label("НЕТ СВЯЗИ", 12f, muted, true)
        header.addView(status)
        header.addView(Button(this).apply {
            text = "НАСТРОЙКА"
            setOnClickListener { showConnectionDialog() }
        })
        root.addView(header)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        soc = label("—%", 64f, white, true)
        stockRange = label("— км", 34f, white, true)
        estimatedRange = label("— км", 34f, white, true)
        top.addView(card("Заряд батареи", soc), LinearLayout.LayoutParams(0, -1, 1.05f).apply { rightMargin = 10 })
        top.addView(card("Штатный запас", stockRange), LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = 10 })
        top.addView(card("Расчётный запас", estimatedRange), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, 0, 3f).apply { bottomMargin = 10 })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        consumption = label("—", 30f, white, true)
        odometer = label("— км", 30f, white, true)
        bottom.addView(card("Расход SOC / 100 км", consumption), LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = 10 })
        bottom.addView(card("Пробег", odometer), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(-1, 0, 2f))
        updated = label("Ожидание данных от Collector", 12f, muted)
        root.addView(updated, LinearLayout.LayoutParams(-1, 34))
        setContentView(root)
    }

    private fun showConnectionDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val urlInput = EditText(this).apply {
            hint = "http://192.168.XX.XX:8765/api/v1/telemetry"
            setSingleLine(true)
            setText(endpoint)
        }
        val codeInput = EditText(this).apply {
            hint = "Код сопряжения"
            setSingleLine(true)
            setText(pairingCode)
        }
        box.addView(label("Адрес локального сервера Collector", 13f, muted))
        box.addView(urlInput)
        box.addView(label("PIN-код сопряжения", 13f, muted))
        box.addView(codeInput)
        AlertDialog.Builder(this)
            .setTitle("Подключение телефона")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                endpoint = urlInput.text.toString().trim().trimEnd('/')
                pairingCode = codeInput.text.toString().trim()
                getPreferences(0).edit()
                    .putString("endpoint", endpoint)
                    .putString("pairing_code", pairingCode)
                    .apply()
                status.text = if (endpoint.isBlank()) "НЕ НАСТРОЕНО" else "ПРОВЕРКА СВЯЗИ…"
                if (endpoint.isNotBlank()) fetchTelemetry()
            }
            .show()
    }

    private fun fetchTelemetry() {
        val target = endpoint
        val pin = pairingCode
        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = 2500
                    setRequestProperty("Accept", "application/json")
                    if (pin.isNotBlank()) setRequestProperty("X-Evolute-Pairing-Code", pin)
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                handler.post { render(json) }
            } catch (e: Exception) {
                handler.post {
                    status.text = "НЕТ СВЯЗИ"
                    status.setTextColor(Color.rgb(255, 105, 105))
                    updated.text = "Collector недоступен: ${e.message ?: "ошибка соединения"}"
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun render(json: JSONObject) {
        fun number(vararg keys: String): Double? {
            for (key in keys) if (json.has(key) && !json.isNull(key)) {
                val v = json.optDouble(key, Double.NaN)
                if (v.isFinite()) return v
            }
            return null
        }
        fun fmt(v: Double?, suffix: String = "") = v?.let {
            val n = if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it)
            n + suffix
        } ?: "—"
        val charge = number("soc", "batteryPercentage")
        soc.text = fmt(charge, "%")
        soc.setTextColor(when {
            charge == null -> white
            charge <= 15 -> Color.rgb(255, 91, 91)
            charge <= 35 -> Color.rgb(255, 190, 80)
            else -> accent
        })
        stockRange.text = fmt(number("stockRangeKm", "stockRange", "remainsBatteryMileage"), " км")
        estimatedRange.text = fmt(number("calculatedRangeKm", "estimatedRangeKm"), " км")
        consumption.text = fmt(number("consumptionPercentPer100Km", "socPer100Km"), " %")
        odometer.text = fmt(number("odometerKm", "odometer"), " км")
        val online = json.optBoolean("online", true)
        status.text = if (online) "COLLECTOR ONLINE" else "АВТОМОБИЛЬ НЕ В СЕТИ"
        status.setTextColor(if (online) accent else Color.rgb(255, 190, 80))
        val stamp = json.optString("capturedAt", json.optString("updatedAt", ""))
        updated.text = if (stamp.isBlank()) "Данные получены" else "Обновлено: $stamp"
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        worker.shutdownNow()
        super.onDestroy()
    }
}

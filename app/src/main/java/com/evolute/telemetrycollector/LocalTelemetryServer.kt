package com.evolute.telemetrycollector

import android.content.Context
import android.util.Log
import com.evolute.telemetrycollector.data.SnapshotDb
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only local bridge for the separate head-unit display.
 * Exposes only derived dashboard metrics; never exposes raw sensors, tokens,
 * vehicle identifiers, account data, or API payloads.
 */
class LocalTelemetryServer(private val context: Context) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val pairingCode: String
        get() = getOrCreatePairingCode(context)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                val socketServer = ServerSocket(PORT, 8, java.net.InetAddress.getByName("0.0.0.0"))
                serverSocket = socketServer
                while (running.get()) {
                    try {
                        val client = socketServer.accept()
                        client.soTimeout = 2500
                        handle(client)
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "Local telemetry client error", e)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Local telemetry server failed", e)
            } finally {
                running.set(false)
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }, "evolute-local-telemetry").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine()?.take(2048).orEmpty()
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            var suppliedPin = ""
            var lines = 0
            while (lines++ < 40) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.length <= 2048 && line.substringBefore(':').equals("X-Evolute-Pairing-Code", true)) {
                    suppliedPin = line.substringAfter(':').trim()
                }
            }

            if (method != "GET") {
                respond(client, 405, JSONObject().put("error", "method_not_allowed"))
                return
            }
            if (path != "/api/v1/telemetry") {
                respond(client, 404, JSONObject().put("error", "not_found"))
                return
            }
            val expected = pairingCode.toByteArray(Charsets.UTF_8)
            val actual = suppliedPin.toByteArray(Charsets.UTF_8)
            if (suppliedPin.isBlank() || !MessageDigest.isEqual(expected, actual)) {
                respond(client, 401, JSONObject().put("error", "pairing_required"))
                return
            }

            try {
                val db = SnapshotDb.get(context)
                val latest = db.latestSnapshot()
                if (latest == null) {
                    respond(client, 503, JSONObject().put("error", "no_telemetry_yet"))
                    return
                }
                val payload = JSONObject()
                    .put("soc", latest.soc)
                    .put("odometerKm", latest.odometer)
                    .put("stockRangeKm", latest.stockRange ?: JSONObject.NULL)
                    .put("calculatedRangeKm", db.calculatedRangeKm() ?: JSONObject.NULL)
                    .put("consumptionPercentPer100Km", db.averageConsumptionPer100Km() ?: JSONObject.NULL)
                    .put("online", latest.online)
                    .put("ready", latest.ready)
                    .put("capturedAt", latest.capturedAt)
                    .put("updatedAt", latest.capturedAt)
                respond(client, 200, payload)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to prepare local telemetry response", e)
                respond(client, 503, JSONObject().put("error", "telemetry_unavailable"))
            }
        }
    }

    private fun respond(socket: Socket, status: Int, body: JSONObject) {
        val reason = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Service Unavailable"
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        try {
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val PORT = 8765
        private const val TAG = "EvoluteLocalBridge"
        private const val PREFS = "headunit_bridge"
        private const val PIN_KEY = "pairing_code"

        fun getOrCreatePairingCode(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(PIN_KEY, null)?.let { return it }
            val random = SecureRandom()
            val code = (10000000 + random.nextInt(90000000)).toString()
            prefs.edit().putString(PIN_KEY, code).apply()
            return code
        }

        fun localIpv4Address(): String {
            return try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                val candidates = interfaces.flatMap { network ->
                    Collections.list(network.inetAddresses).mapNotNull { address ->
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            address.hostAddress?.let { network.name to it }
                        } else null
                    }
                }
                (candidates.firstOrNull { it.first in listOf("wlan0", "ap0", "swlan0") }
                    ?: candidates.firstOrNull { it.second.startsWith("192.168.") || it.second.startsWith("10.") }
                    ?: candidates.firstOrNull())?.second ?: "IP появится после подключения Wi-Fi"
            } catch (_: Exception) {
                "IP недоступен"
            }
        }
    }
}

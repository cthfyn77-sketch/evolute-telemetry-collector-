package com.evolute.telemetrycollector.data

/** Data contracts only; all HTTP requests are made by the EVACONNECT Python client. */
class ApiClient private constructor() {
    data class Telemetry(
        val soc: Double, val odometer: Double, val stockRange: Double?,
        val online: Boolean, val ready: Boolean, val updatedAt: String?,
        val sensorsJson: String = "{}"
    )
    data class Tokens(
        val access: String, val refresh: String, val userId: String?,
        val userToken: String?, val widgetId: String?
    )

    /** Reject impossible core values before they can contaminate local history or range estimates. */
    internal fun validateTelemetry(t: Telemetry): String? {
        if (!t.soc.isFinite() || t.soc !in 0.0..100.0) return "Некорректный SOC"
        if (!t.odometer.isFinite() || t.odometer < 0.0) return "Некорректный одометр"
        if (t.stockRange != null && (!t.stockRange.isFinite() || t.stockRange < 0.0)) {
            return "Некорректный штатный запас хода"
        }
        return null
    }
}

interface TokenStore {
    fun get(): ApiClient.Tokens?
    fun save(tokens: ApiClient.Tokens)
    fun clear()
}

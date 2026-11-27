package com.evolute.telemetrycollector.data

/**
 * Converts SOC consumption to an estimated energy consumption for display.
 * The 52 kWh capacity is an approximate usable-pack assumption, not a measured value.
 */
internal object BatteryConsumption {
    const val ASSUMED_CAPACITY_KWH = 52.0

    fun estimateKwhPer100Km(consumptionPercentPer100Km: Double?): Double? {
        val percent = consumptionPercentPer100Km ?: return null
        if (!percent.isFinite() || percent < 0.0) return null
        return percent * ASSUMED_CAPACITY_KWH / 100.0
    }
}

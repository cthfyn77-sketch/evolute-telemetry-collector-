package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test

class BatteryConsumptionTest {
    @Test fun convertsSocConsumptionUsingApproximate52KwhCapacity() {
        assertEquals(20.8, BatteryConsumption.estimateKwhPer100Km(40.0)!!, 0.0001)
        assertEquals(26.0, BatteryConsumption.estimateKwhPer100Km(50.0)!!, 0.0001)
    }

    @Test fun returnsNullForMissingOrInvalidConsumption() {
        assertNull(BatteryConsumption.estimateKwhPer100Km(null))
        assertNull(BatteryConsumption.estimateKwhPer100Km(Double.NaN))
        assertNull(BatteryConsumption.estimateKwhPer100Km(Double.POSITIVE_INFINITY))
        assertNull(BatteryConsumption.estimateKwhPer100Km(-1.0))
    }
}

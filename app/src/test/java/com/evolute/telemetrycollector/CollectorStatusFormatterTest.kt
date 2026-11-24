package com.evolute.telemetrycollector

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectorStatusFormatterTest {
    @Test fun formatsEmptyDatabaseAndLoggedOutState() {
        assertEquals(
            "Записей: 0\nПока нет записей\nАвторизация: не выполнена",
            CollectorStatusFormatter.format(0, "Пока нет записей", false)
        )
    }

    @Test fun formatsSavedRecordsAndAuthenticatedState() {
        assertEquals(
            "Записей: 12\nSOC: 68.0%\nАвторизация: сохранена",
            CollectorStatusFormatter.format(12, "SOC: 68.0%", true)
        )
    }
}

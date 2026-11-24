package com.evolute.telemetrycollector

/** Pure status text formatter, kept separate from Android views for JVM tests. */
internal object CollectorStatusFormatter {
    fun format(recordCount: Long, latestRecord: String, authorized: Boolean): String =
        "Записей: $recordCount\n$latestRecord\nАвторизация: ${if (authorized) "сохранена" else "не выполнена"}"
}

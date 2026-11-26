# Evolute Telemetry Collector

Отдельное Android-приложение для локального сбора телеметрии Evolute.

## Источник данных

Телеметрия получается через встроенный Python-клиент **evaconnect** (vendored source из
[ca11mebaraka/evaconnect](https://github.com/ca11mebaraka/evaconnect)), запускаемый внутри APK через Chaquopy.
Foreground service вызывает `EvoluteClient.default_car_id()` и `EvoluteClient.get_telemetry()`.
Собственный Kotlin HTTP-клиент больше не используется для опроса телеметрии.

Для мобильной сборки сохранена логика клиента evaconnect, а Pydantic-модели заменены
небольшим stdlib-адаптером: Pydantic v2 требует нативный `pydantic-core`, для которого
нет стандартного Android wheel в Chaquopy. HTTP-транспорт остаётся библиотечным HTTPX.

Авторизация по SMS пока выполняется экраном Android; access/refresh token передаются
в evaconnect только в памяти процесса. При ротации refresh token новая пара сразу
сохраняется обратно в Android Keystore (AES-GCM). Файл `credentials.json` не создаётся.

## Возможности
- Авторизация Evolute по телефону и SMS-коду.
- Получение телеметрии через evaconnect.
- Сбор каждые 30 секунд в foreground-службе с постоянным уведомлением.
- Локальная отдельная SQLite-база `evolute_collector.sqlite`.
- Поля: время локального снимка, время обновления телеметрии API, SOC, одометр, штатный запас хода, online, ready.
- Полный JSON-ответ telemetry сохраняется локально в `sensors_json`: все верхнеуровневые поля и датчики; на главном экране отображается список первых 80 полей, полный JSON доступен в CSV.
- Перед сохранением рекурсивно скрываются ключи с токенами, VIN, IMEI, телефоном и идентификаторами пользователя/автомобиля.
- Экспорт истории в CSV через системный выбор файла.
- Локальная история поездок восстанавливается по фактическим изменениям одометра и SOC; поездка закрывается после 15 минут без движения, короткие колебания одометра игнорируются.
- Токены шифруются AES-GCM ключом Android Keystore.

## Ограничения безопасности
- Package: `com.evolute.telemetrycollector` — не совпадает с приложением Range Monitor.
- Никаких команд автомобилю, управления зарядкой или изменения настроек.
- Расчётный запас хода строится по фактическому расходу SOC на завершённых интервалах по 5 км; прогноз использует среднее последних восьми валидных интервалов. До накопления первого полного интервала показывается штатный запас хода.
- Токены не включаются в исходный код, CSV или диагностические сообщения.
- Данные остаются локально на устройстве; внешнего сервера проекта нет.

## Сборка
GitHub Actions workflow `.github/workflows/android.yml` собирает debug APK и публикует
его как artifact `evolute-telemetry-collector-evaconnect-debug`.

## Примечание о периодичности
Интервал опроса приложения — 30 секунд при работающей foreground-службе. Сам evaconnect
ограничивает частоту телеметрии минимум пятью секундами. Android и прошивка телефона
могут остановить службу из-за ограничений фоновой работы или энергосбережения; для
длительного сбора следует разрешить приложению фоновую работу/неограниченное
энергопотребление в настройках Android.


## v130 — telemetry outage protection

- Samples separated by more than three minutes are treated as a telemetry gap.
- Range estimation starts a fresh SOC window after a gap; SOC and odometer changes are not interpolated across missing samples.
- Trip reconstruction excludes intervals spanning a telemetry outage rather than inventing trip duration or SOC consumption.
- The dashboard reports stale server timestamps and identifies a recovered, synchronized connection.
- Battery SOH changes are accepted only after three consistent readings; isolated spikes remain unconfirmed.
- Release version: 0.7.0 (versionCode 7).

## v131 — battery history and telemetry quality

- Adds a local read-only history summary for numeric battery voltage, current, temperature and SOH sensor fields already present in stored sensor JSON.
- Shows latest value, observed range and sample count without assuming or converting API units.
- Flags isolated one-sample spikes only when the following sample returns near the prior value; intervals spanning more than three minutes are not connected.
- Alerts are telemetry-quality hints, not vehicle fault diagnoses. No vehicle commands or remote services are added.
- Release version: 0.8.0 (versionCode 8).


## v132 — extended battery history

- Battery history diagnostics now analyze the latest 480 snapshots (about four hours at the normal 30-second collection interval).
- Snapshot queries support up to 1,000 recent records; the dashboard history analysis is cached and recalculated only when a new snapshot is stored.
- The existing telemetry-gap and battery spike rules are unchanged.
- Release version: 0.9.0 (versionCode 9).


## v133 — separate SOC charts

- Splits the combined SOC plot into two independent charts: SOC versus odometer distance and SOC versus capture time.
- The time chart retains highlighting for stationary charging sessions; the distance chart is shown once odometer movement is present.
- Both charts use the same latest 240 locally stored snapshots. Telemetry collection, range estimation, gap handling and battery-history analysis are unchanged.
- Release version: 0.10.0 (versionCode 10).


## v134 — graphical battery dashboard and complete mileage history

- Keeps the SOC-versus-odometer card visible during parking and plots the full local mileage history (up to 20,000 snapshots), collapsing repeated stationary samples.
- The SOC-versus-time chart remains based on the latest 240 snapshots.
- Adds individual history plots for numeric battery voltage, current, temperature and SOH sensor fields actually supplied by EVACONNECT, with current/min/max values and sample counts.
- Battery metric plots use the existing four-hour (480 snapshot) analysis window. Existing collection, range estimation, telemetry-gap handling and SOH confirmation logic are unchanged.
- Release version: 0.11.0 (versionCode 11).


## v137 — filtered battery telemetry history

- Battery history charts and summary exclude a one-sample spike only when the next reading returns close to the previous level within three minutes.
- Raw telemetry snapshots in SQLite are not modified or deleted; filtering applies only to the derived battery-history view.
- Sustained changes remain visible. Missing-data intervals are not bridged.
- A quality alert identifies each excluded isolated spike.
- Release version: 0.12.0 (versionCode 12).


## v138 — odometer anomaly protection

- Removes an isolated odometer jump from derived trip and range calculations only when the next reading returns within 0.1 km of the previous reading and both intervals are no longer than three minutes.
- Sustained odometer changes are preserved; the sanitizer never joins readings across a telemetry outage.
- Original snapshots remain intact in SQLite and CSV exports.
- Release version: 0.13.0 (versionCode 13).

## v139 — per-kilometre SOC consumption

- Measures consumed SOC at every completed 1-km odometer interval, interpolating SOC at kilometre boundaries when telemetry samples arrive between them.
- Calculates average consumption from up to the latest eight valid one-kilometre intervals; partial intervals are not treated as completed measurements.
- Estimates remaining range from the latest SOC and the rolling average SOC consumption per kilometre.
- Resets the measurement baseline after a telemetry outage or a stationary charging event; odometer anomaly protection from v138 remains active.
- Raw telemetry snapshots and CSV export remain unchanged.
- Release version: 0.14.0 (versionCode 14).

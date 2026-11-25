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
- Расчётный запас хода строится по SOC на контрольных точках каждого километра; окно сглаживания — последние 5 км. До накопления истории показывается штатный запас хода.
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

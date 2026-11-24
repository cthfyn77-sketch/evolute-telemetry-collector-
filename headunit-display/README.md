# Evolute Telemetry Display

Standalone Android APK for the stock head-unit screen of the first-generation 2025 Evolute i-JOY.

This is a separate Gradle project under `headunit-display/`. It does not alter the existing Collector app or its authentication flow.

## Current prototype

- Landscape, immersive, dark dashboard.
- Displays SOC, factory range, estimated range, SOC consumption per 100 km and odometer.
- Polls a configured local JSON endpoint every five seconds.
- Supports an optional pairing PIN sent as `X-Evolute-Pairing-Code`.
- Shows connection and vehicle-online status.
- Does not authenticate to Evolute or send commands to the vehicle.

## Local telemetry contract

Configure the app with a URL such as `http://PHONE_LAN_IP:8765/api/v1/telemetry`. The Collector-side local read-only endpoint is a separate integration task and is not yet implemented in this prototype.

Expected JSON example:

```json
{
  "soc": 72,
  "stockRangeKm": 245,
  "calculatedRangeKm": 218,
  "consumptionPercentPer100Km": 18.4,
  "odometerKm": 12450.6,
  "online": true,
  "capturedAt": "2026-11-24T12:00:00Z"
}
```

Only fields actually supplied by the Collector should be displayed. The prototype intentionally does not invent sensor values.

## Build

```bash
gradle -p headunit-display assembleDebug
```

APK output: `headunit-display/app/build/outputs/apk/debug/app-debug.apk`.

## Connectivity note

Wireless Android Auto uses Wi-Fi for its phone-to-head-unit link. Android hotspot concurrency and routing vary by phone and head unit. Verify the actual network topology in the vehicle before relying on a phone-hosted local endpoint.

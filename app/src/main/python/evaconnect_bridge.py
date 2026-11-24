"""Android bridge: run the vendored evaconnect client and return redacted telemetry."""
from __future__ import annotations
import json
from evaconnect.auth import TokenStore
from evaconnect.client import EvoluteClient
import re


_SENSITIVE_KEY = re.compile(
    r"(access.?token|refresh.?token|authorization|password|secret|imei|vin|phone|"
    r"user.?id|car.?id|vehicle.?id|(^|_)id$)",
    re.IGNORECASE,
)


def _redact_api_value(value):
    """Keep telemetry fields, but never persist account/vehicle credentials or IDs."""
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if _SENSITIVE_KEY.search(str(key)):
                result[str(key)] = "[REDACTED]"
            else:
                result[str(key)] = _redact_api_value(item)
        return result
    if isinstance(value, (list, tuple)):
        return [_redact_api_value(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def fetch_telemetry(access_token: str, refresh_token: str) -> str:
    store = TokenStore(path=None)
    store.access_token = access_token or None
    store.refresh_token = refresh_token or None
    try:
        with EvoluteClient(token_store=store) as client:
            car_id = client.default_car_id()
            telemetry = client.get_telemetry(car_id=car_id)
            sensors = dict(getattr(telemetry, "sensors", None) or {})
            soc = sensors.get("batteryPercentage")
            odometer = sensors.get("odometer")
            stock_range = sensors.get("remainsBatteryMileage")
            if soc is None or odometer is None:
                raise ValueError("evaconnect вернул телеметрию без SOC или одометра")
            payload = {
                "ok": True,
                "soc": float(soc),
                "odometer": float(odometer),
                "stock_range": None if stock_range in (None, "") else float(stock_range),
                "online": bool(getattr(telemetry, "is_online", False)),
                "ready": bool(getattr(telemetry, "is_car_state_ready", False)),
                "updated_at": str(getattr(telemetry, "car_state_updated_at", "") or ""),
                # Preserve the complete decoded telemetry response, including
                # top-level fields and every sensor. Redact account/vehicle identifiers.
                "sensors_json": json.dumps(
                    _redact_api_value(
                        getattr(telemetry, "api_raw", None)
                        or {"sensors": getattr(telemetry, "sensors_raw", None) or sensors}
                    ),
                    ensure_ascii=False,
                    separators=(",", ":"),
                    default=str,
                ),
            }
    except Exception as exc:
        status = getattr(exc, "status_code", None)
        if status is not None:
            error = f"{type(exc).__name__}: HTTP {status}"
        elif isinstance(exc, ValueError):
            error = str(exc)[:160]
        else:
            error = type(exc).__name__
        payload = {"ok": False, "error": error}
    payload["access_token"] = store.access_token or ""
    payload["refresh_token"] = store.refresh_token or ""
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

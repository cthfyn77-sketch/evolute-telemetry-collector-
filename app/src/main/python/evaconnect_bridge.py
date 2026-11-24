"""Android bridge: run the vendored evaconnect client and return redacted telemetry."""
from __future__ import annotations
import json
from evaconnect.auth import TokenStore
from evaconnect.client import EvoluteClient

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

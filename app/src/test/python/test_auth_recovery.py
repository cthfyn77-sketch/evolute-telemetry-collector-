import json
import unittest
from unittest.mock import patch

import evaconnect_bridge
from evaconnect.errors import EvoluteAuthError


class FakeClient:
    def __init__(self, error):
        self.error = error

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def default_car_id(self):
        raise self.error


class AuthRecoveryBridgeTest(unittest.TestCase):
    def test_rejected_refresh_token_is_marked_as_auth_required(self):
        error = EvoluteAuthError("refresh token rejected")
        with patch.object(evaconnect_bridge, "EvoluteClient", return_value=FakeClient(error)):
            result = json.loads(evaconnect_bridge.fetch_telemetry("old-access", "old-refresh"))

        self.assertFalse(result["ok"])
        self.assertTrue(result["auth_required"])
        self.assertEqual(result["access_token"], "old-access")
        self.assertEqual(result["refresh_token"], "old-refresh")

    def test_non_auth_failure_does_not_request_sign_in(self):
        error = OSError("network unavailable")
        with patch.object(evaconnect_bridge, "EvoluteClient", return_value=FakeClient(error)):
            result = json.loads(evaconnect_bridge.fetch_telemetry("access", "refresh"))

        self.assertFalse(result["ok"])
        self.assertFalse(result["auth_required"])


if __name__ == "__main__":
    unittest.main()

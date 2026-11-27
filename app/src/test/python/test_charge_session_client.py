import json
import unittest

import httpx

from evaconnect.auth import TokenStore
from evaconnect.client import EvoluteClient
from evaconnect.errors import EvoluteAPIError


class ChargeSessionClientTest(unittest.TestCase):
    def make_client(self, status_code, body=None, content=None):
        def handler(request):
            self.assertEqual(request.method, "GET")
            self.assertEqual(request.url.path, "/charge-service/session/v2/current")
            self.assertEqual(request.headers.get("access-token"), "test-access")
            response_content = content
            if response_content is None and body is not None:
                response_content = json.dumps(body).encode("utf-8")
            return httpx.Response(
                status_code,
                content=response_content or b"",
                headers={"content-type": "application/json"},
            )

        store = TokenStore(path=None)
        store.access_token = "test-access"
        return EvoluteClient(
            base_url="https://unit.test",
            token_store=store,
            transport=httpx.MockTransport(handler),
        )

    def test_active_session_preserves_unknown_api_fields(self):
        payload = {
            "status": "charging",
            "power": 7200,
            "vendorSpecific": {"phaseCount": 3},
        }
        client = self.make_client(200, payload)
        try:
            session = client.get_charge_session()
        finally:
            client.close()

        self.assertIsNotNone(session)
        self.assertEqual(session.api_raw, payload)
        self.assertEqual(session.power, 7200)
        self.assertEqual(session.vendorSpecific, {"phaseCount": 3})

    def test_404_means_no_active_session(self):
        client = self.make_client(404, {"message": "not found"})
        try:
            self.assertIsNone(client.get_charge_session())
        finally:
            client.close()

    def test_empty_204_and_json_null_mean_no_session(self):
        for status, body, raw in (
            (204, None, None),
            (200, None, b"null"),
            (200, None, b""),
        ):
            client = self.make_client(status, body, content=raw)
            try:
                self.assertIsNone(client.get_charge_session())
            finally:
                client.close()

    def test_non_object_success_response_is_rejected(self):
        client = self.make_client(200, ["unexpected", "array"])
        try:
            with self.assertRaises(EvoluteAPIError):
                client.get_charge_session()
        finally:
            client.close()

    def test_malformed_json_is_reported_without_body(self):
        client = self.make_client(200, content=b"{not-json")
        try:
            with self.assertRaises(EvoluteAPIError) as raised:
                client.get_charge_session()
            self.assertEqual(raised.exception.status_code, 200)
            self.assertNotIn("not-json", str(raised.exception))
        finally:
            client.close()

    def test_transport_timeout_is_propagated(self):
        def handler(request):
            raise httpx.ReadTimeout("upstream timed out", request=request)

        store = TokenStore(path=None)
        store.access_token = "test-access"
        client = EvoluteClient(
            base_url="https://unit.test",
            token_store=store,
            transport=httpx.MockTransport(handler),
        )
        try:
            with self.assertRaises(httpx.ReadTimeout):
                client.get_charge_session()
        finally:
            client.close()

    def test_server_error_is_propagated_with_status(self):
        client = self.make_client(503, {"message": "temporarily unavailable"})
        try:
            with self.assertRaises(EvoluteAPIError) as raised:
                client.get_charge_session()
            self.assertEqual(raised.exception.status_code, 503)
        finally:
            client.close()


if __name__ == "__main__":
    unittest.main()

import unittest

from evaconnect_bridge import _redact_api_value


class BridgeRedactionTest(unittest.TestCase):
    def test_redacts_camel_case_identifiers_recursively(self):
        payload = {
            "accountId": "account-secret",
            "ownerId": "owner-secret",
            "vehicleId": "vehicle-secret",
            "chargerId": "charger-secret",
            "stationId": "station-secret",
            "transactionId": "transaction-secret",
            "connectorId": "connector-secret",
            "licensePlate": "plate-secret",
            "registrationNumber": "registration-secret",
            "sensors": {
                "sessionId": "session-secret",
                "batteryPercentage": 87,
            },
        }

        redacted = _redact_api_value(payload)

        self.assertEqual(redacted["accountId"], "[REDACTED]")
        self.assertEqual(redacted["ownerId"], "[REDACTED]")
        self.assertEqual(redacted["vehicleId"], "[REDACTED]")
        self.assertEqual(redacted["chargerId"], "[REDACTED]")
        self.assertEqual(redacted["stationId"], "[REDACTED]")
        self.assertEqual(redacted["transactionId"], "[REDACTED]")
        self.assertEqual(redacted["connectorId"], "[REDACTED]")
        self.assertEqual(redacted["licensePlate"], "[REDACTED]")
        self.assertEqual(redacted["registrationNumber"], "[REDACTED]")
        self.assertEqual(redacted["sensors"]["sessionId"], "[REDACTED]")
        self.assertEqual(redacted["sensors"]["batteryPercentage"], 87)

    def test_keeps_non_identifier_keys_and_redacts_standard_id_forms(self):
        payload = {
            "id": "root-secret",
            "_id": "mongo-secret",
            "valid": True,
            "odometer": 12345.6,
        }

        redacted = _redact_api_value(payload)

        self.assertEqual(redacted["id"], "[REDACTED]")
        self.assertEqual(redacted["_id"], "[REDACTED]")
        self.assertTrue(redacted["valid"])
        self.assertEqual(redacted["odometer"], 12345.6)

    def test_redacts_identifiers_inside_lists(self):
        redacted = _redact_api_value([
            {"customerId": "customer-secret"},
            {"state": "active"},
        ])

        self.assertEqual(redacted[0]["customerId"], "[REDACTED]")
        self.assertEqual(redacted[1]["state"], "active")


if __name__ == "__main__":
    unittest.main()

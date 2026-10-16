import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from publish_google_play import publish


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name) / "test.aab"
        self.bundle.write_bytes(b"synthetic bundle")
        self.session = Mock()

    def respond(self, *responses):
        self.session.request.side_effect = [
            Mock(ok=True, content=b"json", json=Mock(return_value=value)) for value in responses
        ]

    def test_publish_uses_uploaded_code_and_replaces_pending_review(self):
        self.respond({"id": "edit"}, {"tracks": [{"track": "beta"}]},
                     {"versionCode": 132}, {}, {})
        self.assertEqual(publish(self.session, self.bundle, "beta", "completed", "v1.4.4"), "132")
        calls = self.session.request.call_args_list
        self.assertEqual(calls[3].kwargs["json"]["releases"][0]["versionCodes"], ["132"])
        self.assertEqual(calls[4].kwargs["params"], {"changesInReviewBehavior": "CANCEL_IN_REVIEW_AND_SUBMIT"})
        self.assertFalse(any(call.args[0] == "DELETE" for call in calls))

    def test_production_never_contacts_google(self):
        for track in ("production", "wear:production"):
            with self.assertRaises(ValueError):
                publish(self.session, self.bundle, track, "completed", "v1.4.4")
        self.session.request.assert_not_called()

    def test_unknown_track_discards_edit_without_upload(self):
        self.respond({"id": "edit"}, {"tracks": [{"track": "beta"}]}, {})
        with self.assertRaises(ValueError):
            publish(self.session, self.bundle, "missing", "draft", "v1.4.4")
        self.assertEqual(self.session.request.call_args_list[-1].args[0], "DELETE")
        self.assertEqual(len(self.session.request.call_args_list), 3)

    def test_rejected_upload_does_not_commit(self):
        self.respond({"id": "edit"}, {"tracks": [{"track": "beta"}]})
        failed = Mock(ok=False, status_code=403, json=Mock(return_value={"error": {"message": "Permission denied"}}))
        self.session.request.side_effect = [
            *self.session.request.side_effect, failed,
            Mock(ok=True, content=b"", json=Mock(return_value={})),
        ]
        with self.assertRaisesRegex(RuntimeError, "Permission denied"):
            publish(self.session, self.bundle, "beta", "draft", "v1.4.4")
        self.assertFalse(any(call.args[1].endswith(":commit") for call in self.session.request.call_args_list))
        self.assertEqual(self.session.request.call_args_list[-1].args[0], "DELETE")

    def test_invalid_status_never_contacts_google(self):
        with self.assertRaises(ValueError):
            publish(self.session, self.bundle, "beta", "unexpected", "v1.4.4")
        self.session.request.assert_not_called()


if __name__ == "__main__":
    unittest.main()

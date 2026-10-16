import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from publish_google_play import publish


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name) / 'test.aab'
        self.bundle.write_bytes(b'synthetic bundle')
        self.digest = hashlib.sha256(self.bundle.read_bytes()).hexdigest()
        self.session = Mock()

    def respond(self, *responses):
        self.session.request.side_effect = [
            Mock(ok=True, content=b'json', json=Mock(return_value=value)) for value in responses
        ]

    def test_uploads_new_bundle_and_replaces_pending_review(self):
        self.respond({'id': 'edit'}, {'tracks': [{'track': 'beta'}]}, {},
                     {'versionCode': 134}, {}, {})
        self.assertEqual(publish(self.session, self.bundle, 'beta', 'completed', 'v1.5.1-dev.1'), '134')
        calls = self.session.request.call_args_list
        self.assertEqual(calls[4].kwargs['json']['releases'][0]['versionCodes'], ['134'])
        self.assertEqual(calls[5].kwargs['params'], {'changesInReviewBehavior': 'CANCEL_IN_REVIEW_AND_SUBMIT'})
        self.assertFalse(any(call.args[0] == 'DELETE' for call in calls))

    def test_promotes_uploaded_bundle_to_production_without_reupload(self):
        self.respond({'id': 'edit'}, {'tracks': [{'track': 'beta'}, {'track': 'production'}]},
                     {'bundles': [{'versionCode': 133, 'sha256': self.digest}]}, {}, {})
        self.assertEqual(publish(self.session, self.bundle, 'production', 'completed', 'v1.5.0'), '133')
        calls = self.session.request.call_args_list
        self.assertFalse(any('/upload/' in call.args[1] for call in calls))
        self.assertEqual(calls[3].kwargs['json']['track'], 'production')
        self.assertEqual(calls[3].kwargs['json']['releases'][0]['versionCodes'], ['133'])

    def test_already_published_release_is_idempotent(self):
        self.respond({'id': 'edit'}, {'tracks': [{'track': 'production', 'releases': [
            {'versionCodes': ['133'], 'status': 'completed'}]}]},
            {'bundles': [{'versionCode': 133, 'sha256': self.digest}]}, {})
        self.assertEqual(publish(self.session, self.bundle, 'production', 'completed', 'v1.5.0'), '133')
        calls = self.session.request.call_args_list
        self.assertEqual(calls[-1].args[0], 'DELETE')
        self.assertFalse(any(call.args[0] == 'PUT' or call.args[1].endswith(':commit') for call in calls))

    def test_prereleases_cannot_reach_production(self):
        with self.assertRaises(ValueError):
            publish(self.session, self.bundle, 'production', 'completed', 'v1.5.1-dev.1')
        self.session.request.assert_not_called()

    def test_invalid_track_never_contacts_google(self):
        with self.assertRaises(ValueError):
            publish(self.session, self.bundle, 'wear:production', 'completed', 'v1.5.0')
        self.session.request.assert_not_called()

    def test_missing_track_discards_edit_without_upload(self):
        self.respond({'id': 'edit'}, {'tracks': [{'track': 'beta'}]}, {})
        with self.assertRaises(ValueError):
            publish(self.session, self.bundle, 'production', 'draft', 'v1.5.0')
        self.assertEqual(self.session.request.call_args_list[-1].args[0], 'DELETE')
        self.assertEqual(len(self.session.request.call_args_list), 3)

    def test_rejected_upload_does_not_commit(self):
        self.respond({'id': 'edit'}, {'tracks': [{'track': 'beta'}]}, {})
        failed = Mock(ok=False, status_code=403, json=Mock(return_value={'error': {'message': 'Permission denied'}}))
        self.session.request.side_effect = [
            *self.session.request.side_effect, failed,
            Mock(ok=True, content=b'', json=Mock(return_value={})),
        ]
        with self.assertRaisesRegex(RuntimeError, 'Permission denied'):
            publish(self.session, self.bundle, 'beta', 'draft', 'v1.5.0')
        self.assertFalse(any(call.args[1].endswith(':commit') for call in self.session.request.call_args_list))
        self.assertEqual(self.session.request.call_args_list[-1].args[0], 'DELETE')

    def test_invalid_status_never_contacts_google(self):
        with self.assertRaises(ValueError):
            publish(self.session, self.bundle, 'beta', 'unexpected', 'v1.5.0')
        self.session.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()

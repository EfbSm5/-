"""Fully mocked, no-device checks of the own-notification diagnostic contract."""
import contextlib
import importlib.util
import io
import itertools
import pathlib
import unittest
from unittest.mock import patch
import test_type_notification_confirmation as transport

SPEC = importlib.util.spec_from_file_location(
    'structure_host', pathlib.Path(__file__).with_name('notification-structure.py'))
entry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(entry)
host, PROFILE = entry.bounded, entry.PROFILE
RID = transport.RID


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update(notificationStructureStage='verified', reasonCode='NONE', cleanupReasonCode='NONE',
                  recoveryReceiptId=RID, runId=RID, mainPid='123')
    fields.update(changes)
    return '\n'.join(['INSTRUMENTATION_STATUS: ' + k + '=' + v
                      for k, v in fields.items() if v is not None] + [
                          'INSTRUMENTATION_STATUS_CODE: 0', 'OK (1 test)',
                          'INSTRUMENTATION_CODE: -1']) + '\n'


def parse(text):
    evidence = host.Evidence(PROFILE)
    for line in text.splitlines():
        evidence.feed(line)
    return evidence


class StructureHostTests(unittest.TestCase):
    def exercise(self, **options):
        clock = patch.object(host.time, 'monotonic', side_effect=itertools.count(100, 100)) \
            if options.get('timeout') else contextlib.nullcontext()
        with clock, patch.object(transport, 'entry', entry), patch.object(transport, 'host', host), \
             patch.object(transport, 'PROFILE', PROFILE), patch.object(transport, 'report_text', report_text), \
             patch.object(host, 'TEST_CLASS', PROFILE.test_class):
            return transport.NotificationHostTests().exercise(**options)

    def test_specific_authorization_and_method(self):
        argv = ['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', '/tmp/unused']
        for flag in ([], [host.SERVICE_PROFILE.authorization], [transport.PROFILE.authorization],
                     ['--allow-ui-initialization-diagnostic']):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(argv + flag, profile=PROFILE)
        host.parse_args(argv + [PROFILE.authorization], profile=PROFILE)
        self.assertEqual(PROFILE.method, 'inspectOwnNotificationStructure')
        self.assertEqual(PROFILE.gate, 'notificationStructure')
        self.assertEqual(PROFILE.timeout_seconds, 60)
        self.assertEqual(host.SERVICE_PROFILE.timeout_seconds, 0)

    def test_complete_guards_and_cleanup_are_required(self):
        parse(report_text()).verified(RID, 123)
        for key in PROFILE.true_fields:
            for value in (None, 'false'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_no_structure_or_cleanup_failure_cannot_pass(self):
        for changes in ({'structureCaptured': 'false'}, {'notificationRemoved': 'false'},
                        {'cleanupReasonCode': 'NOTIFICATION_CLEANUP'}):
            text = report_text(notificationStructureStage='failed', passed='false', **changes)
            evidence = parse('PRIVATE_NOTIFICATION_BODY\n' + text)
            with self.assertRaises(RuntimeError):
                evidence.verified(RID, 123)
            self.assertIn('receipt', evidence.failure_diagnostics(RID, 123))
            self.assertNotIn('PRIVATE_NOTIFICATION_BODY', str(evidence.failure_diagnostics(RID, 123)))

    def test_evidence_write_failures_never_authorize_structure_use(self):
        for category in ('IO', 'UNSUPPORTED', 'SECURITY', 'ILLEGAL_STATE',
                         'NULL_POINTER', 'TIMEOUT', 'OTHER'):
            code = 'STRUCTURE_EVIDENCE_' + category
            text = report_text(notificationStructureStage='failed', passed='false',
                               structureCaptured='false', reasonCode=code)
            evidence = parse('PRIVATE_FILE_ERROR_TEXT\n' + text)
            with self.subTest(code=code), self.assertRaises(RuntimeError):
                evidence.verified(RID, 123)
            diagnostic = evidence.failure_diagnostics(RID, 123)
            self.assertEqual(diagnostic['receipt']['reasonCode'], code)
            self.assertNotIn('PRIVATE_FILE_ERROR_TEXT', str(diagnostic))

    def test_only_complete_identity_bound_failure_is_retained(self):
        for changes in ({'structureCaptured': None}, {'recoveryReceiptId': 'wrong'}, {'mainPid': '124'}):
            evidence = parse(report_text(notificationStructureStage='failed', passed='false', **changes))
            self.assertNotIn('receipt', evidence.failure_diagnostics(RID, 123))

    def test_cross_profile_partial_duplicate_skip_crash_rejected(self):
        for text in (transport.report_text(), report_text() * 2,
                     report_text().replace('OK (1 test)', 'OK (0 tests)'),
                     report_text() + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                     report_text() + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n'):
            with self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def test_transport_runs_one_method_without_destructive_commands(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_identity_and_settings_change_reject_success(self):
        for options in ({'hardware': 'wrong'}, {'change_ticks': True}, {'change_ime': True}):
            with self.subTest(options=options):
                result, summary, _, _ = self.exercise(**options)
                self.assertEqual(result, 2)
                self.assertFalse(summary['passed'])

    def test_timeout_ends_only_local_client_without_retry(self):
        result, summary, count, child = self.exercise(timeout=True)
        self.assertEqual((result, count), (2, 1))
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        child.terminate.assert_called_once()
        child.kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()

"""Fully mocked identity-probe transport and fail-closed evidence checks; no ADB."""
import contextlib
import importlib.util
import io
import itertools
import pathlib
import unittest
from unittest.mock import patch
import test_type_notification_confirmation as transport

SPEC = importlib.util.spec_from_file_location(
    'identity_host', pathlib.Path(__file__).with_name('notification-identity.py'))
entry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(entry)
host, PROFILE = entry.bounded, entry.PROFILE
RID = transport.RID


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update(notificationIdentityStage='verified', reasonCode='NONE', cleanupReasonCode='NONE',
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


class IdentityHostTests(unittest.TestCase):
    def exercise(self, **options):
        clock = patch.object(host.time, 'monotonic', side_effect=itertools.count(100, 100)) \
            if options.get('timeout') else contextlib.nullcontext()
        with clock, patch.object(transport, 'entry', entry), patch.object(transport, 'host', host), \
             patch.object(transport, 'PROFILE', PROFILE), patch.object(transport, 'report_text', report_text), \
             patch.object(host, 'TEST_CLASS', PROFILE.test_class):
            return transport.NotificationHostTests().exercise(**options)

    def test_only_explicit_identity_authorization(self):
        argv = ['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', '/tmp/unused']
        for flag in ([], [host.SERVICE_PROFILE.authorization], [transport.PROFILE.authorization],
                     ['--allow-ui-initialization-diagnostic'], ['--allow-own-notification-structure']):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(argv + flag, profile=PROFILE)
        host.parse_args(argv + [PROFILE.authorization], profile=PROFILE)
        self.assertEqual(PROFILE.method, 'inspectOwnNotificationIdentity')
        self.assertEqual(PROFILE.gate, 'notificationIdentity')
        self.assertEqual(PROFILE.timeout_seconds, 60)
        self.assertEqual(host.SERVICE_PROFILE.timeout_seconds, 0)

    def test_all_guards_and_cleanup_required(self):
        parse(report_text()).verified(RID, 123)
        for key in PROFILE.true_fields:
            for value in (None, 'false'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_failure_retains_only_complete_bound_whitelist(self):
        evidence = parse('PRIVATE_UI_TEXT\n' + report_text(
            notificationIdentityStage='failed', passed='false', reasonCode='STRUCTURE_OTHER'))
        with self.assertRaises(RuntimeError):
            evidence.verified(RID, 123)
        self.assertIn('receipt', evidence.failure_diagnostics(RID, 123))
        self.assertNotIn('PRIVATE_UI_TEXT', str(evidence.failure_diagnostics(RID, 123)))
        for changes in ({'structureCaptured': None}, {'mainPid': '124'}, {'recoveryReceiptId': 'wrong'}):
            evidence = parse(report_text(notificationIdentityStage='failed', passed='false', **changes))
            self.assertNotIn('receipt', evidence.failure_diagnostics(RID, 123))

    def test_title_binding_failure_stages_never_pass(self):
        for phase in ('INITIAL', 'REBIND', 'AFTER_EXPAND'):
            for kind in ('MISSING', 'AMBIGUOUS'):
                reason = 'IDENTITY_' + phase + '_TITLE_' + kind
                evidence = parse('PRIVATE_WINDOW_TEXT\n' + report_text(
                    notificationIdentityStage='failed', passed='false',
                    structureCaptured='false', reasonCode=reason))
                with self.subTest(reason=reason), self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                diagnostic = evidence.failure_diagnostics(RID, 123)
                self.assertEqual(diagnostic['receipt']['reasonCode'], reason)
                self.assertNotIn('PRIVATE_WINDOW_TEXT', str(diagnostic))

    def test_title_wait_timeouts_are_distinct_and_cannot_pass(self):
        for reason in ('IDENTITY_INITIAL_TITLE_WAIT_TIMEOUT', 'IDENTITY_AFTER_EXPAND_TITLE_WAIT_TIMEOUT'):
            evidence = parse('PRIVATE_TITLE\n' + report_text(
                notificationIdentityStage='failed', passed='false',
                structureCaptured='false', reasonCode=reason))
            with self.subTest(reason=reason), self.assertRaises(RuntimeError):
                evidence.verified(RID, 123)
            diagnostic = evidence.failure_diagnostics(RID, 123)
            self.assertEqual(diagnostic['receipt']['reasonCode'], reason)
            self.assertNotIn('PRIVATE_TITLE', str(diagnostic))

    def test_partial_duplicate_skip_crash_other_profile_rejected(self):
        for text in (transport.report_text(), report_text() * 2,
                     report_text().replace('notificationIdentityStage', 'notificationStructureStage'),
                     report_text().replace('OK (1 test)', 'OK (0 tests)'),
                     report_text() + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                     report_text() + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n'):
            with self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def test_transport_one_method_no_destructive_commands(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_device_and_baseline_changes_fail(self):
        for options in ({'hardware': 'wrong'}, {'change_ticks': True}, {'change_ime': True}):
            with self.subTest(options=options):
                result, summary, _, _ = self.exercise(**options)
                self.assertEqual(result, 2)
                self.assertFalse(summary['passed'])

    def test_timeout_only_terminates_local_client(self):
        result, summary, count, child = self.exercise(timeout=True)
        self.assertEqual((result, count), (2, 1))
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        child.terminate.assert_called_once()
        child.kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()

"""Mock-only expansion gate, receipt and transport regressions; no device access."""
import contextlib
import importlib.util
import io
import itertools
import pathlib
import unittest
from unittest.mock import patch
import test_type_notification_confirmation as transport

SPEC = importlib.util.spec_from_file_location(
    'expansion_host', pathlib.Path(__file__).with_name('notification-expansion.py'))
entry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(entry)
host, PROFILE = entry.bounded, entry.PROFILE
RID = transport.RID


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update(notificationExpansionStage='verified', reasonCode='NONE', cleanupReasonCode='NONE',
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


class ExpansionHostTests(unittest.TestCase):
    def exercise(self, **options):
        clock = patch.object(host.time, 'monotonic', side_effect=itertools.count(100, 100)) \
            if options.get('timeout') else contextlib.nullcontext()
        with clock, patch.object(transport, 'entry', entry), patch.object(transport, 'host', host), \
             patch.object(transport, 'PROFILE', PROFILE), patch.object(transport, 'report_text', report_text), \
             patch.object(host, 'TEST_CLASS', PROFILE.test_class):
            return transport.NotificationHostTests().exercise(**options)

    def test_expansion_requires_its_own_authorization(self):
        argv = ['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', '/tmp/unused']
        for flag in ([], [host.SERVICE_PROFILE.authorization], [transport.PROFILE.authorization],
                     ['--allow-ui-initialization-diagnostic'], ['--allow-own-notification-structure'],
                     ['--allow-own-notification-identity']):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(argv + flag, profile=PROFILE)
        host.parse_args(argv + [PROFILE.authorization], profile=PROFILE)
        self.assertEqual(PROFILE.method, 'inspectOwnNotificationExpansion')
        self.assertEqual(PROFILE.gate, 'notificationExpansion')
        self.assertEqual(PROFILE.timeout_seconds, 60)

    def test_every_guard_including_actual_expansion_required(self):
        parse(report_text()).verified(RID, 123)
        for key in PROFILE.true_fields:
            for value in (None, 'false'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_failure_is_private_and_bound(self):
        evidence = parse('PRIVATE_ROW_TEXT\n' + report_text(
            notificationExpansionStage='failed', passed='false', ownRowExpanded='false',
            reasonCode='EXPANSION_POST_TIMEOUT'))
        with self.assertRaises(RuntimeError):
            evidence.verified(RID, 123)
        diagnostic = evidence.failure_diagnostics(RID, 123)
        self.assertEqual(diagnostic['receipt']['reasonCode'], 'EXPANSION_POST_TIMEOUT')
        self.assertNotIn('PRIVATE_ROW_TEXT', str(diagnostic))
        for changes in ({'ownRowExpanded': None}, {'mainPid': '124'}, {'recoveryReceiptId': 'wrong'}):
            self.assertNotIn('receipt', parse(report_text(
                notificationExpansionStage='failed', passed='false', **changes)).failure_diagnostics(RID, 123))

    def test_expansion_failures_never_authorize_export(self):
        suffixes = ('SECURITY', 'ILLEGAL_STATE', 'NULL_POINTER', 'TIMEOUT', 'OTHER',
                    'OWN_ACTIVITY_LAUNCHED', 'LEFT_SYSTEM_UI', 'BOUNDS_EMPTY', 'POST_TIMEOUT',
                    'PHASE_INVALID', 'TRUNCATED', 'IDENTITY_INVALID', 'ACTION_ALREADY_PRESENT',
                    'ACTION_MISSING', 'ACTION_AMBIGUOUS', 'ACTION_UNAVAILABLE')
        for suffix in suffixes:
            reason = 'EXPANSION_' + suffix
            evidence = parse('PRIVATE_GESTURE_ERROR\n' + report_text(
                notificationExpansionStage='failed', passed='false', ownRowExpanded='false',
                structureCaptured='false', reasonCode=reason))
            with self.subTest(reason=reason), self.assertRaises(RuntimeError):
                evidence.verified(RID, 123)
            diagnostic = evidence.failure_diagnostics(RID, 123)
            self.assertEqual(diagnostic['receipt']['reasonCode'], reason)
            self.assertNotIn('PRIVATE_GESTURE_ERROR', str(diagnostic))

    def test_active_package_guard_sites_remain_failed_and_private(self):
        sites = ('INITIAL_BIND', 'REBIND', 'PRE_GESTURE', 'AFTER_BIND', 'AFTER_VALIDATE')
        categories = ('ACTIVE_OWN', 'ACTIVE_OTHER', 'PACKAGE_UNAVAILABLE')
        for site, category in itertools.product(sites, categories):
            reason = 'EXPANSION_' + site + '_' + category
            evidence = parse('PRIVATE_PACKAGE_NAME\n' + report_text(
                notificationExpansionStage='failed', passed='false', ownRowExpanded='false',
                structureCaptured='false', reasonCode=reason))
            with self.subTest(reason=reason), self.assertRaises(RuntimeError):
                evidence.verified(RID, 123)
            diagnostic = evidence.failure_diagnostics(RID, 123)
            self.assertEqual(diagnostic['receipt']['reasonCode'], reason)
            self.assertNotIn('PRIVATE_PACKAGE_NAME', str(diagnostic))
            self.assertNotIn('receipt', evidence.failure_diagnostics('wrong', 123))

    def test_other_profiles_partial_duplicate_skip_crash_rejected(self):
        for text in (transport.report_text(), report_text() * 2,
                     report_text().replace('notificationExpansionStage', 'notificationIdentityStage'),
                     report_text().replace('OK (1 test)', 'OK (0 tests)'),
                     report_text() + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                     report_text() + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n'):
            with self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def test_one_method_no_destructive_device_commands(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_device_or_baseline_change_rejected(self):
        for options in ({'hardware': 'wrong'}, {'change_ticks': True}, {'change_ime': True}):
            with self.subTest(options=options):
                result, summary, _, _ = self.exercise(**options)
                self.assertEqual(result, 2)
                self.assertFalse(summary['passed'])

    def test_timeout_only_stops_local_client(self):
        result, summary, count, child = self.exercise(timeout=True)
        self.assertEqual((result, count), (2, 1))
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        child.terminate.assert_called_once()
        child.kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()

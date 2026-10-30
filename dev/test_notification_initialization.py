"""Offline initialization-only host checks; no real ADB calls."""
import contextlib
import importlib.util
import io
import itertools
import pathlib
import unittest
from unittest.mock import patch
import test_type_notification_confirmation as transport

SPEC = importlib.util.spec_from_file_location(
    'initialization_host', pathlib.Path(__file__).with_name('notification-initialization.py'))
entry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(entry)
host, PROFILE = entry.bounded, entry.PROFILE
RID = transport.RID


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update(initDiagnosticStage='verified', reasonCode='NONE', cleanupReasonCode='NONE',
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


class InitializationHostTests(unittest.TestCase):
    def exercise(self, **options):
        # Reuse the fully mocked device/transport fixture, not another device harness.
        # Only its assertion's expected class and this entry/profile differ.
        profile = PROFILE
        clock = patch.object(host.time, 'monotonic', side_effect=itertools.count(100, 100)) \
            if options.get('timeout') else contextlib.nullcontext()
        with clock, patch.object(transport, 'entry', entry), patch.object(transport, 'host', host), \
             patch.object(transport, 'PROFILE', profile), patch.object(transport, 'report_text', report_text), \
             patch.object(entry, 'PROFILE', profile), patch.object(host, 'TEST_CLASS', profile.test_class):
            return transport.NotificationHostTests().exercise(**options)

    def test_authorization_and_class_are_isolated(self):
        argv = ['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', '/tmp/unused']
        for flag in ([], [host.SERVICE_PROFILE.authorization], [transport.PROFILE.authorization]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(argv + flag, profile=PROFILE)
        host.parse_args(argv + [PROFILE.authorization], profile=PROFILE)
        self.assertEqual(PROFILE.test_class, 'com.example.agent.rootpilot.NotificationInitializationInstrumentedTest')
        self.assertEqual(PROFILE.method, 'diagnoseInitialization')
        self.assertEqual(PROFILE.gate, 'notificationInitialization')
        self.assertEqual(PROFILE.timeout_seconds, 60)
        self.assertEqual(PROFILE.counts, ())
        self.assertEqual(host.SERVICE_PROFILE.timeout_seconds, 0)
        self.assertEqual(host.TIMEOUT_SECONDS, 240)

    def test_success_requires_all_guard_facts(self):
        self.assertEqual(parse(report_text()).verified(RID, 123)['runId'], RID)
        for key in PROFILE.true_fields:
            for value in (None, 'false'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_accessibility_diagnostics_are_optional_and_do_not_change_success_contract(self):
        self.assertEqual(set(PROFILE.diagnostic_fields), {
            'accessibilityEnabledBefore', 'accessibilityEnabledWhileConnected', 'enabledServicesUnchanged',
        })
        for value in ('UNKNOWN', 'NULL', 'ZERO', 'ONE', 'OTHER'):
            with self.subTest(value=value):
                evidence = parse(report_text(accessibilityEnabledBefore=value,
                    accessibilityEnabledWhileConnected=value, enabledServicesUnchanged='true'))
                self.assertEqual(evidence.verified(RID, 123)['accessibilityEnabledBefore'], value)
        self.assertEqual(parse(report_text()).verified(RID, 123)['runId'], RID)

    def test_connected_accessibility_change_is_preserved_and_cannot_pass(self):
        text = report_text(initDiagnosticStage='failed', passed='false', baselineRestored='false',
            accessibilityUnchanged='false', cleanupReasonCode='ACCESSIBILITY_CHANGED',
            accessibilityEnabledBefore='ZERO', accessibilityEnabledWhileConnected='ONE',
            enabledServicesUnchanged='true')
        evidence = parse(text)
        with self.assertRaises(RuntimeError):
            evidence.verified(RID, 123)
        receipt = evidence.failure_diagnostics(RID, 123)['receipt']
        self.assertEqual(receipt['accessibilityEnabledBefore'], 'ZERO')
        self.assertEqual(receipt['accessibilityEnabledWhileConnected'], 'ONE')
        self.assertEqual(receipt['enabledServicesUnchanged'], 'true')
        self.assertEqual(receipt['baselineRestored'], 'false')
        result, summary, _, _ = self.exercise(text=text)
        self.assertEqual(result, 2)
        self.assertFalse(summary['passed'])
        self.assertEqual(summary['failureDiagnostics']['receipt'], receipt)

    def test_diagnostics_do_not_export_service_lists_or_accept_duplicate_fields(self):
        text = report_text(enabledAccessibilityServices='private/service',
            accessibilityEnabledBefore='NULL', accessibilityEnabledWhileConnected='ZERO',
            enabledServicesUnchanged='false')
        self.assertNotIn('private/service', str(parse(text).verified(RID, 123)))
        duplicate = text.replace('INSTRUMENTATION_STATUS_CODE: 0',
            'INSTRUMENTATION_STATUS: accessibilityEnabledBefore=ONE\nINSTRUMENTATION_STATUS_CODE: 0')
        with self.assertRaisesRegex(RuntimeError, 'duplicate_receipt_field'):
            parse(duplicate)

    def test_initialization_failure_is_fixed_evidence_not_pass(self):
        text = report_text(initDiagnosticStage='failed', passed='false',
                           uiAutomationAvailable='false', uiDeviceAvailable='false',
                           reasonCode='UI_AUTOMATION_NULL')
        evidence = parse('PRIVATE_EXCEPTION_TEXT\n' + text)
        with self.assertRaises(RuntimeError):
            evidence.verified(RID, 123)
        receipt = evidence.failure_diagnostics(RID, 123)['receipt']
        self.assertEqual(receipt['reasonCode'], 'UI_AUTOMATION_NULL')
        self.assertNotIn('PRIVATE_EXCEPTION_TEXT', str(receipt))
        self.assertNotIn('receipt', parse(text.replace('INSTRUMENTATION_STATUS_CODE: 0', '')).failure_diagnostics(RID, 123))
        self.assertNotIn('receipt', parse(report_text(passed=None)).failure_diagnostics(RID, 123))

    def test_framework_failure_subtypes_remain_failed_and_private(self):
        for code in ('UI_AUTOMATION_CONNECTION_SHUTDOWN', 'UI_AUTOMATION_ALREADY_CONNECTED',
                     'UI_AUTOMATION_ALREADY_REGISTERED', 'UI_AUTOMATION_REGISTRATION_FAILED',
                     'UI_AUTOMATION_DISCONNECT_CONNECTING'):
            with self.subTest(code=code):
                evidence = parse('PRIVATE_EXCEPTION_TEXT\n' + report_text(
                    initDiagnosticStage='failed', passed='false', uiAutomationAvailable='false',
                    uiDeviceAvailable='false', reasonCode=code))
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                diagnostic = evidence.failure_diagnostics(RID, 123)
                self.assertEqual(diagnostic['receipt']['reasonCode'], code)
                self.assertNotIn('PRIVATE_EXCEPTION_TEXT', str(diagnostic))

    def test_other_profile_or_skipped_test_never_passes(self):
        for text in (transport.report_text(), report_text() * 2,
                     report_text() + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                     report_text().replace('OK (1 test)', 'OK (0 tests)'),
                     report_text() + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n'):
            with self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def test_single_method_transport_and_identity(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['passed'])
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_identity_and_ime_fail_closed(self):
        for options in ({'hardware': 'other'}, {'change_ticks': True}, {'change_ime': True}):
            with self.subTest(options=options):
                result, summary, _, _ = self.exercise(**options)
                self.assertEqual(result, 2)
                self.assertFalse(summary['passed'])

    def test_failure_diagnostic_survives_host_without_private_output(self):
        text = report_text(initDiagnosticStage='failed', passed='false', uiDeviceAvailable='false',
                           reasonCode='UI_DEVICE_ILLEGAL_STATE')
        result, summary, _, _ = self.exercise(text='PRIVATE_EXCEPTION_TEXT\n' + text)
        self.assertEqual(result, 2)
        self.assertEqual(summary['failureDiagnostics']['receipt']['reasonCode'], 'UI_DEVICE_ILLEGAL_STATE')
        self.assertNotIn('PRIVATE_EXCEPTION_TEXT', str(summary))

    def test_timeout_only_terminates_local_client(self):
        result, summary, _, child = self.exercise(timeout=True)
        self.assertEqual(result, 2)
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        self.assertTrue(summary['deviceCleanupUnconfirmed'])
        child.terminate.assert_called_once()
        child.kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()

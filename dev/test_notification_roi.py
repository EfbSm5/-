"""Offline single-ROI host checks: all device and process calls are mocked."""
import contextlib
import importlib.util
import io
import itertools
import pathlib
import unittest
from unittest.mock import patch
import test_type_notification_confirmation as transport

SPEC = importlib.util.spec_from_file_location(
    'roi_host', pathlib.Path(__file__).with_name('notification-roi.py'))
entry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(entry)
host, PROFILE = entry.bounded, entry.PROFILE
RID = transport.RID


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update({key: str(value) for key, value in PROFILE.counts})
    fields.update(notificationRoiCaptureStage='verified', reasonCode='NONE',
                  cleanupReasonCode='NONE', recoveryReceiptId=RID, runId=RID,
                  mainPid='123', noScreenshots='false')
    fields.update(changes)
    return '\n'.join(['INSTRUMENTATION_STATUS: ' + key + '=' + value
                      for key, value in fields.items() if value is not None] + [
                          'INSTRUMENTATION_STATUS_CODE: 0', 'OK (1 test)',
                          'INSTRUMENTATION_CODE: -1']) + '\n'


def parse(text):
    evidence = host.Evidence(PROFILE)
    for line in text.splitlines():
        evidence.feed(line)
    return evidence


class RoiHostTests(unittest.TestCase):
    def exercise(self, **options):
        clock = patch.object(host.time, 'monotonic', side_effect=itertools.count(100, 100)) \
            if options.get('timeout') else contextlib.nullcontext()
        with clock, patch.object(transport, 'entry', entry), patch.object(transport, 'host', host), \
             patch.object(transport, 'PROFILE', PROFILE), patch.object(transport, 'report_text', report_text), \
             patch.object(host, 'TEST_CLASS', PROFILE.test_class):
            return transport.NotificationHostTests().exercise(**options)

    def test_roi_requires_its_own_authorization(self):
        argv = ['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', '/tmp/unused']
        for flag in ([], [host.SERVICE_PROFILE.authorization], [transport.PROFILE.authorization],
                     ['--allow-own-notification-long-press'], ['--allow-own-notification-identity']):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(argv + flag, profile=PROFILE)
        host.parse_args(argv + [PROFILE.authorization], profile=PROFILE)
        self.assertEqual(PROFILE.method, 'inspectOwnNotificationRoi')
        self.assertEqual(PROFILE.gate, 'notificationRoiCapture')
        self.assertEqual(PROFILE.test_class, 'com.example.agent.rootpilot.NotificationInitializationInstrumentedTest')
        self.assertEqual(PROFILE.timeout_seconds, 60)
        self.assertEqual(PROFILE.counts, (('screenshotCalls', 1),))
        self.assertNotIn('noScreenshots', PROFILE.true_fields)
        self.assertIn('noProductScreenshots', PROFILE.true_fields)
        self.assertEqual(host.SERVICE_PROFILE.timeout_seconds, 0)
        self.assertEqual(host.TIMEOUT_SECONDS, 240)

    def test_success_requires_all_guard_facts(self):
        self.assertEqual(parse(report_text()).verified(RID, 123)['runId'], RID)
        for key in PROFILE.true_fields:
            for value in (None, 'false'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_exactly_one_capture_required(self):
        for value in (None, '0', '2', '-1', 'true', '01'):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                parse(report_text(screenshotCalls=value)).verified(RID, 123)

    def test_every_fixed_roi_failure_stays_failed_and_private(self):
        for suffix in ('IDENTITY', 'BOUNDS', 'PRIVACY', 'LIMIT', 'CAPTURE', 'POST', 'WRITE'):
            text = 'PRIVATE_SCREEN_DATA\n' + report_text(
                notificationRoiCaptureStage='failed', passed='false', roiCaptured='false',
                reasonCode='ROI_' + suffix)
            with self.subTest(suffix=suffix):
                evidence = parse(text)
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                diagnostic = evidence.failure_diagnostics(RID, 123)
                self.assertEqual(diagnostic['receipt']['reasonCode'], 'ROI_' + suffix)
                self.assertNotIn('PRIVATE_SCREEN_DATA', str(diagnostic))

    def test_privacy_subreasons_preserve_zero_capture_failure(self):
        for suffix in ('API', 'LOCK', 'ROOT', 'MATCH', 'WINDOWS', 'META', 'OWN', 'OVERLAP',
                       'MATCH_COUNT', 'MATCH_PKG', 'MATCH_ID', 'MATCH_TEXT',
                       'MATCH_WINDOW', 'MATCH_BOUNDS'):
            reason = 'ROI_PRIVACY_' + suffix
            text = report_text(notificationRoiCaptureStage='failed', passed='false',
                               roiCaptured='false', screenshotCalls='0', reasonCode=reason)
            with self.subTest(reason=reason):
                evidence = parse('PRIVATE_WINDOW_DATA\n' + text)
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                diagnostic = evidence.failure_diagnostics(RID, 123)
                self.assertEqual(diagnostic['receipt']['reasonCode'], reason)
                self.assertEqual(diagnostic['receipt']['screenshotCalls'], '0')
                self.assertNotIn('PRIVATE_WINDOW_DATA', str(diagnostic))

    def test_other_profile_skip_crash_or_duplicate_never_passes(self):
        for text in (transport.report_text(), report_text() * 2,
                     report_text() + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                     report_text().replace('OK (1 test)', 'OK (0 tests)'),
                     report_text() + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n'):
            with self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def test_incomplete_failure_has_no_exportable_receipt(self):
        text = report_text(notificationRoiCaptureStage='failed', passed='false', reasonCode='ROI_POST')
        for incomplete in (text.replace('INSTRUMENTATION_STATUS_CODE: 0', ''),
                           text.replace('INSTRUMENTATION_STATUS: screenshotCalls=1\n', ''),
                           text.replace('INSTRUMENTATION_STATUS: baselineRestored=true\n', '')):
            self.assertNotIn('receipt', parse(incomplete).failure_diagnostics(RID, 123))

    def test_cleanup_and_identity_must_match(self):
        for changes in ({'cleanupReasonCode': 'ROI_WRITE'}, {'mainPid': '124'},
                        {'recoveryReceiptId': 'another'}, {'runId': 'not_uuid'}):
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                parse(report_text(**changes)).verified(RID, 123)

    def test_single_method_transport(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['passed'])
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_transport_identity_and_ime_fail_closed(self):
        for options in ({'hardware': 'other'}, {'change_ticks': True}, {'change_ime': True}):
            with self.subTest(options=options):
                result, summary, _, _ = self.exercise(**options)
                self.assertEqual(result, 2)
                self.assertFalse(summary['passed'])

    def test_failure_diagnostic_preserved_without_image_data(self):
        text = report_text(notificationRoiCaptureStage='failed', passed='false',
                           roiCaptured='false', reasonCode='ROI_PRIVACY')
        result, summary, _, _ = self.exercise(text='PRIVATE_SCREEN_DATA\n' + text)
        self.assertEqual(result, 2)
        self.assertEqual(summary['failureDiagnostics']['receipt']['reasonCode'], 'ROI_PRIVACY')
        self.assertNotIn('PRIVATE_SCREEN_DATA', str(summary))

    def test_timeout_terminates_local_client_only(self):
        result, summary, _, child = self.exercise(timeout=True)
        self.assertEqual(result, 2)
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        self.assertTrue(summary['deviceCleanupUnconfirmed'])
        child.terminate.assert_called_once()
        child.kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()

"""Offline notification host regression; all ADB and instrumentation calls are mocked."""
import contextlib
import importlib.util
import io
import json
import pathlib
import shlex
import subprocess
import unittest
import uuid
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location(
    'notification_host', pathlib.Path(__file__).with_name('type-notification-confirmation.py'))
entry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(entry)
host = entry.bounded
PROFILE = entry.PROFILE
RID = '12345678-1234-4234-8234-123456789abc'
RUN = '12345678-1234-4234-8234-123456789def'
UI_GUARD_REASONS = (
    'NOTIFICATION_UI_OPEN_REJECTED', 'NOTIFICATION_UI_TITLE_WAIT_TIMEOUT',
    'NOTIFICATION_UI_TITLE_MISSING', 'NOTIFICATION_UI_TITLE_AMBIGUOUS',
    'NOTIFICATION_UI_ROW_ANCESTOR_MISSING', 'NOTIFICATION_UI_ROW_ANCESTOR_LIMIT',
    'NOTIFICATION_UI_APP_LABEL_EMPTY', 'NOTIFICATION_UI_APP_LABEL_MISSING',
    'NOTIFICATION_UI_APP_LABEL_AMBIGUOUS', 'NOTIFICATION_UI_ROW_TITLE_MISSING',
    'NOTIFICATION_UI_ROW_TITLE_AMBIGUOUS', 'NOTIFICATION_UI_EXPAND_MISSING',
    'NOTIFICATION_UI_EXPAND_AMBIGUOUS', 'NOTIFICATION_UI_EXPAND_DISABLED',
    'NOTIFICATION_UI_EXPAND_NOT_CLICKABLE', 'NOTIFICATION_UI_EXPAND_WAIT_TIMEOUT',
    'NOTIFICATION_UI_ACTION_MISSING', 'NOTIFICATION_UI_ACTION_AMBIGUOUS',
    'NOTIFICATION_UI_ACTION_DISABLED', 'NOTIFICATION_UI_ACTION_NOT_CLICKABLE',
)


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update({k: str(v) for k, v in PROFILE.counts})
    fields.update(notificationStage='verified', reasonCode='NONE', cleanupReasonCode='NONE',
                  recoveryReceiptId=RID, runId=RUN, mainPid='123')
    fields.update(changes)
    lines = ['INSTRUMENTATION_STATUS: ' + k + '=' + v for k, v in fields.items() if v is not None]
    return '\n'.join(lines + ['INSTRUMENTATION_STATUS_CODE: 0', 'OK (1 test)', 'INSTRUMENTATION_CODE: -1']) + '\n'


def parse(text):
    evidence = host.Evidence(PROFILE)
    for line in text.splitlines():
        evidence.feed(line)
    return evidence


class NotificationHostTests(unittest.TestCase):
    def argv(self, output):
        return ['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', str(output), PROFILE.authorization]

    def test_authorization_is_notification_specific(self):
        args = self.argv('/tmp/unused')
        for argv in (args[:-1], args[:-1] + [host.SERVICE_PROFILE.authorization],
                     ['--serial', '', *args[2:]]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(argv, profile=PROFILE)
        self.assertEqual(host.parse_args(args, profile=PROFILE).serial, 'explicit-device')

    def test_complete_receipt_and_exact_counts(self):
        self.assertEqual(parse(report_text()).verified(RID, 123)['targetCommitCalls'], '1')
        for key, expected in PROFILE.counts:
            for value in (None, str(expected + 1), str(expected - 1)):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_missing_or_false_facts_never_pass(self):
        for key in PROFILE.true_fields:
            for value in (None, 'false'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)
        for changes in ({'notificationStage': 'failed'}, {'cleanupReasonCode': 'FAILED'},
                        {'reasonCode': 'FAILED'}, {'mainPid': '124'}, {'runId': 'not_uuid'},
                        {'recoveryReceiptId': RUN}):
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                parse(report_text(**changes)).verified(RID, 123)

    def test_service_receipt_cannot_substitute_for_notification(self):
        text = report_text(notificationStage=None) + 'INSTRUMENTATION_STATUS: serviceDestroyStage=verified\nINSTRUMENTATION_STATUS_CODE: 0\n'
        with self.assertRaises(RuntimeError):
            parse(text).verified(RID, 123)
        with self.assertRaises(RuntimeError):
            evidence = host.Evidence()  # The existing Service profile must remain isolated.
            for line in report_text().splitlines():
                evidence.feed(line)
            evidence.verified(RID, 123)

    def test_skips_crash_partial_duplicate_and_wrong_count_fail(self):
        base = report_text()
        for text in (base + 'INSTRUMENTATION_STATUS_CODE: -4\n', base + base,
                     base + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n',
                     base.replace('OK (1 test)', 'OK (2 tests)'),
                     base.replace('INSTRUMENTATION_CODE: -1', ''),
                     base.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS: passed=true\nINSTRUMENTATION_STATUS_CODE: 0')):
            with self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def exercise(self, *, hardware='hardware', change_ticks=False, change_ime=False, text=None, timeout=False, returncode=0):
        output = pathlib.Path('/tmp') / ('notification-host-mock-' + str(uuid.uuid4()))
        identities = 0
        imes = 0

        def adb(command, **kwargs):
            nonlocal identities, imes
            self.assertEqual(command[:3], ['adb', '-s', 'explicit-device'])
            self.assertEqual(kwargs['timeout'], 15)
            argv = shlex.split(command[4]) if command[3] == 'shell' else command[3:]
            if argv == ['get-state']:
                value = 'device'
            elif argv == ['getprop', 'ro.serialno']:
                value = hardware
            elif argv == ['getprop', 'ro.product.model']:
                value = 'model'
            elif argv == ['pidof', host.PACKAGE]:
                identities += 1
                value = '123'
            elif argv[:2] == ['su', '-c']:
                value = '10001\n' + ('100' if change_ticks and identities > 1 else '99') + '\n' + host.PACKAGE
            elif argv == ['settings', 'get', 'secure', 'default_input_method']:
                imes += 1
                value = 'other/.Ime' if change_ime and imes > 1 else 'original/.Ime'
            elif argv == ['settings', 'get', 'secure', 'enabled_accessibility_services']:
                value = 'unchanged/services'
            else:
                self.fail('unexpected device command')
            return subprocess.CompletedProcess(command, 0, value + '\n', '')

        child = Mock(returncode=returncode)
        child.stdout = io.StringIO(report_text() if text is None else text)
        child.poll.return_value = None if timeout else returncode
        child.wait.side_effect = [subprocess.TimeoutExpired('local-adb', 35), 0] if timeout else None
        child.wait.return_value = returncode
        try:
            with patch.object(host.subprocess, 'run', side_effect=adb), \
                 patch.object(host.subprocess, 'Popen', return_value=child) as popen, \
                 patch.object(host.uuid, 'uuid4', return_value=uuid.UUID(RID)), \
                 patch.object(host, 'TIMEOUT_SECONDS', 0 if timeout else 5), \
                 contextlib.redirect_stdout(io.StringIO()):
                result = entry.main(self.argv(output))
            summary = json.loads((output / 'summary.json').read_text())
            if popen.called:
                command = popen.call_args.args[0]
                self.assertEqual(command[:3], ['adb', '-s', 'explicit-device'])
                self.assertIn('--no-restart', command)
                self.assertIn(host.TEST_CLASS + '#' + PROFILE.method, command)
                self.assertIn(PROFILE.gate, command)
                self.assertEqual(command.count('instrument'), 1)
                for forbidden in ('kill', 'force-stop', 'stopservice', 'ACTION_STOP',
                                  'typeServiceDestroy', 'typeProcessRecovery', 'typeCommitBoundaryRecovery'):
                    self.assertNotIn(forbidden, command)
            return result, summary, popen.call_count, child
        finally:
            exact = output.resolve()
            self.assertEqual(exact.parent, pathlib.Path('/tmp').resolve())
            if exact.exists():
                (exact / 'summary.json').unlink()
                exact.rmdir()

    def test_offline_entry_is_single_method_and_non_destructive(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['passed'])
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_wrong_device_rejected_before_instrumentation(self):
        result, summary, count, _ = self.exercise(hardware='other')
        self.assertEqual((result, count), (2, 0))
        self.assertEqual(summary['hostError'], 'wrong_hardware')

    def test_identity_ime_and_process_exit_must_hold(self):
        for options in ({'change_ticks': True}, {'change_ime': True}, {'returncode': 1}):
            with self.subTest(options=options):
                result, summary, _, _ = self.exercise(**options)
                self.assertEqual(result, 2)
                self.assertFalse(summary['passed'])

    def test_timeout_only_terminates_local_client(self):
        result, summary, _, child = self.exercise(timeout=True)
        self.assertEqual(result, 2)
        self.assertTrue(summary['deviceCleanupUnconfirmed'])
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        child.terminate.assert_called_once()
        child.kill.assert_not_called()

    def test_failed_receipt_is_preserved_without_passing(self):
        text = report_text(notificationStage='failed', reasonCode='NOTIFICATION_UI_UNAVAILABLE',
                           passed='false').replace('OK (1 test)', 'INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!')
        result, summary, _, _ = self.exercise(text='PRIVATE_RUNNER_TEXT\n' + text)
        self.assertEqual(result, 2)
        self.assertFalse(summary['passed'])
        self.assertTrue(summary['deviceCleanupUnconfirmed'])
        diagnostic = summary['failureDiagnostics']
        self.assertEqual(diagnostic['receipt']['reasonCode'], 'NOTIFICATION_UI_UNAVAILABLE')
        self.assertEqual(diagnostic['statusCounts'], {'0': 1, '-2': 1})
        self.assertTrue(diagnostic['runnerFailure'])
        self.assertNotIn('PRIVATE_RUNNER_TEXT', json.dumps(summary))

    def test_unbound_duplicate_or_partial_receipts_are_not_exported(self):
        samples = [report_text(recoveryReceiptId=RUN), report_text(mainPid='456'),
                   report_text() + report_text(),
                   'INSTRUMENTATION_STATUS: reasonCode=FAILED\nINSTRUMENTATION_CODE: -1\n']
        for text in samples:
            with self.subTest(text=text):
                result, summary, _, _ = self.exercise(text=text)
                self.assertEqual(result, 2)
                self.assertNotIn('receipt', summary['failureDiagnostics'])

    def test_bootstrap_failure_keeps_only_fixed_diagnostics(self):
        text = ('INSTRUMENTATION_RESULT: shortMsg=PRIVATE_MESSAGE\n'
                'INSTRUMENTATION_STATUS_CODE: PRIVATE_CODE\nINSTRUMENTATION_CODE: 0\n')
        result, summary, _, _ = self.exercise(text=text)
        self.assertEqual(result, 2)
        self.assertEqual(summary['failureDiagnostics']['reportCount'], 0)
        self.assertEqual(summary['failureDiagnostics']['statusCounts'], {'other': 1})
        self.assertNotIn('PRIVATE', json.dumps(summary))

    def test_service_profile_also_preserves_bound_failed_receipt(self):
        evidence = host.Evidence()
        for line in ('INSTRUMENTATION_STATUS: serviceDestroyStage=failed',
                     'INSTRUMENTATION_STATUS: recoveryReceiptId=' + RID,
                     'INSTRUMENTATION_STATUS: mainPid=123',
                     'INSTRUMENTATION_STATUS: reasonCode=TIMEOUT',
                     'INSTRUMENTATION_STATUS: cleanupReasonCode=NONE',
                     'INSTRUMENTATION_STATUS: passed=false',
                     'INSTRUMENTATION_STATUS: baselineRestored=false',
                     'INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -2'):
            evidence.feed(line)
        with self.assertRaises(RuntimeError):
            evidence.verified(RID, 123)
        diagnostic = evidence.failure_diagnostics(RID, 123)
        self.assertEqual(diagnostic['receipt']['serviceDestroyStage'], 'failed')
        self.assertEqual(diagnostic['receipt']['reasonCode'], 'TIMEOUT')

    def test_closed_but_incomplete_frames_are_not_exported(self):
        for key in PROFILE.report_fields - {'runId'}:
            with self.subTest(profile='notification', missing=key):
                evidence = parse(report_text(notificationStage='failed', **{key: None})
                                 if key != 'notificationStage' else report_text(notificationStage=None))
                self.assertNotIn('receipt', evidence.failure_diagnostics(RID, 123))
        fields = dict(serviceDestroyStage='failed', reasonCode='TIMEOUT', cleanupReasonCode='NONE',
                      recoveryReceiptId=RID, mainPid='123', passed='false', baselineRestored='false')
        for missing in fields:
            with self.subTest(profile='service', missing=missing):
                evidence = host.Evidence()
                for key, value in fields.items():
                    if key != missing:
                        evidence.feed('INSTRUMENTATION_STATUS: ' + key + '=' + value)
                evidence.feed('INSTRUMENTATION_STATUS_CODE: 0')
                self.assertNotIn('receipt', evidence.failure_diagnostics(RID, 123))
        # An unbound run is legitimate in a complete early-failure notification report.
        evidence = parse(report_text(notificationStage='failed', runId=None, passed='false'))
        self.assertIn('receipt', evidence.failure_diagnostics(RID, 123))

    def test_notification_stage_reason_codes_remain_failure_only(self):
        for reason in ('NOTIFICATION_MANAGER_ACCESS', 'NOTIFICATION_QUERY', 'NOTIFICATION_OBSERVER_REGISTER',
                       'NOTIFICATION_UI_DEVICE', 'NOTIFICATION_UI_OPEN', 'NOTIFICATION_UI_ROW_QUERY',
                       'NOTIFICATION_UI_EXPAND', 'NOTIFICATION_UI_ACTION_QUERY', 'NOTIFICATION_UI_CLICK'):
            with self.subTest(reason=reason):
                evidence = parse(report_text(notificationStage='failed', reasonCode=reason, passed='false'))
                self.assertEqual(evidence.failure_diagnostics(RID, 123)['receipt']['reasonCode'], reason)
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)

    def test_ui_guard_failures_keep_complete_fixed_receipt_and_unknown_counts(self):
        for reason in UI_GUARD_REASONS:
            with self.subTest(reason=reason):
                fields = dict.fromkeys(PROFILE.true_fields, 'false')
                fields.update({key: '-1' for key, _ in PROFILE.counts})
                fields.update(notificationStage='failed', reasonCode=reason, baselineRestored='true')
                evidence = parse(report_text(**fields))
                receipt = evidence.failure_diagnostics(RID, 123)['receipt']
                self.assertEqual(receipt['reasonCode'], reason)
                self.assertEqual(receipt['notificationUiClicked'], 'false')
                self.assertEqual(receipt['notificationActivityObserved'], 'false')
                self.assertTrue(all(receipt[key] == '-1' for key, _ in PROFILE.counts))
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                self.assertNotIn('receipt', evidence.failure_diagnostics(RUN, 123))
                self.assertNotIn('receipt', evidence.failure_diagnostics(RID, 124))

    def test_ui_guard_failure_host_summary_stays_failed_and_does_not_retry(self):
        for reason in UI_GUARD_REASONS:
            with self.subTest(reason=reason):
                fields = dict.fromkeys(PROFILE.true_fields, 'false')
                fields.update({key: '-1' for key, _ in PROFILE.counts})
                fields.update(notificationStage='failed', reasonCode=reason, baselineRestored='true')
                text = ('PRIVATE_NOTIFICATION_TEXT\nINSTRUMENTATION_STATUS: stream=PRIVATE_EXCEPTION\n' +
                        report_text(**fields).replace('OK (1 test)', 'FAILURES!!!'))
                result, summary, count, child = self.exercise(text=text)
                self.assertEqual((result, count), (2, 1))
                self.assertFalse(summary['passed'])
                self.assertTrue(summary['deviceCleanupUnconfirmed'])
                self.assertEqual(summary['failureDiagnostics']['receipt']['reasonCode'], reason)
                self.assertNotIn('PRIVATE_', json.dumps(summary))
                child.terminate.assert_not_called()
                child.kill.assert_not_called()

    def test_privacy_and_missing_activity_evidence(self):
        result, summary, _, _ = self.exercise(text='SECRET_TOKEN\nINSTRUMENTATION_STATUS: stream=SECRET\n' +
                                            report_text(notificationActivityObserved=None))
        self.assertEqual(result, 2)
        self.assertNotIn('SECRET', json.dumps(summary))
        with self.assertRaises(RuntimeError):
            parse(report_text(reasonCode='unsafe token text'))


if __name__ == '__main__':
    unittest.main()

"""Offline manual notification host tests; every process/device call is mocked."""
import contextlib
import importlib.util
import io
import json
import pathlib
import shlex
import subprocess
import tempfile
import unittest
import uuid
from unittest.mock import Mock, patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, pathlib.Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


entry = load('manual_notification_host', 'type-notification-manual-confirmation.py')
automatic = load('automatic_notification_host_unchanged', 'type-notification-confirmation.py')
host = entry.bounded
PROFILE = entry.PROFILE
RID = '12345678-1234-4234-8234-123456789abc'
RUN = '12345678-1234-4234-8234-123456789def'


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update({key: str(value) for key, value in PROFILE.counts})
    fields.update(manualNotificationStage='verified', reasonCode='NONE', cleanupReasonCode='NONE',
                  recoveryReceiptId=RID, runId=RUN, mainPid='123', notificationUiClicked='false')
    fields.update(changes)
    lines = ['INSTRUMENTATION_STATUS: ' + key + '=' + value
             for key, value in fields.items() if value is not None]
    return '\n'.join(lines + ['INSTRUMENTATION_STATUS_CODE: 0', 'OK (1 test)',
                              'INSTRUMENTATION_CODE: -1']) + '\n'


def parse(text, profile=PROFILE):
    evidence = host.Evidence(profile)
    for line in text.splitlines():
        evidence.feed(line)
    return evidence


class ManualNotificationHostTests(unittest.TestCase):
    def argv(self, output):
        return ['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', str(output), PROFILE.authorization]

    def test_profile_is_exact_and_old_timeouts_are_unchanged(self):
        self.assertEqual(PROFILE.method, 'realNotificationManuallyApprovesTypeOnce')
        self.assertEqual(PROFILE.gate, 'typeNotificationManualConfirmation')
        self.assertEqual(PROFILE.stage, 'manualNotificationStage')
        self.assertEqual(PROFILE.true_fields, (
            'passed', 'baselineRestored', 'manualMode', 'manualWaitArmed',
            'noAutomaticApproval', 'notificationActivityObserved', 'oldConfirmationInvalid',
            'targetSameInstance', 'targetTextMatches', 'originalImeRestored',
            'historyFinalized', 'snapshotCleared'))
        self.assertEqual(PROFILE.counts, (('confirmedActions', 1), ('targetCommitCalls', 1),
                                         ('duplicateExecutions', 0), ('automaticApprovalAttempts', 0)))
        self.assertEqual(PROFILE.authorization, '--allow-live-execution-and-manual-notification-approval')
        self.assertEqual(PROFILE.timeout_seconds, 420)
        self.assertEqual(host.TIMEOUT_SECONDS, 240)
        self.assertEqual(host.SERVICE_PROFILE.timeout_seconds, 0)
        self.assertEqual(automatic.PROFILE.timeout_seconds, 0)
        self.assertEqual(automatic.bounded.TIMEOUT_SECONDS, 240)
        self.assertEqual(host.CLEANUP_SECONDS, 35)
        self.assertEqual(automatic.bounded.CLEANUP_SECONDS, 35)
        with patch.object(entry.bounded, 'main', return_value=17) as main:
            self.assertEqual(entry.main(['only-forwarded']), 17)
        main.assert_called_once_with(['only-forwarded'], profile=PROFILE)

    def test_authorizations_are_isolated_before_any_adb(self):
        args = self.argv('/tmp/unused-manual-mock')
        for authorization in (None, host.SERVICE_PROFILE.authorization, automatic.PROFILE.authorization):
            argv = args[:-1] + ([] if authorization is None else [authorization])
            with self.subTest(authorization=authorization), \
                 patch.object(host.subprocess, 'run') as run, \
                 patch.object(host.subprocess, 'Popen') as popen, \
                 contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                entry.main(argv)
            run.assert_not_called()
            popen.assert_not_called()
        for profile in (host.SERVICE_PROFILE, automatic.PROFILE):
            with self.subTest(profile=profile.method), contextlib.redirect_stderr(io.StringIO()), \
                 self.assertRaises(SystemExit):
                host.parse_args(args, profile=profile)
        self.assertEqual(host.parse_args(args, profile=PROFILE).serial, 'explicit-device')

    def test_explicit_device_identity_values_are_required(self):
        args = self.argv('/tmp/unused-manual-mock')
        for option in ('--serial', '--expected-hardware-serial', '--expected-model'):
            for value in ('', ' ', '-other', 'private\nvalue'):
                changed = list(args)
                changed[changed.index(option) + 1] = value
                with self.subTest(option=option, value=value), \
                     contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    host.parse_args(changed, profile=PROFILE)

    def test_complete_receipt_has_manual_nonautomation_proofs(self):
        report = parse(report_text()).verified(RID, 123)
        self.assertEqual(report['manualNotificationStage'], 'verified')
        self.assertEqual(report['noAutomaticApproval'], 'true')
        self.assertEqual(report['automaticApprovalAttempts'], '0')
        # The shared profile has no false_fields; this does not establish touch provenance.
        self.assertNotIn('notificationUiClicked', PROFILE.report_fields)
        self.assertNotIn('notificationUiClicked', report)
        self.assertNotIn('ready', PROFILE.report_fields)

    def test_each_true_field_is_required_and_exact(self):
        for key in PROFILE.true_fields:
            for value in (None, 'false', 'TRUE', '1'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_each_count_is_required_and_exact(self):
        for key, expected in PROFILE.counts:
            for value in (None, str(expected + 1), str(expected - 1), '0' + str(expected), 'true'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_stage_reason_cleanup_and_identity_must_match(self):
        for changes in ({'manualNotificationStage': None}, {'manualNotificationStage': 'failed'},
                        {'manualNotificationStage': 'ready'}, {'reasonCode': 'TIMEOUT'},
                        {'cleanupReasonCode': 'FAILED'}, {'mainPid': '124'}, {'mainPid': '0123'},
                        {'runId': None}, {'runId': 'not_uuid'}, {'runId': RUN.upper()},
                        {'recoveryReceiptId': RUN}):
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                parse(report_text(**changes)).verified(RID, 123)

    def test_automatic_and_service_receipts_cannot_replace_manual(self):
        for stage in ('notificationStage', 'serviceDestroyStage'):
            text = report_text(manualNotificationStage=None).replace(
                'INSTRUMENTATION_STATUS_CODE: 0',
                'INSTRUMENTATION_STATUS: ' + stage + '=verified\nINSTRUMENTATION_STATUS_CODE: 0')
            with self.subTest(stage=stage), self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)
        for profile in (host.SERVICE_PROFILE, automatic.PROFILE):
            with self.subTest(profile=profile.method), self.assertRaises(RuntimeError):
                parse(report_text(), profile).verified(RID, 123)

    def test_skips_partial_duplicate_and_runner_failures_never_pass(self):
        base = report_text()
        samples = (
            base + 'INSTRUMENTATION_STATUS_CODE: -4\n',
            base + 'INSTRUMENTATION_STATUS_CODE: -3\n',
            base + base,
            base + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n',
            base + 'INSTRUMENTATION_FAILED: private failure\n',
            base.replace('OK (1 test)', 'OK (2 tests)'),
            base.replace('OK (1 test)', ''),
            base.replace('INSTRUMENTATION_CODE: -1', ''),
            base.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
            base.replace('INSTRUMENTATION_STATUS_CODE: 0', ''),
            base + 'INSTRUMENTATION_STATUS: manualNotificationStage=verified\n',
            base.replace('INSTRUMENTATION_STATUS_CODE: 0',
                         'INSTRUMENTATION_STATUS: passed=true\nINSTRUMENTATION_STATUS_CODE: 0'),
        )
        for index, text in enumerate(samples):
            with self.subTest(sample=index), self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def exercise(self, *, hardware='hardware', model='model', state='device',
                 changed_identity=None, change_ime=False, change_accessibility=False,
                 text=None, timeout=False, terminate_timeout=False, returncode=0):
        identities = 0
        imes = 0
        accessibility_reads = 0

        def adb(command, **kwargs):
            nonlocal identities, imes, accessibility_reads
            self.assertEqual(command[:3], ['adb', '-s', 'explicit-device'])
            self.assertEqual(kwargs['timeout'], 15)
            argv = shlex.split(command[4]) if command[3] == 'shell' else command[3:]
            if argv == ['get-state']:
                value = state
            elif argv == ['getprop', 'ro.serialno']:
                value = hardware
            elif argv == ['getprop', 'ro.product.model']:
                value = model
            elif argv == ['pidof', host.PACKAGE]:
                identities += 1
                value = '124' if changed_identity == 'pid' and identities > 1 else '123'
            elif argv[:2] == ['su', '-c']:
                uid, ticks, name = '10001', '99', host.PACKAGE
                if identities > 1:
                    if changed_identity == 'uid':
                        uid = '10002'
                    elif changed_identity == 'ticks':
                        ticks = '100'
                    elif changed_identity == 'name':
                        name += ':other'
                value = '\n'.join((uid, ticks, name))
            elif argv == ['settings', 'get', 'secure', 'default_input_method']:
                imes += 1
                value = 'other/.Ime' if change_ime and imes > 1 else 'original/.Ime'
            elif argv == ['settings', 'get', 'secure', 'enabled_accessibility_services']:
                accessibility_reads += 1
                value = 'other/services' if change_accessibility and accessibility_reads > 1 else 'unchanged/services'
            else:
                self.fail('unexpected device command')
            return subprocess.CompletedProcess(command, 0, value + '\n', '')

        child = Mock(returncode=returncode)
        child.stdout = io.StringIO(report_text() if text is None else text)
        child.poll.return_value = None if timeout else returncode
        child.wait.return_value = returncode
        if timeout:
            waits = [subprocess.TimeoutExpired('local-adb', 35)]
            if terminate_timeout:
                waits.append(subprocess.TimeoutExpired('local-adb', 3))
            child.wait.side_effect = waits + [0]
        # A zero profile timeout falls back to 240 seconds; negative forces immediate expiry.
        test_profile = PROFILE._replace(timeout_seconds=-1 if timeout else 5)
        with tempfile.TemporaryDirectory(prefix='rootpilot-manual-host-mock-', dir='/tmp') as directory:
            output = pathlib.Path(directory)
            self.assertEqual(output.resolve().parent, pathlib.Path('/tmp').resolve())
            output.rmdir()  # The bounded host requires a fresh direct /tmp child.
            with patch.object(host.subprocess, 'run', side_effect=adb), \
                 patch.object(host.subprocess, 'Popen', return_value=child) as popen, \
                 patch.object(host.uuid, 'uuid4', return_value=uuid.UUID(RID)), \
                 patch.object(entry, 'PROFILE', test_profile), \
                 contextlib.redirect_stdout(io.StringIO()) as stdout:
                result = entry.main(self.argv(output))
            summary = json.loads((output / 'summary.json').read_text())
            self.assertNotIn('PRIVATE_', stdout.getvalue())
            self.assertEqual({path.name for path in output.iterdir()}, {'summary.json'})
            if popen.called:
                command = popen.call_args.args[0]
                self.assertEqual(command[:3], ['adb', '-s', 'explicit-device'])
                self.assertIn('--no-restart', command)
                self.assertIn(host.TEST_CLASS + '#' + PROFILE.method, command)
                self.assertIn(PROFILE.gate, command)
                self.assertEqual(command.count('instrument'), 1)
                self.assertEqual(command.count('-e'), 5)
                for forbidden in ('kill', 'force-stop', 'stopservice', 'ACTION_STOP',
                                  'typeNotificationConfirmation', 'typeServiceDestroy',
                                  'typeProcessRecovery', 'typeCommitBoundaryRecovery'):
                    self.assertNotIn(forbidden, command)
            return result, summary, popen.call_count, child

    def test_offline_entry_runs_only_manual_single_method(self):
        result, summary, count, child = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['passed'])
        self.assertTrue(summary['mainIdentityUnchanged'])
        self.assertTrue(summary['originalImeRestored'])
        self.assertTrue(summary['accessibilitySettingsUnchanged'])
        self.assertFalse(summary['deviceCleanupUnconfirmed'])
        self.assertEqual(summary['evidence']['automaticApprovalAttempts'], '0')
        child.terminate.assert_not_called()
        child.kill.assert_not_called()

    def test_wrong_device_rejected_before_instrumentation(self):
        for options, reason in (({'hardware': 'other'}, 'wrong_hardware'),
                                ({'model': 'other'}, 'wrong_model'),
                                ({'state': 'offline'}, 'device_not_ready')):
            with self.subTest(options=options):
                result, summary, count, _ = self.exercise(**options)
                self.assertEqual((result, count), (2, 0))
                self.assertFalse(summary['passed'])
                self.assertEqual(summary['hostError'], reason)

    def test_pid_uid_start_ticks_and_process_name_must_hold(self):
        for changed_identity in ('pid', 'uid', 'ticks', 'name'):
            with self.subTest(changed_identity=changed_identity):
                result, summary, count, _ = self.exercise(changed_identity=changed_identity)
                self.assertEqual((result, count), (2, 1))
                self.assertFalse(summary['passed'])
                self.assertTrue(summary['deviceCleanupUnconfirmed'])
                self.assertNotIn('mainIdentityUnchanged', summary)

    def test_ime_accessibility_and_client_exit_must_hold(self):
        for options, reason in (({'change_ime': True}, 'original_ime_changed'),
                                ({'change_accessibility': True}, 'accessibility_settings_changed'),
                                ({'returncode': 1}, 'instrumentation_adb_failed')):
            with self.subTest(options=options):
                result, summary, count, _ = self.exercise(**options)
                self.assertEqual((result, count), (2, 1))
                self.assertFalse(summary['passed'])
                self.assertEqual(summary['hostError'], reason)

    def test_timeout_only_terminates_local_client_after_original_cleanup(self):
        result, summary, count, child = self.exercise(timeout=True)
        self.assertEqual((result, count), (2, 1))
        self.assertFalse(summary['passed'])
        self.assertTrue(summary['deviceCleanupUnconfirmed'])
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        self.assertEqual([call.kwargs['timeout'] for call in child.wait.call_args_list], [35, 3])
        child.terminate.assert_called_once_with()
        child.kill.assert_not_called()

    def test_timeout_escalation_still_kills_only_mock_local_client(self):
        result, summary, count, child = self.exercise(timeout=True, terminate_timeout=True)
        self.assertEqual((result, count), (2, 1))
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        self.assertEqual([call.kwargs['timeout'] for call in child.wait.call_args_list], [35, 3, 3])
        child.terminate.assert_called_once_with()
        child.kill.assert_called_once_with()

    def test_failed_final_receipt_is_filtered_preserved_and_never_passes(self):
        text = ('PRIVATE_RUNNER_TEXT\nINSTRUMENTATION_STATUS: stream=PRIVATE_EXCEPTION\n' +
                report_text(manualNotificationStage='failed', passed='false', reasonCode='TIMEOUT')
                .replace('OK (1 test)', 'INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!'))
        result, summary, count, child = self.exercise(text=text)
        self.assertEqual((result, count), (2, 1))
        self.assertFalse(summary['passed'])
        self.assertTrue(summary['deviceCleanupUnconfirmed'])
        diagnostic = summary['failureDiagnostics']
        self.assertEqual(diagnostic['receipt']['manualNotificationStage'], 'failed')
        self.assertEqual(diagnostic['receipt']['reasonCode'], 'TIMEOUT')
        self.assertEqual(diagnostic['statusCounts'], {'0': 1, '-2': 1})
        self.assertTrue(diagnostic['runnerFailure'])
        self.assertNotIn('PRIVATE_', json.dumps(summary))
        child.terminate.assert_not_called()
        child.kill.assert_not_called()

    def test_closed_partial_or_unbound_receipts_are_not_exported(self):
        for key in PROFILE.report_fields - {'runId'}:
            with self.subTest(missing=key):
                changes = {'manualNotificationStage': 'failed', 'passed': 'false', key: None}
                self.assertNotIn('receipt', parse(report_text(**changes)).failure_diagnostics(RID, 123))
        for changes in ({'recoveryReceiptId': RUN}, {'mainPid': '124'}):
            with self.subTest(changes=changes):
                self.assertNotIn('receipt', parse(report_text(**changes)).failure_diagnostics(RID, 123))
        # A complete early failure can predate run binding, but is still not success.
        early = parse(report_text(manualNotificationStage='failed', passed='false', runId=None))
        self.assertIn('receipt', early.failure_diagnostics(RID, 123))
        with self.assertRaises(RuntimeError):
            early.verified(RID, 123)

    def test_host_rejects_skip_duplicate_partial_and_failure_without_retry(self):
        base = report_text()
        samples = (base + 'INSTRUMENTATION_STATUS_CODE: -4\n', base + base,
                   base.replace('INSTRUMENTATION_CODE: -1', ''),
                   base.replace('manualNotificationStage=verified', 'notificationStage=verified'),
                   base + 'INSTRUMENTATION_RESULT: shortMsg=PRIVATE_CRASH\n')
        for index, text in enumerate(samples):
            with self.subTest(sample=index):
                result, summary, count, child = self.exercise(text=text)
                self.assertEqual((result, count), (2, 1))
                self.assertFalse(summary['passed'])
                self.assertTrue(summary['deviceCleanupUnconfirmed'])
                self.assertNotIn('PRIVATE_', json.dumps(summary))
                child.terminate.assert_not_called()
                child.kill.assert_not_called()

    def test_bootstrap_failure_keeps_only_fixed_diagnostics(self):
        text = ('INSTRUMENTATION_RESULT: shortMsg=PRIVATE_MESSAGE\n'
                'INSTRUMENTATION_STATUS_CODE: PRIVATE_CODE\nINSTRUMENTATION_CODE: 0\n')
        result, summary, count, _ = self.exercise(text=text)
        self.assertEqual((result, count), (2, 1))
        diagnostic = summary['failureDiagnostics']
        self.assertEqual(diagnostic['reportCount'], 0)
        self.assertEqual(diagnostic['statusCounts'], {'other': 1})
        self.assertNotIn('receipt', diagnostic)
        self.assertNotIn('PRIVATE_', json.dumps(summary))

    def test_invalid_scalar_and_unknown_fields_do_not_leak(self):
        for text in ('PRIVATE_TOKEN\nINSTRUMENTATION_STATUS: token=PRIVATE_TOKEN\n' + report_text(),
                     report_text(reasonCode='PRIVATE unsafe message')):
            with self.subTest(invalid_scalar='unsafe' in text):
                result, summary, _, _ = self.exercise(text=text)
                self.assertNotIn('PRIVATE', json.dumps(summary))
                if 'unsafe' in text:
                    self.assertEqual(result, 2)
                    self.assertEqual(summary['hostError'], 'invalid_receipt_scalar')
                else:
                    self.assertEqual(result, 0)


if __name__ == '__main__':
    unittest.main()

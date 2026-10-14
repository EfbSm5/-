"""Entirely offline regression for the Service-only host; subprocesses are mocked."""
import contextlib
import importlib.util
import io
import json
import pathlib
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

SPEC = importlib.util.spec_from_file_location('service_host', pathlib.Path(__file__).with_name('type-service-destroy.py'))
host = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host)
RID = '12345678-1234-4234-8234-123456789abc'
RUN = '12345678-1234-4234-8234-123456789def'


def report_text(receipt=RID, **changes):
    fields = dict.fromkeys(host.TRUE_FIELDS, 'true')
    fields.update(dict.fromkeys(host.ZERO_FIELDS, '0'))
    fields.update(serviceDestroyStage='verified', reasonCode='NONE', cleanupReasonCode='NONE',
                  recoveryReceiptId=receipt, runId=RUN, mainPid='123')
    fields.update(changes)
    lines = ['INSTRUMENTATION_STATUS: ' + k + '=' + v for k, v in fields.items() if v is not None]
    return '\n'.join(lines + ['INSTRUMENTATION_STATUS_CODE: 0', 'OK (1 test)', 'INSTRUMENTATION_CODE: -1']) + '\n'


def parse(text):
    evidence = host.Evidence()
    for line in text.splitlines():
        evidence.feed(line)
    return evidence


class ServiceHostTests(unittest.TestCase):
    def args(self, output):
        return host.parse_args(['--serial', 'explicit-device', '--expected-hardware-serial', 'hardware',
                                '--expected-model', 'model', '--output', str(output),
                                '--allow-live-execution-and-service-destroy'])

    def test_explicit_authorization_and_identity_required(self):
        for argv in ([], ['--serial', 'device'],
                     ['--serial', 'device', '--expected-hardware-serial', 'hardware', '--expected-model', 'model', '--output', '/tmp/example']):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(argv)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            host.parse_args(['--serial', '', '--expected-hardware-serial', 'hardware', '--expected-model', 'model',
                             '--output', '/tmp/example', '--allow-live-execution-and-service-destroy'])

    def test_complete_single_receipt(self):
        self.assertEqual(parse(report_text()).verified(RID, 123)['runId'], RUN)

    def test_each_required_fact_is_enforced_under_optimization(self):
        for key in host.TRUE_FIELDS + host.ZERO_FIELDS:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                parse(report_text(**{key: None})).verified(RID, 123)
        for changes in ({'confirmedActions': '1'}, {'targetCommitCalls': '1'}, {'mainPid': '456'},
                        {'recoveryReceiptId': RUN}, {'runId': 'not_uuid'}, {'reasonCode': 'FAILED'},
                        {'cleanupReasonCode': 'FAILED'}, {'serviceDestroyStage': 'failed'}):
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                parse(report_text(**changes)).verified(RID, 123)

    def test_skips_crashes_partial_duplicate_and_multitest_fail(self):
        base = report_text()
        for text in (base + 'INSTRUMENTATION_STATUS_CODE: -4\n',
                     base + 'INSTRUMENTATION_STATUS_CODE: -2\n',
                     base + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n',
                     base.replace('OK (1 test)', 'OK (2 tests)'),
                     base.replace('INSTRUMENTATION_CODE: -1', ''), base + base,
                     base.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS: passed=true\nINSTRUMENTATION_STATUS_CODE: 0'),
                     'INSTRUMENTATION_STATUS: passed=true\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n'):
            with self.subTest(text=text[-100:]), self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def test_arbitrary_output_not_retained(self):
        evidence = parse('SECRET_MODEL_OUTPUT\nINSTRUMENTATION_STATUS: stream=SECRET_TOKEN\n' + report_text())
        self.assertNotIn('SECRET', json.dumps(evidence.verified(RID, 123)))
        with self.assertRaises(RuntimeError):
            parse(report_text(reasonCode='unsafe credential text'))

    def exercise(self, *, hardware='hardware', change_pid=False, change_ticks=False, change_ime=False,
                 text=None, timeout=False, returncode=0):
        with tempfile.TemporaryDirectory(prefix='service-host-test-') as scratch:
            output = pathlib.Path(scratch) / 'unused'
            # The host deliberately accepts only new direct /tmp children.
            output = pathlib.Path('/tmp') / ('service-host-mock-' + str(uuid.uuid4()))
            calls = []
            identity_count = 0
            ime_count = 0

            def adb(command, **kwargs):
                nonlocal identity_count, ime_count
                calls.append(command)
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
                    identity_count += 1
                    value = '456' if change_pid and identity_count > 1 else '123'
                elif argv[:2] == ['su', '-c']:
                    value = '10001\n' + ('100' if change_ticks and identity_count > 1 else '99') + '\n' + host.PACKAGE
                elif argv == ['settings', 'get', 'secure', 'default_input_method']:
                    ime_count += 1
                    value = 'other/.Ime' if change_ime and ime_count > 1 else 'original/.Ime'
                elif argv == ['settings', 'get', 'secure', 'enabled_accessibility_services']:
                    value = 'unchanged/services'
                else:
                    self.fail('unexpected device command: ' + repr(argv))
                return subprocess.CompletedProcess(command, 0, value + '\n', '')

            child = Mock(returncode=returncode)
            child.stdout = io.StringIO(report_text() if text is None else text)
            child.poll.return_value = None if timeout else returncode
            if timeout:
                child.wait.side_effect = [subprocess.TimeoutExpired('local-adb', 35), 0]
            else:
                child.wait.return_value = returncode
            try:
                with patch.object(host.subprocess, 'run', side_effect=adb), \
                     patch.object(host.subprocess, 'Popen', return_value=child) as popen, \
                     patch.object(host.uuid, 'uuid4', return_value=uuid.UUID(RID)), \
                     patch.object(host, 'TIMEOUT_SECONDS', 0 if timeout else 5), \
                     contextlib.redirect_stdout(io.StringIO()):
                    result = host.run(self.args(output))
                summary = json.loads((output / 'summary.json').read_text())
                if popen.called:
                    command = popen.call_args.args[0]
                    self.assertEqual(command[:3], ['adb', '-s', 'explicit-device'])
                    self.assertIn('--no-restart', command)
                    self.assertIn(host.TEST_CLASS + '#' + host.METHOD, command)
                    self.assertEqual(command.count('instrument'), 1)
                    for forbidden in ('kill', 'force-stop', 'stopservice', 'ACTION_STOP', 'typeProcessRecovery', 'typeCommitBoundaryRecovery'):
                        self.assertNotIn(forbidden, command)
                return result, summary, popen.call_count, child
            finally:
                # Verified exact test-created paths, never a wildcard or user output.
                expected = output.resolve()
                self.assertEqual(expected.parent, pathlib.Path('/tmp').resolve())
                if expected.exists():
                    (expected / 'summary.json').unlink()
                    expected.rmdir()

    def test_offline_end_to_end_and_no_destructive_host_commands(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['passed'])
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_wrong_device_stops_before_instrumentation(self):
        result, summary, count, _ = self.exercise(hardware='other')
        self.assertEqual((result, count), (2, 0))
        self.assertEqual(summary['hostError'], 'wrong_hardware')

    def test_same_pid_reuse_ime_and_runner_exit_fail(self):
        for kwargs in ({'change_pid': True}, {'change_ticks': True}, {'change_ime': True}, {'returncode': 1}):
            with self.subTest(kwargs=kwargs):
                result, summary, _, _ = self.exercise(**kwargs)
                self.assertEqual(result, 2)
                self.assertFalse(summary['passed'])

    def test_timeout_only_terminates_local_adb_and_cannot_pass(self):
        result, summary, _, child = self.exercise(timeout=True)
        self.assertEqual(result, 2)
        self.assertEqual(summary['hostError'], 'instrumentation_timeout')
        self.assertTrue(summary['deviceCleanupUnconfirmed'])
        child.terminate.assert_called_once()
        child.kill.assert_not_called()

    def test_raw_output_never_written_and_incomplete_evidence_fails(self):
        result, summary, _, _ = self.exercise(text='SECRET_MODEL_OUTPUT\n' + report_text(serviceDestroyed=None))
        self.assertEqual(result, 2)
        self.assertNotIn('SECRET', json.dumps(summary))

    def test_fresh_output_is_required_without_subprocess(self):
        with tempfile.TemporaryDirectory(prefix='service-host-existing-') as existing:
            with patch.object(host.subprocess, 'run') as run, patch.object(host.subprocess, 'Popen') as popen:
                with self.assertRaises(SystemExit):
                    host.run(self.args(existing))
                run.assert_not_called()
                popen.assert_not_called()


if __name__ == '__main__':
    unittest.main()

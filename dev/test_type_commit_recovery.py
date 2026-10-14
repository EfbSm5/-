"""Offline host-contract tests. Every subprocess call is mocked; no ADB is executed."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import uuid

SPEC = importlib.util.spec_from_file_location('recovery_host', Path(__file__).with_name('type-commit-recovery.py'))
host = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host)


class Child:
    def __init__(self, text, timeout=False):
        self.stdout = io.StringIO(text)
        self.returncode = 0
        self.timeout = timeout

    def wait(self, timeout=None):
        if self.timeout and timeout == 45:
            raise subprocess.TimeoutExpired('mock-adb', timeout)
        return self.returncode

    def poll(self):
        return self.returncode


class FakeAdb:
    def __init__(self, boundary, fault=None):
        self.boundary = boundary
        self.fault = fault
        self.commands = []
        self.instruments = []
        self.killed = False
        self.kill_commands = []
        self.rid = None

    def check_target(self, command):
        if command[:3] != ['adb', '-s', 'explicit-target']:
            raise AssertionError('unlocked target')
        self.commands.append(command)
        return command[3:]

    def popen(self, command, **kwargs):
        args = self.check_target(command)
        self.instruments.append(args)
        self.rid = args[args.index('recoveryReceiptId') + 1]
        method = args[args.index('class') + 1].split('#')[1]
        if method.startswith('prepare'):
            text = '' if self.fault == 'no_ready' else 'INSTRUMENTATION_STATUS: typeRecoveryStage=ready_for_main_process_kill\n'
            return Child(text)
        count = 0 if self.boundary == 'BEFORE_SUBMIT' else 1
        if self.fault == 'wrong_count':
            count += 1
        fields = ['typeRecoveryStage=verified', 'reasonCode=NONE', 'cleanupReasonCode=NONE',
                  'passed=true', 'baselineRestored=true', 'verifyClaimed=true',
                  'confirmedActions=1', f'targetCommitCalls={count}',
                  'postReopenModelRequests=0', 'postReopenScreenshots=0', 'postReopenExecutions=0']
        text = ''.join('INSTRUMENTATION_STATUS: ' + f + '\n' for f in fields) + 'OK (1 test)\n'
        if self.fault == 'assumption':
            text += 'INSTRUMENTATION_STATUS_CODE: -4\n'
        return Child(text, timeout=self.fault == 'verify_timeout')

    def run(self, command, **kwargs):
        args = self.check_target(command)
        code, text = 0, ''
        if args == ['get-state']:
            text = 'offline' if self.fault == 'offline' else 'device'
        elif args[:2] == ['exec-out', 'run-as']:
            ready = dict(id=self.rid, mainPid=11, appUid=123, boundary=self.boundary,
                         runId=str(uuid.uuid4()), readyAt=1000, boundaryReachedAt=900,
                         boundaryDeadline=15900, snapshotDigest='a'*64, historyDigest='b'*64,
                         baselineDigest='c'*64, imeRecoveryDigest='d'*64,
                         fixturePid=44, fixtureInstanceId=str(uuid.uuid4()))
            if self.fault == 'wrong_receipt': ready['id'] = str(uuid.uuid4())
            if self.fault == 'wrong_boundary': ready['boundary'] = 'NOT_A_BOUNDARY'
            if self.fault == 'bad_digest': ready['snapshotDigest'] = 'not-a-digest'
            if self.fault == 'bad_deadline': ready['boundaryDeadline'] = 999
            text = json.dumps(ready)
        else:
            if args[0] != 'shell' or len(args) != 2:
                raise AssertionError('shell argv not quoted')
            shell = shlex.split(args[1])
            if shell[:2] == ['getprop', 'ro.serialno']:
                text = 'wrong-phone' if self.fault == 'wrong_phone' else 'verified-hardware'
            elif shell[:2] == ['getprop', 'ro.product.model']:
                text = 'verified-model'
            elif shell[:2] == ['pidof', 'com.example.agent']:
                text = '11 12' if self.fault == 'ambiguous_pid' else ('12' if self.killed else '11')
            elif shell[:2] == ['pidof', 'com.example.rootpilot.fixture']:
                text = '45' if self.fault == 'fixture_recreated' else '44'
            elif shell == ['run-as', 'com.example.agent', 'pwd']:
                text = '/data/user/0/com.example.agent'
            elif shell[:2] == ['run-as', 'com.example.agent']:
                if not shell[-1].endswith('/invalidated'):
                    raise AssertionError('unexpected private write')
            elif shell[:3] == ['settings', 'get', 'secure']:
                text = 'original-ime' if shell[-1] == 'default_input_method' else 'unchanged-services'
                if self.killed and self.fault == 'ime_changed' and shell[-1] == 'default_input_method':
                    text = 'changed-ime'
            elif shell[:2] == ['am', 'start']:
                text = 'Status: ok'
            elif shell[:2] == ['su', '-c']:
                script = shell[2]
                if 'kill -9' in script:
                    self.kill_commands.append(script)
                    if self.fault == 'guard_rejected': code = 1
                    else:
                        self.killed = True
                        text = 'OWNED_MAIN_KILLED'
                elif 'echo ABSENT' in script:
                    text = 'ABSENT'
                else:
                    text = '123\n222\ncom.example.agent'
            else:
                raise AssertionError('unexpected shell operation')
        return subprocess.CompletedProcess(command, code, stdout=text, stderr='')


class HostContractTest(unittest.TestCase):
    def argv(self, output, boundary='BEFORE_SUBMIT'):
        return ['--serial', 'explicit-target', '--expected-hardware-serial', 'verified-hardware',
                '--expected-model', 'verified-model', '--boundary', boundary, '--output', str(output),
                '--allow-live-execution-and-process-kill']

    def run_fake(self, boundary='BEFORE_SUBMIT', fault=None):
        # TemporaryDirectory owns only this test's fresh, explicit /tmp child; it is removed on exit.
        with tempfile.TemporaryDirectory(prefix='rootpilot-host-test-', dir='/tmp') as directory:
            output = Path(directory)
            fake = FakeAdb(boundary, fault)
            # The real command refuses an existing directory; simulate nonexistence only at its preflight.
            real_exists = Path.exists
            def exists(path):
                return False if path == output.resolve() else real_exists(path)
            with patch.object(host.subprocess, 'run', side_effect=fake.run), \
                 patch.object(host.subprocess, 'Popen', side_effect=fake.popen), \
                 patch.object(Path, 'exists', exists), \
                 patch.object(Path, 'mkdir'), contextlib.redirect_stdout(io.StringIO()):
                status = host.main(self.argv(output, boundary))
            summary = json.loads((output / 'summary.json').read_text())
            return status, summary, fake

    def test_no_live_acknowledgement_never_calls_adb(self):
        with patch.object(host.subprocess, 'run') as run, patch.object(host.subprocess, 'Popen') as popen, \
             contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit): host.main(self.argv('/tmp/unused')[:-1])
            run.assert_not_called()
            popen.assert_not_called()

    def test_output_must_be_new_direct_tmp_child(self):
        for output in ('/tmp', '/tmp/parent/nested'):
            with self.subTest(output=output), patch.object(host.subprocess, 'run') as run:
                with self.assertRaises(SystemExit): host.main(self.argv(output))
                run.assert_not_called()

    def test_both_boundaries_complete_only_with_all_receipts(self):
        for boundary in ('BEFORE_SUBMIT', 'AFTER_SUBMIT'):
            with self.subTest(boundary=boundary):
                status, result, fake = self.run_fake(boundary)
                self.assertEqual(status, 0)
                self.assertTrue(result['passed'])
                self.assertTrue(result['originalImeRestored'])
                self.assertEqual(len(fake.instruments), 2)
                self.assertEqual(len(fake.kill_commands), 1)
                guard = fake.kill_commands[0]
                for required in ('/proc/11/stat', '/proc/11/status', '/proc/11/cmdline', '/baseline.json',
                                 '/ready.json', '/files/rootpilot_run.json', '/rootpilot_history/runs.json',
                                 '/rootpilot_original_ime', '/invalidated', '/claimed', '/proc/uptime',
                                 'now-r<5000', 'now<d-1000'):
                    self.assertIn(required, guard)
                self.assertTrue(guard.endswith('&& kill -9 11 && echo OWNED_MAIN_KILLED'))

    def test_bad_preflight_or_receipt_never_kills(self):
        for fault in ('offline', 'wrong_phone', 'ambiguous_pid', 'no_ready', 'wrong_receipt',
                      'wrong_boundary', 'bad_digest', 'bad_deadline'):
            with self.subTest(fault=fault):
                status, result, fake = self.run_fake(fault=fault)
                self.assertEqual(status, 2)
                self.assertFalse(result['passed'])
                self.assertFalse(result['killAttempted'])
                self.assertEqual(fake.kill_commands, [])
                self.assertLessEqual(len(fake.instruments), 1)

    def test_guard_rejection_does_not_retry_or_launch_verify(self):
        status, result, fake = self.run_fake(fault='guard_rejected')
        self.assertEqual(status, 2)
        self.assertFalse(result['killConfirmed'])
        self.assertEqual(len(fake.kill_commands), 1)
        self.assertEqual(len(fake.instruments), 1)

    def test_post_kill_mismatch_skip_and_timeout_are_not_pass(self):
        for fault in ('fixture_recreated', 'wrong_count', 'assumption', 'verify_timeout', 'ime_changed'):
            with self.subTest(fault=fault):
                status, result, fake = self.run_fake(fault=fault)
                self.assertEqual(status, 2)
                self.assertFalse(result['passed'])
                self.assertEqual(len(fake.kill_commands), 1)

    def test_guards_are_not_optimization_removable_assertions(self):
        import ast
        tree = ast.parse(Path(SPEC.origin).read_text())
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(tree)))
        with self.assertRaisesRegex(RuntimeError, 'fixed_reason'):
            host.require(False, 'fixed_reason')


if __name__ == '__main__':
    unittest.main()

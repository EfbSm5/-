#!/usr/bin/env python3
"""Opt-in, bounded real-Service destruction acceptance; no install or device process kill."""
import argparse
import json
import pathlib
import queue
import re
import shlex
import subprocess
import threading
import time
import uuid
from typing import NamedTuple

PACKAGE = 'com.example.agent'
METHOD = 'realServiceDestroyedAtUnapprovedType'
TEST_CLASS = PACKAGE + '.rootpilot.TypeProcessRecoveryInstrumentedTest'
TIMEOUT_SECONDS = 240
CLEANUP_SECONDS = 35
MAX_OUTPUT_BYTES = 2 * 1024 * 1024
TRUE_FIELDS = (
    'passed', 'baselineRestored', 'serviceDestroyed', 'asyncCleanupComplete',
    'oldConfirmationInvalid', 'serviceRecreated', 'snapshotRetained',
    'historyInterrupted', 'targetSameInstance', 'originalImeRestored',
    'recoveryRequired', 'uploadConsentRevoked', 'unapprovedRecoverRejected',
)
ZERO_FIELDS = (
    'confirmedActions', 'targetCommitCalls', 'postReopenModelRequests',
    'postReopenScreenshots', 'postReopenExecutions',
)
REPORT_FIELDS = set(TRUE_FIELDS + ZERO_FIELDS) | {
    'serviceDestroyStage', 'reasonCode', 'cleanupReasonCode',
    'recoveryReceiptId', 'runId', 'mainPid',
}


class AcceptanceProfile(NamedTuple):
    method: str
    gate: str
    stage: str
    true_fields: tuple
    counts: tuple
    authorization: str
    authorization_help: str
    test_class: str = TEST_CLASS
    timeout_seconds: int = 0
    diagnostic_fields: tuple = ()

    @property
    def report_fields(self):
        return set(self.true_fields) | {key for key, _ in self.counts} | set(self.diagnostic_fields) | {
            self.stage, 'reasonCode', 'cleanupReasonCode', 'recoveryReceiptId', 'runId', 'mainPid',
        }


SERVICE_PROFILE = AcceptanceProfile(
    METHOD, 'typeServiceDestroy', 'serviceDestroyStage', TRUE_FIELDS,
    tuple((key, 0) for key in ZERO_FIELDS),
    '--allow-live-execution-and-service-destroy',
    'Authorize real model/screenshot upload of the fixed fixture and Context.stopService; zero approvals',
)


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def parse_args(argv=None, *, profile=SERVICE_PROFILE):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-hardware-serial', required=True)
    parser.add_argument('--expected-model', required=True)
    parser.add_argument('--output', required=True, help='Fresh direct child directory of /tmp')
    parser.add_argument(profile.authorization, action='store_true', required=True,
                        help=profile.authorization_help)
    args = parser.parse_args(argv)
    for value in (args.serial, args.expected_hardware_serial, args.expected_model):
        if not value.strip() or value.startswith('-') or any(ord(c) < 32 for c in value):
            parser.error('device identity values must be explicit and nonempty')
    return args


class Evidence:
    """Read instrumentation frames without persisting arbitrary runner/error output."""
    def __init__(self, profile=SERVICE_PROFILE):
        self.profile = profile
        self.frame = {}
        self.reports = []
        self.ok_count = 0
        self.success_codes = 0
        self.failure = False
        self.status_counts = {}

    def failure_diagnostics(self, receipt_id, pid):
        # Keep only bounded counters and an identity-bound, scalar-whitelisted receipt.
        # Neither a partial frame nor arbitrary runner messages are diagnostic evidence.
        result = dict(reportCount=len(self.reports), openReport=bool(self.frame),
                      statusCounts=dict(self.status_counts), runnerFailure=self.failure,
                      okCount=self.ok_count, successCodeCount=self.success_codes)
        if len(self.reports) == 1:
            report = self.reports[0]
            required = self.profile.report_fields - set(self.profile.diagnostic_fields) - {'runId'}
            if self.profile == SERVICE_PROFILE and report.get(self.profile.stage) == 'failed':
                # Service failures intentionally omit success-only facts and may lack a runId.
                required = {self.profile.stage, 'reasonCode', 'cleanupReasonCode',
                            'recoveryReceiptId', 'mainPid', 'passed', 'baselineRestored'}
            if (required <= report.keys() and report.get(self.profile.stage) in ('failed', 'verified')
                    and report.get('recoveryReceiptId') == receipt_id and report.get('mainPid') == str(pid)):
                result['receipt'] = dict(report)
        return result

    def feed(self, line):
        line = line.strip()
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, sep, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if sep and key in self.profile.report_fields:
                require(key not in self.frame, 'duplicate_receipt_field')
                # Only a constrained scalar can ever enter an evidence file.
                require(re.fullmatch(r'[A-Za-z0-9_\-]{1,128}', value) is not None, 'invalid_receipt_scalar')
                self.frame[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = line.partition(': ')[2]
            category = code if code in ('-4', '-3', '-2', '-1', '0', '1', '2') else 'other'
            self.status_counts[category] = self.status_counts.get(category, 0) + 1
            if code not in ('0', '1', '2'):
                self.failure = True
            if self.profile.stage in self.frame:
                self.reports.append(self.frame)
            self.frame = {}
        elif line == 'INSTRUMENTATION_CODE: -1':
            self.success_codes += 1
        elif line.startswith('INSTRUMENTATION_CODE:'):
            self.failure = True
        elif line == 'OK (1 test)':
            self.ok_count += 1
        elif ('FAILURES' in line or 'Process crashed' in line
              or line.startswith('INSTRUMENTATION_FAILED:')
              or line.startswith('INSTRUMENTATION_RESULT: shortMsg=')):
            self.failure = True

    def verified(self, receipt_id, pid):
        require(not self.failure, 'instrumentation_failure_or_skip')
        require(self.ok_count == 1 and self.success_codes == 1, 'single_test_completion_missing')
        require(len(self.reports) == 1 and not self.frame, 'single_receipt_missing')
        report = self.reports[0]
        require(report.get(self.profile.stage) == 'verified', 'verification_not_completed')
        require(report.get('reasonCode') == 'NONE' and report.get('cleanupReasonCode') == 'NONE', 'verification_or_cleanup_failed')
        require(report.get('recoveryReceiptId') == receipt_id and report.get('mainPid') == str(pid), 'receipt_identity_mismatch')
        try:
            valid_run = str(uuid.UUID(report.get('runId', ''))) == report.get('runId')
        except ValueError:
            valid_run = False
        require(valid_run, 'invalid_run_identity')
        require(all(report.get(k) == 'true' for k in self.profile.true_fields), 'required_evidence_missing')
        require(all(report.get(k) == str(expected) for k, expected in self.profile.counts), 'side_effect_evidence_missing')
        return report


def run(args, *, profile=SERVICE_PROFILE):
    path = pathlib.Path(args.output)
    root = path.resolve()
    if path.is_symlink() or root.exists() or root.parent != pathlib.Path('/tmp').resolve():
        raise SystemExit('output_must_be_fresh_tmp_directory')
    root.mkdir(mode=0o700)
    receipt_id = str(uuid.uuid4())
    summary = {'receiptId': receipt_id, 'passed': False, 'deviceCleanupUnconfirmed': False}
    child = None
    thread = None
    evidence = Evidence(profile)
    adb_prefix = ['adb', '-s', args.serial]

    def adb(argv):
        command = adb_prefix + (['shell', shlex.join(argv[1:])] if argv[0] == 'shell' else argv)
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        require(result.returncode == 0, 'adb_command_failed')
        return result.stdout.strip()

    def identity():
        pid = adb(['shell', 'pidof', PACKAGE])
        require(pid.isdigit() and int(pid) > 0, 'ambiguous_main_pid')
        fields = adb(['shell', 'su', '-c',
                      f"awk '/^Uid:/ {{print $2}}' /proc/{pid}/status; "
                      f"awk '{{print $22}}' /proc/{pid}/stat; "
                      f"tr '\\000' '\\n' < /proc/{pid}/cmdline"]).splitlines()
        require(len(fields) == 3 and fields[0].isdigit() and fields[1].isdigit()
                and fields[2] == PACKAGE, 'main_identity_unconfirmed')
        return int(pid), int(fields[0]), fields[1]

    try:
        require(adb(['get-state']) == 'device', 'device_not_ready')
        require(adb(['shell', 'getprop', 'ro.serialno']) == args.expected_hardware_serial, 'wrong_hardware')
        require(adb(['shell', 'getprop', 'ro.product.model']) == args.expected_model, 'wrong_model')
        before = identity()
        original_ime = adb(['shell', 'settings', 'get', 'secure', 'default_input_method'])
        accessibility = adb(['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'])
        summary.update(mainPid=before[0], mainUid=before[1], mainStartTicks=before[2])
        command = adb_prefix + ['shell', 'am', 'instrument', '-w', '-r', '--no-restart',
            '-e', 'class', profile.test_class + '#' + profile.method,
            '-e', profile.gate, 'true',
            '-e', 'recoveryBaseline', 'empty_task_saved_api_or_defaults',
            '-e', 'recoveryReceiptId', receipt_id,
            '-e', 'expectedRootPilotPid', str(before[0]),
            PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, bufsize=1)
        messages = queue.Queue(maxsize=512)
        stopped = threading.Event()

        def enqueue(value):
            while not stopped.is_set():
                try:
                    messages.put(value, timeout=.1)
                    return
                except queue.Full:
                    pass

        def pump():
            count = 0
            try:
                while not stopped.is_set():
                    line = child.stdout.readline(8193)
                    if not line:
                        break
                    count += len(line.encode('utf-8'))
                    if len(line) > 8192 or count > MAX_OUTPUT_BYTES:
                        enqueue(('error', 'instrumentation_output_limit'))
                        return
                    enqueue(('line', line))
            except Exception:
                enqueue(('error', 'instrumentation_output_unreadable'))
            finally:
                enqueue(('end', ''))

        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        deadline = time.monotonic() + (profile.timeout_seconds or TIMEOUT_SECONDS)
        while True:
            remaining = deadline - time.monotonic()
            require(remaining > 0, 'instrumentation_timeout')
            try:
                kind, value = messages.get(timeout=min(.2, remaining))
            except queue.Empty:
                continue
            if kind == 'error':
                raise RuntimeError(value)
            if kind == 'end':
                break
            evidence.feed(value)
        child.wait(timeout=max(.1, deadline - time.monotonic()))
        require(child.returncode == 0, 'instrumentation_adb_failed')
        report = evidence.verified(receipt_id, before[0])
        require(identity() == before, 'main_process_changed')
        require(adb(['shell', 'settings', 'get', 'secure', 'default_input_method']) == original_ime, 'original_ime_changed')
        require(adb(['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services']) == accessibility, 'accessibility_settings_changed')
        summary.update(passed=True, evidence=report, mainIdentityUnchanged=True,
                       originalImeRestored=True, accessibilitySettingsUnchanged=True)
    except Exception as error:
        # All RuntimeErrors originate in fixed-code guards above, never subprocess text.
        summary['hostError'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    finally:
        if child is not None:
            if child.poll() is None:
                try:
                    child.wait(timeout=CLEANUP_SECONDS)
                except subprocess.TimeoutExpired:
                    summary['deviceCleanupUnconfirmed'] = True
                    # These are local ADB clients only. Never kill/stop a device process.
                    child.terminate()
                    try:
                        child.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        try:
                            child.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            summary['hostAdbReapUnconfirmed'] = True
            stopped.set()
            if thread is not None:
                thread.join(timeout=3)
                if thread.is_alive():
                    summary['hostOutputCloseUnconfirmed'] = True
                    summary['passed'] = False
                elif child.stdout is not None:
                    child.stdout.close()
            if not summary['passed']:
                summary['deviceCleanupUnconfirmed'] = True
                summary['failureDiagnostics'] = evidence.failure_diagnostics(receipt_id, summary.get('mainPid'))
        with (root / 'summary.json').open('x') as output:
            json.dump(summary, output, indent=2)
        print('passed=' + str(summary['passed']) + ' evidence=' + str(root), flush=True)
    return 0 if summary['passed'] else 2


def main(argv=None, *, profile=SERVICE_PROFILE):
    return run(parse_args(argv, profile=profile), profile=profile)


if __name__ == '__main__':
    raise SystemExit(main())

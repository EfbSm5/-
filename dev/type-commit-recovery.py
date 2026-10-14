#!/usr/bin/env python3
"""Opt-in real-Service Type recovery; never installs, retries, or changes settings."""
import argparse
import hashlib
import json
import pathlib
import queue
import re
import shlex
import subprocess
import threading
import time
import uuid


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit current ADB target')
    parser.add_argument('--expected-hardware-serial', required=True, help='Previously verified ro.serialno')
    parser.add_argument('--expected-model', required=True, help='Previously verified ro.product.model')
    parser.add_argument('--output', required=True, help='Fresh direct child directory of /tmp')
    parser.add_argument('--boundary', choices=['BEFORE_SUBMIT', 'AFTER_SUBMIT'], required=True)
    parser.add_argument('--allow-live-execution-and-process-kill', action='store_true', required=True,
                        help='Authorize real model/screen upload, one fixed Type approval, and killing the owned app process')
    args = parser.parse_args(argv)
    if any(not value.strip() or value.startswith('-') for value in
           (args.serial, args.expected_hardware_serial, args.expected_model)):
        parser.error('device identity values must be explicit and nonempty')
    return args


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def run(a):
    root = pathlib.Path(a.output).resolve()
    if root.exists() or root.parent != pathlib.Path('/tmp').resolve():
        raise SystemExit('output_must_be_fresh_tmp_directory')
    root.mkdir(mode=0o700)
    adb_prefix = ['adb', '-s', a.serial]
    pkg = 'com.example.agent'
    rid = str(uuid.uuid4())
    relative = 'no_backup/rootpilot-type-process-recovery-test/' + rid
    methods = {
        'BEFORE_SUBMIT': ('prepareBeforeCommitBoundary', 'verifyBeforeCommitBoundary'),
        'AFTER_SUBMIT': ('prepareAfterCommitBoundary', 'verifyAfterCommitBoundary'),
    }
    expected_commits = 0 if a.boundary == 'BEFORE_SUBMIT' else 1
    summary = dict(receiptId=rid, boundary=a.boundary, adbSerial=a.serial, passed=False,
                   killAttempted=False, killConfirmed=False)
    processes = []
    identity_checked = False

    def adb(argv, timeout=15):
        command = adb_prefix + (['shell', shlex.join(argv[1:])] if argv[0] == 'shell' else argv)
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('adb_exit_' + str(result.returncode))
        return result.stdout.strip()

    def main_pid():
        value = adb(['shell', 'pidof', pkg])
        if not value.isdigit():
            raise RuntimeError('ambiguous_main_pid')
        return int(value)

    def identity(pid):
        value = adb(['shell', 'su', '-c',
            f"awk '/^Uid:/ {{print $2}}' /proc/{pid}/status; awk '{{print $22}}' /proc/{pid}/stat; tr '\\000' '\\n' < /proc/{pid}/cmdline"])
        fields = value.splitlines()
        require(len(fields) == 3 and fields[0].isdigit() and fields[1].isdigit() and (fields[2] == pkg), 'main_identity_unconfirmed')
        return int(fields[0]), fields[1]

    def instrument(method, pid, filename):
        command = adb_prefix + ['shell', 'am', 'instrument', '-w', '-r', '--no-restart',
            '-e', 'typeProcessRecovery', 'true', '-e', 'typeCommitBoundaryRecovery', 'true',
            '-e', 'recoveryBaseline', 'empty_task_saved_api_or_defaults',
            '-e', 'recoveryReceiptId', rid, '-e', 'expectedRootPilotPid', str(pid),
            '-e', 'class', pkg + '.rootpilot.TypeProcessRecoveryInstrumentedTest#' + method,
            pkg + '.test/androidx.test.runner.AndroidJUnitRunner']
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        processes.append(child)
        messages = queue.Queue()
        def pump():
            with (root / filename).open('x') as output:
                for line in child.stdout:
                    output.write(line)
                    output.flush()
                    messages.put(line)
            messages.put(None)
        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        return child, messages, thread

    try:
        require(adb(['get-state']) == 'device', 'device_not_ready')
        require(adb(['shell', 'getprop', 'ro.serialno']) == a.expected_hardware_serial, 'wrong_hardware')
        require(adb(['shell', 'getprop', 'ro.product.model']) == a.expected_model, 'wrong_model')
        old = main_pid()
        uid, ticks = identity(old)
        appdir = adb(['shell', 'run-as', pkg, 'pwd'])
        require(appdir in ('/data/user/0/' + pkg, '/data/data/' + pkg), 'unexpected_app_directory')
        identity_checked = True
        original = adb(['shell', 'settings', 'get', 'secure', 'default_input_method'])
        services = adb(['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'])
        summary.update(oldPid=old, uid=uid, oldStartTicks=ticks, originalIme=original)
        print('prepare boundary=' + a.boundary + ' receipt=' + rid + ' pid=' + str(old), flush=True)
        prepare, messages, pt = instrument(methods[a.boundary][0], old, 'prepare.txt')
        deadline = time.monotonic() + 220
        while True:
            if time.monotonic() >= deadline:
                raise RuntimeError('prepare_timeout_220s')
            try:
                line = messages.get(timeout=.2)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError('prepare_finished_without_ready')
            if line.strip() == 'INSTRUMENTATION_STATUS: typeRecoveryStage=ready_for_main_process_kill':
                break
        seen = time.monotonic()
        raw = adb(['exec-out', 'run-as', pkg, 'cat', relative + '/ready.json'])
        ready = json.loads(raw)
        # Field contract must be reviewed against the instrumentation before any execution.
        require(ready['id'] == rid and ready['mainPid'] == old and (ready['appUid'] == uid), 'receipt_identity')
        require(ready['boundary'] == a.boundary, 'wrong_boundary')
        require(str(uuid.UUID(ready['runId'])) == ready['runId'], 'invalid_run_id')
        for key in ('readyAt', 'boundaryReachedAt', 'boundaryDeadline'):
            require(type(ready[key]) is int and ready[key] > 0, 'invalid_time_' + key)
        require(ready['boundaryReachedAt'] <= ready['readyAt'] < ready['boundaryDeadline'], 'invalid_gate_interval')
        require(ready['boundaryDeadline'] - ready['boundaryReachedAt'] <= 15000, 'unexpected_gate_duration')
        for key in ('snapshotDigest', 'historyDigest', 'baselineDigest', 'imeRecoveryDigest'):
            require(re.fullmatch('[0-9a-f]{64}', ready[key]), 'invalid_digest_' + key)
        (root / 'ready.json').write_text(raw)
        directory = appdir + '/' + relative
        q = shlex.quote
        def digest_guard(path, digest):
            return f'test "$(sha256sum {q(path)} | cut -d" " -f1)" = {q(digest)}'
        guards = [
            f'test "$(awk \'{{print $22}}\' /proc/{old}/stat)" = {q(ticks)}',
            f'test "$(awk \'/^Uid:/ {{print $2}}\' /proc/{old}/status)" = {uid}',
            f'test "$(tr \'\\000\' \'\\n\' < /proc/{old}/cmdline)" = {q(pkg)}',
            digest_guard(directory + '/ready.json', hashlib.sha256(raw.encode()).hexdigest()),
            digest_guard(directory + '/baseline.json', ready['baselineDigest']),
            digest_guard(appdir + '/files/rootpilot_run.json', ready['snapshotDigest']),
            digest_guard(appdir + '/no_backup/rootpilot_history/runs.json', ready['historyDigest']),
            digest_guard(appdir + '/no_backup/rootpilot_original_ime', ready['imeRecoveryDigest']),
            f'test "$(settings get secure default_input_method)" = {q(pkg + "/.rootpilot.input.RootPilotInputMethodService")}',
            f'test ! -e {q(directory + "/invalidated")}',
            f'test ! -e {q(directory + "/invalidated-ready.json")}',
            f'test ! -e {q(directory + "/claimed")}',
            f'awk \'BEGIN {{r={ready["readyAt"]}; d={ready["boundaryDeadline"]}}} {{now=$1*1000; exit !(now>=r && now-r<5000 && now<d-1000)}}\' /proc/uptime',
            f'kill -9 {old}', 'echo OWNED_MAIN_KILLED',
        ]
        summary['killAttempted'] = True
        result = adb(['shell', 'su', '-c', ' && '.join(guards)], timeout=8)
        require(result == 'OWNED_MAIN_KILLED', 'kill_unconfirmed')
        summary.update(killConfirmed=True, runId=ready['runId'], readyAt=ready['readyAt'],
                       boundaryDeadline=ready['boundaryDeadline'], readyToKillHostMs=round((time.monotonic()-seen)*1000),
                       fixturePid=ready['fixturePid'], fixtureInstanceId=ready['fixtureInstanceId'])
        print('owned_main_killed', flush=True)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if adb(['shell', 'su', '-c', f'if test -d /proc/{old}; then echo PRESENT; else echo ABSENT; fi']) == 'ABSENT':
                break
            time.sleep(.05)
        else:
            raise RuntimeError('old_pid_still_present')
        adb(['shell', 'am', 'start', '-W', '-n', pkg + '/.rootpilot.RootPilotActivity'], timeout=20)
        new = main_pid()
        require(new != old and identity(new)[0] == uid, 'new_process_identity')
        require(adb(['shell', 'pidof', 'com.example.rootpilot.fixture']) == str(ready['fixturePid']), 'fixture_recreated')
        summary['newPid'] = new
        verify, _, vt = instrument(methods[a.boundary][1], new, 'verify.txt')
        verify.wait(timeout=45)
        vt.join(timeout=3)
        require(not vt.is_alive(), 'verify_output_not_closed')
        text = (root / 'verify.txt').read_text()
        result_lines = {line.removeprefix('INSTRUMENTATION_STATUS: ').strip() for line in text.splitlines()}
        required = ('typeRecoveryStage=verified', 'reasonCode=NONE', 'cleanupReasonCode=NONE', 'passed=true',
                    'baselineRestored=true', 'verifyClaimed=true', 'confirmedActions=1',
                    'targetCommitCalls=' + str(expected_commits), 'postReopenModelRequests=0',
                    'postReopenScreenshots=0', 'postReopenExecutions=0', 'OK (1 test)')
        require(all((x in result_lines for x in required)), 'verify_receipt_incomplete')
        require(not any((x in text for x in ('FAILURES!!!', 'shortMsg=Process crashed', 'INSTRUMENTATION_STATUS_CODE: -1', 'INSTRUMENTATION_STATUS_CODE: -2', 'INSTRUMENTATION_STATUS_CODE: -4'))), 'verify_failure')
        require(verify.returncode == 0, 'verify_adb_failed')
        require(adb(['shell', 'settings', 'get', 'secure', 'default_input_method']) == original, 'ime_changed')
        require(adb(['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services']) == services, 'accessibility_settings_changed')
        prepare.wait(timeout=10)
        pt.join(timeout=3)
        require(not pt.is_alive(), 'prepare_output_not_closed')
        summary.update(passed=True, originalImeRestored=True, accessibilitySettingsUnchanged=True)
    except Exception as error:
        summary['hostError'] = str(error) if isinstance(error, (AssertionError, RuntimeError)) else type(error).__name__
        print('HOST_STOP=' + summary['hostError'], flush=True)
        if identity_checked and not summary['killAttempted']:
            try:
                adb(['shell', 'run-as', pkg, 'sh', '-c',
                     'mkdir -p ' + shlex.quote(relative) + '; printf HOST_ABORTED > ' + shlex.quote(relative + '/invalidated')])
                summary['hostAbortMarked'] = True
            except Exception:
                summary['hostAbortUnconfirmed'] = True
    finally:
        for child in processes:
            if child.poll() is None:
                try:
                    child.wait(timeout=35)
                except subprocess.TimeoutExpired:
                    summary['deviceCleanupUnconfirmed'] = True
                    child.terminate()
                    try:
                        child.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        try:
                            child.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            summary['hostAdbReapUnconfirmed'] = True
        (root / 'summary.json').write_text(json.dumps(summary, indent=2))
        print('passed=' + str(summary['passed']) + ' evidence=' + str(root), flush=True)
    return 0 if summary['passed'] else 2


def main(argv=None):
    return run(parse_args(argv))


if __name__ == '__main__':
    raise SystemExit(main())

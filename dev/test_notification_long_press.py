"""Mock-only isolated long-press profile; no device operations."""
import contextlib
import importlib.util
import io
import pathlib
import unittest
from unittest.mock import patch
import test_notification_expansion as swipe

spec = importlib.util.spec_from_file_location(
    'long_press_host', pathlib.Path(__file__).with_name('notification-long-press.py'))
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)
host, PROFILE = entry.bounded, entry.PROFILE
RID = swipe.RID


def report_text(**changes):
    fields = dict.fromkeys(PROFILE.true_fields, 'true')
    fields.update(notificationLongPressExpansionStage='verified', reasonCode='NONE',
                  cleanupReasonCode='NONE', recoveryReceiptId=RID, runId=RID,
                  mainPid='123', failureStructureCaptured='false',
                  captureSite='NONE', budgetCause='NONE', visitedNodes='-1',
                  maxVisitedDepth='-1', pendingAtFirstCut='-1',
                  firstCutVisitedNodes='-1', firstCutDepth='-1')
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


class LongPressHostTests(unittest.TestCase):
    def exercise(self, **options):
        with patch.object(swipe, 'entry', entry), patch.object(swipe, 'host', host), \
             patch.object(swipe, 'PROFILE', PROFILE), patch.object(swipe, 'report_text', report_text):
            return swipe.ExpansionHostTests().exercise(**options)

    def test_only_explicit_long_press_authorizes_one_method(self):
        args = ['--serial', 'device', '--expected-hardware-serial', 'hardware',
                '--expected-model', 'model', '--output', '/tmp/unused']
        for flag in ([], [swipe.PROFILE.authorization], [host.SERVICE_PROFILE.authorization],
                     ['--allow-own-notification-identity']):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.parse_args(args + flag, profile=PROFILE)
        host.parse_args(args + [PROFILE.authorization], profile=PROFILE)
        self.assertEqual(PROFILE.method, 'inspectOwnNotificationLongPressExpansion')
        self.assertEqual(PROFILE.gate, 'notificationLongPressExpansion')
        self.assertEqual(PROFILE.timeout_seconds, 60)

    def test_all_success_guards_still_required(self):
        parse(report_text()).verified(RID, 123)
        self.assertEqual(PROFILE.true_fields, swipe.PROFILE.true_fields)
        for key in PROFILE.true_fields:
            for value in (None, 'false'):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    parse(report_text(**{key: value})).verified(RID, 123)

    def test_failure_ancestor_is_not_success_and_is_private(self):
        for captured in ('true', 'false'):
            evidence = parse('PRIVATE_NODE_TEXT\n' + report_text(
                notificationLongPressExpansionStage='failed', passed='false',
                ownRowExpanded='false', structureCaptured='false',
                failureStructureCaptured=captured, reasonCode='IDENTITY_ROW_UNRELIABLE'))
            with self.assertRaises(RuntimeError):
                evidence.verified(RID, 123)
            result = evidence.failure_diagnostics(RID, 123)
            self.assertEqual(result['receipt']['failureStructureCaptured'], captured)
            self.assertNotIn('PRIVATE_NODE_TEXT', str(result))
            self.assertNotIn('receipt', evidence.failure_diagnostics('wrong', 123))
        partial = parse(report_text(notificationLongPressExpansionStage='failed',
                                    passed='false', failureStructureCaptured=None))
        self.assertNotIn('receipt', partial.failure_diagnostics(RID, 123))

    def test_timeout_snapshot_is_failure_only_and_private(self):
        for captured in ('true', 'false'):
            evidence = parse('PRIVATE_NODE_TEXT\n' + report_text(
                notificationLongPressExpansionStage='failed', passed='false',
                reasonCode='EXPANSION_POST_TIMEOUT', ownRowExpanded='false',
                noOwnActivityLaunch='false', structureCaptured='false',
                failureStructureCaptured=captured))
            with self.assertRaises(RuntimeError):
                evidence.verified(RID, 123)
            result = evidence.failure_diagnostics(RID, 123)
            self.assertEqual(result['receipt']['reasonCode'], 'EXPANSION_POST_TIMEOUT')
            self.assertEqual(result['receipt']['failureStructureCaptured'], captured)
            self.assertEqual(result['receipt']['noOwnActivityLaunch'], 'false')
            self.assertNotIn('PRIVATE_NODE_TEXT', str(result))
            self.assertNotIn('receipt', evidence.failure_diagnostics('wrong', 123))
            self.assertNotIn('receipt', evidence.failure_diagnostics(RID, 124))

    def test_truncation_metadata_is_failure_only_and_scoped(self):
        for site in ('INITIAL_CURRENT', 'REBIND_CURRENT', 'AFTER_EXPAND'):
            for cause in ('DEPTH_LIMIT', 'NODE_LIMIT', 'BOTH'):
                fields = dict(notificationLongPressExpansionStage='failed', passed='false',
                              reasonCode='EXPANSION_TRUNCATED', structureCaptured='false',
                              ownRowExpanded='false', captureSite=site, budgetCause=cause,
                              visitedNodes='32', maxVisitedDepth='7', pendingAtFirstCut='2',
                              firstCutVisitedNodes='12', firstCutDepth='7')
                evidence = parse('PRIVATE_NODE_TEXT\n' + report_text(**fields) +
                                 'INSTRUMENTATION_STATUS: nodeText=PRIVATE_NODE_TEXT\n')
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                result = evidence.failure_diagnostics(RID, 123)
                for key in entry.CAPTURE_FIELDS:
                    self.assertEqual(result['receipt'][key], fields[key])
                self.assertNotIn('PRIVATE_NODE_TEXT', str(result))
                self.assertNotIn('receipt', evidence.failure_diagnostics('wrong', 123))
        for key in entry.CAPTURE_FIELDS:
            partial = parse(report_text(notificationLongPressExpansionStage='failed',
                                        passed='false', **{key: None}))
            self.assertNotIn('receipt', partial.failure_diagnostics(RID, 123))
            self.assertNotIn(key, swipe.PROFILE.report_fields)

    def test_identity_sites_remain_failures_with_complete_private_receipts(self):
        for site in ('INITIAL_CURRENT', 'REBIND_CURRENT', 'AFTER_EXPAND', 'MODAL_PREFIX'):
            reason = 'EXPANSION_' + site + '_IDENTITY_INVALID'
            with self.subTest(site=site):
                evidence = parse('PRIVATE_NODE_TEXT\n' + report_text(
                    notificationLongPressExpansionStage='failed', passed='false',
                    reasonCode=reason, ownRowExpanded='false', structureCaptured='false'))
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                result = evidence.failure_diagnostics(RID, 123)
                self.assertEqual(result['receipt']['reasonCode'], reason)
                self.assertEqual(result['receipt']['captureSite'], 'NONE')
                self.assertEqual(result['receipt']['visitedNodes'], '-1')
                self.assertNotIn('PRIVATE_NODE_TEXT', str(result))
                self.assertNotIn('receipt', evidence.failure_diagnostics('wrong', 123))

    def test_prefix_predicates_are_failure_only_without_new_fields(self):
        suffixes = ['PHASE_INVALID']
        suffixes += [site + '_CHILDREN_' + kind
                     for site in ('ROW', 'EXPANDED') for kind in ('EMPTY', 'MULTIPLE')]
        suffixes += ['ROW_' + field + '_INVALID' for field in
                     ('PACKAGE', 'ENABLED', 'CLICKABLE', 'SCROLLABLE', 'CLASS')]
        suffixes += ['ROW_CANDIDATE_PRESENT', 'EXPANDED_CANDIDATE_PRESENT']
        suffixes += [site + '_' + field + '_INVALID' for site in ('EXPANDED', 'CONTENT')
                     for field in ('RESOURCE', 'PACKAGE', 'CLASS', 'ENABLED', 'CLICKABLE',
                                   'LONG_CLICKABLE', 'SCROLLABLE')]
        suffixes += [site + '_' + kind for site in ('ROW_EXPANDED', 'EXPANDED_CONTENT')
                     for kind in ('MISSING', 'AMBIGUOUS')]
        self.assertEqual(len(set(suffixes)), 30)
        for suffix in suffixes:
            reason = 'EXPANSION_MODAL_PREFIX_' + suffix
            with self.subTest(reason=reason):
                evidence = parse(report_text(notificationLongPressExpansionStage='failed',
                    passed='false', reasonCode=reason, ownRowExpanded='false', structureCaptured='false'))
                with self.assertRaises(RuntimeError):
                    evidence.verified(RID, 123)
                result = evidence.failure_diagnostics(RID, 123)
                self.assertEqual(result['receipt']['reasonCode'], reason)
                self.assertEqual(result['receipt']['captureSite'], 'NONE')
                self.assertNotIn('receipt', evidence.failure_diagnostics('wrong', 123))

    def test_profiles_cannot_exchange_receipts(self):
        with self.assertRaises(RuntimeError):
            parse(swipe.report_text()).verified(RID, 123)
        with self.assertRaises(RuntimeError):
            swipe.parse(report_text()).verified(RID, 123)
        self.assertNotIn('failureStructureCaptured', swipe.PROFILE.report_fields)

    def test_partial_duplicate_skip_crash_rejected(self):
        for text in (report_text() * 2, report_text().replace('OK (1 test)', 'OK (0 tests)'),
                     report_text() + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                     report_text() + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n'):
            with self.assertRaises(RuntimeError):
                parse(text).verified(RID, 123)

    def test_one_method_no_destructive_commands(self):
        result, summary, count, _ = self.exercise()
        self.assertEqual((result, count), (0, 1))
        self.assertTrue(summary['mainIdentityUnchanged'])

    def test_baseline_changes_rejected(self):
        for options in ({'hardware': 'wrong'}, {'change_ticks': True}, {'change_ime': True}):
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

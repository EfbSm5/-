"""Offline tests for the two-stage Type report validator; no ADB, device or credential."""
import importlib.util
import json
import pathlib
import unittest

spec = importlib.util.spec_from_file_location(
    'two_stage_type_report', pathlib.Path(__file__).with_name('two_stage_type_report.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

IDENTITY = {
    'nonce': '38a75ba4-c409-4e1f-90a1-895df09c8afd', 'mainPid': 7213,
    'freshSessionId': '6d816542-b887-4aa4-ae06-c43d041c0002',
    'fixtureInstanceId': 'a2db4ef5-c1c7-4b68-af52-1b47d6e5a1a9', 'fixturePid': 7112,
    'fixtureTaskId': 146, 'fixtureDisplayId': 0, 'fixtureProcessStartTicks': 510577,
}


def local_pass():
    return dict(IDENTITY, schemaVersion=1, diagnosticKind=m.LOCAL, imeShowInputRequested=True,
                imeInputViewShown=True, fixedRequests=1, registeredHttpConstructions=0, ownerCompleted=True,
                commitCalls=1, matches=True, fixtureRetained=True, resourcesRestored=True, cleanupConfirmed=True,
                observedPreparation=True, observedFinalConfirmation=True, passed=True, failureStage='NONE',
                clickLedgerExternal=True)


def real_pass():
    return dict(local_pass(), diagnosticKind=m.REAL, fixedRequests=None, modelRequests=1, modelUsageReported=True)


class Tests(unittest.TestCase):
    def parse(self, report, kind):
        return m.parse_report(json.dumps(report), IDENTITY, kind)

    def rejects(self, report, kind):
        with self.assertRaises(ValueError):
            self.parse(report, kind)

    def test_local_pass_default_kind(self):
        self.assertTrue(m.parse_report(json.dumps(local_pass()), IDENTITY)['passed'])

    def test_real_pass(self):
        self.assertTrue(self.parse(real_pass(), m.REAL)['passed'])

    def test_kind_cannot_cross(self):
        self.rejects(local_pass(), m.REAL)
        self.rejects(real_pass(), m.LOCAL)
        self.rejects(dict(local_pass(), modelRequests=1, modelUsageReported=True), m.LOCAL)

    def test_real_requires_model_request_and_null_local_fields(self):
        for key, value in (('modelRequests', 0), ('modelRequests', None), ('modelRequests', 3),
                           ('ownerCompleted', None), ('ownerCompleted', False), ('fixedRequests', 1),
                           ('registeredHttpConstructions', 1), ('registeredHttpConstructions', None),
                           ('commitCalls', 2)):
            with self.subTest(key=key, value=value):
                self.rejects(dict(real_pass(), **{key: value}), m.REAL)

    def test_real_failure_with_unknown_counts_is_valid_evidence(self):
        report = dict(real_pass(), passed=False, failureStage='PREPARATION_ACTION', cleanupConfirmed=False,
                      observedPreparation=False, observedFinalConfirmation=False, commitCalls=0, matches=False,
                      modelRequests=None, modelUsageReported=None)
        self.assertFalse(self.parse(report, m.REAL)['passed'])

    def test_reason_sets_are_kind_specific(self):
        failed = dict(passed=False, cleanupConfirmed=False)
        self.assertFalse(self.parse(dict(real_pass(), failureStage='ADMISSION_FACTORY_ARM', **failed), m.REAL)['passed'])
        self.rejects(dict(local_pass(), failureStage='ADMISSION_API_LOAD', **failed), m.LOCAL)
        self.rejects(dict(real_pass(), failureStage='UNKNOWN', **failed), m.REAL)

    def test_identity_mismatch_and_duplicates_rejected(self):
        self.rejects(dict(real_pass(), fixturePid=7113), m.REAL)
        raw = json.dumps(real_pass())[:-1] + ', "passed": true}'
        with self.assertRaises(ValueError):
            m.parse_report(raw, IDENTITY, m.REAL)

    def test_unknown_kind_rejected(self):
        with self.assertRaises(ValueError):
            m.parse_report(json.dumps(real_pass()), IDENTITY, 'OTHER')

    def test_rejection_never_echoes_payload(self):
        with self.assertRaises(ValueError) as caught:
            self.parse(dict(real_pass(), failureStage='SECRET-TEXT', passed=False), m.REAL)
        self.assertEqual(str(caught.exception), 'TWO_STAGE_REPORT_INVALID')


if __name__ == '__main__':
    unittest.main()

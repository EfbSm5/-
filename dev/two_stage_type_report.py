"""Offline, closed-schema validator for the two-stage Type report (local fixed model or real model).

This module never operates a device or approves an action. A valid failure report
is diagnostic evidence, not acceptance. Expected identity must come from this
attempt's independently verified fixture and instrumentation process, and the
caller fixes the expected kind so a local report can never pass as a real one.
"""
import json
import uuid

LOCAL = 'TWO_STAGE_LOCAL_MODEL'
REAL = 'TWO_STAGE_REAL_MODEL'
IDENTITY = {
    'nonce', 'mainPid', 'freshSessionId', 'fixtureInstanceId', 'fixturePid',
    'fixtureTaskId', 'fixtureDisplayId', 'fixtureProcessStartTicks',
}
BOOLS = {
    'fixtureRetained', 'resourcesRestored', 'cleanupConfirmed',
    'observedPreparation', 'observedFinalConfirmation', 'passed', 'clickLedgerExternal',
}
NULLABLE_BOOLS = {'imeShowInputRequested', 'imeInputViewShown', 'ownerCompleted', 'matches'}
COUNTS = {'fixedRequests', 'registeredHttpConstructions', 'commitCalls'}
# Real runs have no fixed local response; their guarded uploads are counted in modelRequests.
LOCAL_ONLY = {'fixedRequests'}
REAL_ONLY = {'modelRequests', 'modelUsageReported'}
# Initial request plus AgentLoop's single parse retry.
MAX_REAL_REQUESTS = 2
COMMON_REASONS = {
    'NONE', 'ADMISSION', 'ADMISSION_IDLE', 'ADMISSION_API_CONFIG', 'ADMISSION_RECOVERY_MARKERS',
    'ADMISSION_ENABLED_IME', 'ADMISSION_UNLOCKED_INTERACTIVE',
    'ADMISSION_NOTIFICATION_OVERLAY', 'ADMISSION_HISTORY',
    'ADMISSION_FIXTURE_IDENTITY', 'ADMISSION_FIXTURE_READY_EMPTY',
    'ADMISSION_ROOT_OBSERVATION', 'ADMISSION_FACTORY_ARM', 'ADMISSION_SERVICE_START',
    'PREPARATION', 'PREPARATION_ACTION', 'FINAL_CONFIRMATION', 'IME_LOCATION_MAIN_ENTRY',
    'IME_LOCATION_SCOPE_BEFORE', 'IME_LOCATION_ROOT_INSPECTION',
    'IME_LOCATION_ROOT_NONE', 'IME_LOCATION_ROOT_MULTIPLE', 'IME_LOCATION_NODE_LIMIT',
    'IME_LOCATION_PREVIEW_NOT_UNIQUE', 'IME_LOCATION_BUTTON_NOT_UNIQUE',
    'IME_LOCATION_BUTTON_NOT_FULLY_VISIBLE', 'IME_LOCATION_OUTSIDE_DISPLAY',
    'IME_LOCATION_SCOPE_AFTER', 'COMMIT',
}
REASONS = {LOCAL: COMMON_REASONS, REAL: COMMON_REASONS | {'ADMISSION_API_LOAD'}}
BASE_FIELDS = IDENTITY | BOOLS | NULLABLE_BOOLS | COUNTS | {'schemaVersion', 'diagnosticKind', 'failureStage'}
FIELDS = {LOCAL: BASE_FIELDS, REAL: BASE_FIELDS | REAL_ONLY}


def require(condition):
    if not condition:
        # Never echo rejected payloads: they may contain unexpected sensitive data.
        raise ValueError('TWO_STAGE_REPORT_INVALID')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def validate_identity(identity):
    require(type(identity) is dict and set(identity) == IDENTITY)
    for key in ('nonce', 'freshSessionId', 'fixtureInstanceId'):
        value = identity[key]
        require(type(value) is str and len(value) == 36)
        try:
            canonical = str(uuid.UUID(value))
        except (ValueError, AttributeError):
            raise ValueError('TWO_STAGE_REPORT_INVALID') from None
        require(canonical == value)
    for key in IDENTITY - {'nonce', 'freshSessionId', 'fixtureInstanceId'}:
        value = identity[key]
        limit = 2**63 - 1 if key == 'fixtureProcessStartTicks' else 2**31 - 1
        require(type(value) is int and 0 <= value <= limit)
        require(value == 0 if key == 'fixtureDisplayId' else value > 0)
    require(identity['mainPid'] != identity['fixturePid'])


def count(value):
    return value is None or (type(value) is int and 0 <= value <= 2**31 - 1)


def parse_report(raw, expected_identity, kind=LOCAL):
    require(kind in FIELDS)
    validate_identity(expected_identity)
    require(type(raw) is str and len(raw.encode('utf-8')) <= 16384)
    try:
        report = json.loads(raw, object_pairs_hook=unique_object,
                            parse_constant=lambda _: require(False))
    except (ValueError, TypeError, RecursionError):
        raise ValueError('TWO_STAGE_REPORT_INVALID') from None
    require(type(report) is dict and set(report) == FIELDS[kind])
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1)
    require(report['diagnosticKind'] == kind)
    validate_identity({key: report[key] for key in IDENTITY})
    require(all(report[key] == expected_identity[key] for key in IDENTITY))
    for key in BOOLS:
        require(type(report[key]) is bool)
    for key in NULLABLE_BOOLS:
        require(report[key] is None or type(report[key]) is bool)
    for key in COUNTS:
        require(count(report[key]))
    reason = report['failureStage']
    require(type(reason) is str and reason in REASONS[kind])
    require(report['clickLedgerExternal'] is True)
    require(not report['observedFinalConfirmation'] or report['observedPreparation'])
    require(report['passed'] == (reason == 'NONE'))
    if kind == REAL:
        require(all(report[key] is None for key in LOCAL_ONLY))
        require(count(report['modelRequests']))
        require(report['modelUsageReported'] is None or type(report['modelUsageReported']) is bool)
    if report['passed']:
        require(all(report[key] is True for key in (
            'cleanupConfirmed', 'resourcesRestored', 'fixtureRetained', 'matches',
            'observedPreparation', 'observedFinalConfirmation')))
        require(report['commitCalls'] == 1)
        require(report['ownerCompleted'] is True and report['registeredHttpConstructions'] == 0)
        if kind == LOCAL:
            require(report['fixedRequests'] == 1)
        else:
            require(type(report['modelRequests']) is int and 1 <= report['modelRequests'] <= MAX_REAL_REQUESTS)
    return report

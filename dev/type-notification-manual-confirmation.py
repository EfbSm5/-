#!/usr/bin/env python3
"""Opt-in manual notification Type approval; no automatic approval or UI probe.

The receipt proves no automatic approval attempt, not the physical source of a touch.
Only the final receipt is consumed; the Android test owns the manual waiting window.
"""
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    'rootpilot_bounded_manual_acceptance', pathlib.Path(__file__).with_name('type-service-destroy.py'))
bounded = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bounded)

PROFILE = bounded.AcceptanceProfile(
    method='realNotificationManuallyApprovesTypeOnce',
    gate='typeNotificationManualConfirmation',
    stage='manualNotificationStage',
    true_fields=(
        'passed', 'baselineRestored', 'manualMode', 'manualWaitArmed',
        'noAutomaticApproval', 'notificationActivityObserved', 'oldConfirmationInvalid',
        'targetSameInstance', 'targetTextMatches', 'originalImeRestored',
        'historyFinalized', 'snapshotCleared',
    ),
    counts=(('confirmedActions', 1), ('targetCommitCalls', 1),
            ('duplicateExecutions', 0), ('automaticApprovalAttempts', 0)),
    authorization='--allow-live-execution-and-manual-notification-approval',
    authorization_help='Authorize fixed fixture screenshot/model upload and one manual notification Type approval',
    timeout_seconds=420,
)


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

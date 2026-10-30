#!/usr/bin/env python3
"""Opt-in real notification UI approval of one fixture Type; no install or retries."""
import importlib.util
import pathlib

# Reuse the same bounded transport, device identity and privacy checks as Service acceptance.
_spec = importlib.util.spec_from_file_location(
    'rootpilot_bounded_acceptance', pathlib.Path(__file__).with_name('type-service-destroy.py'))
bounded = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bounded)

PROFILE = bounded.AcceptanceProfile(
    method='realNotificationApprovesTypeOnce',
    gate='typeNotificationConfirmation',
    stage='notificationStage',
    true_fields=(
        'passed', 'baselineRestored', 'notificationUiClicked', 'notificationActivityObserved',
        'oldConfirmationInvalid', 'targetSameInstance', 'targetTextMatches',
        'originalImeRestored', 'historyFinalized', 'snapshotCleared',
    ),
    counts=(('confirmedActions', 1), ('targetCommitCalls', 1), ('duplicateExecutions', 0)),
    authorization='--allow-live-execution-and-notification-approval',
    authorization_help='Authorize fixed fixture screenshot/model upload and one real notification Type approval',
)


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

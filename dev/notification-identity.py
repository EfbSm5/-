#!/usr/bin/env python3
"""Opt-in own-row identity diagnostic; no model, notification action, or UI text output."""
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    'rootpilot_bounded_identity', pathlib.Path(__file__).with_name('type-service-destroy.py'))
bounded = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bounded)

PROFILE = bounded.AcceptanceProfile(
    method='inspectOwnNotificationIdentity',
    gate='notificationIdentity',
    stage='notificationIdentityStage',
    true_fields=(
        'passed', 'baselineRestored', 'uiAutomationAvailable', 'uiDeviceAvailable',
        'mainProcessUnchanged', 'imeUnchanged', 'accessibilityUnchanged',
        'noModelRequests', 'noScreenshots', 'noExecutions',
        'structureCaptured', 'notificationRemoved',
    ),
    counts=(),
    authorization='--allow-own-notification-identity',
    authorization_help='Authorize temporary own notification, opening shade, and at most one own-row expansion; no model, screenshot or action click',
    test_class=bounded.PACKAGE + '.rootpilot.NotificationInitializationInstrumentedTest',
    timeout_seconds=60,
)


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

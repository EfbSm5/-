#!/usr/bin/env python3
"""Opt-in bounded own-notification structure probe; no model or action clicks."""
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    'rootpilot_bounded_structure', pathlib.Path(__file__).with_name('type-service-destroy.py'))
bounded = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bounded)

PROFILE = bounded.AcceptanceProfile(
    method='inspectOwnNotificationStructure',
    gate='notificationStructure',
    stage='notificationStructureStage',
    true_fields=(
        'passed', 'baselineRestored', 'uiAutomationAvailable', 'uiDeviceAvailable',
        'mainProcessUnchanged', 'imeUnchanged', 'accessibilityUnchanged',
        'noModelRequests', 'noScreenshots', 'noExecutions',
        'structureCaptured', 'notificationRemoved',
    ),
    counts=(),
    authorization='--allow-own-notification-structure',
    authorization_help='Authorize a temporary own notification and opening its shade; no model, screenshot or action click',
    test_class=bounded.PACKAGE + '.rootpilot.NotificationInitializationInstrumentedTest',
    timeout_seconds=60,
)


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

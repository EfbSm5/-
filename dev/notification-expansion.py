#!/usr/bin/env python3
"""Opt-in single own-row downward gesture diagnostic, never action approval."""
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    'rootpilot_bounded_expansion', pathlib.Path(__file__).with_name('type-service-destroy.py'))
bounded = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bounded)

PROFILE = bounded.AcceptanceProfile(
    method='inspectOwnNotificationExpansion',
    gate='notificationExpansion',
    stage='notificationExpansionStage',
    true_fields=(
        'passed', 'baselineRestored', 'uiAutomationAvailable', 'uiDeviceAvailable',
        'mainProcessUnchanged', 'imeUnchanged', 'accessibilityUnchanged',
        'noModelRequests', 'noScreenshots', 'noExecutions',
        'structureCaptured', 'notificationRemoved', 'ownRowExpanded', 'noOwnActivityLaunch',
    ),
    counts=(),
    authorization='--allow-own-notification-expansion-gesture',
    authorization_help='Authorize one bounded downward gesture within the verified own harmless notification row; no action click, model, screenshot or gesture retry',
    test_class=bounded.PACKAGE + '.rootpilot.NotificationInitializationInstrumentedTest',
    timeout_seconds=60,
)


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

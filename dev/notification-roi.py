#!/usr/bin/env python3
"""Opt-in single own-notification ROI capture, never notification approval."""
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    'rootpilot_bounded_roi', pathlib.Path(__file__).with_name('type-service-destroy.py'))
bounded = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bounded)

PROFILE = bounded.AcceptanceProfile(
    method='inspectOwnNotificationRoi',
    gate='notificationRoiCapture',
    stage='notificationRoiCaptureStage',
    true_fields=(
        'passed', 'baselineRestored', 'uiAutomationAvailable', 'uiDeviceAvailable',
        'mainProcessUnchanged', 'imeUnchanged', 'accessibilityUnchanged',
        'noModelRequests', 'noExecutions', 'noOwnActivityLaunch', 'noProductScreenshots',
        'roiCaptured', 'notificationRemoved',
    ),
    counts=(('screenshotCalls', 1),),
    authorization='--allow-own-notification-roi-capture',
    authorization_help='Authorize one screenshot with full-frame pixels temporarily in memory, persisting only the verified own harmless notification ROI; no gestures, notification approval or model requests',
    test_class=bounded.PACKAGE + '.rootpilot.NotificationInitializationInstrumentedTest',
    timeout_seconds=60,
)


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

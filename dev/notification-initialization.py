#!/usr/bin/env python3
"""Opt-in, non-model notification initialization diagnostic; no UI actions or retries."""
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    'rootpilot_bounded_initialization', pathlib.Path(__file__).with_name('type-service-destroy.py'))
bounded = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bounded)

PROFILE = bounded.AcceptanceProfile(
    method='diagnoseInitialization',
    gate='notificationInitialization',
    stage='initDiagnosticStage',
    true_fields=(
        'passed', 'baselineRestored', 'uiAutomationAvailable', 'uiDeviceAvailable',
        'mainProcessUnchanged', 'imeUnchanged', 'accessibilityUnchanged',
        'noModelRequests', 'noScreenshots', 'noExecutions',
    ),
    counts=(),
    authorization='--allow-ui-initialization-diagnostic',
    authorization_help='Authorize initialization only; no model, screenshot, notification opening or input',
    test_class=bounded.PACKAGE + '.rootpilot.NotificationInitializationInstrumentedTest',
    timeout_seconds=60,
    diagnostic_fields=(
        'accessibilityEnabledBefore', 'accessibilityEnabledWhileConnected', 'enabledServicesUnchanged',
    ),
)


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

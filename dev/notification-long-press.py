#!/usr/bin/env python3
"""Opt-in one own-notification long press, never approval or gesture fallback."""
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    'rootpilot_long_press_expansion', pathlib.Path(__file__).with_name('notification-expansion.py'))
expansion = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(expansion)
bounded = expansion.bounded

CAPTURE_FIELDS = frozenset({
    'captureSite', 'budgetCause', 'visitedNodes', 'maxVisitedDepth',
    'pendingAtFirstCut', 'firstCutVisitedNodes', 'firstCutDepth',
})


class LongPressProfile(bounded.AcceptanceProfile):
    @property
    def report_fields(self):
        return super().report_fields | {'failureStructureCaptured'} | CAPTURE_FIELDS


PROFILE = LongPressProfile(**expansion.PROFILE._replace(
    method='inspectOwnNotificationLongPressExpansion',
    gate='notificationLongPressExpansion',
    stage='notificationLongPressExpansionStage',
    authorization='--allow-own-notification-long-press',
    authorization_help='Authorize exactly one long press on the freshly verified own harmless notification row; no approval, settings change, model, screenshot, fallback or retry',
)._asdict())


def main(argv=None):
    return bounded.main(argv, profile=PROFILE)


if __name__ == '__main__':
    raise SystemExit(main())

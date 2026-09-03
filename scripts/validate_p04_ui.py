#!/usr/bin/env python3
"""Validate the P04 textual design-system, navigation and UI-test baseline."""

from __future__ import annotations

import csv
import hashlib
import json
import re
import struct
from pathlib import Path
from xml.etree import ElementTree

import yaml

from generate_p04_contracts import GOLDEN_OUTPUT, SCREEN_OUTPUT, TOKEN_OUTPUT, outputs, parameter_kind, scalar_paths
from validate_p02_quality import validate_repository


ROOT = Path(__file__).resolve().parents[1]
UI_ROOT = ROOT / "docs/初始开发文件存档/UI设计稿与实现契约_v1.0"
TOKEN_SOURCE = UI_ROOT / "android_ledger_ui_tokens_v1.json"
SCREEN_SOURCE = UI_ROOT / "android_ledger_screen_contract_v1.yaml"
MATRIX_SOURCE = UI_ROOT / "UI需求追踪矩阵_v1.csv"
TARGET_REQUIREMENTS = (
    {"REQ-016", "REQ-083", "REQ-085", "REQ-090"}
    | {f"REQ-{number:03d}" for number in range(18, 28)}
    | {f"REQ-{number:03d}" for number in range(62, 71)}
)
REQUIRED_COMPONENTS = {
    "AmountText",
    "MoneyStack",
    "MetricCard",
    "StatusBadge",
    "CategoryGrid",
    "CategoryTile",
    "JournalTransactionRow",
    "AccountSummaryCard",
    "ProgressSummary",
    "MoneyExpressionField",
    "FilterBuilder",
    "AttachmentField",
    "LocationField",
    "ChartCard",
    "AccessibleDataTable",
    "MapPanel",
    "OperationProgressPanel",
    "SensitiveValueField",
    "HighRiskConfirmation",
    "LedgerScaffold",
    "LedgerTopAppBar",
    "LedgerNavigationBar",
    "LedgerSaveFab",
    "LedgerButton",
    "LedgerIconButton",
    "LedgerTextField",
    "SearchField",
    "SelectorField",
    "DateTimeZoneField",
    "FormSection",
    "ValidationSummary",
    "LedgerCard",
    "LedgerChip",
    "LedgerBanner",
    "LedgerLoadingState",
    "LedgerEmptyState",
    "LedgerErrorState",
    "LedgerProgressIndicator",
    "LedgerSnackbarHost",
    "LedgerTabRow",
    "LedgerDialog",
    "LedgerBottomSheet",
    "LedgerDatePickerDialog",
    "LedgerTimePickerDialog",
}


class P04ValidationError(AssertionError):
    pass


def read_matrix() -> list[dict[str, str]]:
    with MATRIX_SOURCE.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def validate_contract_data(tokens: dict, screen_document: dict, matrix: list[dict[str, str]]) -> dict[str, int]:
    screens = screen_document.get("screens", [])
    scalar_count = len(list(scalar_paths(tokens)))
    state_count = sum(len(screen.get("requiredStates", [])) for screen in screens)
    if scalar_count != 434:
        raise P04ValidationError(f"expected all 434 token scalars, found {scalar_count}")
    if len(tokens.get("color", {}).get("categoryPalette", [])) != 16:
        raise P04ValidationError("the complete 16-category palette is required")
    if set(tokens["color"]["semantic"]) != {"positive", "warning", "danger", "info", "neutralTransaction"}:
        raise P04ValidationError("semantic colors differ from the frozen token contract")
    if len(screens) != 215 or len({screen["route"] for screen in screens}) != 215:
        raise P04ValidationError("all 215 unique screen routes are required")
    if state_count != 646:
        raise P04ValidationError(f"expected all 646 required states, found {state_count}")
    for screen in screens:
        for raw in screen.get("params", []):
            try:
                parameter_kind(raw)
            except (ValueError, AttributeError) as error:
                raise P04ValidationError(f"unsafe route parameter {screen['id']}:{raw}") from error
    matrix_ids = {row["需求ID"] for row in matrix}
    if len(matrix) != 90 or not TARGET_REQUIREMENTS.issubset(matrix_ids):
        raise P04ValidationError("P04 requirement acceptance rows are incomplete")
    return {"tokens": scalar_count, "screens": len(screens), "states": state_count, "requirements": len(TARGET_REQUIREMENTS)}


def source_text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise P04ValidationError(f"required P04 source missing: {relative}")
    return path.read_text(encoding="utf-8")


def resource_keys(relative: str) -> set[str]:
    root = ElementTree.parse(ROOT / relative).getroot()
    return {element.attrib["name"] for element in root.findall("string")}


def validate_repository_state() -> dict[str, int]:
    baseline = validate_repository()
    if baseline["requirements"] != 90 or baseline["screens"] != 215:
        raise P04ValidationError("the inherited complete textual contract baseline failed")
    tokens = json.loads(TOKEN_SOURCE.read_text(encoding="utf-8"))
    screen_document = yaml.safe_load(SCREEN_SOURCE.read_text(encoding="utf-8"))
    counts = validate_contract_data(tokens, screen_document, read_matrix())

    for path, expected in outputs(tokens, screen_document).items():
        if not path.is_file() or path.read_bytes() != expected:
            raise P04ValidationError(f"generated P04 output drift: {path.relative_to(ROOT)}")

    theme = source_text("core/designsystem/src/main/kotlin/app/ledger/core/designsystem/LedgerTheme.kt")
    for accessor in ("colors", "typography", "spacing", "shapes", "motion", "dimensions"):
        if not re.search(rf"public val {accessor}:", theme):
            raise P04ValidationError(f"LedgerTheme.{accessor} is missing")
    if "GeneratedLedgerTokenContract.scalarValues" not in theme or "dynamicColor" not in theme:
        raise P04ValidationError("typed theme mapping or dynamic-color boundary is missing")

    component_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((ROOT / "core/designsystem/src/main/kotlin").rglob("*.kt"))
    )
    missing_components = sorted(
        component for component in REQUIRED_COMPONENTS if not re.search(rf"public fun {component}\s*\(", component_sources)
    )
    if missing_components:
        raise P04ValidationError(f"governed components missing: {missing_components}")
    for marker in ("LedgerVicoRenderer", "LedgerMapDesignContract", "LedgerGlanceTokens", "LedgerIconRegistry"):
        if marker not in component_sources:
            raise P04ValidationError(f"restricted design contract missing: {marker}")

    navigation = source_text("core/navigation/src/main/kotlin/app/ledger/core/navigation/NavigationContract.kt")
    stacks = source_text("core/navigation/src/main/kotlin/app/ledger/core/navigation/FiveStackNavigator.kt")
    if not re.search(r"public (?:open )?class LedgerDestinationKey internal constructor", navigation):
        raise P04ValidationError("Navigation 3 destination construction is not closed")
    if "public fun fromAllowlistedKey" in navigation or "public fun fromName" in navigation:
        raise P04ValidationError("arbitrary string route argument construction is public")
    for unsafe_type in ("MoneyArgument", "NoteArgument", "NameArgument", "CardArgument", "AttachmentArgument", "LocationArgument"):
        if unsafe_type in navigation:
            raise P04ValidationError(f"sensitive route type exists: {unsafe_type}")
    if "roots.mapValues" not in stacks or "Map<TopLevelDestination, NavBackStack<LedgerDestinationKey>>" not in stacks:
        raise P04ValidationError("five independent Navigation 3 back stacks are missing")

    previews = source_text("core/designsystem/src/debug/kotlin/app/ledger/core/designsystem/ComponentPreviews.kt")
    for marker in ("widthDp = 320", "widthDp = 360", "widthDp = 480", "fontScale = 1.3f", "fontScale = 2f", 'locale = "zh-rCN"', 'locale = "ja"', 'locale = "en"'):
        if marker not in previews:
            raise P04ValidationError(f"preview matrix marker missing: {marker}")
    device_test = source_text(
        "core/designsystem/src/androidTest/kotlin/app/ledger/core/designsystem/DesignSystemDeviceTest.kt"
    )
    for test_name in (
        "tokenOnlyGoldenMatchesEveryPixel",
        "hiddenFinancialAndSensitiveValuesNeverEnterTheRenderedOrSemanticTree",
        "constrainedWidthsFontScalesThemesAndReducedMotionRenderWithoutClipping",
        "simplifiedChineseJapaneseAndEnglishResourcesRender",
    ):
        if test_name not in device_test:
            raise P04ValidationError(f"device UI test missing: {test_name}")

    resource_sets = [
        resource_keys("core/designsystem/src/main/res/values/strings.xml"),
        resource_keys("core/designsystem/src/main/res/values-ja/strings.xml"),
        resource_keys("core/designsystem/src/main/res/values-en/strings.xml"),
    ]
    if any(keys != resource_sets[0] for keys in resource_sets[1:]) or len(resource_sets[0]) < 40:
        raise P04ValidationError("zh/ja/en design-system string resources are incomplete or asymmetric")

    golden = GOLDEN_OUTPUT.read_bytes()
    if golden[:8] != b"\x89PNG\r\n\x1a\n":
        raise P04ValidationError("token golden is not a PNG")
    width, height = struct.unpack(">II", golden[16:24])
    if (width, height) != (128, 104):
        raise P04ValidationError(f"unexpected token golden dimensions: {(width, height)}")
    if hashlib.sha256(golden).hexdigest() != "25abe0640e33215c2f7d8c9a1cb30025459511761fc7c842c2ba7a655a64d991":
        raise P04ValidationError("token-only golden digest drift")

    policy = source_text("build-logic/src/main/kotlin/app/ledger/buildlogic/SourcePolicyEngine.kt")
    for rule in (
        "UI-WRAPPER",
        "UI-COLOR-LITERAL",
        "UI-SPACING-LITERAL",
        "UI-LOCAL-THEME",
        "UI-ICON-REGISTRY",
        "UI-SWIPE-DELETE",
        "UI-COMPONENT-DUPLICATE",
        "PRIVACY-TEST-TAG",
    ):
        if rule not in policy:
            raise P04ValidationError(f"P04 static rule missing: {rule}")

    state = source_text("docs/初始开发文件存档/implementation/PROJECT_STATE.md")
    evidence = source_text("docs/初始开发文件存档/implementation/TEST_EVIDENCE.md")
    if "| P04 | VERIFIED |" not in state or "### P04 result" not in state:
        raise P04ValidationError("PROJECT_STATE does not truthfully mark P04 VERIFIED")
    if any(f"P04-E{number:03d}" not in evidence for number in range(1, 12)):
        raise P04ValidationError("P04 evidence ledger is incomplete")
    return counts


def main() -> int:
    try:
        counts = validate_repository_state()
    except (AssertionError, KeyError, TypeError, ValueError, OSError) as error:
        print(f"P04 UI validation: FAIL — {error}")
        return 1
    print(
        "P04 UI validation: PASS — "
        f"{counts['tokens']} token scalars, {counts['screens']} routes, {counts['states']} states, "
        f"{counts['requirements']} tracked requirements"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

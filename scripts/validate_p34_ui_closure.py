#!/usr/bin/env python3
"""Fail closed on P34 whole-product screen, locale, accessibility and UI-governance drift."""

from __future__ import annotations

import argparse
import csv
import hashlib
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

import yaml


ROOT = Path(__file__).resolve().parents[1]
UI_ROOT = ROOT / "docs/UI设计稿与实现契约_v1.0"
SCREEN_SOURCE = UI_ROOT / "android_ledger_screen_contract_v1.yaml"
MATRIX_SOURCE = UI_ROOT / "UI需求追踪矩阵_v1.csv"
SCREEN_LEDGER = ROOT / "docs/implementation/SCREEN_COVERAGE.csv"
REQUIREMENT_LEDGER = ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv"
MANUAL_FINDINGS = ROOT / "docs/testing/ManualTestFindings/UI.md"
MANUAL_PROGRESS = ROOT / "docs/testing/ManualTestFindings/UI_FIX_PROGRESS.md"
MANUAL_FINDINGS_SHA256 = "bcdbc9573bff31add46cad6a298413898db5e04b3cd00d9ffeae5a2901924e13"
SUPPORTED_RESOURCE_MODULES = (
    "app",
    "core/designsystem",
    "core/files",
    "core/geo",
    "feature/accounts",
    "feature/analysis",
    "feature/automation",
    "feature/journal",
    "feature/liabilities",
    "feature/onboarding",
    "feature/planning",
    "feature/record",
    "feature/settings",
    "feature/settlement",
    "feature/transfer",
    "feature/vault",
    "widget",
)
FORMAT_ARGUMENT = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")
SCREEN_FIELDS = {
    "group": "group",
    "module": "module",
    "route": "route",
    "title": "title",
    "presentation": "presentation",
    "result": "result",
}
FINDING_SECTION_KEY = re.compile(
    r"(共用|(?:G|ONB|REC|JRN|ACC|BUD|PRJ|GOL|LIA|CRD|INS|LOA|SET|AUT|ANA|MGT|CAT|MER|PLC|VLT|SETG|CLR|TRF|IMP|EXP|BKP|RST|ATT|SYS|WGT)-\d+|\d+(?:\.\d+)?(?:–\d+(?:\.\d+)?)?)",
)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def kotlin_sources() -> dict[str, str]:
    roots = ("app", "analytics", "core", "feature", "finance", "transfer", "widget")
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).glob("**/src/main/**/*.kt"))
        if "/build/" not in path.as_posix()
    }


def android_test_sources() -> dict[str, str]:
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for path in sorted(ROOT.glob("**/src/androidTest/**/*.kt"))
        if "/build/" not in path.as_posix()
    }


def normalize_list(values: list[str] | None) -> str:
    return " | ".join(values or [])


def validate_manual_findings_progress(
    findings_text: str | None = None,
    progress_text: str | None = None,
) -> list[str]:
    findings_text = MANUAL_FINDINGS.read_text(encoding="utf-8") if findings_text is None else findings_text
    progress_text = MANUAL_PROGRESS.read_text(encoding="utf-8") if progress_text is None else progress_text
    errors: list[str] = []
    digest = hashlib.sha256(findings_text.encode("utf-8")).hexdigest()
    if digest != MANUAL_FINDINGS_SHA256:
        errors.append("the immutable UI.md review report changed")
    current_section = ""
    finding_sections: list[str] = []
    finding_count = 0
    for line in findings_text.splitlines():
        if line.startswith("## "):
            current_section = line[3:].strip()
        elif line.startswith("### "):
            current_section = line[4:].strip()
        elif line.startswith("- 差异"):
            finding_count += 1
            finding_sections.append(current_section)
    unique_sections = list(dict.fromkeys(finding_sections))
    if finding_count != 224 or len(unique_sections) != 206:
        errors.append("UI.md must retain 224 discrepancy bullets across 206 sections")
    code_spans = re.findall(r"`([^`]+)`", progress_text)
    for section in unique_sections:
        match = FINDING_SECTION_KEY.match(section)
        key = (match.group(1) if match else section.split()[0]).rstrip(".")
        if key == "共用":
            mapped = any("OnboardingScaffold" in span for span in code_spans)
        elif key[0].isdigit():
            boundary = re.compile(rf"(?<![\w.-]){re.escape(key)}(?![\w.-])")
            mapped = any(boundary.search(span) for span in code_spans)
        else:
            mapped = any(key in span for span in code_spans)
        if not mapped:
            errors.append(f"manual discrepancy section lacks an explicit progress cross-reference: {section}")
    if "- [ ]" in progress_text:
        errors.append("manual UI discrepancy progress still contains an unchecked item")
    return errors


def validate_screen_contract(
    screens: list[dict] | None = None,
    coverage: list[dict[str, str]] | None = None,
) -> list[str]:
    screens = screens or yaml.safe_load(SCREEN_SOURCE.read_text(encoding="utf-8"))["screens"]
    coverage = coverage or read_csv(SCREEN_LEDGER)
    errors: list[str] = []
    if len(screens) != 215 or len({item["id"] for item in screens}) != 215 or len({item["route"] for item in screens}) != 215:
        errors.append("screen YAML must retain 215 unique IDs and routes")
    if sum(len(item.get("requiredStates", [])) for item in screens) != 646:
        errors.append("screen YAML must retain all 646 required states")
    rows = {row["screen_id"]: row for row in coverage}
    if len(coverage) != 215 or len(rows) != 215:
        errors.append("SCREEN_COVERAGE must contain exactly 215 unique rows")
    for screen in screens:
        screen_id = screen["id"]
        row = rows.get(screen_id)
        if row is None:
            errors.append(f"{screen_id} is missing from SCREEN_COVERAGE")
            continue
        for yaml_field, csv_field in SCREEN_FIELDS.items():
            expected = screen.get(yaml_field) or ""
            if row.get(csv_field, "") != expected:
                errors.append(f"{screen_id} {csv_field} differs from YAML")
        comparisons = {
            "params": normalize_list(screen.get("params")),
            "required_states": normalize_list(screen.get("requiredStates")),
            "primary_components": normalize_list(screen.get("primaryComponents")),
            "notes": normalize_list(screen.get("notes")),
        }
        for field, expected in comparisons.items():
            if row.get(field, "") != expected:
                errors.append(f"{screen_id} {field} differs from YAML")
        if row.get("status") != "VERIFIED":
            errors.append(f"{screen_id} is not VERIFIED")
        if "P34-E001" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks P34 whole-product evidence")
    return errors


def resource_directory(module: str, locale: str) -> Path:
    res = ROOT / module / "src/main/res"
    if locale == "zh-CN" and (res / "values-zh-rCN").is_dir():
        return res / "values-zh-rCN"
    if locale == "en" and not (res / "values-en").is_dir() and (res / "values-zh-rCN").is_dir():
        return res / "values"
    return res / {"zh-CN": "values", "ja": "values-ja", "en": "values-en"}[locale]


def resource_entries(directory: Path) -> dict[tuple[str, str], tuple[str, ...]]:
    entries: dict[tuple[str, str], tuple[str, ...]] = {}
    for path in sorted(directory.glob("*.xml")):
        root = ElementTree.parse(path).getroot()
        for element in root:
            name = element.attrib.get("name")
            if not name or element.attrib.get("translatable") == "false":
                continue
            if element.tag == "string":
                entries[(element.tag, name)] = ("".join(element.itertext()),)
            elif element.tag == "plurals":
                entries[(element.tag, name)] = tuple("".join(item.itertext()) for item in element.findall("item"))
            elif element.tag == "string-array":
                entries[(element.tag, name)] = tuple("".join(item.itertext()) for item in element.findall("item"))
    return entries


def format_signature(values: tuple[str, ...]) -> frozenset[tuple[str, str]]:
    """Return arguments used by any variant without counting CLDR plural branches."""
    return frozenset(
        (position or "implicit", kind.lower())
        for value in values
        for position, kind in FORMAT_ARGUMENT.findall(value.replace("%%", ""))
    )


def validate_localization(
    override: dict[tuple[str, str], dict[tuple[str, str], tuple[str, ...]]] | None = None,
) -> list[str]:
    errors: list[str] = []
    plural_count = 0
    for module in SUPPORTED_RESOURCE_MODULES:
        localized = {
            locale: (override or {}).get((module, locale), resource_entries(resource_directory(module, locale)))
            for locale in ("zh-CN", "ja", "en")
        }
        if not all(localized.values()):
            errors.append(f"{module} lacks a supported-language resource set")
            continue
        canonical = set(localized["zh-CN"])
        for locale, entries in localized.items():
            if set(entries) != canonical:
                missing = sorted(canonical - set(entries))[:5]
                extra = sorted(set(entries) - canonical)[:5]
                errors.append(f"{module} {locale} resource keys differ: missing={missing}, extra={extra}")
        for key in canonical.intersection(*(set(entries) for entries in localized.values())):
            signatures = {locale: format_signature(entries[key]) for locale, entries in localized.items()}
            if len(set(signatures.values())) != 1:
                errors.append(f"{module} {key} format arguments differ across languages")
        plural_count += sum(1 for key in canonical if key[0] == "plurals")
    if plural_count == 0:
        errors.append("no Android plurals resource exists")
    app = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((ROOT / "app/src/main/kotlin").rglob("*.kt"))
    )
    if "pluralStringResource" not in app or "R.plurals.global_operation_count" not in app:
        errors.append("production UI does not consume a locale-aware plurals resource")
    resource_text = "\n".join(
        path.read_text(encoding="utf-8")
        for module in SUPPORTED_RESOURCE_MODULES
        for path in sorted((ROOT / module / "src/main/res").glob("values*/*.xml"))
    )
    if "同步" in resource_text or re.search(r"\b(?:sync|synchronization)\b", resource_text, re.IGNORECASE):
        errors.append("ordinary UI contains forbidden backup-as-sync terminology")
    return errors


def validate_ui_governance(
    sources: dict[str, str] | None = None,
    tests: dict[str, str] | None = None,
) -> list[str]:
    sources = sources or kotlin_sources()
    tests = tests or android_test_sources()
    errors: list[str] = []
    combined = "\n".join(sources.values())
    test_text = "\n".join(tests.values())
    screen_ids = [item["id"] for item in yaml.safe_load(SCREEN_SOURCE.read_text(encoding="utf-8"))["screens"]]
    for screen_id in screen_ids:
        if f'"{screen_id}"' not in combined:
            errors.append(f"production destinations do not reference {screen_id}")
    if re.search(r"@Composable\s*\{", combined):
        errors.append("anonymous @Composable page declaration exists")
    design = sources.get(
        "core/designsystem/src/main/kotlin/app/ledger/core/designsystem/FoundationComponents.kt",
        "",
    )
    for marker in (
        "isTraversalGroup = true",
        "TOP_BAR_TRAVERSAL_INDEX",
        "CONTENT_TRAVERSAL_INDEX",
        "FIXED_ACTION_TRAVERSAL_INDEX",
        "BOTTOM_NAVIGATION_TRAVERSAL_INDEX",
        "stringResource(R.string.ledger_selected)",
        "stringResource(R.string.ledger_not_selected)",
        "contentDescription = save",
    ):
        if marker not in design:
            errors.append(f"design-system accessibility marker missing: {marker}")
    if re.search(r'stateDescription\s*=\s*"(?:selected|not selected)"', combined):
        errors.append("selection semantics contain a hard-coded language")
    business = sources.get(
        "core/designsystem/src/main/kotlin/app/ledger/core/designsystem/BusinessComponents.kt",
        "",
    )
    if "if (hidden) stringResource(R.string.ledger_amount_hidden) else model.fullAccessibleText" not in business:
        errors.append("hidden amounts are not replaced by the localized non-sensitive semantic label")
    theme = sources.get(
        "core/designsystem/src/main/kotlin/app/ledger/core/designsystem/LedgerTheme.kt",
        "",
    )
    for marker in ("LocalLedgerTimeZone", "ledgerTimeZoneId", "!ValueAnimator.areAnimatorsEnabled()"):
        if marker not in theme:
            errors.append(f"global presentation preference marker missing: {marker}")
    charts = sources.get(
        "core/designsystem/src/main/kotlin/app/ledger/core/designsystem/ChartsMapsAndRiskComponents.kt",
        "",
    )
    for marker in (
        "ledger_previous_page",
        "ledger_next_page",
        "text = AnnotatedString(model.summary)",
        "AccessibleDataTable(dataTable)",
        "fallbackContent()",
        "LedgerTheme.colors.chart.axis",
        "LedgerTheme.colors.chart.grid",
        "LedgerTheme.colors.chart.selection",
        "LineCartesianLayer.LineStroke.Dashed",
        "missingPointIndices",
        "VisualizationCompatibility.resolve",
        "LedgerTheme.dimensions.chartPreferredHeight",
        "customActions = listOf(",
        "Math.floorMod(selectedPointIndex - 1, points.size)",
        "LedgerHorizontalBarChart",
        "ledger_collapse_data_table",
    ):
        if marker not in charts:
            errors.append(f"chart/map accessibility marker missing: {marker}")
    models = sources.get(
        "core/designsystem/src/main/kotlin/app/ledger/core/designsystem/ComponentModels.kt",
        "",
    )
    for marker in (
        "val formattedValues: List<String>",
        "val missingPointIndices: Set<Int>",
        "val includeZeroInRange: Boolean = true",
        "a non-zero chart baseline must be explained",
    ):
        if marker not in models:
            errors.append(f"typed chart contract marker missing: {marker}")
    navigation = sources.get(
        "core/navigation/src/main/kotlin/app/ledger/core/navigation/NavigationContract.kt",
        "",
    )
    for marker in (
        "data class LedgerScreenUiState",
        "sealed interface LedgerScreenUiAction",
        "sealed interface LedgerScreenEffect",
        "RecordDestinationKey",
        "TransferDestinationKey",
    ):
        if marker not in navigation:
            errors.append(f"screen state/action boundary marker missing: {marker}")
    route_sources = [text for path, text in sources.items() if path.endswith("Routes.kt")]
    if sum("EntryProviderScope<LedgerDestinationKey>" in text for text in route_sources) < 10:
        errors.append("feature-owned Navigation 3 entry providers are incomplete")
    anomaly = sources.get(
        "feature/analysis/src/main/kotlin/app/ledger/feature/analysis/P26AnalysisScreens.kt",
        "",
    )
    anomaly_screen = anomaly.split("internal fun AnomalyRulesScreen", 1)[-1].split("private fun anomalyTitle", 1)[0]
    if "fixedAction =" not in anomaly_screen or re.search(
        r"item\s*\{\s*LedgerButton\(stringResource\(R\.string\.analysis_save_rule\)",
        anomaly_screen,
    ):
        errors.append("ANA-013 save must stay fixed outside the scrolling rule/finding list")
    if re.search(r"contentDescription\s*=\s*model\.(?:accessibleLabel|fullAccessibleText|summary)", combined):
        errors.append("business data is routed through contentDescription instead of text semantics")
    if re.search(r"onToggleTable\s*=\s*\{\s*\}", combined):
        errors.append("a visible chart data-table action is wired to a no-op callback")
    if re.search(r"onLongClick\s*=\s*\{\s*\}", combined):
        errors.append("a transaction row advertises a no-op long-click action")
    record_screen = sources.get(
        "feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt",
        "",
    )
    for marker in (
        "LedgerMapStyleConfiguration.OpenFreeMap",
        "onCoordinateSelected = actions.onLocationCoordinate",
        "RecordLocationEditorState.PermissionDenied",
        "RecordLocationEditorState.MapUnavailable",
        "metadata?.typeLabel.attachmentIcon()",
        "record_settlement_difference",
    ):
        if marker not in record_screen:
            errors.append(f"REC-009/010/011 production UI marker missing: {marker}")
    app_view_model = sources.get("app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt", "")
    for marker in (
        'target == "REC-009" && editor?.locationPresentation == RecordLocationEditorState.Locating',
        "LocationSaveDisposition.LOCATED",
        "OrdinaryLocationProvider.MANUAL",
        "recordAttachmentPresentations(",
        "state.selectedScheduleInstallmentNumber",
        "loanTransactionContext(state)",
    ):
        if marker not in app_view_model:
            errors.append(f"record production state/write marker missing: {marker}")
    for marker in (
        "::formatImportPreviewValue",
        "formatPresentationString(JournalR.string.p15_journal_copy_name, source.name)",
        "formatPresentationString(AnalysisR.string.analysis_copy_name, name)",
    ):
        if marker not in app_view_model:
            errors.append(f"localized presentation boundary marker missing: {marker}")
    import_controller = sources.get("app/src/main/kotlin/app/ledger/app/ImportController.kt", "")
    for marker in ("formatPreviewValue: (StagingValue) -> String", "val previewValues", "formatPreviewValue(field.value)"):
        if marker not in import_controller:
            errors.append(f"typed import preview formatting marker missing: {marker}")
    import_screen = sources.get(
        "feature/transfer/src/main/kotlin/app/ledger/feature/transfer/ImportWizardScreen.kt",
        "",
    )
    for marker in ("importTargetFieldLabel()", "importDuplicateMatchLabel()", "importValidationMessage()"):
        if marker not in import_screen:
            errors.append(f"localized import label marker missing: {marker}")
    backup_controller = sources.get("app/src/main/kotlin/app/ledger/app/BackupController.kt", "")
    restore_controller = sources.get("app/src/main/kotlin/app/ledger/app/RestoreController.kt", "")
    if "createdAt = formatCreatedAt(snapshot.createdAt)" not in backup_controller:
        errors.append("backup snapshots bypass the application date/time presentation boundary")
    if "createdAt = formatCreatedAt(createdAt)" not in restore_controller:
        errors.append("restore snapshots bypass the application date/time presentation boundary")
    specialized_screen = sources.get(
        "feature/record/src/main/kotlin/app/ledger/feature/record/SpecializedTransactionScreens.kt",
        "",
    )
    for marker in ("LedgerDateTimePickerFlow(", "LedgerDatePickerFlow(", "actions.onOpenAttachment"):
        if marker not in specialized_screen:
            errors.append(f"REC-013/020/021/022 interaction marker missing: {marker}")
    credit_screen = sources.get(
        "feature/liabilities/src/main/kotlin/app/ledger/feature/liabilities/CreditScreens.kt",
        "",
    )
    for marker in ("credit_payment_account", "credit_payment_date", "LedgerDatePickerFlow("):
        if marker not in credit_screen:
            errors.append(f"REC-014 interaction marker missing: {marker}")
    loan_screen = sources.get(
        "feature/liabilities/src/main/kotlin/app/ledger/feature/liabilities/LoanScreens.kt",
        "",
    )
    for marker in ("onSelectPaymentAccount", "ScheduleInstallmentSelector", "LoanOperationDateTimePicker"):
        if marker not in loan_screen:
            errors.append(f"REC-018/019 interaction marker missing: {marker}")
    installment_screen = sources.get(
        "feature/liabilities/src/main/kotlin/app/ledger/feature/liabilities/InstallmentScreens.kt",
        "",
    )
    for marker in ("installment_credit_account", "LedgerChoiceSelector(", "LedgerDatePickerFlow("):
        if marker not in installment_screen:
            errors.append(f"REC-027 interaction marker missing: {marker}")
    batch_screen = sources.get(
        "feature/record/src/main/kotlin/app/ledger/feature/record/BatchRecordScreens.kt",
        "",
    )
    for marker in ("errors.groupBy(BatchValidationIssue::code)", "ValidationSummary(", "batch_issue_group_count"):
        if marker not in batch_screen:
            errors.append(f"REC-025 validation grouping marker missing: {marker}")
    ordinary_record_screen = sources.get(
        "feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt",
        "",
    )
    shared_record_scaffold = sources.get(
        "feature/record/src/main/kotlin/app/ledger/feature/record/TransactionEditorScaffold.kt",
        "",
    )
    if "fun TransactionEditorScaffold(" not in shared_record_scaffold:
        errors.append("REC-003/024 shared transaction editor scaffold is missing")
    if "TransactionEditorScaffold(" not in ordinary_record_screen or "TransactionEditorScaffold(" not in batch_screen:
        errors.append("REC-024 batch and ordinary editors do not share TransactionEditorScaffold")
    for marker in ("BatchReferenceSelector(", "record_search_category", "record_search_account"):
        if marker not in batch_screen:
            errors.append(f"REC-024 explicit searchable reference selection marker missing: {marker}")
    journal_screen = sources.get(
        "feature/journal/src/main/kotlin/app/ledger/feature/journal/JournalDestination.kt",
        "",
    )
    if "stickyHeader(" not in journal_screen or "pagingItems[pagingIndex]" not in journal_screen:
        errors.append("JRN-001 date groups are not real sticky paging headers")
    if "val kindLabel = transaction.kind.label()" not in journal_screen:
        errors.append("10.5 journal rows bypass localized transaction-kind labels")
    planning_screen = sources.get(
        "feature/planning/src/main/kotlin/app/ledger/feature/planning/ProjectGoalScreens.kt",
        "",
    )
    for marker in (
        "transaction.kind.projectTransactionLabel()",
        "goal.status.goalStatusLabel()",
        "movementLabel(movement.kind)",
    ):
        if marker not in planning_screen:
            errors.append(f"10.5 planning presentation label marker missing: {marker}")
    consumption_map_screen = sources.get(
        "feature/analysis/src/main/kotlin/app/ledger/feature/analysis/P27AnalysisScreens.kt",
        "",
    )
    if "mapGroupKindLabel(point.kind)" not in consumption_map_screen:
        errors.append("10.5 consumption-map detail bypasses localized kind labels")
    more_screen = sources.get(
        "app/src/main/kotlin/app/ledger/app/MoreRootScreen.kt",
        "",
    )
    help_entry = "FeatureEntry(stringResource(R.string.global_help), stringResource(R.string.global_help_explanation), onHelp)"
    if help_entry not in more_screen:
        errors.append("G-008 offline help is not reachable from More")
    if "private val schemaVersionMarker = LedgerSchemaVersionMarker(context)" not in app_view_model:
        errors.append("G-003 schema marker is not a ViewModel-level opening dependency")
    open_saved_book = app_view_model.split("private fun openSavedBook", 1)[-1].split("\n    private fun", 1)[0]
    if "schemaVersionMarker.migrationExpected()" not in open_saved_book:
        errors.append("G-003 opening does not derive migration presentation before database open")
    projection = sources.get(
        "finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt",
        "",
    )
    if "AnalyticsProjectionEngine.staleTables(database, localRevision)" not in projection:
        errors.append("ANA-015 full projection mismatch audit omits analytics tables")
    if "add(ProjectionFamily.ANALYTICS)" not in projection:
        errors.append("ANA-015 stale analytics tables do not mark the analytics projection family")
    analysis_screen = sources.get(
        "feature/analysis/src/main/kotlin/app/ledger/feature/analysis/P26AnalysisScreens.kt",
        "",
    )
    for marker in ("AnalysisExportScope.entries", "actions.onPrepareExport"):
        if marker not in analysis_screen:
            errors.append(f"ANA-010 complete export flow marker missing: {marker}")
    preview = read("core/designsystem/src/debug/kotlin/app/ledger/core/designsystem/ComponentPreviews.kt")
    for marker in (
        "widthDp = 320",
        "widthDp = 360",
        "widthDp = 480",
        "widthDp = 600",
        "fontScale = 1.3f",
        "fontScale = 1.6f",
        "fontScale = 2f",
        'locale = "zh-rCN"',
        'locale = "ja"',
        'locale = "en"',
        "BatchComponentsPreview",
        "DialogPreview",
        "BottomSheetPreview",
        "DatePickerPreview",
        "TimePickerPreview",
    ):
        if marker not in preview:
            errors.append(f"component preview matrix missing {marker}")
    for marker in (
        "dynamicColorChangesOnlyTheMaterialShellAndPreservesLedgerSemanticColors",
        "selectionAndDataTablePagingSemanticsAreLocalizedInAllThreeLanguages",
        "scaffoldTraversalOrderPlacesFieldsThenFixedSaveBeforeBottomNavigation",
        "transactionMeaningAndAccessibleTextSurviveGrayscaleRendering",
        "RenderCase(600, 2f",
        "RenderCase(480, 1.6f",
        "accountHomeGoldenMatchesTokenAndYamlDerivedPixels",
        "talkBackServiceCompletesTheCriticalRecordNavigationFlow",
        "allRenderedCoreActionsMeetTouchTargetAndSemanticDescriptionRules",
        "lightAndDarkThemeTextPairsMeetWcagContrast",
        "chartExplorerAnnouncesExactValuesAndWrapsAtBothEdges",
        "chartAndBatchMatrixRemainInsideCompactTwoHundredPercentBounds",
        "createAndroidComposeRule<MainActivity>()",
        "viewModel.saveOrdinaryRecord()",
    ):
        if marker not in test_text:
            errors.append(f"P34 device evidence marker missing: {marker}")
    critical_flow_markers = (
        "talkBackServiceCompletesTheCriticalRecordNavigationFlow",
        "optional location\n            // prefetch is intentionally unresolved; its timeout/failure must never block the save",
        "settlementImbalanceBlocksTheProductionSaveIntentWithoutWriting",
        "editingConflictOffersProductionHistoryNavigationAndNeverDispatchesOverwrite",
        "refundRemainingLimitAndCrossMonthPolicyCompleteThroughUserActions",
        "creditOverpaymentDisablesProductionSaveAndCannotDispatchAWrite",
        "batchCommitWithAnyValidationErrorDispatchesNoWrite",
        "trashRestoreDispatchesOnceAndIneligiblePurgeExposesReasonWithoutPurging",
        "budgetHierarchyExcessDisablesSaveAndDispatchesNoMutation",
        "candidateConfirmationOpensFullEditorWithoutChangingFormalMetrics",
        "mergeRestorePurgeTombstoneWinsThroughTheApplyAction",
        "primaryNumberAndSecurityCodeEachRequireAnIndependentAuthenticationAction",
        "assertWidgetQuickEntryOpensPrefilledFormWithoutSubmittingMutation",
    )
    for marker in critical_flow_markers:
        if marker not in test_text:
            errors.append(f"critical Compose interaction evidence missing: {marker}")
    golden_markers = (
        "categoryHomeEditorValidationAndSettlementGoldensMatchEveryPixel",
        "transferAdjustmentExchangeAndOpeningGoldensMatchEveryPixel",
        "journalListAndDetailGoldensMatchEveryPixel",
        "accountHomeGoldenMatchesTokenAndYamlDerivedPixels",
        "creditAccountAndOfficialDifferenceGoldensMatchEveryPixel",
        "budgetHomeAndConstraintEditorGoldensMatchEveryPixel",
        "loanCombinationDetailAndPrepaymentResultGoldensMatchEveryPixel",
        "reportAndIntegrityGoldensMatchEveryPixel",
        "consumptionMapAndDetailGoldensMatchEveryPixel",
        "p28ProductionGoldensMatchEveryPixel",
        "fiveVisibleBackupPhasesAndGeneratedScreenshotsAreStable",
        "contractDerivedVltScreenshotsMatchPixelBaselines",
        "contractDerivedSetgClrAndSysScreenshotsMatchPixelBaselines",
        "frozenGlobalAndOnboardingGoldensMatchEveryPixel",
    )
    for marker in golden_markers:
        if marker not in test_text:
            errors.append(f"critical golden evidence missing: {marker}")
    hash_marker = "val HASH_QUERIES = listOf("
    if hash_marker not in projection:
        errors.append("canonical projection hash query inventory is missing")
    else:
        hash_queries = projection.split(hash_marker, 1)[1].split("\n        )", 1)[0]
        for table in (
            "widget_book_snapshot",
            "widget_account_snapshot",
            "widget_credit_snapshot",
            "widget_goal_snapshot",
        ):
            if f'"{table}" to' in hash_queries:
                errors.append(f"date-sensitive widget cache contaminates canonical projection hash: {table}")
    return errors


def validate_requirements(rows: list[dict[str, str]] | None = None) -> list[str]:
    rows = rows or read_csv(REQUIREMENT_LEDGER)
    errors: list[str] = []
    if len(rows) != 90 or len({row["requirement_id"] for row in rows}) != 90:
        errors.append("REQUIREMENT_COVERAGE must contain 90 unique rows")
        return errors
    frozen = {row["需求ID"] for row in read_csv(MATRIX_SOURCE)}
    if {row["requirement_id"] for row in rows} != frozen:
        errors.append("implementation requirement IDs differ from the frozen matrix")
    for row in rows:
        requirement = row["requirement_id"]
        if requirement == "REQ-084":
            if row["status"] not in {"IN_PROGRESS", "VERIFIED"}:
                errors.append("REQ-084 must remain IN_PROGRESS after P34 or be VERIFIED by P35")
            if row["status"] == "VERIFIED" and "P35-E" not in row["verification_evidence"]:
                errors.append("REQ-084 P35 promotion lacks P35 evidence")
        elif row["status"] != "VERIFIED":
            errors.append(f"{requirement} must remain VERIFIED at or after P34")
        if requirement != "REQ-084" and "P34-E" not in row["verification_evidence"]:
            errors.append(f"{requirement} lacks P34 UI review evidence")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    decision = read("docs/implementation/DECISION_LOG.md")
    mapping_path = ROOT / "docs/implementation/P34_UI_CONTRACT_CLOSURE.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    for marker in ("Stage status: VERIFIED", "215 / 215"):
        if marker not in state:
            errors.append(f"PROJECT_STATE missing {marker}")
    if not re.search(r"Current stage: P(?:34|35|36)\b", state):
        errors.append("PROJECT_STATE is not at or after P34")
    if "89 VERIFIED" not in state and "90 / 90 VERIFIED" not in state:
        errors.append("PROJECT_STATE lacks a valid P34-or-later requirement total")
    for index in range(1, 9):
        if f"P34-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P34-E{index:03d}")
    for marker in ("215", "646", "TalkBack", "600dp", "160%", "P34 is `VERIFIED`"):
        if marker not in mapping:
            errors.append(f"P34 mapping missing {marker}")
    if "DL-167" not in decision or "P34" not in decision:
        errors.append("DECISION_LOG lacks P34 interpretation")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-ledgers", action="store_true")
    args = parser.parse_args()
    errors = (
        validate_screen_contract()
        + validate_localization()
        + validate_ui_governance()
        + validate_requirements()
        + validate_manual_findings_progress()
    )
    if not args.skip_ledgers:
        errors += validate_ledgers()
    if errors:
        print("P34 UI closure validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P34 UI closure validation passed — 215 screens, 646 states, 90 requirements, 17 three-language modules")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

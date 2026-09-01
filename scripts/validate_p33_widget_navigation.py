#!/usr/bin/env python3
"""Reject P33 widget, navigation, background-operation, settings, or evidence drift."""

from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "WGT-001": ("widget/config/type/{appWidgetId}", ["appWidgetId:Int"], {"content"}),
    "WGT-002": ("widget/config/data/{appWidgetId}/{type}", ["appWidgetId:Int", "type:WidgetType"], {"content", "noEligibleData"}),
    "WGT-003": ("widget/config/privacy/{appWidgetId}", ["appWidgetId:Int"], {"content"}),
    "SETG-001": ("settings", [], {"content"}),
    "SETG-002": ("settings/appearance", [], {"content"}),
    "SETG-003": ("settings/language-region", [], {"content"}),
    "SETG-004": ("settings/currencies", [], {"content", "searching"}),
    "SETG-005": ("settings/calendar", [], {"content"}),
    "SETG-008": ("settings/trash", [], {"content"}),
    "SETG-012": ("settings/about", [], {"content"}),
    "TRF-001": ("transfer", [], {"content", "operationActive"}),
    "G-006": ("more", [], {"content", "badgeUpdates", "operationInProgress"}),
    "G-007": ("operations", [], {"active", "paused", "failed", "completed", "empty"}),
    "G-008": ("help/{topicKey}", ["topicKey:String"], {"content", "notFound"}),
    "SYS-002": ("permission/notifications", [], {"firstAsk", "denied"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin", "core/background/src/main/kotlin", "feature/settings/src/main/kotlin",
        "feature/transfer/src/main/kotlin", "finance/application/src/main/kotlin",
        "finance/data/src/main/kotlin", "widget/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots for path in sorted((ROOT / root).rglob("*.kt"))
    }


def named(sources: dict[str, str], filename: str) -> str:
    return "\n".join(source for path, source in sources.items() if path.endswith(filename))


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def validate_contract() -> list[str]:
    screens = {
        item["id"]: item
        for item in yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))["screens"]
    }
    errors: list[str] = []
    for screen_id, (route, params, states) in EXPECTED.items():
        actual = screens.get(screen_id, {})
        if actual.get("route") != route or actual.get("params", []) != params or set(actual.get("requiredStates", [])) != states:
            errors.append(f"{screen_id} route/params/requiredStates drift")
    if sum(len(value[2]) for value in EXPECTED.values()) != 26:
        errors.append("P33 required-state baseline must remain exactly 26")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "WidgetModels.kt", "LedgerWidgetRuntime.kt", "LedgerGlanceWidget.kt", "WidgetConfigurationActivity.kt",
        "AppWidgetConfigurationRepository.kt", "WidgetSnapshotApplication.kt",
        "SecureRoomWidgetSnapshotApplicationPort.kt", "MoreRootScreen.kt", "TransferHubScreen.kt",
        "P33SettingsScreens.kt", "OperationNotificationCoordinator.kt", "AppRootViewModel.kt",
        "AppRootScreen.kt", "LedgerApplication.kt", "ImportWorker.kt", "ExportWorker.kt", "BackupWorker.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P33 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    models = named(sources, "WidgetModels.kt")
    require_tokens(errors, models, "nine widget types and fail-closed states", (
        "QUICK_ENTRY", "MONTH_CONSUMPTION", "MONTH_BUDGET", "TODAY_AVAILABLE", "ACCOUNT",
        "CORE_NET_ASSETS", "CREDIT_CARD", "GOAL", "FINANCIAL_OVERVIEW",
        "val revealAmounts: Boolean = false", "data object NoEligibleData", "data object Locked", "data object Stale",
        "snapshotLocalDate != today.toStorageInt()", "type == LedgerWidgetType.QUICK_ENTRY",
    ))
    enum_match = re.search(r"enum class LedgerWidgetType\s*\{([^}]*)}", models, re.DOTALL)
    if not enum_match or len(re.findall(r"\b[A-Z][A-Z_]+\b", enum_match.group(1))) != 9:
        errors.append("LedgerWidgetType must contain exactly nine closed values")

    snapshot_model = named(sources, "WidgetSnapshotApplication.kt")
    require_tokens(errors, snapshot_model, "bounded widget read model", (
        "Complete, bounded read model consumed by Glance", "WidgetBookSnapshot", "WidgetAccountSnapshot",
        "WidgetCreditSnapshot", "WidgetGoalSnapshot", "interface WidgetSnapshotApplicationPort",
        "interface WidgetSnapshotRefreshApplicationPort", "refreshIfStale",
    ))
    if re.search(r"\bval\s+(?:note|latitude|longitude|pan|securityCode|vault|attachment|merchant)\b", snapshot_model, re.IGNORECASE):
        errors.append("widget snapshot read model contains a sensitive or unbounded business field")

    snapshot_port = named(sources, "SecureRoomWidgetSnapshotApplicationPort.kt")
    read_path = snapshot_port.split("override suspend fun quickTargets", 1)[0]
    require_tokens(errors, snapshot_port, "SQLCipher snapshot-only Glance read", (
        "EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME", "DeviceLedgerKeyProvider", "databaseDek",
        "widget_book_snapshot", "widget_account_snapshot", "widget_credit_snapshot", "widget_goal_snapshot",
        "passphrase.fill(0)", "database.close()", "override suspend fun refreshIfStale",
        "RoomProjectionEngine().rebuildWidgetSnapshot",
    ))
    for forbidden in ("business_transaction", "posting", "journal_entry", "category", "card_vault_secret", "location_record"):
        if forbidden in read_path:
            errors.append(f"Glance read path queries complex/sensitive table {forbidden}")

    glance = named(sources, "LedgerGlanceWidget.kt") + named(sources, "LedgerWidgetRuntime.kt")
    require_tokens(errors, glance, "Glance snapshot and privacy behavior", (
        "LedgerWidgetRuntime.resolve", "LedgerWidgetContent.Locked", "LedgerWidgetContent.Stale",
        "if (!reveal) return \"••••\"", "widget_previous_month_comparison", "widget_snapshot_change",
        "bundle.creditAccounts.firstOrNull()", "LedgerGlanceTokens", "updateAll(context.applicationContext)",
        "GlanceAppWidgetManager(context).getAppWidgetId(id)", "withLanguageTag(languageTag)",
        "savedConfigurations[configuration.appWidgetId] = configuration",
    ))
    if "import androidx.glance.appwidget.AppWidgetId" in glance:
        errors.append("widget render path uses the restricted AppWidgetId implementation type")
    if "AppLock" in glance or "FinancialMutationCoordinator" in glance:
        errors.append("widget render path depends on app lock or performs a write")

    configuration = named(sources, "WidgetConfigurationActivity.kt") + named(sources, "AppWidgetConfigurationRepository.kt")
    require_tokens(errors, configuration, "WGT configuration and per-widget opt-in", (
        "WidgetConfigurationStep.TYPE", "WidgetConfigurationStep.DATA", "WidgetConfigurationStep.PRIVACY",
        "widget_no_eligible_title", "onBack = { step = WidgetConfigurationStep.TYPE }", "revealAmounts",
        "widgetConfigurationsList", "selectedId", "WidgetQuickTargetKindProto", "WidgetQuickDirectionProto",
        "GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(appWidgetId)",
        "withLanguageTag(LedgerWidgetRuntime.languageTag())",
    ))

    root = named(sources, "AppRootViewModel.kt") + named(sources, "AppRootScreen.kt")
    require_tokens(errors, root, "closed widget deep links and full-form quick entry", (
        "WIDGET_DEEP_LINK_HOST", "parseWidgetDeepLink", "parseWidgetDestination", "widget destination is not allowlisted",
        "WIDGET_CONSUMPTION_REPORT_KEY", "openWidgetQuickForm", "OrdinaryRecordPolicy.createEditor",
        "OperationCenterLoadState", "SqlCipherBackgroundOperationRepository", "CANCEL_REQUESTED",
        "NotificationPermissionPresentation.FIRST_ASK", "NotificationPermissionPresentation.DENIED",
        "refreshWidgetSnapshot(state.bookId)", "refreshWidgetSnapshot(ready.bookId)",
        "LedgerWidgetRuntime.updateAll(context)",
    ))
    quick_method = root[root.find("private fun openWidgetQuickForm"):root.find("private fun openWidgetQuickForm") + 6000]
    if "FinancialMutationCoordinator" in quick_method or re.search(r"\.(?:save|commit|insert)\(", quick_method):
        errors.append("quick-entry widget deep link writes instead of opening a complete form")

    more = named(sources, "MoreRootScreen.kt")
    require_tokens(errors, more, "single grouped More hub and durable badges", (
        "global_group_planning", "global_group_liabilities", "global_group_settlement", "global_group_automation",
        "global_group_data", "global_group_reference", "global_group_settings", "ACTIVE_OPERATION_STATES",
        "global_transfer_center", "p12_title_cards", "global_vault", "global_help", "requiresAdditionalSettlement",
    ))
    if re.search(r"\b(?:ModalNavigationDrawer|DismissibleNavigationDrawer|NavigationDrawerItem)\b", more):
        errors.append("drawer navigation exists in the unified More hub")

    settings = named(sources, "P33SettingsScreens.kt")
    require_tokens(errors, settings, "remaining governed settings", (
        '"SETG-001"', '"SETG-002"', '"SETG-003"', '"SETG-005"', '"SETG-012"',
        "SettingsThemeMode.entries", "dynamicColor", "defaultAmountsHidden", "reduceMotion", "SUPPORTED_LANGUAGES",
        "availableZoneIds", "SettingsWeekStart.entries", "licenses", "openSourceCode",
    ))

    transfer = named(sources, "TransferHubScreen.kt")
    require_tokens(errors, transfer, "TRF operation and notification state", (
        "operationActive", "notificationPermissionAvailable", "transfer_hub_import", "transfer_hub_export",
        "transfer_hub_backup", "transfer_hub_restore", "transfer_hub_operations",
    ))

    notification = named(sources, "OperationNotificationCoordinator.kt")
    require_tokens(errors, notification, "central long-operation notification policy", (
        'CHANNEL_ID: String = "ledger-operations"', 'Uri.parse("ledger://screen/G-007")',
        "POST_NOTIFICATIONS", "FLAG_IMMUTABLE", "setOngoing(true)", "setOnlyAlertOnce(true)",
    ))
    workers = "\n".join(named(sources, name) for name in ("ImportWorker.kt", "ExportWorker.kt", "BackupWorker.kt"))
    require_tokens(errors, workers, "operationId-only resumable workers", (
        "inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)", "OperationNotificationCoordinator.create",
        "BackgroundOperationState.CANCEL_REQUESTED", "BackgroundOperationState.ROLLING_BACK",
    ))
    require_tokens(errors, named(sources, "ImportWorker.kt"), "import safe cancellation", (
        "BackgroundOperationState.CANCEL_REQUESTED", "BackgroundOperationState.ROLLING_BACK", "ImportFailure.Cancelled.code",
    ))
    forbidden_input = re.compile(r'put(?:String|Long|Int|Boolean)\(\s*"(?!operationId)[^"]+"')
    for path, source in sources.items():
        if path.endswith("Worker.kt") and forbidden_input.search(source.split("doWork", 1)[0]):
            errors.append(f"worker scheduling surface contains non-operationId input in {path}")

    help_source = named(sources, "AppRootScreen.kt")
    require_tokens(errors, help_source, "closed offline help allowlist", (
        '"getting-started"', '"data-transfer"', '"backup-restore"', '"privacy"', '"widgets"',
        "singleOrNull { it.key == topicKey }", "global_help_not_found_title",
    ))
    return errors


def validate_schema_resources_tests() -> list[str]:
    errors: list[str] = []
    migration = read("core/database/src/main/assets/ledger_schema_v3_widget_snapshot.sql")
    require_tokens(errors, migration, "widget snapshot schema v3", (
        "ALTER TABLE widget_book_snapshot", "snapshot_local_date", "month_consumption_base_minor",
        "today_available_base_minor", "previous_core_net_financial_assets_base_minor",
        "ALTER TABLE widget_account_snapshot", "available_minor",
        "ALTER TABLE widget_credit_snapshot", "statement_remaining_minor", "ALTER TABLE widget_goal_snapshot",
    ))
    if "CREATE TABLE" in migration.upper():
        errors.append("P33 migration must extend the four governed snapshot tables instead of adding a parallel model")
    projection = read("finance/data/src/main/kotlin/app/ledger/finance/data/RoomProjectionEngine.kt")
    require_tokens(errors, projection, "commit-built widget projections", (
        '"widget_account_snapshot", "widget_book_snapshot"', "INSERT INTO widget_book_snapshot", "INSERT INTO widget_account_snapshot",
        "INSERT INTO widget_credit_snapshot", "INSERT INTO widget_goal_snapshot", "asOfLocalDate",
        "fun rebuildWidgetSnapshot", "previous_core", "previous_month_consumption_base_minor",
    ))

    manifest = read("widget/src/main/AndroidManifest.xml")
    info = read("widget/src/main/res/xml/ledger_widget_info.xml")
    require_tokens(errors, manifest + info, "Glance provider and system configuration Activity", (
        "LedgerGlanceWidgetReceiver", "android.appwidget.action.APPWIDGET_UPDATE", "android.permission.BIND_APPWIDGET",
        "WidgetConfigurationActivity", "android.appwidget.action.APPWIDGET_CONFIGURE", "@xml/ledger_widget_info",
    ))

    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in ("widget/src/test", "widget/src/androidTest", "finance/data/src/androidTest", "core/database/src/androidTest", "app/src/androidTest", "transfer/data/src/androidTest")
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(errors, tests, "P33 automated evidence", (
        "exactlyNineFrozenTypesResolveAgainstOnlyBoundedSnapshots",
        "amountsAreHiddenUnlessEachConfigurationExplicitlyOptsIn",
        "datedSnapshotsExpireButQuickEntryStillOnlyOpensTheForm",
        "keyUnavailabilityLocksWidgetAndNoApplicationLockStateIsConsulted",
        "wgt001ThroughWgt003ConfigureEligibleDataAndDefaultToHiddenAmounts",
        "wgt002NoEligibleDataReturnsToTypeSelection",
        "widgetConfigurationRendersSimplifiedChineseJapaneseAndEnglishResources",
        "glanceReadUsesOnlyBoundedEncryptedSnapshotRowsWithoutSensitiveColumns",
        "refreshIfStale(BOOK_ID, LocalDate.of(2026, 8, 12))",
        "allP33GlobalTransferSettingsAndNotificationStatesRender",
        "moreTransferSettingsHelpAndPermissionUseAllThreeLocales",
        "durableOperationCenterListsNewestEncryptedOperationsWithoutParameters",
        "everyEncryptedPredecessorMigratesToVersionFiveWithFinancialAndQueryContractsIntact",
    ))
    for module in ("app", "widget", "feature/settings", "feature/transfer"):
        localized: list[set[str]] = []
        for folder in ("values", "values-en", "values-ja"):
            files = sorted((ROOT / module / "src/main/res" / folder).glob("*.xml"))
            localized.append(set().union(*(
                set(re.findall(r'<string name="([^"]+)"', path.read_text(encoding="utf-8"))) for path in files
            )))
        if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
            errors.append(f"{module} strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    decision = read("docs/implementation/DECISION_LOG.md")
    mapping_path = ROOT / "docs/implementation/P33_WIDGET_MORE_SETTINGS_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P33 | VERIFIED |"))
    for index in range(1, 9):
        if f"P33-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P33-E{index:03d}")
    require_tokens(errors, mapping, "P33 mapping", (
        "nine", "SQLCipher", "Glance", "operationId", "three languages", "P33 is `VERIFIED`",
    ))
    require_tokens(errors, decision, "P33 decision log", ("P33", "systemActivity", "Glance"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P33" not in row.get("implementation_evidence", "") or "P33-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P33 evidence")
    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in ("REQ-001", "REQ-070", "REQ-072"):
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P33" not in row.get("implementation_evidence", "") or "P33-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} lacks truthful P33 evidence")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-ledgers", action="store_true")
    args = parser.parse_args()
    errors = validate_contract() + validate_sources() + validate_schema_resources_tests()
    if not args.skip_ledgers:
        errors += validate_ledgers()
    if errors:
        print("P33 widget/navigation validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P33 widget/navigation validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

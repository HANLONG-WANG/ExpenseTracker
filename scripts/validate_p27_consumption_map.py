#!/usr/bin/env python3
"""Reject P27 map-query, MapLibre, privacy, accessibility, route or evidence drift."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "ANA-011": ("analysis/map", [], {"loading", "clusters", "heatmap", "singlePoints", "noLocationData", "mapUnavailable"}),
    "ANA-012": ("analysis/map/location/{placeOrClusterId}", ["placeOrClusterId:StableId"], {"place", "cluster", "singleTransaction"}),
}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def source_map() -> dict[str, str]:
    roots = (
        "analytics/domain/src/main/kotlin", "analytics/data/src/main/kotlin", "app/src/main/kotlin",
        "core/designsystem/src/main/kotlin", "core/geo/src/main/kotlin", "feature/analysis/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).rglob("*.kt"))
    }


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
    if sum(len(item[2]) for item in EXPECTED.values()) != 9:
        errors.append("P27 required-state baseline must remain exactly 9")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = source_map() if sources is None else sources
    errors: list[str] = []
    required = {
        "ConsumptionMap.kt", "ConsumptionMapStore.kt", "SecureRoomAnalyticsApplicationPort.kt", "LedgerMap.kt",
        "ChartsMapsAndRiskComponents.kt", "P27AnalysisScreens.kt", "AnalysisState.kt", "AnalysisController.kt",
        "ConsumptionMapController.kt", "ConsumptionMapFilterRemoval.kt", "AnalysisRootDestination.kt", "AppRootViewModel.kt",
    }
    selected = {path: source for path, source in sources.items() if Path(path).name in required}
    missing = required - {Path(path).name for path in selected}
    if missing:
        errors.append(f"P27 production files missing: {sorted(missing)}")
    for path, source in selected.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder in {path}")

    domain = next((value for path, value in sources.items() if path.endswith("ConsumptionMap.kt")), "")
    require_tokens(errors, domain, "typed bounded map domain", (
        "CONSUMPTION", "ALL_EXPENSES", "CASH_FLOW", "ALL_LOCATED_TRANSACTIONS",
        "BASE_AMOUNT", "TRANSACTION_COUNT", "MERCHANT", "PLACE", "CLUSTERS", "HEATMAP", "SINGLE_POINTS",
        "SPECIAL_TRANSACTION_KINDS", "TransactionKind.TRANSFER", "TransactionKind.CREDIT_PAYMENT",
        "TransactionKind.LOAN_DISBURSEMENT", "TransactionKind.LOAN_PAYMENT", "MAX_RENDERED_POINTS: Int = 512",
        "MapViewport", "crossesDateLine", "minimumBaseAmountMinor", "maximumBaseAmountMinor",
        "ConsumptionMapFilterOptions", "MAX_OPTIONS_PER_DIMENSION: Int = 200",
    ))
    if re.search(r"\b(?:Double|Float)\b", domain):
        errors.append("map domain exposes floating-point authoritative coordinates or amounts")

    store = next((value for path, value in sources.items() if path.endswith("ConsumptionMapStore.kt")), "")
    adapter = next((value for path, value in sources.items() if path.endswith("SecureRoomAnalyticsApplicationPort.kt")), "")
    require_tokens(errors, store + adapter, "RTree candidate plus exact filtered aggregation", (
        "JOIN location_record", "JOIN transaction_revision", "JOIN current_transaction_projection",
        "economic_effect", "posting", "includedKinds", "accountIds", "categoryIds", "merchantIds", "placeIds",
        "projectIds", "minimumBaseAmountMinor", "maximumBaseAmountMinor", "MAX_RENDERED_POINTS + 1",
        "asOfLocalRevision", "ExpiredDrilldown", "passphrase.fill(0)", "lr.lat_e7 BETWEEN", "lr.lon_e7 BETWEEN",
        "consumptionMapFilterOptions", "SELECT uid,name FROM user_account", "SELECT uid,name FROM category",
    ))
    if "FROM location_rtree" not in store:
        errors.append("consumption map query does not select viewport candidates from location_rtree")
    if re.search(r"\bOFFSET\b", store):
        errors.append("map query contains forbidden deep OFFSET")
    if re.search(r"SELECT\s+\*\s+FROM\s+(?:location_record|current_transaction_projection)(?![^\n]*LIMIT)", store, re.IGNORECASE):
        errors.append("map query may load every raw location/transaction row")

    geo = next((value for path, value in sources.items() if path.endswith("LedgerMap.kt")), "")
    design = next((value for path, value in sources.items() if path.endswith("ChartsMapsAndRiskComponents.kt")), "")
    require_tokens(errors, geo + design, "governed MapLibre and sequential-teal rendering", (
        "org.maplibre.android", "AndroidView", "val OpenFreeMap", "isAttributionEnabled = true",
        "GeoJsonOptions().withCluster", "HeatmapLayer", "SINGLE_POINTS", "onViewportChanged", "onPointSelected",
        "ledger-user-location-source", "sequentialTeal", "userLocationColor", "clusterLowDiameter", "selectedPointDiameter",
        "AccessibleDataTable", "MAP_FALLBACK",
    ))
    if re.search(r"\b(?:Geocoder|PlacesClient|SearchBox|reverseGeocod|geocod)\b", "\n".join(sources.values()), re.IGNORECASE):
        errors.append("P27 introduces online place search or reverse geocoding")

    feature = "\n".join(value for path, value in sources.items() if path.startswith("feature/analysis/"))
    require_tokens(errors, feature, "P27 screen and accessibility states", (
        '"ANA-011"', '"ANA-012"', "CLUSTERS,", "HEATMAP,", "SINGLE_POINTS,",
        "AnalysisPresentation.NO_LOCATION_DATA", "AnalysisPresentation.MAP_UNAVAILABLE", "PLACE,", "CLUSTER,", "SINGLE_TRANSACTION,",
        "AccessibleDataTable", "CONSUMPTION_MAP_LOCATION", "clearAndSetSemantics", "analysis_map_historical_fx",
        "analysis_map_list_alternative", "onResetMapFilters", "onCycleMapAccountFilter",
        "onCycleMapCategoryFilter", "onCycleMapMerchantFilter", "onCycleMapPlaceFilter",
        "onCycleMapProjectFilter", "onCycleMapAmountFilter", "onRemoveMapFilter", '"$keyPrefix:$id"',
        "analysis_map_filter_any_count", "analysis_map_complete_filters",
    ))
    if re.search(r"^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:analytics\.data|core\.database|core\.geo))", feature, re.MULTILINE):
        errors.append("P27 feature bypasses application/design/map ownership boundaries")
    if re.search(r"\b(?:MaterialTheme|Color\s*\(|SwipeToDismiss)\b|\b\d+(?:\.\d+)?\.dp\b", feature):
        errors.append("P27 feature bypasses frozen design tokens or interaction governance")

    root = next((value for path, value in sources.items() if path.endswith("AnalysisRootDestination.kt")), "")
    navigation = next((value for path, value in sources.items() if path.endswith("AppRootViewModel.kt")), "")
    require_tokens(errors, root + navigation, "safe map route and app-owned SDK slot", (
        'encodedArguments["placeOrClusterId"]', "StableIdArgument(pointId)", "ConsumptionMapHost",
        "LedgerMapStyleConfiguration.OpenFreeMap", "MapViewport(",
    ))
    controller = next((value for path, value in sources.items() if path.endswith("AnalysisController.kt")), "")
    removal = next((value for path, value in sources.items() if path.endswith("ConsumptionMapFilterRemoval.kt")), "")
    require_tokens(errors, controller + removal, "same-dimension OR and independently removable map filters", (
        "ConsumptionMapFilterSelection", "current + candidate", "removeMapFilter", 'startsWith("account:")',
        'startsWith("category:")', 'startsWith("merchant:")', 'startsWith("place:")',
        'startsWith("project:")', 'stableKey == "amount-minimum"', 'stableKey == "special-transactions"',
    ))
    if re.search(r'encodedArguments\["(?:latitude|longitude|coordinate|amount|merchant|placeName|location|transactions)"\]', root, re.IGNORECASE):
        errors.append("P27 route carries coordinates, finance data, names, or complete results")
    return errors


def validate_tests_resources() -> list[str]:
    errors: list[str] = []
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in (
            "analytics/domain/src/test", "analytics/data/src/androidTest", "app/src/test", "core/geo/src/test",
            "core/geo/src/androidTest", "feature/analysis/src/androidTest",
        )
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    require_tokens(errors, tests, "P27 automated evidence", (
        "consumptionMapUsesRTreeFrozenBaseAmountsDefaultExclusionsAndOpaqueDrilldown",
        "tenThousandLocatedTransactionsRemainDatabaseAggregatedViewportBoundedAndNodeBounded",
        "value<10000", "MAX_RENDERED_POINTS", "actualMapLibreLifecycleRendersAllOverlayModesWithAttributionAndSanitizedSemantics",
        "assertEquals(45, cases.size)", '"ANA-011" to setOf("loading", "clusters", "heatmap", "singlePoints", "noLocationData", "mapUnavailable")',
        '"ANA-012" to setOf("place", "cluster", "singleTransaction")', "P27GoldenDeviceTest",
        "consumptionMapFilterOptions", 'listOf("Account 1", "Account 2")',
        "eachChipRemovesOnlyItsTypedConditionAndSpecialResetRestoresSafeDefault",
        "mapFilterBuilderExposesSameDimensionOrAsIndependentlyRemovableChips",
    ))
    localized = []
    for folder in ("values", "values-en", "values-ja"):
        names = set(re.findall(r'<string name="([^"]+)"', read(f"feature/analysis/src/main/res/{folder}/strings.xml")))
        localized.append({name for name in names if name.startswith("analysis_map_")})
    if not localized[0] or localized[0] != localized[1] or localized[0] != localized[2]:
        errors.append("P27 map strings are incomplete across zh-CN/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P27_CONSUMPTION_MAP_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P36", "| P27 | VERIFIED |"))
    for index in range(1, 8):
        if f"P27-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P27-E{index:03d}")
    require_tokens(errors, mapping, "P27 mapping", ("10,000", "RTree", "sequential teal", "9 required states", "P27 is `VERIFIED`"))
    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P27" not in row.get("implementation_evidence", "") or "P27-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} lacks VERIFIED P27 evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_resources() + validate_ledgers()
    if errors:
        print("P27 consumption map validation: FAIL", file=sys.stderr)
        for item in errors:
            print(f"- {item}", file=sys.stderr)
        return 1
    print("P27 consumption map validation: PASS")
    print("screens=2 required_states=9 rtree=viewport-candidate max_nodes=512 maplibre=cluster+heat+single fallback=accessible-list")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

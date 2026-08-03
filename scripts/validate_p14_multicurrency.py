#!/usr/bin/env python3
"""Reject drift or a false VERIFIED promotion of the frozen P14 scope."""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_STATES = {
    "REC-013": {"editing", "sameAccountError", "fxRequired", "saving"},
    "REC-020": {"editing", "saving"},
    "REC-021": {"editing", "sameCurrencyInfo", "rateMismatch", "saving"},
    "REC-022": {"editing", "immutableCurrency", "saving"},
    "SETG-004": {"content", "searching"},
}
TARGET_REQUIREMENTS = {"REQ-014", "REQ-015", "REQ-017", "REQ-029", "REQ-033"}
PLACEHOLDER = re.compile(r"\b(?:TODO|NotImplemented|UnsupportedOperationException)\b")
FORBIDDEN_FEATURE_IMPORT = re.compile(
    r"(?m)^import\s+(?:androidx\.room|androidx\.compose\.material3|app\.ledger\.(?:finance\.data|core\.(?:database|network|security)))"
)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require_tokens(errors: list[str], text: str, label: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing {token}")


def load_sources() -> dict[str, str]:
    roots = (
        "app/src/main/kotlin",
        "core/designsystem/src/main/kotlin",
        "core/network/src/main/kotlin",
        "feature/accounts/src/main/kotlin",
        "feature/record/src/main/kotlin",
        "feature/settings/src/main/kotlin",
        "finance/application/src/main/kotlin",
        "finance/data/src/main/kotlin",
    )
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for root in roots
        for path in sorted((ROOT / root).rglob("*.kt"))
    }


def validate_contract() -> list[str]:
    contract = yaml.safe_load(read("docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml"))
    actual = {screen["id"]: set(screen.get("requiredStates", [])) for screen in contract["screens"]}
    errors: list[str] = []
    if sum(map(len, EXPECTED_STATES.values())) != 15:
        errors.append("P14 state oracle must contain exactly five screens and 15 states")
    for screen_id, expected in EXPECTED_STATES.items():
        if actual.get(screen_id) != expected:
            errors.append(f"{screen_id} requiredStates drift: {sorted(actual.get(screen_id, set()))}")
    return errors


def validate_sources(sources: dict[str, str] | None = None) -> list[str]:
    sources = load_sources() if sources is None else sources
    errors: list[str] = []
    required = {
        "SpecializedTransactionEntry.kt",
        "SecureRoomSpecializedTransactionEntryPort.kt",
        "FxQuoteNetworkClient.kt",
        "SpecializedTransactionState.kt",
        "SpecializedTransactionScreens.kt",
        "CurrencySettingsScreen.kt",
    }
    missing = required - {Path(path).name for path in sources}
    if missing:
        errors.append(f"P14 production files missing: {sorted(missing)}")
    for path, source in sources.items():
        if PLACEHOLDER.search(source):
            errors.append(f"placeholder production implementation in {path}")

    feature = "\n".join(
        source for path, source in sources.items() if path.startswith(("feature/record/", "feature/settings/"))
    )
    if FORBIDDEN_FEATURE_IMPORT.search(feature) or re.search(r"\b(?:Dao|Entity|execSQL|JournalEntry|Posting)\b", feature):
        errors.append("P14 feature bypasses governed UI/application boundaries")
    require_tokens(
        errors,
        feature,
        "P14 governed UI",
        (
            "SpecializedTransactionKind.TRANSFER",
            "SpecializedTransactionKind.BALANCE_ADJUSTMENT",
            "SpecializedTransactionKind.FX_EXCHANGE",
            "SpecializedTransactionKind.OPENING_BALANCE",
            "MoneyExpressionField",
            "FxEvidenceSection",
            "EFFECTIVE_RATE_SUMMARY",
            "FX_COST_SECTION",
            "CurrencySettingsPolicy",
        ),
    )

    record_grid = next((source for path, source in sources.items() if path.endswith("OrdinaryRecordScreens.kt")), "")
    require_tokens(errors, record_grid, "other-transaction routes", ('"REC-013"', '"REC-020"', '"REC-021"'))
    if '"TRF-001" to R.string.record_other_transfer' in record_grid:
        errors.append("internal transfer is routed to the data-transfer screen")

    application = next((source for path, source in sources.items() if path.endswith("SpecializedTransactionEntry.kt")), "")
    require_tokens(
        errors,
        application,
        "typed P14 application boundary",
        (
            "SpecializedAccountAmountDraft",
            "require(outgoing.accountId != incoming.accountId)",
            "require(outgoing.baseMinor == incoming.baseMinor)",
            "SpecializedTransactionEntryPort",
        ),
    )
    data = next((source for path, source in sources.items() if path.endswith("SecureRoomSpecializedTransactionEntryPort.kt")), "")
    require_tokens(
        errors,
        data,
        "encrypted P14 adapter",
        (
            "DefaultFinancialMutationCoordinator",
            "RecordTransferCommand",
            "RecordBalanceAdjustmentCommand",
            "RecordFxExchangeCommand",
            "RecordOpeningBalanceCommand",
            "FxRateSource.HISTORICAL_FALLBACK",
            "UPDATE book SET valuation_revision=?",
            "request.effectiveDate == online.quote.fetchedAt.atZone(ZoneOffset.UTC).toLocalDate()",
            "EncryptedDatabaseFactory.openPrimary",
        ),
    )
    if re.search(r"UPDATE\s+book\s+SET\s+local_revision", data, re.IGNORECASE):
        errors.append("current FX refresh must not advance book.localRevision")
    if re.search(r"UPDATE\s+account_balance_checkpoint", data, re.IGNORECASE):
        errors.append("P14 must not mutate immutable checkpoint facts")
    if re.search(r"\b(?:Float|Double)\b", application + data):
        errors.append("P14 authoritative amount/rate path contains floating point")

    network = next((source for path, source in sources.items() if path.endswith("FxQuoteNetworkClient.kt")), "")
    require_tokens(
        errors,
        network,
        "FX network boundary",
        (
            "sourceCode: String",
            "targetCode: String",
            "date: LocalDate?",
            "retryOnConnectionFailure(false)",
            "callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
            "CALL_TIMEOUT_SECONDS = 15L",
        ),
    )
    request_body = network.partition("public data class NetworkFxQuoteRequest(")[2].partition(") {")[0]
    if re.search(r"amount|account|book|note|name|location|attachment", request_body, re.IGNORECASE):
        errors.append("FX network request carries ledger or sensitive data")

    app = next((source for path, source in sources.items() if path.endswith("AppRootViewModel.kt")), "")
    require_tokens(
        errors,
        app,
        "P14 application integration",
        (
            "mutableSpecializedTransactionPending",
            "SpecializedTransactionWriteRequest.Transfer",
            "SpecializedTransactionWriteRequest.BalanceAdjustment",
            "SpecializedTransactionWriteRequest.FxExchange",
            "SpecializedTransactionWriteRequest.OpeningBalance",
        ),
    )
    if re.search(r"(?m)^import\s+app\.ledger\.finance\.domain\.(?:Journal|Posting)", app):
        errors.append("AppRootViewModel must not construct Journal or Posting")
    return errors


def validate_tests_and_resources() -> list[str]:
    tests = "\n".join(
        path.read_text(encoding="utf-8")
        for root in (
            "core/network/src/test",
            "feature/record/src/test",
            "feature/record/src/androidTest",
            "feature/settings/src/test",
            "feature/settings/src/androidTest",
            "finance/data/src/androidTest",
        )
        for path in sorted((ROOT / root).rglob("*.kt"))
    )
    errors: list[str] = []
    require_tokens(
        errors,
        tests,
        "P14 automated evidence",
        (
            "allThirteenRequiredStatesRenderAcrossWidthsFontsLocalesAndThemes",
            "fxExchangeExposesEffectiveRateAndCostSemantics",
            "transferAdjustmentExchangeAndOpeningGoldensMatchEveryPixel",
            "quotesAndAllSpecializedTransactionsRemainAtomicBalancedAndRevisionSeparated",
            "contentAndSearchingStatesRenderFromLegalTenderCatalog",
            "request sends only pair and date and parses exact decimal evidence",
            "assertEquals(0L, scalar(\"SELECT COUNT(*) FROM journal_entry WHERE base_debit_total_minor<>base_credit_total_minor\"))",
        ),
    )
    golden_dir = ROOT / "feature/record/src/androidTest/assets/goldens"
    goldens = sorted(golden_dir.glob("p14_*.png")) if golden_dir.is_dir() else []
    if len(goldens) != 4 or any(path.stat().st_size < 1_000 for path in goldens):
        errors.append("exactly four non-empty P14 Compose/token goldens are required")

    for module, prefix in (("feature/record", "specialized_"), ("feature/settings", "currency_settings_")):
        resource_sets = []
        for relative in ("values/strings.xml", "values-en/strings.xml", "values-ja/strings.xml"):
            text = read(f"{module}/src/main/res/{relative}")
            resource_sets.append({key for key in re.findall(r'<string name="([^"]+)"', text) if key.startswith(prefix)})
        if resource_sets[0] != resource_sets[1] or resource_sets[0] != resource_sets[2]:
            errors.append(f"{module} P14 strings are incomplete across zh/en/ja")
    return errors


def validate_ledgers() -> list[str]:
    errors: list[str] = []
    state = read("docs/implementation/PROJECT_STATE.md")
    evidence = read("docs/implementation/TEST_EVIDENCE.md")
    mapping_path = ROOT / "docs/implementation/P14_MULTICURRENCY_MAPPING.md"
    mapping = mapping_path.read_text(encoding="utf-8") if mapping_path.is_file() else ""
    require_tokens(errors, state, "PROJECT_STATE", ("Current stage: P14", "Stage status: VERIFIED"))
    for index in range(1, 9):
        if f"P14-E{index:03d}" not in evidence:
            errors.append(f"TEST_EVIDENCE missing P14-E{index:03d}")
    require_tokens(
        errors,
        mapping,
        "P14 mapping",
        ("15 required states", "FinancialMutationCoordinator", "valuationRevision", "P14 is `VERIFIED`"),
    )

    with (ROOT / "docs/implementation/SCREEN_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        screens = {row["screen_id"]: row for row in csv.DictReader(handle)}
    for screen_id in EXPECTED_STATES:
        row = screens.get(screen_id, {})
        if row.get("status") != "VERIFIED" or "P14" not in row.get("implementation_evidence", "") or "P14-E" not in row.get("verification_evidence", ""):
            errors.append(f"{screen_id} must carry VERIFIED P14 implementation and test evidence")

    with (ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv").open(encoding="utf-8", newline="") as handle:
        requirements = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in TARGET_REQUIREMENTS:
        row = requirements.get(requirement_id, {})
        if row.get("status") not in {"IN_PROGRESS", "VERIFIED"} or "P14" not in row.get("implementation_evidence", "") or "P14-E" not in row.get("verification_evidence", ""):
            errors.append(f"{requirement_id} must carry truthful P14 implementation and test evidence")
    return errors


def main() -> int:
    errors = validate_contract() + validate_sources() + validate_tests_and_resources() + validate_ledgers()
    if errors:
        print("P14 multicurrency validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("P14 multicurrency validation: PASS")
    print("screens=5 required_states=15 goldens=4 visual_inputs=contract_token_yaml_only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# Domain and Schema Coverage Baseline

Last updated: 2026-08-01 (Asia/Tokyo)
Stage: P01
Status meaning: `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED`, `VERIFIED`, `BLOCKED`. P01 promotes only architecture boundaries proven by recorded build evidence; no domain, schema, accounting, security or feature behavior is claimed.

## Architecture decisions

Source: `docs/规格冻结_v1.0/系统架构.md` §22, except ADR-007A from `docs/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md` §1.

| ID | Frozen decision | Status | Evidence required to reach VERIFIED |
|---|---|---|---|
| ADR-001 | Coarse-grained multi-module modular monolith | VERIFIED (`P01-E003`, `P01-E004`) | Dependency graph and architecture tests |
| ADR-002 | SQLCipher primary database is the ledger's sole source of truth | NOT_STARTED | Device database and offline-first integration tests |
| ADR-003 | Current state + immutable revisions + immutable financial log | NOT_STARTED | Domain and database contract tests |
| ADR-004 | No full event sourcing | NOT_STARTED | Schema/API inspection |
| ADR-005 | Lightweight CQRS separates writes from query projections | NOT_STARTED | Module/API and projection tests |
| ADR-006 | Every financial write passes through `FinancialMutationCoordinator` | NOT_STARTED | Static call-site rule and integration tests |
| ADR-007 | Journal and Posting are append-only in ordinary operations | NOT_STARTED | DAO constraints and mutation tests |
| ADR-007A | Controlled privacy purge is the sole physical-delete exception and only applies to a fully reversed, closed transaction chain | NOT_STARTED | Purge eligibility, maintenance-lock, tombstone and merge tests |
| ADR-008 | Editing reverses old effects and appends replacements | NOT_STARTED | Property and integration tests |
| ADR-009 | Core financial projections update in the same database transaction | NOT_STARTED | Failure-injection and rollback tests |
| ADR-010 | Single process and single write gate | NOT_STARTED | Manifest/process and concurrency tests |
| ADR-011 | Network data is never authoritative ledger data | NOT_STARTED | Offline and adapter tests |
| ADR-012 | Large imports use an encrypted staging or shadow database | NOT_STARTED | Device large-import and rollback tests |
| ADR-013 | Restore validates in a shadow directory before atomic exchange | NOT_STARTED | Device fault-injection tests |
| ADR-014 | Managed backups are logically full and physically incremental | NOT_STARTED | Repository retention/deduplication tests |
| ADR-015 | Same-book merge uses stable IDs and a commit graph | NOT_STARTED | Three-way merge and conflict tests |
| ADR-016 | Ledger, vault and recovery-password key hierarchies are separate | NOT_STARTED | Keystore/Tink device security tests |
| ADR-017 | App lock is UI access control; vault uses a cryptographic authentication gate | NOT_STARTED | Biometric/device-credential tests |
| ADR-018 | WorkManager carries only opaque operation IDs | NOT_STARTED | Static InputData privacy audit |
| ADR-019 | Reports use a typed AST and never accept user SQL | NOT_STARTED | Compiler whitelist/security tests |
| ADR-020 | The domain model enforces the no-split-transaction limitation | NOT_STARTED | Domain/import/report contract tests |

The 12 UI-derived decisions from UI contract §18 are separately registered below. They remain `NOT_STARTED`; implementation evidence belongs to P04 and screen-level verification belongs to later feature phases.

| ID | Frozen UI decision | Status |
|---|---|---|
| UI-ADR-001 | Every top-level page has the same top-right More Features entry. | NOT_STARTED |
| UI-ADR-002 | Financial records do not use swipe-to-delete. | NOT_STARTED |
| UI-ADR-003 | Category hierarchy uses first-level groups and the same selectable tile component. | NOT_STARTED |
| UI-ADR-004 | Unsaved forms are discarded with explanation after process death because sensitive SavedState and drafts are prohibited. | NOT_STARTED |
| UI-ADR-005 | Invalid ordinary forms keep Save actionable so validation can explain errors; only absolute prerequisites disable it. | NOT_STARTED |
| UI-ADR-006 | Long operations share one Operation Center. | NOT_STARTED |
| UI-ADR-007 | Transaction lists have no swipe quick-edit/delete gesture. | NOT_STARTED |
| UI-ADR-008 | Map failure provides a list alternative. | NOT_STARTED |
| UI-ADR-009 | Settlement suggestions may be displayed only when returned by a domain/application query service; UI never writes its own calculation. | NOT_STARTED |
| UI-ADR-010 | Pie charts automatically change to bars above six categories. | NOT_STARTED |
| UI-ADR-011 | Top-level pages use a small fixed app bar, not a collapsing large title. | NOT_STARTED |
| UI-ADR-012 | Token JSON is the machine-readable source of concrete visual values. | NOT_STARTED |

## Permanent invariants

Source: `docs/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md` §32. These 35 rows are the canonical invariant checklist.

| ID | Invariant | Primary verification class | Status |
|---|---|---|---|
| INV-001 | Every Journal Entry has equal base-currency debits and credits. | Domain property + database audit + restore validation | NOT_STARTED |
| INV-002 | Every Posting currency matches its LedgerAccount currency. | Planner property + database audit | NOT_STARTED |
| INV-003 | Every formal transaction has at most one category, project and goal. | Type/domain validation + import tests | NOT_STARTED |
| INV-004 | Ordinary expense/income has one Primary amount and no category split. | Domain property + UI/import contract | NOT_STARTED |
| INV-005 | Every current transaction references one complete, self-consistent current revision. | Database integrity audit | NOT_STARTED |
| INV-006 | Old revisions, Journals and Effects are not changed during ordinary operations. | DAO/static rule + mutation tests | NOT_STARTED |
| INV-007 | Each APPLY Entry is reversed at most once. | Unique constraint + property tests | NOT_STARTED |
| INV-008 | An Active transaction's net APPLY chain yields exactly one current financial effect. | Domain/database audit | NOT_STARTED |
| INV-009 | A Trashed transaction has zero current net financial effect. | Domain/database audit | NOT_STARTED |
| INV-010 | Refunds cannot exceed the refundable balance without explicit override. | Domain property + UI tests | NOT_STARTED |
| INV-011 | Refund cash-flow date, accrual date and budget month may differ. | Cross-month integration tests | NOT_STARTED |
| INV-012 | Credit-card repayment creates neither expense nor income. | Planner property + report tests | NOT_STARTED |
| INV-013 | Loan principal repayment creates no expense. | Planner property + report tests | NOT_STARTED |
| INV-014 | Loan interest, fees and penalties create non-consumption expense. | Planner property + report tests | NOT_STARTED |
| INV-015 | Internal transfer does not change net financial assets. | Planner property + projection tests | NOT_STARTED |
| INV-016 | Current FX rates cannot change historical base-currency amounts. | Historical-regression tests | NOT_STARTED |
| INV-017 | Current FX revaluation is not income or expense. | Projection/report tests | NOT_STARTED |
| INV-018 | First-level category budget total cannot exceed total budget. | Domain property + UI tests | NOT_STARTED |
| INV-019 | Second-level category budget total cannot exceed its parent budget. | Domain property + UI tests | NOT_STARTED |
| INV-020 | Rollover chains rebuild from transaction effects, adjustments and prior-month rollover. | Property + projection rebuild tests | NOT_STARTED |
| INV-021 | Goal balance does not alter real account balance. | Planner/projection tests | NOT_STARTED |
| INV-022 | Settlement position deltas sum to zero across participants. | Domain property + database audit | NOT_STARTED |
| INV-023 | External-participant payment cannot alter the local user's account. | Planner property + UI integration | NOT_STARTED |
| INV-024 | Editing a settled activity transaction does not rewrite historical settlement payments. | Revision/integration tests | NOT_STARTED |
| INV-025 | Loan-schedule principal total equals principal still to be repaid. | Property tests | NOT_STARTED |
| INV-026 | Installment-schedule principal total equals installment principal. | Property tests | NOT_STARTED |
| INV-027 | The recurrence occurrence unique key prevents duplicate generation. | Concurrency/idempotency tests | NOT_STARTED |
| INV-028 | Candidate records create no formal financial effects. | Domain/database/report tests | NOT_STARTED |
| INV-029 | Category/account/card tombstones or archives preserve historical references. | Migration/history tests | NOT_STARTED |
| INV-030 | Purge tombstones win over old entity versions during merge restore. | Merge integration tests | NOT_STARTED |
| INV-031 | Every core projection aligns to the same `localRevision`. | Atomicity/failure-injection audit | NOT_STARTED |
| INV-032 | Vault fields never enter FTS, audit snapshots, logs or telemetry. | Static/privacy/device audit | NOT_STARTED |
| INV-033 | Failed import, restore or large batch leaves the main ledger unchanged. | Shadow-DB fault injection | NOT_STARTED |
| INV-034 | Every monetary accumulation detects `Long` overflow. | Boundary/property/static tests | NOT_STARTED |
| INV-035 | Every cache depending on current transaction content carries a version. | Architecture/cache invalidation tests | NOT_STARTED |

The 16 product-level system invariants in `需求.md` §26 remain additional acceptance constraints. They are covered by REQ rows and the architecture/security/operation gates; they do not replace the 35 canonical permanent invariants above.

## Logical schema families

Source: domain/schema document §25. The inventory names every logical table, including the 11 typed transaction-detail tables. All families are `NOT_STARTED`.

| ID | Family | Logical tables | Status |
|---|---|---|---|
| SCHEMA-FAMILY-01 | Book, commit and audit | `book`, `book_commit`, `book_commit_parent`, `command_receipt`, `entity_change`, `entity_revision`, `purge_tombstone` | NOT_STARTED |
| SCHEMA-FAMILY-02 | Accounts and cards | `ledger_account`, `user_account`, `payment_card`, `card_vault_secret`, `account_balance_checkpoint` | NOT_STARTED |
| SCHEMA-FAMILY-03 | Classification, merchants and places | `category`, `merchant`, `merchant_alias`, `place`, `location_record`, `location_rtree`, `place_rtree` | NOT_STARTED |
| SCHEMA-FAMILY-04 | Transactions and revisions | `business_transaction`, `transaction_revision`, `revision_amount`, `fx_rate_snapshot`, `expense_revision_detail`, `income_revision_detail`, `transfer_revision_detail`, `refund_revision_detail`, `credit_payment_revision_detail`, `loan_disbursement_revision_detail`, `loan_payment_revision_detail`, `balance_adjustment_revision_detail`, `fx_exchange_revision_detail`, `settlement_payment_revision_detail`, `opening_balance_revision_detail`, `transaction_revision_attachment`, `transaction_revision_settlement_share`, `transaction_dependency` | NOT_STARTED |
| SCHEMA-FAMILY-05 | Accounting facts | `journal_entry`, `posting`, `economic_effect`, `budget_effect`, `project_effect`, `goal_effect`, `statement_effect`, `loan_effect`, `settlement_effect` | NOT_STARTED |
| SCHEMA-FAMILY-06 | Refunds | `refund_allocation`, `refund_status_projection` | NOT_STARTED |
| SCHEMA-FAMILY-07 | Credit and installments | `credit_account_profile`, `credit_limit_period`, `credit_statement`, `credit_statement_revision`, `credit_payment_allocation`, `installment_plan`, `installment_plan_revision`, `installment_schedule_revision`, `installment_schedule_item`, `installment_refund_allocation` | NOT_STARTED |
| SCHEMA-FAMILY-08 | Loans | `loan_contract`, `loan_tranche`, `loan_terms_revision`, `loan_rate_period`, `loan_schedule_revision`, `loan_schedule_item`, `loan_actual_allocation`, `loan_simulation`, `loan_simulation_item` | NOT_STARTED |
| SCHEMA-FAMILY-09 | Settlement | `participant`, `settlement_activity`, `settlement_activity_participant`, `settlement_payment_record` | NOT_STARTED |
| SCHEMA-FAMILY-10 | Budget, project and goal | `project`, `goal`, `goal_movement`, `budget_template`, `budget_template_revision`, `budget_template_category_limit`, `budget_month`, `budget_month_revision`, `budget_category_limit`, `budget_adjustment`, `budget_rollover` | NOT_STARTED |
| SCHEMA-FAMILY-11 | Templates and recurrence | `transaction_blueprint`, `transaction_blueprint_revision`, `blueprint_settlement_share_rule`, `recurrence_series`, `recurrence_series_revision`, `recurrence_rule_weekday`, `recurrence_exception`, `recurrence_occurrence`, `recurrence_candidate` | NOT_STARTED |
| SCHEMA-FAMILY-12 | Attachments | `encrypted_blob`, `attachment`, `blob_gc_candidate` | NOT_STARTED |

Implementation rules from §33: export Room Schema JSON from v1; no destructive migration; independently version main DB, import staging DB and backup manifest; preserve `rule_set_version`; use Expand → Backfill → Switch → Contract; never reinterpret historical facts; run SQLCipher, integrity, FK, journal-balance, subtype, projection, FTS/R*Tree, scale and old-backup validation after migration.

## Projection and index families

Source: domain/schema document §§26–27. Every core projection stores `as_of_local_revision`; current-valuation projections additionally store `as_of_valuation_revision`.

| ID | Family | Members | Status |
|---|---|---|---|
| PROJECTION-FAMILY-01 | Current transaction | `current_transaction_projection` | NOT_STARTED |
| PROJECTION-FAMILY-02 | Account | `account_balance_current`, `account_valuation_current`, `account_balance_daily` | NOT_STARTED |
| PROJECTION-FAMILY-03 | Budget and planning | `budget_usage_projection`, `budget_future_reservation`, `project_usage_projection`, `goal_balance_projection`, `budget_rollover`, `refund_status_projection` | NOT_STARTED |
| PROJECTION-FAMILY-04 | Liabilities | `credit_statement_projection`, `credit_account_projection`, `installment_progress_projection`, `loan_progress_projection`, `loan_future_cashflow_projection`, `loan_simulation_item` | NOT_STARTED |
| PROJECTION-FAMILY-05 | Settlement | `settlement_position_projection` | NOT_STARTED |
| PROJECTION-FAMILY-06 | Analytics | `analytics_daily_total`, `analytics_daily_category`, `analytics_daily_account`, `analytics_daily_merchant`, `analytics_daily_project`, `analytics_daily_place` and corresponding `analytics_monthly_*` tables | NOT_STARTED |
| PROJECTION-FAMILY-07 | Widgets | `widget_book_snapshot`, `widget_account_snapshot`, `widget_credit_snapshot`, `widget_goal_snapshot` | NOT_STARTED |
| INDEX-FAMILY-01 | Search | `transaction_fts`; current effective transactions only; no vault/account/location-sensitive fields | NOT_STARTED |
| INDEX-FAMILY-02 | Geography | `location_rtree`, `place_rtree`; R*Tree bounding candidates followed by precise Kotlin distance | NOT_STARTED |

## Background, staging, import, backup and recovery operations

Sources: architecture §§13–15 and domain/schema §28.

| ID | Scope | Required records/states | Status |
|---|---|---|---|
| BACKGROUND-OPERATION | Durable operation state | `background_operation`, `operation_checkpoint`; QUEUED → PREPARING → RUNNING → PAUSED/CANCEL_REQUESTED/FAILED_* → COMMITTING/ROLLING_BACK → SUCCEEDED | NOT_STARTED |
| IMPORT-METADATA | Main-database import audit | `import_record`, `import_batch_commit`, `import_source_reference` | NOT_STARTED |
| IMPORT-STAGING | One-time encrypted SQLCipher staging DB | `staging_raw_row`, `staging_parsed_row`, `staging_mapping`, `staging_validation_error`, `staging_duplicate_candidate`, `staging_prepared_command`, `staging_attachment` | NOT_STARTED |
| BACKUP-RESTORE-METADATA | Backup/Drive/restore/merge | `backup_repository`, `backup_snapshot`, `backup_object`, `backup_snapshot_object`, `drive_upload_session`, `restore_record`, `merge_session`, `merge_conflict`, `merge_resolution` | NOT_STARTED |

Worker/UIDT/service payloads may contain only `operationId`; full parameters remain encrypted in the primary database. Large import/restore/batch flows must use the same planner/coordinator, checkpoints, atomic commit/exchange, rollback and temporary cleanup.

## Mandatory test and release quality gates

| Gate | Frozen source | Status |
|---|---|---|
| Pure domain property suite for accounting, refunds, FX, loans, installments, budget, settlement, expressions and recurrence | Tech stack §16; architecture §21 | NOT_STARTED |
| Room/SQLCipher schema, all migrations, FTS5, R*Tree, WAL plaintext and projection rebuild on device | Tech stack §§5,16; architecture §21 | NOT_STARTED |
| Keystore, BiometricPrompt, SAF, location and foreground/UIDT behavior on actual devices | Tech stack §16 | NOT_STARTED |
| Failure injection for attachment, commits, Drive, storage, restore exchange, Keystore, biometrics, row 99,999 and projection versions | Architecture §21.3 | NOT_STARTED |
| Architecture/static privacy boundaries and coordinator-only financial writes | Architecture §21.4; UI contract §16.6 | NOT_STARTED |
| 215-screen route/state/component coverage, screenshots, three languages, accessibility and privacy semantics | UI contract §§13,16–17 | NOT_STARTED |
| Target-scale paging, reports, map, 100k-row import and tens-of-GB streaming operations | Requirements §25; UI contract §16.5 | NOT_STARTED |
| Release AAB, Baseline Profile, locks, verification metadata, SBOM, licenses, NOTICE and privacy/release documentation | Tech stack §16.4 and release plan | NOT_STARTED |

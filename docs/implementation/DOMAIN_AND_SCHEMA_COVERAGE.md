# Domain and Schema Coverage Baseline

Last updated: 2026-08-02 (Asia/Tokyo)
Stage: P06
Status meaning: `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED`, `VERIFIED`, `BLOCKED`. P06 verifies the deterministic pure Kotlin accounting planner; schema, persistence, projection rebuilds, security adapters and feature behavior remain later scope.

## P05 domain/application result

P05 implements the complete typed model and port surface in `:finance:domain`, `:finance:application`, `:analytics:domain` and `:transfer:domain`. The exact §1—§35 mapping, lifecycle classification and later-phase boundaries are recorded in `P05_DOMAIN_API_MAPPING.md`. This is deliberately not a Room/schema or full P06 accounting-rule claim.

## P06 accounting result

P06 implements all eleven frozen transaction rules, exact RevisionAmount/FX evidence materialization, APPLY/REVERSE revision lifecycles, typed effect families, dependency-policy closure, canonical SHA-256 hashes and the coordinator idempotency/conflict boundary. `P06_ACCOUNTING_INVARIANT_MAPPING.md` records the exact all-35 audit and distinguishes the 25 accounting-core automated rows from retained or later persistence/feature evidence. No logical schema or projection family is promoted by this pure planner stage.

## Architecture decisions

Source: `docs/规格冻结_v1.0/系统架构.md` §22, except ADR-007A from `docs/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md` §1.

| ID | Frozen decision | Status | Evidence required to reach VERIFIED |
|---|---|---|---|
| ADR-001 | Coarse-grained multi-module modular monolith | VERIFIED (`P01-E003`, `P01-E004`) | Dependency graph and architecture tests |
| ADR-002 | SQLCipher primary database is the ledger's sole source of truth | NOT_STARTED | Device database and offline-first integration tests |
| ADR-003 | Current state + immutable revisions + immutable financial log | IN_PROGRESS (`P05-E001`, `P05-E002`, `P06-E001`, `P06-E002`: complete append-only planner lifecycle; database remains P07) | Domain and database contract tests |
| ADR-004 | No full event sourcing | IN_PROGRESS (`P05-E001`: current pointer + immutable facts API) | Schema/API inspection |
| ADR-005 | Lightweight CQRS separates writes from query projections | IN_PROGRESS (`P05-E001`, `P05-E004`: typed query/projection ports) | Module/API and projection tests |
| ADR-006 | Every financial write passes through `FinancialMutationCoordinator` | IN_PROGRESS (`P05-E002`, `P05-E003`, `P06-E002`, `P06-E003`: canonical/idempotent coordinator plus static call-site gate; data integration remains P07) | Static call-site rule and integration tests |
| ADR-007 | Journal and Posting are append-only in ordinary operations | IN_PROGRESS (`P05-E001`, `P05-E002`, `P06-E001`, `P06-E002`: exact append-only APPLY/REVERSE plan and audit; DAO constraints remain P07) | DAO constraints and mutation tests |
| ADR-007A | Controlled privacy purge is the sole physical-delete exception and only applies to a fully reversed, closed transaction chain | IN_PROGRESS (`P05-E002`: fail-closed eligibility/tombstone/merge types; integration remains P31) | Purge eligibility, maintenance-lock, tombstone and merge tests |
| ADR-008 | Editing reverses old effects and appends replacements | IN_PROGRESS (`P05-E001`, `P06-E001`, `P06-E002`: exact old-rule reversal plus new-rule APPLY equivalence; persistence remains P07/P08) | Property and integration tests |
| ADR-009 | Core financial projections update in the same database transaction | IN_PROGRESS (`P05-E001`, `P05-E002`, `P06-E002`: one target-revision `ProjectionChangeSet`; database failure injection remains P08) | Failure-injection and rollback tests |
| ADR-010 | Single process and single write gate | IN_PROGRESS (`P05-E002`: `LedgerWriteGate`; runtime proof remains later) | Manifest/process and concurrency tests |
| ADR-011 | Network data is never authoritative ledger data | IN_PROGRESS (`P05-E001`, `P06-E001`, `P06-E002`: planner accepts only frozen FX/reference evidence and has no network dependency) | Offline and adapter tests |
| ADR-012 | Large imports use an encrypted staging or shadow database | IN_PROGRESS (`P05-E001`, `P05-E002`: typed staging/shadow ports) | Device large-import and rollback tests |
| ADR-013 | Restore validates in a shadow directory before atomic exchange | IN_PROGRESS (`P05-E001`: validation/exchange contract) | Device fault-injection tests |
| ADR-014 | Managed backups are logically full and physically incremental | IN_PROGRESS (`P05-E001`: snapshot/object graph contracts) | Repository retention/deduplication tests |
| ADR-015 | Same-book merge uses stable IDs and a commit graph | IN_PROGRESS (`P05-E001`, `P05-E002`: commit graph/conflict/tombstone precedence model) | Three-way merge and conflict tests |
| ADR-016 | Ledger, vault and recovery-password key hierarchies are separate | NOT_STARTED | Keystore/Tink device security tests |
| ADR-017 | App lock is UI access control; vault uses a cryptographic authentication gate | NOT_STARTED | Biometric/device-credential tests |
| ADR-018 | WorkManager carries only opaque operation IDs | IN_PROGRESS (`P05-E001`, `P05-E002`: one-field `OperationLaunchToken`; Worker remains later) | Static InputData privacy audit |
| ADR-019 | Reports use a typed AST and never accept user SQL | IN_PROGRESS (`P05-E001`, `P05-E002`: closed `ReportSpec` AST; SQL compiler remains later) | Compiler whitelist/security tests |
| ADR-020 | The domain model enforces the no-split-transaction limitation | IN_PROGRESS (`P05-E001`, `P05-E002`: compile-time closed category/payer/project shapes; import/report integration remains later) | Domain/import/report contract tests |

The 12 UI-derived decisions from UI contract §18 are separately registered below. P04 may verify a closed cross-cutting contract, while decisions that require actual screen/application behavior remain `IN_PROGRESS` or `NOT_STARTED`.

| ID | Frozen UI decision | Status |
|---|---|---|
| UI-ADR-001 | Every top-level page has the same top-right More Features entry. | IN_PROGRESS (`P04-E003`: fixed top-app-bar variant; pages remain P11+) |
| UI-ADR-002 | Financial records do not use swipe-to-delete. | VERIFIED (`P04-E005`: source rule and real rejection fixture) |
| UI-ADR-003 | Category hierarchy uses first-level groups and the same selectable tile component. | IN_PROGRESS (`P04-E003`: governed grouped grid/tile; category screens remain P12) |
| UI-ADR-004 | Unsaved forms are discarded with explanation after process death because sensitive SavedState and drafts are prohibited. | IN_PROGRESS (`P02-E004`, `P04-E004`: sensitive state closure; process-death UX remains later) |
| UI-ADR-005 | Invalid ordinary forms keep Save actionable so validation can explain errors; only absolute prerequisites disable it. | IN_PROGRESS (`P04-E003`: validation summary/save component contract; form reducers remain later) |
| UI-ADR-006 | Long operations share one Operation Center. | IN_PROGRESS (`P04-E003`: one operation-progress model/panel; operation destination remains P28+) |
| UI-ADR-007 | Transaction lists have no swipe quick-edit/delete gesture. | VERIFIED (`P04-E003`, `P04-E005`: non-swipe row plus static rejection) |
| UI-ADR-008 | Map failure provides a list alternative. | IN_PROGRESS (`P04-E007`, `P04-E008`: MapPanel fallback verified; actual MapLibre integration remains P26) |
| UI-ADR-009 | Settlement suggestions may be displayed only when returned by a domain/application query service; UI never writes its own calculation. | NOT_STARTED |
| UI-ADR-010 | Pie charts automatically change to bars above six categories. | VERIFIED (`P04-E002`: deterministic 6/7-category boundary test) |
| UI-ADR-011 | Top-level pages use a small fixed app bar, not a collapsing large title. | VERIFIED (`P04-E003`, `P04-E005`: closed wrapper and Material top-bar bypass rejection) |
| UI-ADR-012 | Token JSON is the machine-readable source of concrete visual values. | VERIFIED (`P04-E001`, `P04-E002`: complete generation/hash and typed equality tests) |

## Permanent invariants

Source: `docs/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md` §32. These 35 rows are the canonical invariant checklist.

| ID | Invariant | Primary verification class | Status |
|---|---|---|---|
| INV-001 | Every Journal Entry has equal base-currency debits and credits. | Domain property + database audit + restore validation | IN_PROGRESS (`P06-E001`, `P06-E002`: all rules plus 2,500 generated lifecycle cases balanced; database/restore audit remains) |
| INV-002 | Every Posting currency matches its LedgerAccount currency. | Planner property + database audit | IN_PROGRESS (`P06-E001`, `P06-E002`: typed ledger/account evidence checks; database audit remains) |
| INV-003 | Every formal transaction has at most one category, project and goal. | Type/domain validation + import tests | IN_PROGRESS (`P05-E001`, `P06-E001`, `P06-E002`: closed payload and single typed effects; import tests remain) |
| INV-004 | Ordinary expense/income has one Primary amount and no category split. | Domain property + UI/import contract | IN_PROGRESS (`P06-E001`, `P06-E002`: exactly one component with USER_INPUT/ACCOUNT/BASE; UI/import remain) |
| INV-005 | Every current transaction references one complete, self-consistent current revision. | Database integrity audit | IN_PROGRESS (`P06-E001`, `P06-E002`: lifecycle pointer/revision validation; database integrity remains) |
| INV-006 | Old revisions, Journals and Effects are not changed during ordinary operations. | DAO/static rule + mutation tests | IN_PROGRESS (`P06-E001`, `P06-E002`: field-exact reversal audit and original rule version; DAO enforcement remains) |
| INV-007 | Each APPLY Entry is reversed at most once. | Unique constraint + property tests | IN_PROGRESS (`P06-E001`, `P06-E002`: one-to-one target and prior reversal rejection; database unique constraint remains) |
| INV-008 | An Active transaction's net APPLY chain yields exactly one current financial effect. | Domain/database audit | IN_PROGRESS (`P06-E001`, `P06-E002`: create/edit/restore chain tests; database audit remains) |
| INV-009 | A Trashed transaction has zero current net financial effect. | Domain/database audit | IN_PROGRESS (`P06-E001`, `P06-E002`: 500 generated exact net-zero reversals; database audit remains) |
| INV-010 | Refunds cannot exceed the refundable balance without explicit override. | Domain property + UI tests | IN_PROGRESS (`P06-E001`, `P06-E002`: currency/exact balance/allocation validation; UI/persisted aggregate remain) |
| INV-011 | Refund cash-flow date, accrual date and budget month may differ. | Cross-month integration tests | IN_PROGRESS (`P06-E001`, `P06-E002`: three independent frozen dates tested; projection integration remains) |
| INV-012 | Credit-card repayment creates neither expense nor income. | Planner property + report tests | IN_PROGRESS (`P06-E001`, `P06-E002`: balanced liability transfer and allocation effects, zero EconomicEffect; reports remain) |
| INV-013 | Loan principal repayment creates no expense. | Planner property + report tests | IN_PROGRESS (`P06-E001`, `P06-E002`: principal LoanEffect only; reports remain) |
| INV-014 | Loan interest, fees and penalties create non-consumption expense. | Planner property + report tests | IN_PROGRESS (`P06-E001`, `P06-E002`: separate typed non-consumption effects; reports remain) |
| INV-015 | Internal transfer does not change net financial assets. | Planner property + projection tests | IN_PROGRESS (`P06-E001`, `P06-E002`: 1,000 generated zero-net-asset transfers; projections remain) |
| INV-016 | Current FX rates cannot change historical base-currency amounts. | Historical-regression tests | IN_PROGRESS (`P06-E001`, `P06-E002`: frozen evidence and reversal values/rule versions; repository history remains) |
| INV-017 | Current FX revaluation is not income or expense. | Projection/report tests | IN_PROGRESS (`P06-E001`, `P06-E002`: closed transfer/exchange semantics; valuation projections/reports remain) |
| INV-018 | First-level category budget total cannot exceed total budget. | Domain property + UI tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-019 | Second-level category budget total cannot exceed its parent budget. | Domain property + UI tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-020 | Rollover chains rebuild from transaction effects, adjustments and prior-month rollover. | Property + projection rebuild tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-021 | Goal balance does not alter real account balance. | Planner/projection tests | IN_PROGRESS (`P06-E001`, `P06-E002`: separate GoalEffect leaves Posting shape unchanged; projection remains) |
| INV-022 | Settlement position deltas sum to zero across participants. | Domain property + database audit | IN_PROGRESS (`P06-E001`, `P06-E002`: checked zero-sum share/payment effects; database audit remains) |
| INV-023 | External-participant payment cannot alter the local user's account. | Planner property + UI integration | IN_PROGRESS (`P06-E001`, `P06-E002`: journal-less external operation and full lifecycle tested; UI remains) |
| INV-024 | Editing a settled activity transaction does not rewrite historical settlement payments. | Revision/integration tests | IN_PROGRESS (`P06-E001`, `P06-E002`: only transaction effects reverse; payment record is not planner output; integration remains) |
| INV-025 | Loan-schedule principal total equals principal still to be repaid. | Property tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-026 | Installment-schedule principal total equals installment principal. | Property tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-027 | The recurrence occurrence unique key prevents duplicate generation. | Concurrency/idempotency tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-028 | Candidate records create no formal financial effects. | Domain/database/report tests | IN_PROGRESS (`P06-E001`, `P06-E002`: recurrence and credit-payment candidates fail before materialization; database/report remain) |
| INV-029 | Category/account/card tombstones or archives preserve historical references. | Migration/history tests | IN_PROGRESS (`P06-E001`, `P06-E002`: archived ledger permits exact historical reversal; migration/history remain) |
| INV-030 | Purge tombstones win over old entity versions during merge restore. | Merge integration tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-031 | Every core projection aligns to the same `localRevision`. | Atomicity/failure-injection audit | IN_PROGRESS (`P06-E001`, `P06-E002`: one target revision across every ProjectionChange; persisted atomicity remains P08) |
| INV-032 | Vault fields never enter FTS, audit snapshots, logs or telemetry. | Static/privacy/device audit | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-033 | Failed import, restore or large batch leaves the main ledger unchanged. | Shadow-DB fault injection | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |
| INV-034 | Every monetary accumulation detects `Long` overflow. | Boundary/property/static tests | VERIFIED (`P03-E002`, `P03-E006`, `P06-E002`, `P06-E003`: checked sum plus planner Long.MAX/overflow and static rejection) |
| INV-035 | Every cache depending on current transaction content carries a version. | Architecture/cache invalidation tests | IN_PROGRESS (`P05-E001`, `P05-E002`: typed model/policy foundation; persistence/integration evidence remains later) |

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
| BACKGROUND-OPERATION | Durable operation state | `background_operation`, `operation_checkpoint`; QUEUED → PREPARING → RUNNING → PAUSED/CANCEL_REQUESTED/FAILED_* → COMMITTING/ROLLING_BACK → SUCCEEDED | IN_PROGRESS (`P05-E001`, `P05-E002`: pure operation model/transition tests; persistence remains P28) |
| IMPORT-METADATA | Main-database import audit | `import_record`, `import_batch_commit`, `import_source_reference` | IN_PROGRESS (`P05-E001`: typed records; persistence remains P28) |
| IMPORT-STAGING | One-time encrypted SQLCipher staging DB | `staging_raw_row`, `staging_parsed_row`, `staging_mapping`, `staging_validation_error`, `staging_duplicate_candidate`, `staging_prepared_command`, `staging_attachment` | IN_PROGRESS (`P05-E001`: all seven typed staging records/ports; SQLCipher staging remains P28) |
| BACKUP-RESTORE-METADATA | Backup/Drive/restore/merge | `backup_repository`, `backup_snapshot`, `backup_object`, `backup_snapshot_object`, `drive_upload_session`, `restore_record`, `merge_session`, `merge_conflict`, `merge_resolution` | IN_PROGRESS (`P05-E001`, `P05-E002`: typed metadata/conflict contracts; persistence remains P29-P31) |

Worker/UIDT/service payloads may contain only `operationId`; full parameters remain encrypted in the primary database. Large import/restore/batch flows must use the same planner/coordinator, checkpoints, atomic commit/exchange, rollback and temporary cleanup.

## Mandatory test and release quality gates

| Gate | Frozen source | Status |
|---|---|---|
| Pure domain property suite for accounting, refunds, FX, loans, installments, budget, settlement, expressions and recurrence | Tech stack §16; architecture §21 | IN_PROGRESS (`P03-E002`—`P03-E005`, `P05-E002`, `P06-E001`—`P06-E003`: all P06 transaction rules and accounting-core properties verified; later advanced planners/integration remain) |
| Room/SQLCipher schema, all migrations, FTS5, R*Tree, WAL plaintext and projection rebuild on device | Tech stack §§5,16; architecture §21 | NOT_STARTED |
| Keystore, BiometricPrompt, SAF, location and foreground/UIDT behavior on actual devices | Tech stack §16 | NOT_STARTED |
| Failure injection for attachment, commits, Drive, storage, restore exchange, Keystore, biometrics, row 99,999 and projection versions | Architecture §21.3 | NOT_STARTED |
| Architecture/static privacy boundaries and coordinator-only financial writes | Architecture §21.4; UI contract §16.6 | VERIFIED (`P02-E003`, `P02-E004`: external-dependency, receiver-alias, decoy-scope and privacy-wrapper fixtures) |
| 215-screen route/state/component coverage, screenshots, three languages, accessibility and privacy semantics | UI contract §§13,16–17 | IN_PROGRESS (`P04-E001`—`P04-E008` verify the cross-cutting route/state/design-system and device matrix; all 215 feature screen implementations and final acceptance remain later scope) |
| Target-scale paging, reports, map, 100k-row import and tens-of-GB streaming operations | Requirements §25; UI contract §16.5 | NOT_STARTED |
| Release AAB, Baseline Profile, locks, verification metadata, SBOM, licenses, NOTICE and privacy/release documentation | Tech stack §16.4 and release plan | IN_PROGRESS (`P02-E006` verifies locks/SBOM/license task infrastructure only; release evidence remains P36) |

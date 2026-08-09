# Domain and Schema Coverage Baseline

Last updated: 2026-08-06 (Asia/Tokyo)
Stage: P24
Status meaning: `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED`, `VERIFIED`, `BLOCKED`. P24 verifies typed complete-row batch commands, one atomic coordinator commit, immutable batch reversal, bounded journal selection/editing and REC/JRN integration. It does not promote analytics, large import, performance or later workflows.

## P05 domain/application result

P05 implements the complete typed model and port surface in `:finance:domain`, `:finance:application`, `:analytics:domain` and `:transfer:domain`. The exact §1—§35 mapping, lifecycle classification and later-phase boundaries are recorded in `P05_DOMAIN_API_MAPPING.md`. This is deliberately not a Room/schema or full P06 accounting-rule claim.

## P06 accounting result

P06 implements all eleven frozen transaction rules, exact RevisionAmount/FX evidence materialization, APPLY/REVERSE revision lifecycles, typed effect families, dependency-policy closure, canonical SHA-256 hashes and the coordinator idempotency/conflict boundary. `P06_ACCOUNTING_INVARIANT_MAPPING.md` records the exact all-35 audit and distinguishes the 25 accounting-core automated rows from retained or later persistence/feature evidence. No logical schema or projection family is promoted by this pure planner stage.

## P07 encrypted Schema v1 result

P07 maps the exact 94 frozen §25 tables plus the separately required `rule_set_version` table into one Room-owned SQLCipher primary database. It also implements every §26 projection, FTS5, both R*Tree indexes, §28 operation/import/backup/restore metadata, and a separate seven-table SQLCipher import staging database. `P07_SCHEMA_MAPPING.md` lists every frozen table and checked column/constraint coverage; the complete deterministic DDL exports live in `core/database/schema-contract/` and Room's two v1 identities live in `core/database/schemas/`.

API 36 device evidence creates, closes and reopens both databases, rejects a wrong key, executes FTS5/R*Tree/JSON/window queries, passes integrity and foreign-key checks, proves effective WAL/foreign-key/incremental-vacuum/memory-temp settings, and finds no sensitive sentinel in database/WAL side files. P07 deliberately does not implement P08 repository transactions or projection rebuild algorithms.

## P08 repository/application result

P08 implements the only application write entry, process-local write gate, complete normalized plan mapper and one Room-owned SQLCipher transaction spanning immutable facts, current pointers, synchronous projections, book revision and idempotency receipt. Persisted book/head/rule/expected-revision checks run again inside that transaction; five failure checkpoints prove no partial state.

Current transactions, balance/daily, refund, budget/project/goal, credit/installment/loan, settlement, FTS/R*Tree and widget snapshots rebuild synchronously from authoritative state. Canonical savepoint audits and maintenance rebuilds prove deterministic reconstruction. Bound keyset, FTS-candidate and R*Tree-plus-Haversine query services establish the later feature query boundary. Exact field/table/port mapping is recorded in `P08_REPOSITORY_PROJECTION_MAPPING.md`.

## P09 security-runtime result

P09 separates DeviceLedgerKEK/database DEK/attachment root/security-settings keys from the authentication-bound VaultAuthenticationKEK/Vault DEK and the Argon2id recovery-password KEK. The production `BookSessionManager` owns the real SQLCipher lifecycle and exposes only capability-limited opaque headless leases; app lock controls UI access and never substitutes for per-action vault cryptographic authentication. Exact key, state, platform-test and later-stage boundaries are recorded in `P09_SECURITY_RUNTIME_MAPPING.md`.

## P10 attachment and geospatial infrastructure result

P10 maps the Schema v1 `encrypted_blob`/`attachment`/`blob_gc_candidate` family to a streaming Tink object store with hash+size deduplication, encrypted thumbnails, reference-aware cleanup and a confirmation-bound pipe provider. Foreground location freezes provider/time/E7/accuracy evidence within one three-second monotonic save budget, and `LedgerMap` owns actual MapLibre lifecycle, overlays, attribution and accessible fallback. `P10_FILES_GEO_MAPPING.md` records exact contracts and later-stage boundaries; no financial fact is written outside the P08 coordinator.

## P11 application-runtime result

P11 composes the P09 `BookSessionManager` and P08 coordinator boundary into the only Activity/Compose root. `SessionAwareLedgerWriteGate` rejects before and inside the serialized gate unless the session is Ready. A typed initialization port creates only encrypted book/system-ledger and optional account/category metadata; device evidence proves it creates no transaction, Journal, Posting or monetary effect. Proto DataStore persists only non-sensitive first-run and safe route/scroll metadata. `P11_APP_SHELL_MAPPING.md` records the 18-screen/65-state UI and later-stage boundary.

## P14 specialized-transaction and valuation result

P14 maps internal transfer, balance adjustment, FX exchange and opening balance to the existing P06 planner and sole P08 `FinancialMutationCoordinator` transaction. Cross-currency flows preserve dual authoritative account amounts and append exact account/base/FX evidence; opening/adjustment create no economic or budget effects. A privacy-limited OkHttp adapter supplies reference evidence and an encrypted current cache advances only `valuationRevision`. Exact field, cache, screen and evidence mapping is recorded in `P14_MULTICURRENCY_MAPPING.md`.

## P15 journal/history/trash result

P15 implements bounded current-transaction queries, complete typed filters, immutable revision reads/compare/restore, coordinator-owned allowed-field batch edits and reversible trash transitions. `P15_JOURNAL_MAPPING.md` records the 12-screen/42-state and 500,000-row evidence. Permanent physical deletion remains behind the P31 maintenance guard.

## P16 refund result

P16 realizes the §14/§25.6 refund model as a typed revision plus immutable allocation fact/reversal chain. Linked and independent refunds generate balanced cash/expense postings, `CONTRA_EXPENSE` instead of income, closed budget/project/goal/settlement restore effects, three separately persisted dates/months and exact cumulative eligibility. `P16_REFUND_MAPPING.md` records the two-screen/eight-state UI, dependency-policy and excess-projection precedence details.

## P17 budget result

P17 realizes §15 and the existing Schema v1 budget family through typed month/template mutations, immutable revisions and signed adjustment facts. `BudgetHierarchyPolicy` enforces total/root/child base limits, while synchronous projection rebuild derives total/root/leaf usage and an uncapped positive/negative natural-month rollover chain from immutable effects, adjustments and the prior month. Daily availability subtracts the version-matched future recurrence reservation before exact integer division. `P17_BUDGET_MAPPING.md` records the eight-screen/23-state UI, application/coordinator boundary and retained later-stage ownership.

## P18 project and goal-fund result

P18 realizes §§16—17 through typed project/goal current models and audited row-version mutations. ProjectEffect freezes SELF_SHARE usage and monthly-budget inclusion at transaction time; transfer and loan-principal flows produce no project consumption, while real interest, fee and penalty expense components do. Manual goal allocation/release/adjustment, transaction spending and refund restoration append immutable GoalMovement/GoalEffect facts only through `FinancialMutationCoordinator`; they never create a Journal/Posting or alter the account's real balance.

The synchronous `project_usage_projection` and `goal_balance_projection` rebuild from immutable facts at the committed book revision. Account availability uses checked `actual - reserved`, permits a negative result with a warning, and never silently reduces reservations or blocks spending. Project transactions use bounded `(occurredAt, StableId)` keyset Paging without OFFSET. `P18_PROJECT_GOAL_MAPPING.md` records the exact aggregate/fact/application/query/UI boundary and the 11-screen/31-state evidence.

## P19 credit-account result

P19 realizes §18/§25.7 through typed credit-profile rules, immutable statement revisions and the existing credit-payment transaction/revision/allocation facts. Statement calendars resolve short months, skipped local dates and adjusted due dates in the account time zone; permanent/temporary limits, estimated and official amounts, sealing, overdue/paid states and passive positive balances remain distinct. Official-versus-estimated difference is display evidence only and never creates a balance adjustment or minimum-payment value.

Credit consumption emits balanced liability plus expense facts. Repayment emits a balanced asset/liability transfer with `StatementEffect` allocation and no EconomicEffect, so it creates neither expense nor income. `CreditPaymentAllocationPolicy` deterministically applies earliest-first, specified-statement or explicit unallocated advance rules and rejects every active overpayment against actual debt. Profile, statement and transaction revisions retain optimistic concurrency and canonical command idempotency.

The synchronous `credit_statement_projection` and `credit_account_projection` rebuild from immutable facts at the committed book revision. Formal automatic bookkeeping requires all five eligibility facts and a unique occurrence key; candidate mode persists no financial fact. Every financial mutation terminates at `FinancialMutationCoordinator`, while feature and app layers receive only typed application views/requests. `P19_CREDIT_MAPPING.md` records the exact aggregate/fact/application/query/UI boundary and the nine-screen/29-state evidence. Schema v1 already contained every required table, constraint and index, so P19 introduces no migration or alternate database.

## P20 installment result

P20 realizes §19/§25.7 with one purchase-linked `InstallmentPlan`, immutable terms and schedule revisions, ordered schedule items and explicit refund allocations. `InstallmentAccountingPolicy` uses checked minor-unit arithmetic, closes all five fee models, makes the last term absorb the principal tail, validates the remaining-principal chain and preserves every prior plan version. The complete credit purchase stays one expense/liability transaction; schedule items are future statement assignments and never become duplicate Journal/EconomicEffect rows.

Preview and early-settlement simulation are read-only. Explicit settlement atomically appends a plan/schedule version and one actual asset/liability repayment through `FinancialMutationCoordinator`; a settlement fee is a separate non-consumption expense. A real linked refund can explicitly allocate principal/fee and rebuild the future schedule while retaining the old schedule. Command hash/idempotency and expected-revision conflicts are enforced against the encrypted snapshot.

## P21 loan result

P21 realizes §20/§25.8 with a typed `LoanContract` containing one or more `LoanTranche`s, each bound to its own liability ledger. Terms, rate periods and schedules are immutable revisions. `LoanAccountingPolicy` deterministically supports fixed/floating rates, equal payment, equal principal, interest-only, bullet and custom schedules across the closed frequency set; checked minor-unit arithmetic, exact component reconciliation and last-payment tail closure preserve principal.

Disbursement and actual repayment commands create balanced immutable facts only through `FinancialMutationCoordinator`. Principal repayment changes assets/liability but creates no expense; interest, fees and penalties create explicit non-consumption expense. Simulations are sandbox rows until a confirmed apply appends a new terms/schedule version. `loan_progress_projection` rebuilds from facts at the committed revision, while `loan_future_cashflow_projection` contains only latest-schedule dates strictly after today and never enters current transactions or balances. `P21_LOAN_MAPPING.md` records the exact domain/schema/application/UI boundary and all 15 destinations/41 required states.

## P22 mutual-settlement result

P22 realizes §21/§25.9 through a unique active self participant, arbitrary external participants, activity membership/order/currency/project state, immutable transaction shares and immutable settlement-payment records. `SettlementAllocationPolicy` closes equal, fixed-amount, percentage and weight splits together with exclusion, tax/service-fee distribution and explicit remainder ownership. All authoritative paths use checked integer minor units and prove `sum(paid) = sum(owed) = total` and zero participant-delta sum.

Self-paid expenses debit the local account for the complete payment while only the self owed share creates consumption, budget and project effects; other shares become receivables. An external payer changes no local account, but the self owed share remains the local expense and payable. Self-involved settlements create only balanced asset/settlement effects with no income/expense; external-to-external payments create only an immutable subledger payment and receipt. Every financial path terminates at `FinancialMutationCoordinator`.

`settlement_position_projection` rebuilds paid, owed, settled-paid, settled-received and net positions from immutable effects at one local revision. Editing a settled source appends its ordinary reversal/replacement facts, never changes payment records, and marks the recalculated residual `requires additional settlement`; deterministic suggestions are produced by the application/domain query and only rendered by UI. `P22_SETTLEMENT_MAPPING.md` records the exact domain/schema/application/UI boundary and all 9 destinations/27 required states.

## P23 automation result

P23 realizes §22/§25.11 through immutable blueprint and recurrence revisions, deterministic zoned occurrence identities, explicit exceptions and unique occurrence reservations. Formal occurrences reuse the normal typed transaction/credit/loan application ports; candidates create no Journal, Posting or Effect until confirmation. Startup and unique WorkManager catch-up enter through a restricted headless lease and the same coordinator boundary. `P23_AUTOMATION_MAPPING.md` records exact recurrence, candidate, Worker/privacy and 11-destination coverage.

## P24 batch-entry and bulk-edit result

P24 adds a closed application aggregate around existing transaction commands rather than a universal transaction payload. `BatchEntryRowWriteRequest` admits typed ordinary or refund requests; every row retains category, integer-minor user/account/base evidence, account/card, merchant, time, project, encrypted attachment IDs, mutual-expense shares, location, installment and refund relation. A parent `BatchFinancialCommand` deterministically combines the child plans under one commit identity and one target local revision.

`SecureRoomBatchEntryApplicationPort` validates every row without writing, then submits exactly once through `FinancialMutationCoordinator` and `RoomFinancialCommitRepository`. Immutable facts, reference side effects, current pointers, all synchronous projections, `book.localRevision` and one parent receipt share the same SQLCipher transaction. Audit resolves the exact created transaction set; undo appends new MOVE_TO_TRASH revisions and reverse facts under another parent batch without deleting history. Exact retry returns the original receipt, while failure injection after immutable facts proves total rollback.

P24 introduces no schema or migration. It reuses Schema v1 facts and projections and corrects the search-projection audit population: current transaction projection intentionally includes trashed rows, while FTS and its count check include only active rows. `JournalSelectionSpec.ALL_MATCHING` keeps a query fingerprint plus bounded exclusions and `JournalBulkEditPatch` cannot represent amount, direction, refund relation or mutual-expense share. `P24_BATCH_MAPPING.md` records the complete application/data/UI boundary and five-destination/18-state evidence.

`installment_progress_projection` synchronously separates posted liability and unposted commitment at the committed book revision and rebuilds to the same canonical result. `P20_INSTALLMENT_MAPPING.md` records the exact aggregate/fact/application/query/UI boundary and seven-screen/19-state evidence. Schema v1 already contains all installment tables, constraints and indexes, so P20 introduces no migration or alternate persistence path.

## Architecture decisions

Source: `docs/规格冻结_v1.0/系统架构.md` §22, except ADR-007A from `docs/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md` §1.

| ID | Frozen decision | Status | Evidence required to reach VERIFIED |
|---|---|---|---|
| ADR-001 | Coarse-grained multi-module modular monolith | VERIFIED (`P01-E003`, `P01-E004`) | Dependency graph and architecture tests |
| ADR-002 | SQLCipher primary database is the ledger's sole source of truth | VERIFIED (`P07-E001`, `P07-E003`, `P08-E003`: sole encrypted primary plus real repository/query device integration) | Device database and offline-first integration tests |
| ADR-003 | Current state + immutable revisions + immutable financial log | VERIFIED (`P05-E001`, `P06-E001`, `P07-E001`—`P07-E003`, `P08-E001`, `P08-E003`: normalized append/current transaction and rollback proof) | Domain and database contract tests |
| ADR-004 | No full event sourcing | VERIFIED (`P05-E001`, `P07-E001`, `P08-E001`: normalized current pointers plus immutable accounting facts and rebuildable projections remain separate) | Schema/API inspection |
| ADR-005 | Lightweight CQRS separates writes from query projections | VERIFIED (`P05-E001`, `P07-E001`, `P08-E001`—`P08-E003`: command transaction and typed keyset/FTS/geo query services are separate) | Module/API and projection tests |
| ADR-006 | Every financial write passes through `FinancialMutationCoordinator` | VERIFIED (`P05-E002`, `P06-E003`, `P08-E001`—`P08-E004`, `P24-E001`, `P24-E003`: handler/use case/gate/repository chain, real atomic batch and fail-closed static rejection) | Static call-site rule and integration tests |
| ADR-007 | Journal and Posting are append-only in ordinary operations | VERIFIED (`P06-E001`, `P07-E001`—`P07-E003`, `P08-E003`: repository appends plans and SQLite guards reject mutation) | DAO constraints and mutation tests |
| ADR-007A | Controlled privacy purge is the sole physical-delete exception and only applies to a fully reversed, closed transaction chain | IN_PROGRESS (`P05-E002`, `P07-E001`, `P07-E003`: fail-closed eligibility types plus maintenance-and-internal-guard-only physical deletes; P07 exposes no arming API and the workflow remains P31) | Purge eligibility, maintenance-lock, tombstone and merge tests |
| ADR-008 | Editing reverses old effects and appends replacements | VERIFIED (`P06-E001`, `P06-E002`, `P07-E001`, `P08-E001`, `P08-E003`: immutable reverse/apply plan is persisted by the same append-only mapper and atomic transaction) | Property and integration tests |
| ADR-009 | Core financial projections update in the same database transaction | VERIFIED (`P07-E001`, `P08-E001`, `P08-E003`, `P24-E003`: target-version rebuild and injected failures roll back batch facts, projections, book and receipt) | Failure-injection and rollback tests |
| ADR-010 | Single process and single write gate | VERIFIED (`P01-E003`, `P08-E002`, `P08-E004`: single-process manifest and mutex-backed application entry with static bypass rejection) | Manifest/process and concurrency tests |
| ADR-011 | Network data is never authoritative ledger data | VERIFIED (`P05-E001`, `P06-E001`, `P06-E002`, `P14-E002`—`P14-E004`: network sends only pair/date; online/cache/manual evidence is frozen into immutable amounts and later refresh changes only valuation projection) | Offline and adapter tests |
| ADR-012 | Large imports use an encrypted staging or shadow database | VERIFIED (`P05-E001`, `P05-E002`, `P07-E001`, `P07-E003`, `P28-E002`, `P28-E004`, `P28-E005`: separate seven-table SQLCipher staging, 100,000-row device paging, validated shadow exchange, row-99,999 rollback and whole-batch undo pass) | Device large-import and rollback tests |
| ADR-013 | Restore validates in a shadow directory before atomic exchange | IN_PROGRESS (`P05-E001`: validation/exchange contract) | Device fault-injection tests |
| ADR-014 | Managed backups are logically full and physically incremental | IN_PROGRESS (`P05-E001`: snapshot/object graph contracts) | Repository retention/deduplication tests |
| ADR-015 | Same-book merge uses stable IDs and a commit graph | IN_PROGRESS (`P05-E001`, `P05-E002`, `P07-E001`: commit parents entity changes tombstones merge sessions/conflicts/resolutions persisted; behavior remains P31) | Three-way merge and conflict tests |
| ADR-016 | Ledger, vault and recovery-password key hierarchies are separate | VERIFIED (`P09-E001`, `P09-E003`, `P09-E004`: DeviceLedgerKEK/database/attachment/settings, auth-bound Vault KEK/DEK and Argon2id recovery wrapping are independent) | Keystore/Tink device security tests |
| ADR-017 | App lock is UI access control; vault uses a cryptographic authentication gate | VERIFIED (`P09-E001`, `P09-E002`, `P09-E004`: app lock drops UI access while each vault action uses a newly authenticated CryptoObject) | Biometric/device-credential tests |
| ADR-018 | WorkManager carries only opaque operation IDs | VERIFIED (`P05-E001`, `P05-E002`, `P07-E001`, `P28-E001`, `P28-E002`, `P28-E004`, `P28-E007`: import Worker/route input contains only operation ID, complete descriptors remain encrypted, reopen recovery and static privacy rejection pass) | Static InputData privacy audit |
| ADR-019 | Reports use a typed AST and never accept user SQL | IN_PROGRESS (`P05-E001`, `P05-E002`: closed `ReportSpec` AST; SQL compiler remains later) | Compiler whitelist/security tests |
| ADR-020 | The domain model enforces the no-split-transaction limitation | VERIFIED (`P05-E001`, `P05-E002`, `P28-E001`, `P28-E002`: compile-time closed category/payer shapes plus import delimiter/count rejection require separate transactions and the mutation gate prevents removal) | Domain/import/report contract tests |

The 12 UI-derived decisions from UI contract §18 are separately registered below. P04 may verify a closed cross-cutting contract, while decisions that require actual screen/application behavior remain `IN_PROGRESS` or `NOT_STARTED`.

| ID | Frozen UI decision | Status |
|---|---|---|
| UI-ADR-001 | Every top-level page has the same top-right More Features entry. | IN_PROGRESS (`P04-E003`: fixed top-app-bar variant; pages remain P11+) |
| UI-ADR-002 | Financial records do not use swipe-to-delete. | VERIFIED (`P04-E005`: source rule and real rejection fixture) |
| UI-ADR-003 | Category hierarchy uses first-level groups and the same selectable tile component. | IN_PROGRESS (`P04-E003`: governed grouped grid/tile; category screens remain P12) |
| UI-ADR-004 | Unsaved forms are discarded with explanation after process death because sensitive SavedState and drafts are prohibited. | IN_PROGRESS (`P02-E004`, `P04-E004`: sensitive state closure; process-death UX remains later) |
| UI-ADR-005 | Invalid ordinary forms keep Save actionable so validation can explain errors; only absolute prerequisites disable it. | IN_PROGRESS (`P04-E003`: validation summary/save component contract; form reducers remain later) |
| UI-ADR-006 | Long operations share one Operation Center. | IN_PROGRESS (`P04-E003`, `P28-E006`: import uses the governed operation-progress model/panel with pause/cancel/non-cancelable commit states; export/backup/restore operation destinations remain P29—P31) |
| UI-ADR-007 | Transaction lists have no swipe quick-edit/delete gesture. | VERIFIED (`P04-E003`, `P04-E005`: non-swipe row plus static rejection) |
| UI-ADR-008 | Map failure provides a list alternative. | VERIFIED (`P04-E007`, `P04-E008`, `P10-E004`: token contract and actual MapLibre unavailable/style paths render the accessible data table) |
| UI-ADR-009 | Settlement suggestions may be displayed only when returned by a domain/application query service; UI never writes its own calculation. | VERIFIED (`P22-E001`—`P22-E004`: `SettlementSuggestionPolicy` runs behind the typed application snapshot and the SET feature only renders returned suggestions) |
| UI-ADR-010 | Pie charts automatically change to bars above six categories. | VERIFIED (`P04-E002`: deterministic 6/7-category boundary test) |
| UI-ADR-011 | Top-level pages use a small fixed app bar, not a collapsing large title. | VERIFIED (`P04-E003`, `P04-E005`: closed wrapper and Material top-bar bypass rejection) |
| UI-ADR-012 | Token JSON is the machine-readable source of concrete visual values. | VERIFIED (`P04-E001`, `P04-E002`: complete generation/hash and typed equality tests) |

## Permanent invariants

Source: `docs/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md` §32. These 35 rows are the canonical invariant checklist.

| ID | Invariant | Primary verification class | Status |
|---|---|---|---|
| INV-001 | Every Journal Entry has equal base-currency debits and credits. | Domain property + database audit + restore validation | IN_PROGRESS (`P06-E001`, `P06-E002`, `P07-E001`, `P07-E003`, `P08-E003`: planner, SQL guard and post-commit audit pass; restore validation remains P31) |
| INV-002 | Every Posting currency matches its LedgerAccount currency. | Planner property + database audit | VERIFIED (`P06-E001`, `P06-E002`, `P07-E001`, `P07-E003`, `P08-E003`: typed planner, normalized mapper and SQLite cross-row audit pass on device) |
| INV-003 | Every formal transaction has at most one category, project and goal. | Type/domain validation + import tests | IN_PROGRESS (`P05-E001`, `P06-E001`, `P06-E002`: closed payload and single typed effects; import tests remain) |
| INV-004 | Ordinary expense/income has one Primary amount and no category split. | Domain property + UI/import contract | IN_PROGRESS (`P06-E001`, `P06-E002`: exactly one component with USER_INPUT/ACCOUNT/BASE; UI/import remain) |
| INV-005 | Every current transaction references one complete, self-consistent current revision. | Database integrity audit | VERIFIED (`P06-E001`, `P07-E001`, `P07-E003`, `P08-E003`: pointer/subtype checks run inside the atomic repository transaction and after rebuild) |
| INV-006 | Old revisions, Journals and Effects are not changed during ordinary operations. | DAO/static rule + mutation tests | VERIFIED (`P06-E001`, `P07-E001`—`P07-E003`, `P08-E001`—`P08-E003`, `P15-E004`: mapper is append-only; physical guards reject bypass; bulk edit and old-version restore append BULK_EDIT/RESTORE plus new REVERSE/APPLY facts) |
| INV-007 | Each APPLY Entry is reversed at most once. | Unique constraint + property tests | VERIFIED (`P06-E001`, `P06-E002`, `P07-E001`, `P07-E003`, `P08-E003`: planner and repository execute under the unique reversal constraint) |
| INV-008 | An Active transaction's net APPLY chain yields exactly one current financial effect. | Domain/database audit | VERIFIED (`P06-E001`, `P06-E002`, `P15-E004`: generated create/edit/restore chains and SQLCipher BULK_EDIT/RESTORE integration preserve one active replacement effect) |
| INV-009 | A Trashed transaction has zero current net financial effect. | Domain/database audit | VERIFIED (`P06-E001`, `P06-E002`, `P15-E004`: 500 generated reversals plus SQLCipher purge assessment verify zero account/base/economic/budget/project/goal/statement/loan/settlement net after trash) |
| INV-010 | Refunds cannot exceed the refundable balance without explicit override. | Domain property + UI tests | VERIFIED (`P06-E001`, `P06-E002`, `P16-E002`—`P16-E004`: 500 generated exact-minor plans, cumulative SQLCipher allocations, default rejection and separate danger confirmation/immutable override evidence pass) |
| INV-011 | Refund cash-flow date, accrual date and budget month may differ. | Cross-month integration tests | VERIFIED (`P06-E001`, `P06-E002`, `P16-E002`—`P16-E004`: actual September cash date, July accrual date and original/refund/no-restore budget policies remain independent through facts, rebuild and UI) |
| INV-012 | Credit-card repayment creates neither expense nor income. | Planner property + report tests | VERIFIED (`P06-E001`, `P06-E002`, `P19-E002`, `P19-E003`: balanced asset/liability postings, zero EconomicEffect, SQLCipher current/projection query and deterministic rebuild all preserve the classification) |
| INV-013 | Loan principal repayment creates no expense. | Planner property + report tests | VERIFIED (`P06-E001`, `P06-E002`, `P21-E002`, `P21-E003`: principal-only planner properties and SQLCipher transaction/effect/projection queries produce zero economic expense) |
| INV-014 | Loan interest, fees and penalties create non-consumption expense. | Planner property + report tests | VERIFIED (`P06-E001`, `P06-E002`, `P21-E002`, `P21-E003`: separate checked components rebuild as non-consumption EconomicEffects and never consumption) |
| INV-015 | Internal transfer does not change net financial assets. | Planner property + projection tests | VERIFIED (`P06-E001`, `P06-E002`, `P14-E004`: 1,000 generated zero-net-asset plans plus SQLCipher commit/widget projection retain exact zero net change) |
| INV-016 | Current FX rates cannot change historical base-currency amounts. | Historical-regression tests | VERIFIED (`P06-E001`, `P06-E002`, `P07-E001`, `P08-E001`, `P08-E003`: frozen amount/FX evidence and postings append through the normalized mapper; rebuild never reads a current FX feed) |
| INV-017 | Current FX revaluation is not income or expense. | Projection/report tests | VERIFIED (`P06-E001`, `P06-E002`, `P14-E004`: current-rate refresh only advances valuation projection/version, appends no transaction/Journal/EconomicEffect and leaves localRevision unchanged) |
| INV-018 | First-level category budget total cannot exceed total budget. | Domain property + UI tests | VERIFIED (`P17-E002`—`P17-E004`: 1,000 generated hierarchies, SQLCipher rejection rollback and live exact-difference meters pass) |
| INV-019 | Second-level category budget total cannot exceed its parent budget. | Domain property + UI tests | VERIFIED (`P17-E002`—`P17-E004`: typed root/parent identity, checked child sums, database integration and constraint UI pass) |
| INV-020 | Rollover chains rebuild from transaction effects, adjustments and prior-month rollover. | Property + projection rebuild tests | VERIFIED (`P17-E002`, `P17-E003`: 122 continuous positive/negative months plus transaction, paired adjustment, historical edit and canonical SQLCipher rebuild hash pass) |
| INV-021 | Goal balance does not alter real account balance. | Planner/projection tests | VERIFIED (`P06-E001`, `P06-E002`, `P18-E002`, `P18-E003`: goal movements emit no Journal/Posting, SQLCipher actual balance is unchanged, negative availability remains explicit and GoalEffect projection rebuild hash is stable) |
| INV-022 | Settlement position deltas sum to zero across participants. | Domain property + database audit | VERIFIED (`P06-E001`, `P06-E002`, `P22-E002`, `P22-E003`: 2,000 generated exact allocations plus real SQLCipher expense/payment/rebuild audits prove every participant delta and projected activity total is zero-sum) |
| INV-023 | External-participant payment cannot alter the local user's account. | Planner property + UI integration | VERIFIED (`P06-E001`, `P06-E002`, `P22-E002`—`P22-E004`: external payer expense and external-to-external settlement require no account, create no local balance mutation, and show the no-local-impact state) |
| INV-024 | Editing a settled activity transaction does not rewrite historical settlement payments. | Revision/integration tests | VERIFIED (`P06-E001`, `P06-E002`, `P22-E003`, `P22-E004`: API 36 test retains the exact payment digest across REVERSE/APPLY source edit and exposes only a supplemental residual marker/suggestion) |
| INV-025 | Loan-schedule principal total equals principal still to be repaid. | Property tests | VERIFIED (`P05-E001`, `P05-E002`, `P21-E002`, `P21-E003`: every method plus 2,000 generated checked schedules, tails, partial/full prepayments and committed/rebuilt multi-tranche projections conserve exact remaining principal) |
| INV-026 | Installment-schedule principal total equals installment principal. | Property tests | VERIFIED (`P05-E001`, `P05-E002`, `P20-E002`, `P20-E003`: 2,000 generated checked schedules, all fee models, last-term tails, refund versions and SQLCipher committed/rebuilt progress conserve exact current principal) |
| INV-027 | The recurrence occurrence unique key prevents duplicate generation. | Concurrency/idempotency tests | VERIFIED (`P05-E001`, `P05-E002`, `P07-E001`, `P23-E002`, `P23-E003`: deterministic occurrence identity plus physical `(series_id, series_revision_id, occurrence_instant)` uniqueness survives repeated startup catch-up, Worker-equivalent replay, restart and manual retry) |
| INV-028 | Candidate records create no formal financial effects. | Domain/database/report tests | VERIFIED (`P06-E001`, `P06-E002`, `P19-E002`, `P19-E003`, `P23-E002`, `P23-E003`: recurrence candidates append no transaction/Journal/Posting/economic/budget fact; confirmation consumes the operation link only inside the normal coordinator transaction) |
| INV-029 | Category/account/card tombstones or archives preserve historical references. | Migration/history tests | VERIFIED (`P06-E001`, `P06-E002`, `P07-E001`, `P12-E002`, `P12-E003`, `P12-E008`: restricted historical foreign keys, archive/tombstone/replacement workflows and coordinator-owned category reassignment/place split append new revisions while preserving old facts and references) |
| INV-030 | Purge tombstones win over old entity versions during merge restore. | Merge integration tests | IN_PROGRESS (`P05-E001`, `P05-E002`, `P07-E001`: normalized tombstone and merge conflict/resolution records; merge behavior remains P31) |
| INV-031 | Every core projection aligns to the same `localRevision`. | Atomicity/failure-injection audit | VERIFIED (`P07-E001`, `P08-E001`, `P08-E003`: every P08 synchronous row is rebuilt at the target revision and verified before the atomic book advance; five fault phases roll back) |
| INV-032 | Vault fields never enter FTS, audit snapshots, logs or telemetry. | Static/privacy/device audit | IN_PROGRESS (`P05-E001`, `P05-E002`, `P07-E001`—`P07-E003`: separate vault ciphertext table, exact FTS allowlist and encrypted side-file scan; wider logs/telemetry remain P32) |
| INV-033 | Failed import, restore or large batch leaves the main ledger unchanged. | Shadow-DB fault injection | IN_PROGRESS (`P05-E001`, `P05-E002`, `P07-E001`, `P07-E003`, `P24-E003`, `P28-E002`, `P28-E005`: ordinary/large batch and import crash/cancel/source-row-99,999 failures leave the primary unchanged; restore exchange remains P31) |
| INV-034 | Every monetary accumulation detects `Long` overflow. | Boundary/property/static tests | VERIFIED (`P03-E002`, `P03-E006`, `P06-E002`, `P06-E003`: checked sum plus planner Long.MAX/overflow and static rejection) |
| INV-035 | Every cache depending on current transaction content carries a version. | Architecture/cache invalidation tests | IN_PROGRESS (`P05-E001`, `P07-E001`, `P08-E001`, `P08-E003`, `P14-E004`: synchronous and current-valuation/widget caches carry local/valuation revisions; later analytics/runtime caches retain owning-stage evidence) |

The 16 product-level system invariants in `需求.md` §26 remain additional acceptance constraints. They are covered by REQ rows and the architecture/security/operation gates; they do not replace the 35 canonical permanent invariants above.

## P15 journal, immutable revision and trash realization

P15 adds no generic transaction object and no mutable fact path. `TransactionFilter` is a closed value model; `JournalSelectionSpec` carries either explicit IDs or a query fingerprint plus exceptions; all mutation requests require typed stable IDs and expected revisions. Reads come from the SQLCipher projection/fact schema and writes delegate to `FinancialMutationCoordinator`.

| Domain/schema surface | P15 realization |
|---|---|
| `current_transaction_projection` | Descending `(occurred_at, transaction_id)` keyset page, local-date group key, type/state/source badges and account-context-only running balance (`P15-E003`, `P15-E004`) |
| `TransactionFilter` / `transaction_fts` / R*Tree | Complete typed dimensions with OR inside a dimension and AND between dimensions; bound FTS candidates and bound R*Tree candidates followed by exact Kotlin distance (`P15-E002`, `P15-E003`) |
| `TransactionRevision` / revision details | Current detail and historical snapshots remain separate; comparison names changed fields; restoring an old snapshot appends a new RESTORE revision (`P15-E004`) |
| `Journal` / `Posting` / typed Effects | Bulk edit, restore-old-version, trash and restore are planner-generated REVERSE/APPLY facts submitted only through the coordinator; no UI/application mapper constructs facts (`P15-E001`, `P15-E004`) |
| `transaction_dependency` | Typed tree plus closed `DependencyResolution`; unresolved required policy fails before the mutation plan is committed (`P15-E002`, `P15-E005`) |
| `command_receipt` / `book.localRevision` | Batch children share one BatchFinancialCommand and Room transaction; stale expected revision and duplicate command semantics retain the P08 fail-closed boundary (`P15-E002`, `P15-E004`) |
| Trash retention and purge | Trash reversal yields zero current net effect; assessment checks retention, every typed effect net, dependency, durable operation and backup reference. Physical deletion remains P31-only (`P15-E004`, `DL-069`) |
| Saved filter cache | Per-book AEAD ciphertext in no-backup storage with associated data; it is a Cache lifetime and never authoritative ledger state (`P15-E004`) |

## P16 refund realization

| Domain/schema surface | P16 realization |
|---|---|
| `RefundPayload` / `refund_revision_detail` | Closed linked/independent form with receiving account/card, expense classification, accrual/budget/project/goal policies, explicit excess and optional settlement/installment relationships (`P16-E002`, `P16-E003`) |
| `RefundAllocationFact` / `refund_allocation` | Planner-owned immutable apply/reversal rows freeze original transaction/revision and original/base minor amounts; canonical hash and audit include both directions (`P16-E001`—`P16-E003`) |
| `refund_status_projection` | Rebuilt synchronously from allocation facts; normal amounts retain exact gross/refunded/remaining, while explicit excess is retained in immutable facts and exposed separately under `DL-071` (`P16-E003`) |
| `transaction_dependency` | Rebuilt from net active allocations; original trash requires a complete reverse-dependent or convert-independent policy and commits all revisions/facts atomically (`P16-E002`, `P16-E003`) |
| Typed Effects | Refunds create `CONTRA_EXPENSE`, budget/project/goal RESTORE and inverse settlement effects; trash appends exact REVERSE facts and nets every family to zero (`P16-E002`, `P16-E003`) |
| Journal history/detail | Refund revisions read their receiving account and REFUND amount role; relation summaries expose original/refunded/remaining and cash/accrual/budget dimensions (`P16-E003`) |

## P14 transfer, adjustment, opening and FX realization

P14 adds no new transaction kind or mutable-history path. The application layer accepts four closed request variants with positive integer minor units and typed stable IDs. `SecureRoomSpecializedTransactionEntryPort` rehydrates current references, freezes amount/rate evidence and invokes the deterministic planner through `FinancialMutationCoordinator`.

| Domain/schema surface | P14 realization |
|---|---|
| `TransferPayload` | Same-currency amounts must match; cross-currency outgoing/incoming account amounts remain independent authorities but share one balanced base amount with explicit latest/cache/manual plus implied evidence (`P14-E002`, `P14-E004`) |
| `BalanceAdjustmentPayload` | Explicit increase/decrease; no classification/economic/budget effect. Optional association is stored only as immutable `balance_adjustment_revision_detail.checkpoint_id`; the immutable checkpoint row is never updated (`P14-E004`, `DL-064`) |
| `FxExchangePayload` | Different currencies, dual frozen valuations, exact base-minor spread cost and P06 FX clearing/rounding/cost/gain behavior (`P14-E002`, `P14-E004`) |
| `OpeningBalancePayload` | Unused account and one opening record only; immutable account currency and no income/expense/consumption/budget effect (`P14-E004`) |
| `RevisionAmount` / `FxRateSnapshot` | USER_INPUT/ACCOUNT/BASE amounts, provider/source/rate/quoted/fetched time and stale/manual flags append with the revision; later current-rate refresh leaves them byte-for-byte unchanged (`P14-E003`, `P14-E004`) |
| `account_valuation_current` | Current-date quote updates encrypted base values and stamps a new `as_of_valuation_revision`; `book.localRevision` and historical facts remain unchanged. Historical-date quotes cannot enter this current cache (`P14-E004`) |
| `CommandReceipt` / projections | Duplicate command returns the original receipt; all four writes and synchronous projections are one SQLCipher transaction, and every resulting Journal passes base debit/credit equality (`P14-E004`) |

## P13 ordinary transaction realization

P13 maps category-first expense/income entry to the existing immutable financial kernel without adding a feature-owned financial writer. `OrdinaryTransactionEntrySnapshot` is a typed Current/Projection read model; `OrdinaryTransactionWriteRequest` carries one category, one payer account, optional project/settlement/location/attachments and exact integer-minor evidence. `SecureRoomOrdinaryTransactionEntryPort` rehydrates frozen Current/Revision/Fact state and delegates create/edit planning to `FinancialMutationCoordinator`.

| Domain/schema surface | P13 realization |
|---|---|
| `BusinessTransaction` / `TransactionRevision` | Typed expense/income create and edit with `expectedRevisionId`; edit is coordinator-planned REVERSE/APPLY, never a mutable row update (`P13-E003`) |
| `RevisionAmount` / FX evidence | User/account/base amounts are positive integer minor units; expression is preserved; account/base conversion freezes existing valuation evidence and fails closed when foreign evidence is unavailable (`P13-E002`, `P13-E003`) |
| `Journal` / `Posting` / Effects | Produced only by the P06 deterministic planner behind the coordinator; ViewModel, Composable and feature sources cannot construct or persist them (`P13-E001`, `P13-E006`) |
| Current pointers/projections/receipt | Current revision, receipt, balances, all synchronous projections and `book.localRevision` advance atomically and are idempotent under the supplied command ID (`P13-E003`) |
| `LocationRecord` | Optional captured E7 coordinate row is a narrowly typed commit side effect in the same transaction; timeout/denial produces no row and never a later supplement (`P13-E003`) |
| `Attachment` / encrypted object | SAF bytes stream to the P10 encrypted object store before submission; cancellation/discard removes uncommitted objects; committed revision references only completed encrypted objects (`P13-E004`, inherited `P10-E003`) |
| Settlement shares | One activity, one payer and checked paid/owed totals; mismatched settlement currency is rejected until an explicit conversion path is selected (`P13-E002`, `P13-E004`) |

## P12 account and reference-data realization

P12 maps domain §6—§7 current entities and their Schema v1 rows through typed application drafts/views; no feature obtains a DAO, Entity or SQL connection. Reference-only operations append a `BookCommit`, `entity_revision` and `entity_change`, update the guarded current row, synchronously rebuild P08 projections and advance `book.localRevision` in one SQLCipher transaction. Opening balances use `RecordOpeningBalanceCommand`; category reassignment and referenced-place split use `BatchFinancialCommand`. Both financial paths execute through `FinancialMutationCoordinator`, never the reference-only writer.

| Domain/schema surface | P12 implementation status |
|---|---|
| `UserAccount` / `LedgerAccount` | Four closed types; row-versioned edit; immutable type; first-Posting currency lock; archive and empty-only delete; balance/valuation views (`P12-E002`, `P12-E003`) |
| `PaymentCard` | Separate current entity; bank/debit and credit/primary/supplementary compatibility; archive and replacement link; history count (`P12-E002`, `P12-E003`) |
| `Category` | Direction/depth/parent invariants; display/statistical/default fields; order/search; archive/tombstone audit; atomic historical reassignment through typed batch EDIT revisions (`P12-E002`—`P12-E004`, `P12-E008`) |
| `Merchant` / aliases | Normalized duplicate check; alias search/transfer; merge relation with projection resolution (`P12-E003`, `P12-E004`) |
| `Place` / `LocationRecord` | Fixed E7 centers, optional merchant, merge relation, immutable location query and atomic split clones with transaction revision fan-out; no online reverse geocoding (`P12-E003`, `P12-E004`, `P12-E008`) |
| `AccountBalanceCheckpoint` | Observed/calculated/checked difference; no Journal/Posting/Effect; adjustment link only after explicit separate transaction (`P12-E002`, `P12-E003`) |
| Account/goal/current projections | Current/daily balances, valuation evidence, running transaction balances, goal balances and exact two net-position metrics are read from synchronous P08 projections (`P12-E003`, `P12-E004`) |

`INV-029` is `VERIFIED`: archive, category tombstone, card replacement, historical category reassignment and referenced-place splitting all preserve historical identifiers. The last two use a coordinator-owned `BatchFinancialCommand` that rehydrates frozen facts, appends REVERSE/APPLY revisions under one `BATCH_MUTATION` commit, and clones location records instead of updating old revisions or snapshots in place (`P12-E008`, `DL-058`).

## P25 analytics and integrity realization

P25 completes the typed analytics lifecycle without adding a generic JSON report object or a second financial write entrance. `ReportSpec` and its closed measure/dimension/filter/sort/comparison vocabulary are typed query values; `ReportQueryPlan` freezes the local/valuation revisions; `ReportRow`, `MeasureValue` and opaque `DrilldownQueryId` are bounded projections. Original-currency measure values carry `CurrencyCode`, while economic and valuation results remain in the book base currency.

| Domain/schema surface | P25 realization |
|---|---|
| Typed report definition | Exactly 20 `FixedReportDefinition` values over one bounded `ReportSpec` AST; no arbitrary SQL/formula or universal transaction/report JSON (`P25-E001`, `P25-E002`) |
| Query planning/compiler | Closed rollup/effect/Posting source choice, allowlisted dimensions/filters/JOINs/aggregates, bound values, 500-row report cap and keyset drilldown (`P25-E002`, `P25-E003`) |
| Twelve analytics projections | Six daily plus six monthly total/category/account/merchant/project/place tables rebuild synchronously from immutable facts and stamp one local revision (`P25-E003`) |
| Projection lifecycle | Canonical hash, stale-version detection, savepoint dry rebuild and explicit maintenance repair; stale results are not displayed as current (`P25-E003`, `P25-E004`) |
| Integrity report | Database, foreign key, Journal, Posting currency, revision chain, projection, FTS, R*Tree and fact-rebuild checks through the SQLCipher application port (`P25-E003`, `P25-E004`) |
| Financial write boundary | `RoomProjectionEngine` calls the analytics rebuild inside the existing coordinator-owned transaction; ANA UI, report adapter and repair flow cannot create financial facts and never bypass `FinancialMutationCoordinator` (`P25-E001`, `P25-E006`) |

## P26 custom analytics configuration and deterministic methods

P26 completes the user-owned analysis configuration lifecycle without weakening the P25 query whitelist or financial write boundary. The application port accepts only typed `ReportSpec`, revisioned custom-definition/dashboard/anomaly requests and closed forecast keys. Configuration revisions are not accounting commits: they do not create Journal/Effects and do not advance `book.localRevision`.

| Domain/schema surface | P26 realization |
|---|---|
| Report current/revision | `ReportDefinition` points to an append-only `ReportDefinitionRevision`; normalized measure, dimension, sort, filter-node and typed filter-value rows encode the closed AST without JSON (`P26-E001`, `P26-E003`) |
| Dashboard current/revision | Multiple named dashboards retain immutable ordered item revisions; half-width is a closed metric-card-only variant and revision conflicts fail explicitly (`P26-E001`—`P26-E004`) |
| Anomaly current/revision | Five closed local rule types persist threshold, lookback and algorithm version in append-only revisions; findings are deterministic projections with complete disclosure (`P26-E002`, `P26-E003`) |
| Forecast/derived series | Exact integer/decimal current-average, recurrence-inclusive, historical-same-month, moving-average, trend and forecast outputs freeze algorithm version/window/assumptions; missing input yields insufficient data, never fabricated zero (`P26-E002`, `P26-E003`) |
| Schema v2 migration | One adjacent Expand→Switch Room migration installs 12 normalized analytics configuration tables and switches the contract hash; actual API 36 SQLCipher migration retains v1 book data and passes integrity/reopen (`P26-E003`) |
| Routes/UI/export | Stable IDs and one closed forecast key are the only route values; 7 screens/16 states use governed Vico/table/components; export stops at a typed IMAGE/PDF/CSV/XLSX payload until P29 (`P26-E001`, `P26-E004`, `P26-E005`) |

## P27 consumption map query and geographic realization

P27 completes the read-only geographic analytics surface over existing immutable facts and geographic indexes. `ConsumptionMapQuery` is a closed projection request, not a persisted report formula or financial command. It carries exact E7 viewport bounds, one of four accounting modes, amount/count weight, merchant/place aggregation, cluster/heat/single presentation and typed filters. Filter-option reads are bounded and do not expose DAO/Entity or unbounded ID collections to the feature.

| Domain/schema surface | P27 realization |
|---|---|
| Accounting measures | Consumption/refund and all-expense values read immutable EconomicEffect base amounts; cash flow reads frozen cash/bank Posting base amounts; all-located selects one frozen evidence path without using current FX (`P27-E002`, `P27-E003`) |
| RTree viewport | `location_rtree` produces candidates, exact E7 latitude/longitude predicates remove bounding false positives, and current revision/fact/filter predicates execute before aggregation (`P27-E001`, `P27-E003`) |
| Scale and result lifecycle | SQL aggregates by merchant/place/transaction and caps renderer output at 512 while preserving full viewport totals; 10,000 encrypted rows prove bounded point state and incremental viewport queries (`P27-E003`) |
| Detail/drilldown | A bounded in-memory opaque selection registry records query, point StableId and local revision; stale revision fails closed and keyset drilldown never carries coordinates, labels, money or complete result objects in routes (`P27-E001`, `P27-E003`) |
| SDK/UI boundary | App-owned MapLibre host consumes typed points; the feature owns governed controls, accessible fallback/data table and nine exact ANA states without importing geo/data/Room SDKs (`P27-E001`, `P27-E004`, `P27-E005`) |

## P28 import domain and encrypted application boundary

P28 completes the import runtime over the existing Schema v1 operation/import tables without changing the primary Room schema version. The staging database stays independently versioned and encrypted; raw input is never authoritative and only typed, validated application requests may cross into the primary or shadow ledger.

| Domain/schema surface | P28 realization |
|---|---|
| Stream model | CSV and XLSX readers emit typed cell/row/sheet values with a maximum reported row buffer of one; staging and preparation use bounded chunks/pages (`P28-E002`, `P28-E003`) |
| Staging authority | All seven staging tables are SQLCipher-encrypted under an operation-specific derived key; composite mapping order, row foreign keys and prepared payload hashes survive reopen (`P28-E001`, `P28-E004`) |
| Durable operation | Encrypted parameters persist source handle, format/header/charset and commit descriptor; checkpoints, pause/cancel/final-failure cleanup and COMMITTING replay preserve the closed operation state machine (`P28-E002`, `P28-E004`) |
| Financial boundary | General transactions materialize P24 batch requests; all writes execute through typed application ports and their `FinancialMutationCoordinator`; feature/Worker code owns no financial DAO or SQL (`P28-E001`, `P28-E005`) |
| Structured dependency graph | Fifteen closed entity kinds apply before/after transaction pages through reference, credit, installment, loan, budget, project/goal, settlement and recurrence application ports (`P28-E002`, `P28-E005`) |
| Atomicity and audit | Small batches use one main-database transaction; large/structured batches validate and atomically exchange a shadow. `import_record`, batch commits and source references support fingerprint idempotence, history and whole-batch undo (`P28-E005`) |

## Logical schema families

Source: domain/schema document §25. The inventory names every logical table, including the 11 typed transaction-detail tables. P07 verifies all 12 physical Schema v1 families; P08 verifies normalized plan mapping and transactional behavior for the financial write families while later feature/operation workflows retain their owning stages.

| ID | Family | Logical tables | Status |
|---|---|---|---|
| SCHEMA-FAMILY-01 | Book, commit and audit | `book`, `book_commit`, `book_commit_parent`, `command_receipt`, `entity_change`, `entity_revision`, `purge_tombstone` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-02 | Accounts and cards | `ledger_account`, `user_account`, `payment_card`, `card_vault_secret`, `account_balance_checkpoint` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-03 | Classification, merchants and places | `category`, `merchant`, `merchant_alias`, `place`, `location_record`, `location_rtree`, `place_rtree` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-04 | Transactions and revisions | `business_transaction`, `transaction_revision`, `revision_amount`, `fx_rate_snapshot`, `expense_revision_detail`, `income_revision_detail`, `transfer_revision_detail`, `refund_revision_detail`, `credit_payment_revision_detail`, `loan_disbursement_revision_detail`, `loan_payment_revision_detail`, `balance_adjustment_revision_detail`, `fx_exchange_revision_detail`, `settlement_payment_revision_detail`, `opening_balance_revision_detail`, `transaction_revision_attachment`, `transaction_revision_settlement_share`, `transaction_dependency` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-05 | Accounting facts | `journal_entry`, `posting`, `economic_effect`, `budget_effect`, `project_effect`, `goal_effect`, `statement_effect`, `loan_effect`, `settlement_effect` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-06 | Refunds | `refund_allocation`, `refund_status_projection` | VERIFIED (`P07-E001`—`P07-E003`, `P16-E002`, `P16-E003`: immutable planner facts/reversals, exact SQLCipher cumulative query, synchronous dependency/status rebuild and canonical-hash equality pass) |
| SCHEMA-FAMILY-07 | Credit and installments | `credit_account_profile`, `credit_limit_period`, `credit_statement`, `credit_statement_revision`, `credit_payment_allocation`, `installment_plan`, `installment_plan_revision`, `installment_schedule_revision`, `installment_schedule_item`, `installment_refund_allocation` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-08 | Loans | `loan_contract`, `loan_tranche`, `loan_terms_revision`, `loan_rate_period`, `loan_schedule_revision`, `loan_schedule_item`, `loan_actual_allocation`, `loan_simulation`, `loan_simulation_item` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-09 | Settlement | `participant`, `settlement_activity`, `settlement_activity_participant`, `settlement_payment_record` | VERIFIED (`P07-E001`—`P07-E003`, `P22-E003`: unique self, ordered membership, immutable payments and real encrypted CRUD/reopen/integrity behavior pass) |
| SCHEMA-FAMILY-10 | Budget, project and goal | `project`, `goal`, `goal_movement`, `budget_template`, `budget_template_revision`, `budget_template_category_limit`, `budget_month`, `budget_month_revision`, `budget_category_limit`, `budget_adjustment`, `budget_rollover` | VERIFIED (`P07-E001`—`P07-E003`) |
| SCHEMA-FAMILY-11 | Templates and recurrence | `transaction_blueprint`, `transaction_blueprint_revision`, `blueprint_settlement_share_rule`, `recurrence_series`, `recurrence_series_revision`, `recurrence_rule_weekday`, `recurrence_exception`, `recurrence_occurrence`, `recurrence_candidate` | VERIFIED (`P07-E001`—`P07-E003`, `P23-E001`—`P23-E003`: P23 supplies encrypted adapters, immutable revisions/exceptions, idempotent occurrences, fact-free candidates and coordinator-owned confirmation) |
| SCHEMA-FAMILY-12 | Attachments | `encrypted_blob`, `attachment`, `blob_gc_candidate` | VERIFIED (`P07-E001`—`P07-E003`) |

Implementation rules from §33: export Room Schema JSON from v1; no destructive migration; independently version main DB, import staging DB and backup manifest; preserve `rule_set_version`; use Expand → Backfill → Switch → Contract; never reinterpret historical facts; run SQLCipher, integrity, FK, journal-balance, subtype, projection, FTS/R*Tree, scale and old-backup validation after migration.

## Projection and index families

Source: domain/schema document §§26–27. Every core projection stores `as_of_local_revision`; current-valuation projections additionally store `as_of_valuation_revision`.

| ID | Family | Members | Status |
|---|---|---|---|
| PROJECTION-FAMILY-01 | Current transaction | `current_transaction_projection` | VERIFIED (`P07-E001`, `P08-E001`—`P08-E003`: deterministic rebuild, version/count audit and typed keyset query pass on SQLCipher) |
| PROJECTION-FAMILY-02 | Account | `account_balance_current`, `account_valuation_current`, `account_balance_daily` | VERIFIED (`P08-E001`, `P08-E003`, `P14-E004`: balance/current/daily rebuild plus current FX valuation and independent local/valuation revision stamping pass on SQLCipher) |
| PROJECTION-FAMILY-03 | Budget and planning | `budget_usage_projection`, `budget_future_reservation`, `project_usage_projection`, `goal_balance_projection`, `budget_rollover`, `refund_status_projection` | VERIFIED (`P08-E001`, `P08-E003`, `P16-E003`, `P17-E002`—`P17-E004`, `P18-E002`, `P18-E003`: budget/rollover/refund/project/goal effects rebuild deterministically at one local revision; recurrence reservations already participate as explicit typed rows) |
| PROJECTION-FAMILY-04 | Liabilities | `credit_statement_projection`, `credit_account_projection`, `installment_progress_projection`, `loan_progress_projection`, `loan_future_cashflow_projection`, `loan_simulation_item` | VERIFIED (`P08-E001`, `P08-E003`, `P19-E003`, `P20-E003`, `P21-E003`: credit, installment, loan progress and strict-future cash-flow rows rebuild/version exactly; simulation items remain isolated from current facts) |
| PROJECTION-FAMILY-05 | Settlement | `settlement_position_projection` | VERIFIED (`P07-E001`, `P08-E001`, `P08-E003`, `P22-E002`, `P22-E003`: paid/owed/settled/net positions rebuild canonically at the target revision; application-generated suggestions consume only this typed result) |
| PROJECTION-FAMILY-06 | Analytics | `analytics_daily_total`, `analytics_daily_category`, `analytics_daily_account`, `analytics_daily_merchant`, `analytics_daily_project`, `analytics_daily_place` and corresponding `analytics_monthly_*` tables | VERIFIED (`P07-E001`, `P25-E002`, `P25-E003`: all twelve narrow tables rebuild synchronously from immutable facts, carry one local revision, execute every fixed report and reproduce the same canonical hash on SQLCipher) |
| PROJECTION-FAMILY-07 | Widgets | `widget_book_snapshot`, `widget_account_snapshot`, `widget_credit_snapshot`, `widget_goal_snapshot` | IN_PROGRESS (`P08-E001`, `P08-E003`, `P14-E004`: all four transaction snapshots and current valuation-dependent book snapshot carry local/valuation versions; Glance scheduling/rendering remains P33) |
| INDEX-FAMILY-01 | Search | `transaction_fts`; current active transactions only; no vault/account/location-sensitive fields | VERIFIED (`P07-E001`—`P07-E003`, `P08-E001`—`P08-E003`, `P15-E002`, `P15-E003`, `P24-E003`: exact allowlist, active-only synchronous rebuild/audit, bound FTS candidate query and 500,000-row encrypted device execution) |
| INDEX-FAMILY-02 | Geography | `location_rtree`, `place_rtree`; R*Tree bounding candidates followed by precise Kotlin distance | VERIFIED (`P07-E001`—`P07-E003`, `P08-E001`—`P08-E003`, `P15-E002`, `P27-E003`: both indexes rebuild; radius uses bounded candidates plus Kotlin Haversine, and the map uses RTree viewport candidates plus exact E7 containment, complete filters and bounded database aggregation) |

## Background, staging, import, backup and recovery operations

Sources: architecture §§13–15 and domain/schema §28.

| ID | Scope | Required records/states | Status |
|---|---|---|---|
| BACKGROUND-OPERATION | Durable operation state | `background_operation`, `operation_checkpoint`; QUEUED → PREPARING → RUNNING → PAUSED/CANCEL_REQUESTED/FAILED_* → COMMITTING/ROLLING_BACK → SUCCEEDED | VERIFIED for import (`P05-E001`, `P05-E002`, `P07-E001`, `P28-E001`, `P28-E002`, `P28-E004`: encrypted parameters/checkpoints, foreground execution, pause/cancel/retry/crash recovery and cleanup pass; later operation types retain their owning phases) |
| IMPORT-METADATA | Main-database import audit | `import_record`, `import_batch_commit`, `import_source_reference` | VERIFIED (`P05-E001`, `P07-E001`, `P28-E005`: fingerprint idempotence, exact source-row audit/history and whole-batch undo pass on the primary/shadow SQLCipher runtime) |
| IMPORT-STAGING | One-time encrypted SQLCipher staging DB | `staging_raw_row`, `staging_parsed_row`, `staging_mapping`, `staging_validation_error`, `staging_duplicate_candidate`, `staging_prepared_command`, `staging_attachment` | VERIFIED (`P05-E001`, `P07-E001`, `P07-E003`, `P28-E002`, `P28-E004`: 100,000 rows, all seven tables, mappings/duplicates/commit descriptors and reopen/cleanup pass on API 34) |
| BACKUP-RESTORE-METADATA | Backup/Drive/restore/merge | `backup_repository`, `backup_snapshot`, `backup_object`, `backup_snapshot_object`, `drive_upload_session`, `restore_record`, `merge_session`, `merge_conflict`, `merge_resolution` | IN_PROGRESS (`P05-E001`, `P05-E002`, `P07-E001`: normalized encrypted physical records complete; runtime remains P29-P31) |

Worker/UIDT/service payloads may contain only `operationId`; full parameters remain encrypted in the primary database. Large import/restore/batch flows must use the same planner/coordinator, checkpoints, atomic commit/exchange, rollback and temporary cleanup.

## Mandatory test and release quality gates

| Gate | Frozen source | Status |
|---|---|---|
| Pure domain property suite for accounting, refunds, FX, loans, installments, budget, settlement, expressions and recurrence | Tech stack §16; architecture §21 | IN_PROGRESS (`P03-E002`—`P03-E005`, `P05-E002`, `P06-E001`—`P06-E003`, `P16-E002`, `P17-E002`, `P18-E002`, `P20-E002`, `P21-E002`, `P22-E002`, `P23-E002`: the named implemented aggregates now include 2,000 generated recurrence rules; later owning-stage aggregates/final release replay remain) |
| Room/SQLCipher schema, all migrations, FTS5, R*Tree, WAL plaintext and projection rebuild on device | Tech stack §§5,16; architecture §21 | IN_PROGRESS (`P07-E001`—`P07-E004`, `P08-E003`, `P17-E003`, `P18-E003`, `P25-E003`, `P26-E003`: v1 create/reopen/capabilities/leakage, actual non-destructive v1→v2 migration and exact synchronous financial/budget/project/goal/analytics projection audit/rebuild are verified on API 36; future registered migrations retain their owning stages) |
| Keystore, BiometricPrompt, SAF, location and foreground/UIDT behavior on actual devices | Tech stack §16 | IN_PROGRESS (`P09-E003`, `P09-E004`, `P10-E003`, `P10-E004`, `P28-E003`, `P28-E004`: Keystore/credential, SAF streaming/provider, foreground location and import parser/staging foreground runtime pass; backup/restore platform behaviors remain P29—P31) |
| Failure injection for attachment, commits, Drive, storage, restore exchange, Keystore, biometrics, row 99,999 and projection versions | Architecture §21.3 | IN_PROGRESS (`P08-E003`, `P09-E003`, `P09-E004`, `P10-E003`, `P28-E002`, `P28-E005`: commit/projection, key/auth, attachment failures, import crash/cancel/corruption and source row 99,999 rollback pass; Drive/restore failure injection remains later) |
| Architecture/static privacy boundaries and coordinator-only financial writes | Architecture §21.4; UI contract §16.6 | VERIFIED (`P02-E003`, `P02-E004`, `P08-E001`, `P08-E002`, `P10-E006`, `P15-E001`, `P15-E007`, `P16-E001`, `P16-E006`, `P17-E001`, `P17-E006`, `P18-E001`, `P18-E006`, `P22-E001`, `P22-E006`, `P23-E001`, `P23-E006`, `P24-E001`, `P24-E006`, `P27-E001`, `P27-E006`, `P28-E001`, `P28-E007`: feature SDK/infrastructure, privileged ports, SQL/DAO/fact, sensitive draft/route/Worker payload and direct-writer bypasses are rejected) |
| 215-screen route/state/component coverage, screenshots, three languages, accessibility and privacy semantics | UI contract §§13,16–17 | IN_PROGRESS (`P04-E001`—`P04-E008`, `P10-E004`, `P10-E005`, `P11-E004`—`P11-E006`, `P14-E005`, `P14-E006`, `P15-E005`, `P15-E006`, `P16-E004`, `P16-E005`, `P17-E004`, `P17-E005`, `P18-E004`, `P18-E005`, `P22-E004`, `P22-E005`, `P23-E004`, `P23-E005`, `P24-E004`, `P24-E005`, `P25-E004`, `P25-E005`, `P26-E004`, `P26-E005`, `P27-E004`, `P27-E005`, `P28-E006`: cross-cutting matrix plus all implemented ANA/IMP states and production-pixel goldens pass; later screens/final acceptance remain) |
| Target-scale paging, reports, map, 100k-row import and tens-of-GB streaming operations | Requirements §25; UI contract §16.5 | IN_PROGRESS (`P10-E003`, `P10-E004`, `P15-E003`, `P25-E002`, `P25-E003`, `P27-E003`, `P27-E004`, `P28-E002`, `P28-E004`, `P28-E006`: bounded streaming, real 500,000-transaction keyset/FTS, bounded reports/maps and 100,000-row low-memory/encrypted/virtualized import pass; export/backup/restore tens-of-GB gates remain later) |
| Release AAB, Baseline Profile, locks, verification metadata, SBOM, licenses, NOTICE and privacy/release documentation | Tech stack §16.4 and release plan | IN_PROGRESS (`P02-E006` verifies locks/SBOM/license task infrastructure only; release evidence remains P36) |

# P05 Domain API Mapping

Last updated: 2026-08-01 (Asia/Tokyo)

Scope: pure Kotlin domain/application contracts only. This mapping does not claim Room entities, DAO behavior, SQLCipher persistence, projection rebuilds, feature UI, or the P06 accounting-rule implementation. The four excluded visual drafts were not read or used.

## Lifecycle separation

| Frozen lifecycle | P05 type boundary | Representative records |
|---|---|---|
| Current (C) | `LifecycleRecord<RecordLifecycle.Current>` | `Book`, `UserAccount`, `Category`, `BusinessTransaction`, `Project`, `Goal`, `LoanContract` |
| Revision (R) | `LifecycleRecord<RecordLifecycle.Revision>` | `TransactionRevision`, `RevisionAmount`, `BudgetMonthRevision`, `LoanTermsRevision`, `RecurrenceSeriesRevision` |
| Fact (F) | `LifecycleRecord<RecordLifecycle.Fact>` | `BookCommit`, `CommandReceipt`, `JournalEntry`, `Posting`, all effect records, `PurgeTombstone` |
| Projection (P) | `LifecycleRecord<RecordLifecycle.Projection>` | account/refund/goal/credit/loan/settlement projections and typed projection changes |
| Cache (K) | `LifecycleRecord<RecordLifecycle.Cache>` | `LoanSimulation`, `VersionedReportCache` with revision evidence |
| Operation (O) | `LifecycleRecord<RecordLifecycle.Operation>` | background/import/staging/backup/restore/merge records and checkpoints |

No core aggregate uses a `Map<String, Any?>`, `JsonObject`, `JsonElement`, `JSONObject`, or a universal transaction property bag. The only flexible maps are closed, typed collections such as participant weights, date-indexed forecast inputs, and import value mappings.

## Frozen domain document chapter mapping

| Mapping ID | Source chapter | P05 implementation evidence | Remaining later evidence |
|---|---|---|---|
| P05-DOM-01 | §1 append-only/privacy-purge resolution | `PurgeEligibility`, `PurgeTransactionCommand`, `PurgeTombstone.supersedes`, merge conflict purge-only resolution | P07/P31 database deletion, maintenance lock and merge integration |
| P05-DOM-02 | §2 model layering | Six closed `RecordLifecycle` markers and fixed lifecycle on every record family | P07 table markers and database enforcement |
| P05-DOM-03 | §3 contexts/aggregate roots | Separate Book, Ledger, Account, Classification, Budget, Project, Goal, Refund, Credit, Loan, Settlement, Automation, Attachment, Analytics and Transfer models | Feature/data integration in owning phases |
| P05-DOM-04 | §4 value objects | Strong stable IDs, `PositiveMoney`, currency-checked `AccountAmount`, `Percentage`, `InterestRate`, `Hash256`, `GeoPoint`, display and time/value reuse from P03 | Database converters in P07 |
| P05-DOM-05 | §5 Book/Commit/idempotency | `Book`, `BookCommit`, `CommandReceipt`, `EntityChange`, `EntityRevision`, `FinancialCommand.commandId`, dual local/valuation revision | Commit graph persistence and merge in P07/P31 |
| P05-DOM-06 | §6 accounts/cards/vault | `LedgerAccount`, `UserAccount`, `AccountSnapshot`, `PaymentCard.create`, `CardVaultSecret`, checkpoint and archive/delete/currency policies | Vault crypto and persistence P09/P12 |
| P05-DOM-07 | §7 category/merchant/place/location | Closed category direction/nature/depth, merchant aliases/merge, place and immutable E7 `LocationRecord` | Database/reference screens P10/P12 |
| P05-DOM-08 | §8 transaction aggregate | `BusinessTransaction`, immutable `TransactionRevision`, exactly 11 sealed payloads and full source/action/lifecycle enums | P06 planners and P07 persistence |
| P05-DOM-09 | §9 amount evidence | `RevisionAmount`, exact role/representation enums, positive typed money, account/base/statement/settlement evidence and immutable `FxRateSnapshot` | Mapping/persistence P07/P08 |
| P05-DOM-10 | §10 Journal/Posting | Private validated factories, positive amounts, typed side/currency/rule version/reversal relationships and balanced checked totals | Complete accounting rule generation P06 and database constraints P07 |
| P05-DOM-11 | §11 transaction accounting matrix | Typed transaction payload component models plus effect component/nature/role enums prevent a universal signed-amount transaction | Rule-by-rule planner properties P06 |
| P05-DOM-12 | §12 create/edit/trash/restore/dependencies | Commands require `expectedRevisionId`; dependency types and closed policies; edit kind stability; purge eligibility | Revision planner chains P06/P08/P15/P31 |
| P05-DOM-13 | §13 four-layer effects | Separate immutable Economic/Budget/Project/Goal/Statement/Loan/Settlement effects and typed synchronous `ProjectionChangeSet` | Planner generation P06 and projection persistence P08 |
| P05-DOM-14 | §14 refunds | Typed allocation, independent/linked mode, excess override and distinct accrual/budget/project/goal policies plus refund projection | Refund accounting/property integration P16 |
| P05-DOM-15 | §15 budgets | Template/month current+revision, hierarchy limits, checked policy, adjustments, signed rollover projection and daily-available result | Rollover/rebuild algorithms P17 |
| P05-DOM-16 | §16 projects | One optional `ProjectId` per transaction context, project budget/snapshot/status/goal relation | Project effects and UI P17/P18 |
| P05-DOM-17 | §17 goals | Goal/account currency, manual movement, separate immutable effects, balance/availability projections and underfunded state | Goal planners/projections P18 |
| P05-DOM-18 | §18 credit statements | Profile rules/time zone/limits, current statement, immutable official/estimated revision, assignment and payment allocations | Statement algorithms/persistence P19 |
| P05-DOM-19 | §19 installments | Plan/current revision, closed fee/prepayment/refund policies, schedule revisions/items and exact principal-conservation policy | Schedule algorithms P20 |
| P05-DOM-20 | §20 loans | Contract/tranches, terms/rate periods, overlap policy, schedule revisions/items, actual allocations, typed simulation cache and progress projection | Calculation algorithms P21 |
| P05-DOM-21 | §21 settlement | Participant/activity/share/payment records, one-payer and paid/owed/signed-delta exact conservation, external-payment no-local-account type | Settlement planners and persistence P22 |
| P05-DOM-22 | §22 blueprint/recurrence | Current+revision blueprint, typed share rules, complete recurrence rule/scope/status/exception/occurrence key and non-financial candidate | Occurrence engine P23 |
| P05-DOM-23 | §23 attachments | `EncryptedBlob`, `Attachment`, immutable encrypted/hash values, status and operation-lifecycle GC candidate | Encrypted object store P10 |
| P05-DOM-24 | §24 database common types | Domain values preserve Stable UUID, Instant/LocalDate/YearMonth, Long minor, BigDecimal rate and integer E7 semantics | Room converters P07 |
| P05-DOM-25 | §25 logical table design | C/R/F/P/O domain contracts cover all twelve table families; transfer contracts cover operation/import/backup metadata | Exact Room entities/DDL P07 |
| P05-DOM-26 | §26 query projections | Typed account/refund/goal/credit/loan/settlement results, projection change set and versioned report cache | Complete query projection entities/services P08 |
| P05-DOM-27 | §27 FTS/map indexes | Typed local suggestion, transaction query and analytics filter ports exclude vault fields and arbitrary SQL | FTS/R*Tree P08/P26 |
| P05-DOM-28 | §28 background/import/restore | Durable operation state, opaque launch token, all seven staging record types and backup/restore/merge metadata/ports | WorkManager/UIDT/SQLCipher staging P28-P31 |
| P05-DOM-29 | §29 key indexes | Keyset request uses `(occurredAt, transactionId)`; typed repository queries expose index-shaped requests without DAO/Entity | Index DDL/query plans P07/P08 |
| P05-DOM-30 | §30 constraint ownership | Constructor/type closure handles obvious invalid states; `FinancialMutationPlanValidator` handles cross-record invariants; DB-owned constraints remain out of domain | Database constraints/audit P07/P08 |
| P05-DOM-31 | §31 atomic writes | `DefaultFinancialMutationCoordinator` performs receipt lookup → snapshot → plan → validate → single atomic commit port under write gate | Room `withTransaction` implementation P07/P08 |
| P05-DOM-32 | §32 permanent invariants | P05 models/policies cover INV-001/002/003/004/007/010/018/019/022/023/025/026/027/028/030/031/034/035 foundations | Facts/persistence-dependent invariants remain P06-P31 |
| P05-DOM-33 | §33 migration rules | Every historical fact carries immutable rule/version/hash evidence where applicable; APIs do not reinterpret facts | Schema JSON/migration device evidence P07+ |
| P05-DOM-34 | §34 implementation order | P05 implements the complete pure model/port layer in the frozen dependency order without page or DAO implementation | P06 starts accounting planners; P07 starts schema |
| P05-DOM-35 | §35 final model | Sealed transaction → immutable revision → typed amount/fact/effect graph plus separate projection/application ports | End-to-end database/query proof P06-P08 |

## Compile-time closure

- `RecordExpenseCommand` accepts only `NewTransactionInput<ExpensePayload>` and `ExpensePayload` requires one non-null `CategoryAssignment` and one `ExpensePayer`; income has the equivalent required classification.
- `TransactionContextInput` exposes one `ProjectId?` and one `GoalId?`, never a list; no split category or multi-project command shape exists.
- Commands accept currency-checked `AccountAmount`, whose real constructor and data-class copy are private; callers must use `AccountAmount.create(AccountSnapshot, Money)` and cannot pass raw account money into a formal payload.
- `TransactionPayload` is sealed with exactly 11 implementations matching `TransactionKind`; candidate occurrences do not implement `FinancialCommand` and carry no Journal/Posting/effect fields.
- Remaining cross-record rules are fail-closed in `FinancialMutationPlanValidator` and the specific budget, schedule, settlement, card, rate-period and purge policies.

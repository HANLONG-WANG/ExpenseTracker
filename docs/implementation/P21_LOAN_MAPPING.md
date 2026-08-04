# P21 Loan Mapping

Last updated: 2026-08-04 (Asia/Tokyo)  
Stage: P21 — Loans  
Status: VERIFIED

## Frozen-scope result

P21 implements REQ-041 and REQ-042 and the loan model in domain-model §20/§25.8. It covers `LIA-001`, `REC-017`—`REC-019` and `LOA-001`—`LOA-011`: 15 destinations and 41 required states. P22 and later recurrence, settlement, analytics and maintenance work is not promoted.

## Domain and application mapping

| Frozen concept | Typed implementation | Enforced result |
|---|---|---|
| Loan contract and combination display account | `LoanContract`, `LoanContractMutation`, `LoanContractView` | One contract owns one or more tranches; the display account is not used as an authoritative posting account. |
| Tranche and dedicated liability ledger | `LoanTranche`, `LoanTrancheMutation`, `LoanTrancheView.ledgerAccountId` | Every tranche has an exact ledger account and principal; disbursement/payment allocations identify the tranche explicitly. |
| Terms and rate stages | `LoanTermsRevision`, `LoanRatePeriod`, `LoanTermsDraft` | Repayment method, fixed/floating rate, frequency, rounding, prepayment policy and penalty are versioned; periods must be ordered, gap-free for scheduled dates and non-overlapping. |
| Repayment methods | `LoanAccountingPolicy.generate` | Equal payment, equal principal, interest-only, bullet and custom schedules use checked minor units and deterministic `BigDecimal`; the last payment closes principal tail exactly. |
| Schedule and revisions | `LoanScheduleRevision`, `LoanScheduleItem`, `LoanScheduleSummary` | Future items remain immutable, ordered plan evidence. Saving new terms, rate changes or an applied simulation appends a new terms/schedule version and retains old versions. |
| Actual payment allocation | `LoanActualAllocation`, `LoanPaymentAmountsDraft`, `LoanAccountingPolicy.validatePayment` | Principal, interest, fee and penalty sum exactly to the paid total; principal cannot exceed remaining principal. |
| Partial/full prepayment | `LoanPrepaymentSimulation`, `LoanAccountingPolicy.simulatePrepayment` | Shorten-term and reduce-payment strategies produce an independently reviewable before/after plan; full settlement closes remaining principal. |
| Commands and persistence port | `SaveLoanContractRequest`, `RecordLoanDisbursementRequest`, `RecordLoanPaymentRequest`, `LoanSimulationRequest`, `LoanApplicationPort` | Stable IDs, `commandId`, `expectedRevision`, transaction IDs and immutable evidence are typed. No DAO/entity escapes to the feature. |

## Accounting and atomic write mapping

All formal financial mutation paths call `DefaultFinancialMutationCoordinator`, the concrete `FinancialMutationCoordinator`. Composables and `AppRootViewModel` submit typed application requests and never create `JournalEntry`, `Posting` or financial SQL.

| Operation | Planner/facts | Classification |
|---|---|---|
| Disbursement | `RecordLoanDisbursementCommand` → balanced postings plus per-tranche `LoanEffect(PRINCIPAL)` | Receiving asset and loan liability increase; no income or expense is produced. |
| Actual repayment | `RecordLoanPaymentCommand` → balanced postings, `LoanActualAllocation`, component `LoanEffect`s and economic effects | Principal reduces liability with no expense. Interest, fee and penalty are non-consumption expense and never consumption. |
| Contract/terms/schedule save | `SaveLoanContractCommand` and `LoanContractMutation` | Versioned metadata and plan rows are written inside the same encrypted Room transaction/receipt/book revision as any associated formal payment. |
| Simulation | `LoanSimulationRequest` and simulation tables | Calculation and storage are non-ledger sandbox data. Applying requires an explicit high-risk confirmation and creates a new terms/schedule version through the coordinator. |
| Retry/conflict | canonical command hash, `LoanReplayReceiptVerifier`, `expectedRevision` | Exact replay returns the existing receipt before stale-version evaluation; a changed payload or stale unrelated command fails closed. |

`RoomLoanContractWriter` performs explicit insert/check/update for `loan_contract`, `loan_tranche`, `loan_terms_revision`, `loan_rate_period`, `loan_schedule_revision` and `loan_schedule_item`. Actual transaction revisions, Journals, Postings, Effects, allocations, current pointers, receipts, projections and `book.localRevision` stay under `RoomFinancialCommitRepository`'s single SQLCipher transaction.

## §25.8 schema and projection mapping

| Frozen table/family | P21 writer/query/projection |
|---|---|
| `loan_contract`, `loan_tranche` | `RoomLoanContractWriter`; `SecureRoomLoanApplicationPort.loadContract/loadTranche` |
| `loan_terms_revision`, `loan_rate_period` | append-only version writer and typed rate-stage reader |
| `loan_schedule_revision`, `loan_schedule_item` | append-only schedule writer; accessible plan/actual table reader |
| `loan_disbursement_revision_detail`, `loan_payment_revision_detail` | normalized financial plan writer through the coordinator |
| `loan_actual_allocation`, `loan_effect` | normalized immutable fact/effect writer; exact component and remaining-principal queries |
| `loan_simulation`, `loan_simulation_item` | isolated simulation save/load; never current transaction or balance input |
| `loan_progress_projection` | synchronous fact-derived principal/interest/next-payment rebuild at `asOfLocalRevision` |
| `loan_future_cashflow_projection` | latest schedule only and strictly `planned_date > today`; forecast-only rows carry schedule and local revisions |

## Permanent-invariant evidence

| Invariant | Automated proof |
|---|---|
| Journal balance and exact payment sum | `LoanApplicationPortDeviceTest` checks every disbursement/payment Journal and all component totals. |
| Loan principal repayment creates no expense | planner/domain tests plus the SQLCipher test assert principal-only repayment has no economic expense. |
| Interest, fees and penalties are non-consumption expense | planner/domain tests and device queries assert separate non-consumption effects. |
| Loan-schedule principal total equals principal still to be repaid | `LoanAccountingPolicyTest` proves principal conservation for every method and 2,000 generated schedules, including rounding tails and full/partial prepayment. |
| Rate stages cannot overlap | constructors and policy reject overlaps; tests also reject gaps at scheduled payment dates. |
| Combination totals equal tranche totals | API/device fixtures use two tranches and compare contract totals, per-tranche principal and rebuilt projections. |
| Future plan is forecast, not current state | device tests assert no planned item appears as a transaction/fact/balance row and that future projection uses a strict date boundary. |
| Simulation is pure until apply | before/after database counts and revisions remain unchanged; apply requires matching base version and appends new versions. |
| Overflow fails closed | the authoritative schedule/payment path uses checked `Long` operations and has explicit boundary tests. |

## UI, route and accessibility mapping

Routes are generated from the frozen YAML by `LoanRootDestination`; arguments are limited to nullable/non-null `StableId` values for contract, tranche, transaction and simulation identities. Amount, lender/name, account, rate, fee, penalty, notes and full objects cannot enter routes or saved navigation state.

The governed `LoanDestination` renders all 41 required states using designsystem components and stable `LedgerTestTags`. It provides combination summaries, tranche selection, terms and rate timelines, an accessible schedule table with planned/actual distinction, payment reconciliation, overdue/plan-difference warnings, a simulation comparison and high-risk apply confirmation. UI automation covers 320/360/480dp, 100/130/200% font, zh-CN/ja-JP/en-US and light/dark. Two full-pixel golden digests are produced only from production Compose, tokens and textual/YAML/CSV contracts; excluded visual drafts are not inputs.

## Acceptance status

P21 is `VERIFIED`. Evidence IDs `P21-E001`—`P21-E007` are recorded in `TEST_EVIDENCE.md`. P23 retains occurrence-engine integration for automatically due formal/candidate records; P26 owns wider forecast analytics; no later stage is claimed.

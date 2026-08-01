# P06 Accounting Planner and Invariant Mapping

Last updated: 2026-08-02 (Asia/Tokyo)

P06 implements a pure, deterministic accounting plan. `AccountingPlanningContext` is the complete explicit input boundary for time, IDs, account/reference snapshots, historical FX evidence and current immutable facts. The planner reads no Android service, database, network, system clock or random source. Persistence, SQL constraints, projection tables and migration audits remain P07/P08 and are not claimed here.

## Rule coverage

| Frozen transaction kind | Rule result |
|---|---|
| Expense | Asset/liability or settlement counter-posting; consumption/non-consumption EconomicEffect; optional Budget/Project/Goal/Statement/Settlement effects |
| Income | Receiving account plus frozen regular/non-recurring income account; EconomicEffect and credit-account StatementEffect when applicable |
| Transfer | Direct same-currency posting or two-entry FX clearing bridge; no EconomicEffect |
| Refund | Receiving account, contra-expense, independently dated Budget/Project/Goal/Statement effects |
| Credit payment | Asset reduction plus credit-liability reduction; exact allocation StatementEffects; no expense/income; confirmation candidates rejected |
| Loan disbursement | Asset plus loan liability; LoanEffect; no expense/income |
| Loan payment | Principal to liability; interest/fee/penalty to non-consumption expense; typed LoanEffects |
| Balance adjustment | Normal-balance movement against adjustment equity; credit-account StatementEffect when applicable; no income/expense/budget |
| FX exchange | Source/target account entries through FX clearing; exact rounding/cost/gain account selected from frozen evidence |
| Settlement payment | Balanced local-account/position posting when self participates; external-only payment changes SettlementEffects only |
| Opening balance | Account normal side against opening equity; credit-account StatementEffect when applicable; no ordinary economic or budget effect |

## Permanent invariant audit

`AUTOMATED` means a repeatable domain/static test exists now. It does not promote database- or feature-dependent acceptance ahead of its owner phase.

| Invariant | P06 classification and evidence | Later boundary retained |
|---|---|---|
| INV-001 | AUTOMATED — P06 `AccountingPlannerPropertyTest` checks 2,500 generated lifecycle inputs and every rule test asserts Journal base balance; `JournalEntry.create` rejects imbalance/overflow. | P07 database audit and restore verification remain. |
| INV-002 | AUTOMATED — P06 `Posting.create`, `FrozenAmountEvidence` and all 11 rule tests validate ledger/account currency; inherited mismatch test remains. | P07 persisted ledger lookup audit remains. |
| INV-003 | AUTOMATED — P06 consumes the P05 sealed single classification/project/goal input and emits only one typed target per effect family. | Import and feature acceptance remain. |
| INV-004 | AUTOMATED — P06 plan validator requires component index 0 with exactly USER_INPUT/ACCOUNT/BASE representations for ordinary expense/income; generated tests cover it. | Import/UI acceptance remains. |
| INV-005 | AUTOMATED — P06 validates transaction/current revision ID, kind, state, prior revision and monotonic revision number before producing a plan. | P07 foreign-key/current-pointer audit remains. |
| INV-006 | AUTOMATED — P06 `ImmutableFactAudit` compares every reversed Journal, Posting and Effect field and preserves the original effective time. | P07 DAO append-only enforcement remains. |
| INV-007 | AUTOMATED — P06 checks prior reversed APPLY IDs, unique reversal targets and exact one-to-one reversal bundles. | P07 unique database constraint remains. |
| INV-008 | AUTOMATED — P06 create/restore tests require APPLY-only facts; edit equivalence proves reverse-old plus apply-new. | P07 chain audit remains. |
| INV-009 | AUTOMATED — P06 500-case trash property combines old facts with REVERSE facts and obtains zero account/base net for every ledger. | P07 persisted chain audit remains. |
| INV-010 | AUTOMATED — P06 retains and executes `refundsWithinBalance`; linked refunds require status evidence unless the explicit override is present. | Refund UI and persisted aggregate audit remain P16. |
| INV-011 | AUTOMATED — P06 refund test independently freezes cash-flow effective time, accrual date and budget target month. | Cross-month projection integration remains P16/P17. |
| INV-012 | AUTOMATED — P06 credit-payment rule test emits StatementEffect and no EconomicEffect. | Report/projection integration remains. |
| INV-013 | AUTOMATED — P06 loan-payment test emits principal LoanEffect without a principal EconomicEffect. | Loan projection integration remains P21. |
| INV-014 | AUTOMATED — P06 interest, fee and penalty tests emit three non-consumption expense effects. | Report/projection integration remains P21/P25. |
| INV-015 | AUTOMATED — P06 1,000-case transfer property proves summed user-asset base delta is zero and no EconomicEffect exists. | Account projection rebuild remains P08. |
| INV-016 | AUTOMATED — P06 `FrozenFxConversion`, RevisionAmount, FxRateSnapshot and commit hash retain exact rate/provider/timestamps/account/base amounts; later rates are absent from planner input. | FX repository/history integration remains P14. |
| INV-017 | AUTOMATED — P06 closed transaction/rule set has no current-revaluation command and ordinary transfer emits no income/expense; actual exchange gain/cost remains explicitly evidenced. | Current valuation projection/report proof remains P08/P25. |
| INV-018 | RETAINED — P05 `BudgetHierarchyPolicy` automated hierarchy tests remain passing. | P17 budget editing/projection. |
| INV-019 | RETAINED — P05 child-within-parent automated hierarchy tests remain passing. | P17 budget editing/projection. |
| INV-020 | LATER — typed rollover records exist, but transaction/adjustment/prior-month projection rebuild belongs to P17. | P17. |
| INV-021 | AUTOMATED — P06 GoalEffect is separate from Posting; goal-linked expense has the same account Journal shape and a separate SPEND effect. | P18 goal projection. |
| INV-022 | AUTOMATED — P06 settlement share/payment rules use checked signed deltas; unit/property tests prove the participant total is zero. | P22 persisted position audit. |
| INV-023 | AUTOMATED — P06 external-only settlement payment test creates zero Journal/Postings and only zero-sum SettlementEffects. | P22 UI/integration. |
| INV-024 | AUTOMATED — P06 edit only reverses transaction-linked SettlementEffects; `SettlementPaymentRecord` is not a mutation-plan output and historical payment facts cannot be rewritten. | P22 supplemental-settlement integration. |
| INV-025 | RETAINED — P05 loan schedule principal conservation property remains passing. | P21 schedule persistence. |
| INV-026 | RETAINED — P05 installment schedule principal conservation property remains passing. | P20 schedule persistence. |
| INV-027 | RETAINED — P05 occurrence identity and typed lifecycle exist. | P23 unique persistence/concurrency proof. |
| INV-028 | AUTOMATED — P06 rejects `RECURRENCE_CANDIDATE` before any formal fact materialization. | P23 database/report exclusion. |
| INV-029 | AUTOMATED — P06 archived-account test proves exact reversal remains legal without mutating or losing historical ledger references. | P07/P12 history and tombstone persistence. |
| INV-030 | RETAINED — P05 purge-tombstone precedence model/tests remain passing. | P31 merge restore. |
| INV-031 | AUTOMATED — P06 every `ProjectionChange` is constructed under one target LocalRevision and plan validation checks the aligned set. | P08 atomic persisted projection proof. |
| INV-032 | RETAINED — P02 static privacy/log/telemetry gates and P05 vault boundary remain passing; P06 adds no sensitive output channel. | P07/P32 database/device audit. |
| INV-033 | RETAINED — P05 `AtomicFinancialCommitRepository` accepts one complete immutable plan only. | P07/P28/P31 failure injection. |
| INV-034 | AUTOMATED — P03 checked arithmetic remains VERIFIED; P06 adds Long.MAX_VALUE, overflowing Journal total, signed settlement, loan component and netting coverage. | P07 SQL aggregation audit. |
| INV-035 | RETAINED — P05 cache/projection models carry LocalRevision; P06 commit/projection target is version-aligned. | P08 cache invalidation integration. |

## Determinism and hash domain

`CanonicalFinancialHash` uses length-prefixed UTF-8/binary fields and SHA-256. Command hashes exclude `commandId` and the hash field itself, so the same payload is reproducible and a reused command ID with different content is rejected. Commit roots cover transaction content, revision content, every Journal content hash, RevisionAmount rows, frozen FX evidence and all eight Effect families. Sensitive strings are hashed in memory and are never logged or included in errors.

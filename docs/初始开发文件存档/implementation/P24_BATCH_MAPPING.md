# P24 Batch Entry and Bulk Edit Mapping

P24 is `VERIFIED`. This mapping is limited to P24; it does not promote analytics, import, performance, release, or any later stage.

## Frozen contract coverage

| Contract | Production mapping | Verification |
|---|---|---|
| REC-023 summary table and batch lifecycle | `BatchRecordState`, `BatchRecordController`, governed `BatchSummaryTable`/`BatchToolbar`/`BatchCommitBar`, and `BatchRecordDestination` expose editing, validating, errors, ready-to-commit and committing states. The draft lives only in ViewModel memory and back/top-level navigation requires explicit discard. | `BatchRecordUiContractDeviceTest`; `P24-E004`; `P24-E005` |
| REC-024 complete row editor | Each `BatchRowDraft` carries category, expression and three authoritative amount representations, currency, account/card, merchant, occurrence time/zone, project, encrypted attachment IDs, mutual-expense allocation, location record ID, installment plan ID, refund source ID, and note. Its route carries only `rowId:StableId`. | Static safe-route gate; exact YAML gate; responsive UI matrix in `P24-E004` |
| REC-025 validation | Local structural errors merge with authoritative planner/application validation. Errors jump to the stable row and field; warnings require explicit confirmation; a failed commit retains every input row. | `BatchRecordPolicy`; `SecureRoomBatchEntryApplicationPort.validate`; `P24-E001`; `P24-E004` |
| Copy, insert, move, sort, paste | `BatchRecordPolicy` performs identity-safe copy/insert/reorder/sort and bounded TSV parsing against the encrypted reference snapshot. The summary uses an indexed row provider and `LazyColumn`, so it never materializes all visible models. | 100,000-row composition bound in `P24-E004`; static eager-list rejection in `P24-E001` |
| Atomic submit | Every ordinary/refund row is prepared through its existing typed application adapter and planned as a child of one `BatchFinancialCommand`. One `FinancialMutationCoordinator` call writes one Room/SQLCipher transaction and one parent receipt/commit. There is no feature, Worker, importer, or batch-adapter financial fact SQL. | Planner property test and real SQLCipher failure/retry test in `P24-E002`/`P24-E003` |
| Audit and undo | The parent receipt resolves the exact transaction set. Whole-batch undo emits one new batch of `MoveTransactionToTrashCommand` children, appending REVERSE revisions and retaining the original transactions, facts, commit and receipt. | `BatchEntryApplicationPortDeviceTest` in `P24-E003` |
| JRN-005 query selection | `JournalSelectionSpec` represents `ALL_MATCHING` by query fingerprint plus a bounded exclusion set; it does not retain 500,000 result IDs. Query changes are explicit state transitions. | `JournalSelectionPolicyTest`; JRN device matrix in `P24-E004` |
| JRN-006 allowed edits | `JournalBulkEditPatch` exposes only category, account/card, merchant, project, occurred time, note, budget attribute and statistical nature. Amount, direction, refund relation and mutual-expense share are absent and statically rejected. | `forbiddenJournalBulkFields`; mutation gate; prior coordinator integration plus P24 final UI replay |

## all-or-nothing and failure semantics

The default contract is all-or-nothing. Validation prepares every row without changing the ledger. Any error disables commit and leaves the primary database unchanged. During submit, reference side effects, immutable revisions/facts/effects, current pointers, projections, `book.localRevision`, and the parent `CommandReceipt` share one SQLCipher transaction. An injected failure after immutable facts rolls all of them back. Replaying the identical parent command returns the same receipt and creates no duplicate transaction.

Warnings are not errors, but they cannot be bypassed: the submitted immutable request must carry explicit warning confirmation. Cross-currency rows persist actual user/account/base integer amounts and implied/manual evidence; later quote-cache changes cannot rewrite them.

## Scale and accessibility

- The production summary asks for a row model by visible index. The API 36 test supplies 100,000 rows and proves fewer than 100 row/click semantics are composed.
- The existing query selection proof models 500,000 matches with only its fingerprint and exclusions; prohibited bulk fields remain unrepresentable.
- REC-023—025 cover the exact ten required states across 320/360/480dp, 100/130/200% font, zh-CN/ja-JP/en-US, and light/dark boundaries. Row semantics deliberately omit amount, note, attachment names, coordinates, and other business content.
- Three 360×720 production Compose pixel digests cover summary light, row-editor dark and validation light. They were generated only from the textual UI contract, token JSON, screen YAML, localized resources and production fixtures.

## Schema and projection impact

P24 adds no Schema v2 and no universal JSON. It reuses the normalized v1 transaction/revision/fact/effect/audit tables and synchronous P08 projection rebuild. The batch undo device test uncovered and repaired a projection audit boundary: `current_transaction_projection` retains trashed rows, while FTS intentionally contains only active rows; search count verification now compares those same active populations.

No excluded PNG/HTML visual draft was opened, parsed, hashed, sampled, measured, compared, or used to create a baseline.

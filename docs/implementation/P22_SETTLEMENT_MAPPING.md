# P22 Mutual-settlement Mapping

Status: `VERIFIED` on 2026-08-05 (Asia/Tokyo). This mapping covers only P22. Recurrence, analytics aggregation, widgets, import/backup and release work remain in their owning later stages.

## Frozen scope

P22 implements REQ-043—REQ-045 and the complete REC-011 / SET-001—SET-008 contract: 9 destinations and 27 required states. Routes carry only `StableId` values (`activityId`, optional `activityId`, and `participantId`); amounts, names, notes, accounts, projects, positions and participant lists never enter routes or saved state.

| Destination | Required states | Production realization |
|---|---|---|
| REC-011 | editing, imbalanced, valid, currencyMismatch | Payer, EQUAL/FIXED_AMOUNT/PERCENTAGE/WEIGHT modes, participant inclusion, tax/service-fee distribution, rounding policy and exact self/other summary |
| SET-001 | content, empty, requiresAdditionalSettlement | Activity list, position summary and additional-settlement warning |
| SET-002 | create, edit, validationError | Name/description/currency/date/project and participant membership editor |
| SET-003 | content, empty | Unique-self participant list, add/archive and deterministic ordering |
| SET-004 | open, settled, requiresAdditionalSettlement, empty | Activity totals, every participant position, related transactions, history and settle action |
| SET-005 | receivable, payable, zero | Route-selected participant position, paid/owed/settled breakdown and related transactions |
| SET-006 | selfPays, selfReceives, externalToExternal, saving | Conditional local account, no-local-impact disclosure and duplicate-safe save |
| SET-007 | content, empty | Immutable settlement-payment timeline |
| SET-008 | required, resolved | Recomputed positions, retained-history notice, application-provided suggestions and record action |

All strings have matching zh-CN, ja-JP and en-US key sets. The device matrix covers 320/360/480dp, 100/130/200% font scale and light/dark boundaries with governed components and semantics.

## Exact allocation model

`SettlementAllocationPolicy` performs all authoritative arithmetic in checked integer minor units. It first allocates the base amount, then tax and service fee according to closed `SAME_AS_BASE`, `EQUAL`, `PAYER` or `SPECIFIED` policies. EQUAL, FIXED_AMOUNT, PERCENTAGE and WEIGHT are closed split methods; exclusions remove participants before calculation. PARTICIPANT_ORDER, PAYER, SELF and LARGEST_SHARE are the only remainder recipients. No `Float` or `Double` exists in the authoritative path.

The result carries one paid and one owed amount per included participant and validates:

- `sum(paid) == total`, `sum(owed) == total`;
- `sum(paid - owed) == 0`;
- one and only one active `self` participant for a book;
- one actual payer per expense;
- settlement activity currency, participant membership and project association are explicit, typed state.

## Financial semantics and write boundary

| Case | Local Journal / Effects |
|---|---|
| Self pays a group expense | The local account pays the full amount; only self's owed share creates consumption/economic/budget/project effects; other shares create receivables |
| External participant pays | No local account is required or changed; only self's owed share creates local consumption/economic/budget/project effects; the payer receives a payable position |
| Self pays or receives a settlement | A balanced asset/settlement-ledger Journal changes only account and settlement balances; it creates no income, expense, consumption, budget or project usage |
| External pays external | No local account, Journal, Posting or current transaction is created; an immutable subledger payment record and idempotent command receipt are retained |

Every formal financial expense or self-involved settlement reaches facts through `DefaultFinancialMutationCoordinator` and the existing `LedgerWriteGate`. The Room adapter never directly inserts Journal, Posting or Effect rows. One encrypted Room transaction persists the command receipt, immutable revision/facts/payment record, current pointer, synchronous projections and `book.localRevision`; stale `expectedRevision` fails and replay returns the original receipt.

## History, projection and rebuild

`settlement_effect` stores immutable PAID_FOR_GROUP, OWED_SHARE, SETTLEMENT_PAID and SETTLEMENT_RECEIVED deltas. `settlement_payment_record` stores each payment and optional linked local transaction/reversal; external-to-external records intentionally have no local transaction link. `settlement_position_projection` rebuilds paid, owed, settled-paid, settled-received and net position at the target local revision.

Editing an already-settled source transaction appends its normal REVERSE/APPLY revisions and recalculates theoretical positions. Existing payment rows are not modified or replaced. A non-zero residual marks the activity `requires additional settlement`; SET-008 exposes application-generated deterministic transfer suggestions. UI code only renders those suggestions and cannot create its own authoritative calculation.

## Automated evidence

- Pure Kotlin properties cover all four split modes, all charge/remainder strategies, exclusion, invalid combinations, overflow and 2,000 generated conservation cases.
- API 36 SQLCipher/Room tests cover arbitrary participants, self/external payers, external-to-external payment, multiple partial settlements, retry/conflict/rollback behavior, post-settlement editing, canonical rebuild, `integrity_check`, foreign-key audit and Journal balance.
- REC and SET device suites cover all P22 states plus responsive/localized/theme/accessibility combinations.
- Full-pixel SHA-256 digests cover REC-011, SET-004 and SET-006, rendered only from production Compose, frozen text contracts and tokens.
- `validate_p22_settlements.py`, its seven mutation tests and `p22Check` enforce YAML parity, StableId-only routes, coordinator ownership, checked arithmetic, normalized schema, resource parity and production-source hygiene.

The reproducible commands and exact results are recorded as `P22-E001`—`P22-E007` in `TEST_EVIDENCE.md`; therefore P22 is `VERIFIED`.

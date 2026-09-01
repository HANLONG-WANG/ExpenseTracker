# P12 Account and Reference-Data Mapping

Last updated: 2026-08-02 (Asia/Tokyo)

This document maps the implemented P12 surface to the frozen product/domain/UI contracts. It covers exactly 23 screens and 67 required states: ACC-001—012, MGT-001, CAT-001—004, MER-001—003 and PLC-001—003. Visual values come only from the UI main contract, token JSON, screen YAML and traceability CSV. None of the four excluded PNG/HTML visual drafts was opened or used.

P12 is `VERIFIED`. Historical category reassignment and referenced-place splitting use a coordinator-owned `BatchFinancialCommand`: the adapter rehydrates each typed current revision plus immutable facts and frozen money/FX evidence, the deterministic planner emits REVERSE/APPLY replacements under one `BATCH_MUTATION` commit, and the Room repository commits all financial and reference changes with one `CommandReceipt` in one transaction. No old revision, location record, Journal, Posting or Effect is updated in place.

## Domain and application mapping

| Frozen responsibility | Production implementation | Current evidence/disposition |
|---|---|---|
| Four account kinds, unlimited account count | `AccountDraft`, `ReferenceMutation.SaveAccount`, account type picker and type-specific editor support CASH/BANK/CREDIT/LOAN | Domain policy and SQLCipher device test |
| Currency lock after first Posting | `SecureRoomReferenceDataManagementPort.saveAccount` checks the immutable Posting relation and returns `ACCOUNT_CURRENCY_LOCKED`; ACC-003 disables and explains the field | JVM policy + real SQLCipher/opening-balance device test |
| Archive, empty-only permanent deletion and last-account warning | `ReferenceDataPolicies.accountLifecycle`, guarded adapter mutations and ACC-012 | Policy test + device persistence + all three states |
| Opening balance and checkpoint separation | `SecureRoomOpeningBalanceWritePort` creates `RecordOpeningBalanceCommand` through `FinancialMutationCoordinator`; checkpoint is reference audit data only and never creates a Journal | Device test asserts balance changes only for opening balance and remains unchanged after checkpoint |
| Account/card separation and replacement history | Separate `user_account`/`payment_card` drafts, compatibility policy, archive and replacement mutations; accounts may have zero/many cards | Policy + SQLCipher device test + ACC-009—011 |
| Two independent category trees, maximum two levels and immutable second-level parent | Typed `CategoryDirection`, adapter parent/depth/status validation and `CategoryParentLocked` | Domain + SQLCipher device test |
| Category appearance, order, search, defaults and statistical snapshot | Governed 8-icon/16-token-palette picker, accessible move-up/down ordering, name search and compatible default account/card/merchant selectors | UI matrix + SQLCipher default persistence/rejection test |
| Archive and tombstone deletion | Audited category revisions preserve referenced IDs; deleted row uses governed neutral strike-through rendering | SQLCipher tombstone test + CAT-004 UI |
| Historical category reassignment | `SecureRoomReferenceDataManagementPort` selects affected current transactions, maps their exact frozen facts through `RoomReferenceFinancialSnapshotMapper`, then appends one EDIT revision and REVERSE/APPLY chain per transaction through `FinancialMutationCoordinator` | Domain deterministic-batch test plus SQLCipher device test verifies one atomic batch, current target category, immutable old facts and single reversal per APPLY (`P12-E008`) |
| Merchant name/alias search and merge | Typed drafts, normalized duplicate checks, alias transfer, merge chain, current projection resolution and linked-place list | SQLCipher merge test + MER-001—003 |
| Offline place create/move/merge and optional merchant | Typed fixed-point E7 coordinates, no reverse-geocoding port, governed P10 MapLibre renderer with accessible list fallback | SQLCipher/UI/map integration evidence |
| Place split | Clones selected immutable `LocationRecord`s, points newly appended transaction revisions at the replacements, and executes the fan-out through the same coordinator batch entry | SQLCipher device test verifies one atomic batch, new current location references, preserved source records and no partial state (`P12-E003`, `P12-E008`) |
| Account summary/detail/transactions | Exact “核心净金融资产” / “调整后净金融头寸”, missing-valuation state, Vico balance trend plus accessible data table, cards, goal projection, recent transactions and running balance | ACC-001/005/006 state and device UI matrix |

## Database and audit mapping

All non-financial reference mutations execute in one `LedgerDatabase.inLedgerTransaction`: commit header/parent, current row, `entity_revision`, `entity_change`, synchronous projections and `book.local_revision`. Row versions implement expected-revision conflict rejection. Account opening balance and reference operations that change a formal transaction are excluded from the reference-only path and use the deterministic accounting planner plus `FinancialMutationCoordinator`. For category reassignment and referenced-place split, a narrowly typed `FinancialCommitSideEffect` writes only the accompanying reference metadata after the batch commit header and before new revision foreign keys; it cannot materialize financial facts, and the outer Room transaction rolls everything back on failure.

| Logical model family | P12 read/write use |
|---|---|
| `ledger_account`, `user_account`, `payment_card` | Typed account/card current rows, balance/valuation projections, posting-use lock and replacement links |
| `category`, `merchant`, `merchant_alias`, `place`, `location_record` | Tree/default/status data, aliases/merge chains, fixed-point places and immutable location snapshots |
| `account_balance_checkpoint` | Observed/calculated/difference record only; optional later adjustment transaction link remains null until explicit REC-020 write |
| `book_commit`, `book_commit_parent`, `entity_revision`, `entity_change` | Canonical reference-data audit and monotonic local revision |
| `account_balance_current`, `account_valuation_current`, `account_balance_daily`, `current_transaction_projection`, `goal_balance_projection` | Account list/detail/transaction/goal queries with synchronous P08 revision alignment |

## Screen and state mapping

| Screen group | Exact IDs | State count | Implementation |
|---|---|---:|---|
| Account home/editor/detail/flow | ACC-001—008, ACC-012 | 27 | `AccountsDestination`, typed submissions and reference/opening-balance application ports |
| Physical cards | ACC-009—011 | 9 | Separate card list/editor/detail, compatibility, archive/replacement and P32 vault entry only |
| Management hub/categories | MGT-001, CAT-001—004 | 14 | Independent trees, search, appearance/defaults, accessible order and three-strategy UI |
| Merchants | MER-001—003 | 9 | Search/aliases/linked places, duplicate warning and merge impact |
| Places | PLC-001—003 | 8 | Search/map/list, fixed-point editor, merge/split selection and failure states |

The rows above total 23 screens and 67 required states. `P12UiContractDeviceTest` renders every state from closed enums and separately exercises 320/360/480dp, 100/130/200% font, light/dark, dynamic-color boundary and Simplified Chinese/Japanese/English resources. All 23 P12 screen rows are `VERIFIED`; REC-009/010 remain `IN_PROGRESS` because their complete record-form wiring belongs to P13.

## Coordinator boundary and atomicity

P12 extends the existing P06/P08 boundary without creating another write entry:

1. `RoomReferenceFinancialSnapshotMapper` loads each current transaction/revision, immutable current facts and historical ledger references, including frozen amount and FX evidence;
2. the reference adapter derives deterministic child command/revision/fact/FX IDs from the outer commit ID and builds typed EDIT children;
3. `DeterministicFinancialPlanner` generates REVERSE plus APPLY facts/effects for every child under one canonical `BATCH_MUTATION` plan;
4. `RoomFinancialCommitRepository` validates every child `expectedRevision`, writes one receipt, rebuilds projections and advances `book.localRevision` once;
5. any planner, foreign-key, audit, projection or side-effect failure rolls back the entire batch.

Direct updates to old `transaction_revision.category_id` or `location_record.place_id`, mutable old facts, sequential partial financial commits and success-only UI simulation remain forbidden and are absent from the implementation.

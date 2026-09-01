# P15 Journal, Search, History and Trash Mapping

Last updated: 2026-08-03 (Asia/Tokyo)

P15 is `VERIFIED`. The implementation covers the 12 JRN routes and their exact 42 required states. It adds no second financial writer: every edit, historical restore, trash transition and bulk change reaches `FinancialMutationCoordinator`; the feature and root ViewModel never create a Journal, Posting, DAO or Entity.

## Contract-to-implementation map

| Contract surface | Production implementation | Verification |
|---|---|---|
| JRN-001 list | `JournalPagingSource`, `(occurred_at, transaction_id)` descending keyset, bounded pages, sticky local-date headers, typed row badges and optional account-context running balance | `P15-E002`, `P15-E003`, `P15-E005` |
| JRN-002/003 search and filters | FTS5 candidates, exact structured filters, same-dimension OR/cross-dimension AND, Kotlin Haversine exact filtering after bounded R*Tree candidates, readable summary | `P15-E002`, `P15-E003`, `P15-E005` |
| JRN-004 saved filters | AEAD file in `noBackupFilesDir`, per-book associated data, save/copy/default/reorder/delete, no filter text in route/SavedState | `P15-E004` |
| JRN-005 selection | explicit IDs or query fingerprint plus only included/excluded exceptions; selecting a 500,000-row result never materializes 500,000 IDs in UI state | `P15-E002` |
| JRN-006 bulk edit | exactly category, account/card, merchant, project, occurred time, note, budget attribute and statistical nature; amount, direction, refund relation and settlement share are absent and rejected | `P15-E002`, `P15-E004` |
| JRN-007 detail | current user input, source, account impact, budget/statistical semantics, frozen FX evidence, relationships, place, attachments, timestamps and history count | `P15-E004`, `P15-E005` |
| JRN-008/009 history and comparison | immutable revision timeline, typed field differences and restore-old-version command that appends a RESTORE revision and REVERSE/APPLY facts | `P15-E004` |
| JRN-010 dependencies | typed dependency tree and closed policy choices; incomplete policy resolution blocks the mutation before planning | `P15-E001`, `P15-E005` |
| JRN-011 trash | trashed keyset page, retention timestamp, restore as a new revision and selection-safe actions | `P15-E004`, `P15-E005` |
| JRN-012 purge eligibility | current-time retention, financial net, dependency and durable-operation/backup-reference reasons are rechecked and explained | `P15-E004`, `P15-E005` |

## Query and scale boundary

`RoomTransactionQueryService` binds every filter value, never interpolates user text, and contains no `OFFSET`. Search first obtains FTS5 candidates; geography first obtains at most 2,000 R*Tree candidates and then calculates exact distance in Kotlin. A real API-36 SQLCipher database test inserts exactly 500,000 current transactions and verifies two non-overlapping keyset pages, a unique FTS result and an index-backed query plan. Global rows have no running balance; an account-context query supplies it.

The all-matching selection representation remains bounded by the number of exceptions. The eventual atomic batch necessarily streams/resolves matching transaction identities inside the application/data boundary; the feature never retains the result set.

## Immutable mutation boundary

`SecureRoomJournalApplicationPort` performs no financial SQL writes. It builds typed commands and submits them to the sole coordinator. Bulk edit is one `BatchFinancialCommand`; every child uses `expectedRevision`, appends `BULK_EDIT`, reverses the old facts and applies the replacement facts in one Room transaction. Historical restore rehydrates the selected immutable revision, appends `RESTORE`, and never overwrites either version. Duplicate command receipts and stale revision checks remain the P08 atomic boundary.

Moving to trash reverses current facts; restore appends a new active revision and applies replacement facts. P15 only assesses and confirms permanent-purge eligibility. The physical maintenance transaction is intentionally reserved for P31, so `PHYSICAL_PURGE_REQUIRES_MAINTENANCE` remains an explicit final reason rather than a fake success or feature-side delete.

## UI, accessibility and visual provenance

The exact 12-screen/42-state matrix renders at 320/360/480dp, 100/130/200% font, zh-CN/ja-JP/en-US and alternating light/dark themes. Rows use type text plus governed icon and color, state badges are semantic text, protected notes are excluded from semantics, and no swipe-delete gesture exists. Two exact-pixel 360×720dp baselines cover list-light and detail-dark.

Those baselines were generated only from the implemented Compose tree, textual UI contract, token JSON, screen YAML and localized strings. No excluded PNG/HTML visual draft was opened, parsed, sampled, measured, compared or used as a pixel oracle.

## Retained later-stage boundaries

- P24 owns advanced batch-operation orchestration beyond P15's atomic allowed-field edit.
- P31 owns the final maintenance-only physical purge transaction and purge tombstone workflow.
- P34/P36 retain whole-product acceptance, manual accessibility review and release evidence.

No P16 or later feature is promoted by P15.

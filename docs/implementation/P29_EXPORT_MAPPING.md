# P29 Export Mapping

P29 is `VERIFIED`. This document maps the frozen ordinary-export requirements to the production implementation and repeatable evidence. It does not promote portable backup, Drive backup, restore or any P30+ scope.

## Frozen scope and ownership

| Contract | Production realization | Evidence |
|---|---|---|
| REQ-074 current-filter CSV | `ExportDescriptor(CURRENT_FILTER, CSV)` captures the complete typed journal filter and its human-readable summary; `SecureRoomLedgerExportQueryPort` reuses the existing keyset journal application query; Apache Commons CSV writes UTF-8 BOM, versioned metadata and spreadsheet-safe cells | `P29-E001`, `P29-E002`, `P29-E003`, `P29-E005` |
| REQ-074 full workbook | FastExcel 0.20.2 writes one metadata sheet plus 15 bounded data sheets: accounts, cards, categories, merchants, places, projects, settlements, transactions, credit statements, installments, loans, budgets, goals, recurrences and locations | `P29-E001`, `P29-E003`, `P29-E005` |
| REQ-074 report export | The P26 `ANA-010` handoff supplies an encrypted, revision-stamped `ExportReportSnapshot`; the same source emits report CSV/XLSX, Android `PdfDocument` pages and a bounded PNG report image | `P29-E002`, `P29-E003`, `P29-E004`, `P29-E006` |
| REQ-084 bounded long operation | Every tabular source is keyset-paged, writers request at most 256 rows, PDF keeps one open page, and PNG retains only 22 visible sample rows in a fixed-size bitmap while still scanning/checkpointing the complete source | `P29-E002`, `P29-E003` |
| Ordinary export is not backup | `PORTABLE_BACKUP` is rejected by the descriptor and engine. Every format carries schema/application version, generated time, filter/report scope, local/valuation revision and an explicit “not a complete backup” disclaimer | `P29-E001`, `P29-E002` |

No export path owns a financial DAO or mutation. The current-filter adapter reads the existing application query, the workbook adapter performs allowlisted read-only SQLCipher queries, and report export reads the already prepared P26 snapshot. No P29 code calls or bypasses `FinancialMutationCoordinator` because export performs no financial write.

## Field and sensitive-data closure

`ExportField` is the only ordinary transaction-field vocabulary. Complete card number, security code, account number, password, ciphertext and vault fields do not exist in that enum or in the field-selection UI. Card rows expose display data and `last_four` only. Location coordinates are the only sensitive ordinary-export fields; both E7 fields are absent from `defaultSelection` and can be enabled only together through the explicit sensitive switch.

The production SQLCipher test plants a unique sentinel directly in `card_vault_secret`, executes all 15 workbook queries and the current-filter adapter, then scans every returned header and cell. The sentinel and every forbidden field name remain absent. Descriptor construction, engine validation and source-policy checks fail closed if the allowlist is weakened.

## Streaming, SAF and durable execution

The engine never constructs a 100,000-row table or workbook model. CSV uses Commons CSV record output; XLSX uses FastExcel row output; PDF finishes pages incrementally; the image renderer uses a constant-size bitmap. Progress and cancellation are checked at row/page boundaries.

The selected SAF tree URI is retained only in `SecureTransferHandleStore`, encrypted by the device ledger key hierarchy in `noBackupFilesDir`. WorkManager and API 34 UIDT payloads contain only `operationId`. Generation first targets an app-private operation temporary file. Publishing streams to a provider-side `.partial`, preserves an existing file as `.previous`, renames only after a complete copy, restores the previous file if publication fails, and removes both provider/app temporary material on terminal failure or safe cancellation. Provider operations—not advisory capability flags—decide permission, space and availability outcomes.

Remote document providers use API 34 user-initiated data transfer jobs; local providers and older APIs use foreground WorkManager execution. Encrypted operation parameters and checkpoints survive repository recreation. A crash during generation safely regenerates the unpublished temporary file; a crash in `COMMITTING` detects an already published readable document before marking success. Retry exhaustion and UIDT failures become final, visible states instead of false success.

## UI and operation center

| Screen | Frozen states covered | Implementation |
|---|---|---|
| EXP-001 | `content` | Content and closed format choices, current-filter summary, full-workbook sheet preview and non-backup warning |
| EXP-002 | `editing`, `valid` | Safe field list, vault-exclusion notice and default-off coordinate switch |
| EXP-003 | `content`, `permissionRevoked`, `nameConflict` | `ACTION_OPEN_DOCUMENT_TREE`, file name, persisted SAF permission repair and explicit overwrite confirmation |
| EXP-004 | `running`, `cancelRequested`, `failed`, `succeeded` | Persistent progress, safe cancel, cleanup status, retry, open, share, view location and operation center entry |
| ANA-010 | report export handoff | Opaque report-instance lookup builds a bounded, revisioned snapshot and enters the same EXP flow without sensitive route/SavedState payloads |

All EXP states run in zh-CN, English and Japanese across 320/360/480 dp, 100/130/200% font scales and light/dark themes. Operation-center presentation maps export state to the governed shared long-operation model. Open/share/view-location use read-only URI grants and surface an explicit unavailable state when no external application resolves the intent.

## Scale and fault evidence

- 100,000-row CSV and 100,000 rows distributed across all 15 XLSX data sheets round-trip on a Pixel 6 API 36 device with a reported 512 MiB heap class and source page bounds of 256.
- A 12,000-row PDF produces at least 267 pages; a 100,000-row PNG run reaches the complete source while retaining only its fixed visible sample.
- Cancellation, injected ENOSPC, revoked permission, source/provider unavailability, same-name preservation, explicit atomic overwrite and temporary cleanup return closed outcomes.
- Actual Keystore/SQLCipher tests prove encrypted descriptor/report recovery and sensitive-data exclusion; actual API 34 execution proves UIDT selection and operation-ID-only extras.
- Production Compose pixel digests are `a487270b1501caa3751747c2db458be0e0ef85f8352209daf76a8059ec5cc3a2` and `93d8c476896c3d7f087cbbd80e47a86db0b156ae75b1024239e89505349d225b`. They derive only from code, governed tokens and textual contracts; excluded visual drafts were not opened or used.

The exact commands, environment and results are recorded as `P29-E001`—`P29-E007` in `TEST_EVIDENCE.md`.

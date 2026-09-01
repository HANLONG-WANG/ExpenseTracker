# P28 CSV/XLSX general and structured import mapping

This document maps P28 to requirements REQ-005, REQ-029, REQ-073 and REQ-084, architecture staging/shadow-database rules, and UI contract §12.25. It is based only on the frozen textual contract, token JSON, screen YAML and traceability CSV. The excluded visual PNG/HTML drafts were not opened or used.

## Parsing and bounded memory

`AndroidCsvImportReader` streams Apache Commons CSV 1.14.1 records. Encoding precedence is an explicit user selection, then UTF-8/UTF-16/UTF-32 BOM, then ICU4J `CharsetDetector`, with UTF-8 as the empty/default case. Duplicate and blank headers are normalized deterministically. Cancellation is checked for every record and invalid encodings or damaged input produce typed failures.

`FastExcelImportReader` uses FastExcel 0.20.2 `ReadableWorkbook`, workbook sheet streams and per-sheet row streams. It accepts OOXML ZIP input, rejects legacy OLE2 as unsupported, treats malformed ZIP/XML as corrupt, returns cached formula values without recalculating, and preserves shared strings, dates, instants, exact decimal text, booleans and non-ASCII text. Both readers report a peak row buffer of one. The 100,000-row JVM suite runs with a 256 MiB maximum heap, and encrypted staging uses 256/512-row chunks/pages rather than retaining an entire file.

No OCR, AI classifier, formula evaluator or Apache POI dependency/fallback is present. `stax-api` is the narrow Android compatibility dependency needed by FastExcel/Aalto; it does not replace the frozen parser.

## Nine stages and IMP coverage

The user-visible state machine is exactly nine stages: source, structure, field mapping, entity mapping, FX, validation, confirmation, execution and result. Import history is the separate IMP-010 destination. IMP-001 through IMP-010 preserve the frozen StableId-only routes and all 34 YAML required states.

The controller retains at most 200 preview/sample rows per sheet, while the Compose preview is a `LazyColumn` driven by a row-count and indexed provider. Mapping, entity creation, manual FX, errors and duplicate candidates are persisted in encrypted staging and can be reconstructed after process loss. Duplicate candidates block commit until the user explicitly chooses skip or import anyway. Validation items retain source row numbers so an error can return to its source context.

Preview text is cleared from the unmerged accessibility semantics tree and replaced by a row/status description. Routes, WorkManager `Data`, SavedState and notifications contain only opaque operation identifiers and non-sensitive progress counts; source SAF handles are encrypted in an app-private store.

## Independent SQLCipher staging and recovery

Each operation receives an independently derived SQLCipher database named from its opaque operation ID. Its seven normalized tables are `staging_raw_row`, `staging_parsed_row`, `staging_mapping`, `staging_validation_error`, `staging_duplicate_candidate`, `staging_prepared_command` and `staging_attachment`. Raw and canonical parsed rows, closed transformations, missing-entity decisions, FX decisions, validation codes, duplicate resolutions and prepared command envelopes are durable and keyset-paged.

Foreground WorkManager work receives only `operationId`. The encrypted primary operation record holds source handle, format/header/charset and the full commit descriptor. Parsing checkpoints every 256 durable rows. Pause is honored only after a staging chunk is durable; cancel transitions through cancel-requested and rolling-back, removes staging and the encrypted source handle, and ends failed-final. A retry resumes by replaying the source while skipping the durable checkpoint prefix, so staged row numbers are neither lost nor duplicated. Final retry failure also cleans temporary state. A process restart in `COMMITTING` reconstructs the page sources from staging and safely re-enters the idempotent application port.

## General and structured preparation

General CSV/XLSX preparation uses closed field transformations, required-field checks, entity matching/create-missing decisions, currency/FX decisions, duplicate matching and deterministic prepared IDs/hashes. Ordinary expense and income rows materialize the exact P24 typed batch request. Unsupported specialized transaction kinds fail validation instead of being silently approximated.

The product invariant remains one category and one payer account per transaction. A `category_count`/`payer_count` greater than one, or a delimiter-expanded multi-value field, produces `IMPORT_SEPARATE_TRANSACTIONS_REQUIRED`; the source must be expanded into multiple transactions. The importer never adds a split-transaction model.

Structured workbooks stream all sheets, not merely the currently previewed sheet. The 15 structured entity kinds are account, card, category, merchant, place, goal, project, settlement activity, transaction, credit statement, installment, loan, budget, recurrence and location. Dependency-ordered prepared rows call the existing typed application ports; transaction rows still use the planner/coordinator-backed batch application path. Missing general accounts/categories/merchants are likewise converted to typed structured rows in the same shadow transaction.

## Atomic commit, audit, idempotence and whole-batch undo

Small all-financial imports use one existing P24 batch transaction, including `import_record`, `import_batch_commit` and source-reference side effects. Larger financial imports apply bounded pages to an encrypted same-filesystem shadow copy. Structured imports always use the shadow because multiple typed entity/application ports must form one unit. Every financial page reuses the normal typed planner and `FinancialMutationCoordinator` boundary; UI, Worker and import code never assemble a financial DAO write.

Before exchange, the shadow passes SQLCipher readability, integrity, foreign-key, balanced-journal, subtype and projection-version validation. `finance:data` owns the shadow snapshot/exchange implementation because it coordinates financial SQL; `core:security` exposes only narrow key and encrypted primary/staging access. Exchange verifies that the live head still equals the snapshot head and uses an atomic same-filesystem move with a crash marker and retained safety database. Any row/page failure—including source row 99,999—discards the shadow and leaves the primary ledger byte/logical state unchanged. A source fingerprint makes re-execution return the existing audit instead of importing duplicates.

Financial-only whole-batch undo appends the existing legal reversal commands. Structured/missing-entity whole-batch undo atomically restores the retained encrypted pre-import safety database, writes an explicit RESTORE audit, validates it and exchanges it at an unchanged live head. A later live-head change makes structured undo ineligible instead of deleting unrelated history. History reports imported counts and reversal eligibility from durable audit/source references.

## Verification map

| Evidence | Coverage |
|---|---|
| `P28-E001` | Frozen dependencies, exact contract/routes/states, staging/Worker/privacy/coordinator policies and mutation rejection |
| `P28-E002` | 100,000-row CSV/XLSX, BOM/ICU/non-ASCII, multi-sheet/shared-string/date/numeric/formula cache, corrupt/legacy/cancel, 256 MiB heap |
| `P28-E003` | API 28/34/36 Commons CSV + ICU + FastExcel Android-runtime compatibility |
| `P28-E004` | Real SQLCipher/Keystore 100,000-row staging, encrypted source/operation descriptors and reopen recovery |
| `P28-E005` | Real SQLCipher primary/shadow atomic commit, row 99,999 failure, idempotence, audit and whole-batch undo for financial and all 15 structured kinds |
| `P28-E006` | IMP-001—010 state/accessibility/responsive/localized/privacy matrix and exact production pixel hashes |
| `P28-E007` | Formatting, Detekt, Lint, architecture and application-root regression |
| `P28-E008` | Frozen-source baseline, all script mutations, dependency verification/locks and repository hygiene |

P28 is `VERIFIED`. P29 and later stages are not promoted.

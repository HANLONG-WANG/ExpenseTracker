# P31 Restore, Merge, Purge and Recovery Mapping

P31 is `VERIFIED`. This mapping covers only replacement/merge restore, controlled transaction purge, cloud-backup deletion and G-004/G-005 integration. P32 and later stages remain unstarted.

## Authenticated restore and bounded materialization

Portable and managed-repository sources are decrypted through the P30 Streaming AEAD/recovery-password boundary. Every database, settings, attachment and key-envelope object is copied with a fixed 64 KiB buffer while SHA-256 and declared `Long` length are verified. Wrong password, authentication/hash damage and safe cancellation remove the scoped restore directory. Drive repository downloads resume from the durable partial-file length through an HTTP Range request and publish manifests only after objects.

The scale proof sends a 20 GiB zero stream through the production copy/hash verifier with `-Xmx256m`; no source-sized allocation or `Int` byte count exists.

## Shadow validation and atomic replacement

The restored SQLCipher database is copied to `ledger_shadow_<operation>.db`, opened with the recovery-restored DEK, migrated only through the registered Room migration set, and rebuilt under maintenance state. Validation covers SQLCipher capability, schema version, `integrity_check`, foreign keys, balanced Journal facts, current subtype integrity, book/base identity and every projection family.

A verified pre-restore safety snapshot is created before exchange. Database, WAL/SHM/journal sidecars, prepared key replacement, settings, attachment objects and optional Vault recovery envelope use scoped safety/stage files. A PREPARED exchange marker makes process death before finalization roll back; a FINALIZED marker makes recovery retain the verified new ledger. The live swap uses same-filesystem `ATOMIC_MOVE`; exchange, live verification and rollback execute in `NonCancellable`. ENOSPC and every injected exchange fault restore exact old database/key/artifact bytes. An unreadable old live database can still be replaced from a verified source and is retained byte-for-byte for rollback/quarantine.

## Commit-graph three-way merge

Merge is available only for the same stable book ID and base currency. The planner walks the parent DAG to the closest common ancestor and compares stable entity IDs/content hashes/generations across ancestor, local and incoming cuts. It never orders entity choices by timestamp. One-sided edits resolve automatically; delete/edit and divergent entity versions are conflicts; a transaction revision fork always requires an explicit user choice.

Missing incoming commits receive consecutive local revision numbers inside the shadow transaction. The resolved entity import and two-parent `MERGE` commit execute in the same SQLCipher transaction through `DefaultFinancialMutationCoordinator` and `RoomFinancialCommitRepository`; neither UI nor transfer infrastructure writes financial tables directly. The shadow is revalidated before using the same atomic exchange path.

## Controlled privacy purge

Only a `TRASHED` transaction whose complete account-currency, base-currency and typed-effect chains net to zero can be purged. Inside the coordinator-owned transaction the repository rechecks retention time, current revision, all dependency tables, unresolved operations/merge conflicts and backup attachment reads after acquiring the maintenance lock and narrow purge guard.

The writer removes transaction revisions, Journal/Posting/effect facts, audit/history references and attachment references, then queues orphan encrypted blobs for GC. It appends one `PURGE` commit, entity change, receipt and `purge_tombstone` before advancing the book. Retry returns the same receipt. A failure at any checkpoint rolls back facts, projections, tombstone, receipt and book head together. The tombstone contains only entity identity, purge commit, purge time and generation—no amount, note or text.

During merge, either-side tombstone wins an older/equal entity generation and the UI offers no resurrection choice. Explicit replacement restore deliberately retains its documented snapshot semantics and may reintroduce an older dataset; it is not silently treated as merge.

## Clear and recovery boundaries

G-004 cancels/joins background work before local clearing. The finance-owned local-clear port removes the primary SQLCipher database and device key; the scoped cleaner removes app-owned attachment objects, app-private backup repository, safety snapshots, restore/import/shadow databases, descriptors/checkpoints, backup configuration/handles and Vault recovery envelope. User-controlled SAF exports and Drive files remain untouched. G-005 can enter verified restore even when the live database is unreadable.

CLR-002 separately requires fresh Google authorization plus typed confirmation. The Drive repository manifest is deleted before its catalog snapshot becomes unavailable, and only objects with no remaining snapshot reference are collected. Partial failures remain explicit.

## UI and verification surface

RST-001—RST-007 and CLR-002 implement all textual YAML states with governed components. JRN-012 is connected to the real purge port; G-004/G-005 are connected to cleanup and unreadable-ledger restore. Password values are hidden from semantics and never enter routes/SavedState. The production Compose matrix covers zh-CN/en-US/ja-JP, 320/360/480 dp, 100/130/200% font, light/dark and tombstone non-resurrection. Pixel baselines are derived only from the textual contract, token JSON and production Compose.

Evidence is recorded as `P31-E001`—`P31-E008` in `TEST_EVIDENCE.md`.

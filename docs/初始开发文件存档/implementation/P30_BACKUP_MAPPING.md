# P30 Encrypted Backup Mapping

P30 is `VERIFIED`. This document maps the frozen complete-backup contract to the production implementation and repeatable evidence. Restore, replacement, merge and compatibility migration remain P31 scope and are not promoted here.

## Repository and portable formats

| Contract | Production realization | Evidence |
|---|---|---|
| Always-encrypted managed repository | A versioned `repository-header.header`, encrypted snapshot manifests and Streaming AEAD objects form a logical-full/physical-incremental repository. The SQLCipher catalog records repository, object, immutable COMPLETE snapshot and ordered reference facts. | `P30-E001`, `P30-E002`, `P30-E004` |
| Database chunking and reuse | A validated immutable SQLCipher shadow snapshot is read in fixed 4 MiB chunks. SHA-256 identity plus size/kind deduplicates unchanged chunks and encrypted attachment objects; manifest verification decrypts and hashes every referenced object before publication. | `P30-E002`, `P30-E004` |
| Retention and reference GC | The default is 30 verified snapshots, with configurable count/age. Retention enters the schema's explicit fact-purge guard only through the narrow `finance:data` owner, deletes the manifest and immutable catalog links, then deletes only objects with no remaining snapshot reference. Drive mirrors run the same retained-name GC inside a repository-specific folder. | `P30-E001`, `P30-E002`, `P30-E004` |
| Portable backup | `*.ledger-backup` uses Apache Commons Compress ZIP64 under one Tink Streaming AEAD stream. Its authenticated header/manifest covers the SQLCipher database, settings, attachment objects, history, portable key material and optional recovery-wrapped Vault key. | `P30-E002`, `P30-E005` |

Managed publication writes and authenticates every object, atomically publishes and reopens the encrypted manifest, and only then inserts the immutable COMPLETE snapshot/reference set in one SQLCipher transaction. A retry with the same snapshot ID must match the exact manifest hash and ordered object IDs. Pre-publication cancellation or failure removes the manifest and unreferenced local objects; a failure after immutable publication cannot be reported as if the verified snapshot were absent.

## Recovery password and Vault boundary

Each repository key has two independent envelopes: a device-key-wrapped copy for scheduled background backup, and a recovery-password envelope with its own random salt and serialized Argon2id parameters. Password characters are bounded, cleared, never put in routes/SavedState/Worker data, never uploaded, and hidden from Compose semantics. The UI states that neither the app nor server can recover a lost password.

Changing a password supports future backups only or a durable re-encryption operation over every accessible manifest. Objects remain unchanged. For Drive history rotation, rewritten manifests upload first and the repository header is the sole final publication artifact, so interruption cannot publish a header that claims an incompletely rotated history.

Vault inclusion is disabled until a recovery password exists and a foreground BiometricPrompt exports the Vault DEK once into a recovery-password envelope. Automatic work reads only Vault ciphertext plus this wrapped envelope; it has no API that returns a card number, security code or background plaintext Vault DEK. Device evidence scans the automatic payload against a unique PAN/CVC sentinel.

## SAF and Google Drive

SAF directory selection persists read/write permission and stores the URI only in the encrypted transfer-handle store. Provider operations create `.partial`, preserve `.previous`, publish by rename and clean both names on failure/cancellation. Permission revocation and space exhaustion remain typed failure states.

Google Identity Authorization requests only `drive.file`; access tokens remain in memory. Direct Drive REST v3 transport creates or reuses a repository-specific opaque folder, starts resumable sessions, writes only aligned chunks, persists session URL/next offset/file ID in SQLCipher, queries interrupted sessions, and downloads with HTTP `Range`. Encrypted objects and retained historical manifests upload before the new snapshot manifest. The manifest is the only final artifact; its durable `manifestPublished` bit prevents duplicate publication. A post-publication listing removes only stale managed artifacts from that repository folder. Drive MD5 is transport-level reuse evidence only; Tink authentication and plaintext SHA-256 remain authoritative integrity checks.

## Scheduling, cancellation and recovery

`FinancialMutationCoordinator` notifies the process-local commit registry only after a new atomic financial commit succeeds. The scheduler compares the current `localRevision` with the latest completed snapshot, creates at most one operation after the day's first changed revision, and defaults to 30 retained daily versions. Startup re-enqueues nonterminal full-backup and recovery-key-rotation operations before evaluating the daily policy.

Foreground WorkManager handles ordinary execution; API 34 user-initiated Drive work uses UIDT. Both carry exactly one opaque `operationId`. Encrypted configuration, automatic markers, progress, operation parameters, Drive sessions and byte checkpoints survive process death. Network policy maps to connected or unmetered constraints. Safe cancellation is checked at object/chunk boundaries, leaves no published partial local snapshot and preserves Drive progress for explicit resume.

The constrained 256 MiB JVM suite uses a 48 GiB sparse source and transmits a single 256 KiB chunk while persisting a Long resume offset; no 48 GiB buffer or archive is constructed. ZIP64/AEAD uses 64 KiB copy buffers, while managed database processing is bounded by one 4 MiB chunk plus one copy buffer.

## UI and operation center

| Screen | Frozen states covered | Implementation |
|---|---|---|
| BKP-001 | `configured`, `notConfigured`, `running`, `failed`, `permissionRevoked` | Verified-only status, destination/policy/password/Vault summaries, recent history and manual action |
| BKP-002 | `editing`, `driveAuthRequired`, `permissionRevoked` | App-private, SAF and Drive choices with persisted directory repair and authorization entry |
| BKP-003 | `create`, `change`, `invalid`, `reEncrypting` | Redacted password fields, local-only rules, unrecoverable warning, future/history choice and durable progress |
| BKP-004 | `content`, `vaultPasswordRequired` | Daily policy, count/age retention, guarded Vault inclusion and Drive network policy |
| BKP-005 | `content`, `empty`, `loadingRemote` | Verified snapshot history with logical content, physical increment, location and Vault state |
| BKP-006 | `verified`, `unverified`, `corrupt`, `remoteUnavailable` | Integrity and contents detail; restore is explicitly deferred to P31 |
| BKP-007 | `ready`, `passwordRequired`, `running` | Full scope/destination/Vault/portable choices, five visible phases, safe cancel, retry and Operations link |
| SYS-003 | `disconnected`, `authorizing`, `connected`, `failed` | `drive.file` explanation, authorize and revoke actions |

All eight screens and 28 exact required states render in zh-CN, English and Japanese at 320/360/480 dp, 100/130/200% font scales and light/dark boundaries. User-visible strings consistently say backup—not sync—and the foreground notification is localized in the same three languages. Production pixel hashes are `6df8dddbc7b4ca608c9882cfe77347c67c06c689e06576f5670a3cd6e3f740f1` and `ca479c356c7098cd04d1c9191824655e65786e9c1972bf44f772166dce5e8bac`; they derive only from production Compose, tokens and textual/YAML contracts. Excluded visual drafts were not used.

The exact commands, environments and results are recorded as `P30-E001`—`P30-E008` in `TEST_EVIDENCE.md`.

# P35 Performance, Fault-Injection and Security Audit

Date: 2026-08-12 (Asia/Tokyo)  
Stage: P35 — target-scale performance, fault injection and security audit  
Status: VERIFIED with emulator substitution explicitly authorized by the user; P36 release acceptance is not claimed.

## Provenance and evidence boundary

The user explicitly required Android emulators instead of physical devices for this P35 run. The Android runtime evidence therefore consists of an **API 28 emulator** (`ExpenseTracker_API_28`, Android 9, x86, PIN plus emulator fingerprint enrollment) and an **API 36 emulator** (`ExpenseTracker_API_36` / Gradle Managed Device `pixel6Api36`, Android 16, x86_64), both under KVM. This is truthful emulator evidence and is not represented as physical-device evidence. SQLCipher, Android Keystore, BiometricPrompt/CryptoObject, SAF and process/background behavior were exercised on those Android system images; Robolectric was not used as a substitute.

The frozen requirements, technical-stack performance/testing sections, architecture performance/fault-injection/security sections, complete UI performance acceptance text and the existing P00—P34 implementation ledgers were re-read before implementation. The four excluded visual PNG/HTML drafts were not opened, parsed, hashed, sampled, measured, compared or used.

## Fixed target and budgets

`quality/performance/p35_budgets.json` is the machine-readable regression gate. The benchmark-only SQLCipher fixture in `app/src/benchmark` creates the following real logical scale in bounded 1,000-row transactions; it is absent from debug/release production source sets.

| Surface | Fixed scale / mechanism | Evidence |
|---|---:|---|
| Current transactions | 500,000 `business_transaction` and `current_transaction_projection` rows | SQLCipher target audit count plus keyset page query |
| Changes/history | 1,000,000 immutable transaction revisions plus 1,000,000 postings (2,000,000 history/change rows) | SQLCipher count assertions |
| Attachment relationships/files | 100,000 revision relationships, 50,000 encrypted-object fixture files | SQLCipher association count plus streaming directory enumeration |
| Merchant/location scale | 5,000 merchants, 5,000 places and 5,000 locations/R*Tree entries | SQLCipher count and virtual-table plan assertions |
| Import | 100,000 staged rows, including source row 99,999 failure | P28 streaming/device regression replay |
| Large encrypted streams | 48 GiB simulated backup stream and 20 GiB authenticated restore stream | fixed-buffer JVM tests with 256 KiB/1 MiB boundaries |

The device audit budgets are paging/search ≤5 s, report/map ≤10 s, Java heap growth <64 MiB, file-descriptor growth ≤8, and financial fixture batches ≤1,000 rows. Macro budgets are cold startup P95 ≤3 s, interaction frame-overrun P95 ≤32 ms, jank ≤5%, and at least three measurement iterations for the primary startup/navigation cases. Streaming materialization is prohibited and working buffers are capped at 1 MiB.

## Target-scale SQLCipher and UI measurements

The API 36 emulator target fixture was generated once through the benchmark-only provider in approximately 615,491 ms, then reused through a versioned completion marker. It uses SQLCipher, real schema/migrations, WAL and controlled checkpointing. `P35TargetScaleAuditDeviceTest` produced:

| Measurement | Result | Budget |
|---|---:|---:|
| Keyset paging | 571 ms | 5,000 ms |
| Record-entry defaults | 1 ms | 5,000 ms |
| FTS search | 6 ms | 5,000 ms |
| annual report aggregation | 692 ms | 10,000 ms |
| R*Tree map aggregation | 21 ms | 10,000 ms |
| Java heap growth | 10,092,544 bytes | <67,108,864 bytes |
| file-descriptor growth | 3 | ≤8 |
| native heap at audit | 9,194,528 bytes | observed/bounded by process audit |

The authoritative record-entry reference path returned 15,000 bounded rows in 386 ms. A maintenance-only full reference snapshot returned 15,200 rows in 6,968 ms; the interactive entry path does not use that full snapshot. `EXPLAIN QUERY PLAN` assertions require `ix_current_transaction_keyset` for paging/defaults, `ix_economic_effect_date_nature` for reports, and an R*Tree `VIRTUAL TABLE INDEX` for the map. Search uses FTS5. Query cursors, directory streams and SQLCipher databases are closed with scoped `use`; the measured descriptor delta remains within the fixed gate.

Macrobenchmark ran the actual application over the same target fixture with the checked-in Baseline Profile required:

| Scenario | Result |
|---|---|
| Cold start, 3 iterations | median `timeToInitialDisplay` 215.595 ms; startup/render frame-overrun P95 301 ms. The startup frame value includes process/window composition and is recorded separately from the ≤32 ms interaction gate. |
| Journal paging → accounts → analysis, 3 iterations | frame CPU P95 17.480 ms; frame-overrun P95 1.054 ms; heap approximately 33 MiB |
| Record save → transfer center → import/export/backup → operation center, 2 long-path iterations | heap median 35,708 KiB; anonymous RSS median 120,848 KiB; frame CPU P95 5.2476015 ms; frame-overrun P95 −9.01757825 ms and P99 1.29779645 ms; zero navigation/test failures |

The long-path case intentionally uses two repetitions because each repetition traverses every encrypted-operation destination and does not define the primary three-iteration performance threshold; the startup and large-list/navigation gates retain three repetitions. The complete 32,582-line generated profile was copied byte-for-byte to `app/src/main/baseline-prof.txt` (`SHA-256 dc45c91a232cadc1088737471afe3dbac078c3c6b890acd67de38e7185ac6e8c`). JankStats records only a closed screen-family enum, aggregate frame count, jank count and maximum duration; it contains no amount, note, identity, route parameter or other business payload.

## Database, streaming and failure review

Schema v4 adds one bounded `projection_family_state` row per authoritative projection family. Startup now validates 15 local/valuation generation pairs instead of scanning 500,000-row projections. Financial commits incrementally rebuild only the affected family and update its generation in the same SQLCipher transaction; maintenance still performs full row-count/hash reconstruction. API 36 analytics regression exposed and fixed stale/missing family-generation handling, including the current valuation revision.

WAL remains enabled, foreign keys remain enforced, temporary storage remains memory-only, secure-delete/cipher memory protection remain fail-closed, fixture writes use 1,000-row transactions, and a final `TRUNCATE` plus audit-time `PASSIVE` checkpoint is explicit. No production path acquired the benchmark fixture or direct fixture SQL.

The focused fault suite re-executed these named boundaries:

| Fault | Automated evidence / invariant |
|---|---|
| Database/attachment boundary and process interruption | `cancellationDatabaseFailureAndInterruptedProcessLeaveNoReferencedMissingObject`: no committed reference to a missing encrypted object and temporary cleanup remains retryable |
| Drive interruption | `interruptionResumesAtPersisted256KiBBoundaryWithoutNewSession`: the persisted 256 KiB resumable position is reused and the manifest is not duplicated |
| SAF permission revocation | `revokedPermissionReturnsTypedStateAndCancelCleanupRemovesAppTemporary`: typed failure and app-temporary cleanup |
| ENOSPC | `everyExchangeFaultIncludingStorageFullRollsBackDatabaseKeyAndArtifacts`: database/key/artifact set rolls back together |
| Restore exchange process death | `processDeathBeforeFinalizeRollsBackButAfterFinalizeKeepsVerifiedRestore`: PREPARED is rolled back; FINALIZED retains only a verified restore |
| Keystore replacement | `recoveryWrappedVaultDekIsReboundToFreshDeviceAuthenticationKek`: ciphertext-only recovery rebind, no background PAN/CVC decryption |
| Biometric action reuse/background | `independentActionsUseFreshCryptoObjectsAndBackgroundClearsEveryExposure`: fresh CryptoObject per action, reuse rejected, exposed material cleared |
| Import source row 99,999 | `validationFailureAtSourceRow99999LeavesPrimaryLedgerStateUnchanged`: no primary-ledger mutation; exact retry stays idempotent |
| Ten-year budget history | `tenYearHistoryEditRecomputesEveryLaterRolloverWithoutUnboundedState`: every later month recomputes with bounded state |
| Projection version | `staleProjectionIsNotShownAndFactRebuildRepairsToIdenticalHash`: stale output is withheld and fact rebuild is deterministic |

`BackupStreamingScaleTest`, `RestoreMaterializerTest`, `DriveResumableBackupClientTest`, `PortableBackupContainerTest` and `ManagedBackupRepositoryEngineTest` passed under bounded buffers. The simulated byte counts cross `Int.MAX_VALUE`; payloads are generated/consumed incrementally and are never allocated as complete documents.

## API boundary regression

On the API 28 emulator, SQLCipher v1→v4 migration passed 4/4, financial Room/coordinator persistence passed 3/3, and restore exchange passed 5/5. The full standard managed-device security suite passed 13 tests with two explicitly skipped cases that require an enrolled biometric. A separately configured API 28 emulator with a PIN and emulator fingerprint then passed all 4/4 `VaultPrivacyLifecycleDeviceTest` cases, including real `BiometricPrompt`/CryptoObject, clipboard timer/background clearing and `FLAG_SECURE`.

The API 28 run found a real platform incompatibility: pre-Android 11 does not support `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` with a CryptoObject. The implementation now uses strong biometric plus a localized cancel action for pre-R cryptographic Vault operations, retains credential fallback for non-cryptographic app-lock prompts, and fails closed when a pre-R device has only a credential and no enrolled strong biometric. It never introduces authentication-validity reuse. On the API 36 emulator, the final security suite passed 11/11, database migrations passed 4/4, finance persistence passed 3/3, analytics SQLCipher passed 5/5, attachment infrastructure passed 3/3, SAF passed 2/2, import atomicity/idempotency passed 2/2 and restore exchange passed 5/5.

## Static and dynamic security audit

The P35 validator rejects cleartext networking, production WebView imports, benchmark-provider leakage, unsafe exported providers, business-payload logging, reduced target sizes, missing indexes/checkpoints/bounds, missing fault cases and missing Baseline Profile/JankStats integration. The app uses an explicit fail-closed network security config; the benchmark provider exists only in the benchmark manifest/source set and is protected by `android.permission.DUMP`. Existing provider authorities and grant scopes remain explicit. Production scans found no PAN/CVC/Vault plaintext route, SavedState, log, telemetry, crash, FTS, ordinary export or semantics path.

CycloneDX and license reports were regenerated. The release dependency lock is unchanged (`app/gradle.lockfile` SHA-256 `a9f1266f3f27609cf9f34804e9619f971f08e6ab64407f14a4a4d3e976baa264`). The existing successful OSV batch report covers exactly that 248-component `releaseRuntimeClasspath` and contains zero vulnerable release components. The current aggregate SBOM still contains 883 Maven components, matching the audited inventory count; 18 vulnerable non-release tooling/test components (80 advisory records) remain explicitly recorded as not packaged in the release APK. A fresh OSV upload on 2026-08-12 was rejected by the execution environment's external-dependency-metadata policy, so this audit relies only on the unchanged release lock's successful 2026-08-11 result and does not claim a new network response.

## CI and release-gate integration

`p35Check` combines P34, P35 implementation/mutation validators, benchmark assembly, relevant JVM tests and benchmark Lint. CI runs the API 28/API 36 matrix, SQLCipher and Keystore suites at both supported boundaries, and the API 36 target-scale Macrobenchmark/Baseline Profile suite. Managed-device artifacts now include app, benchmark, database, design-system and security reports. Target scale, memory/descriptor limits, query-plan/index requirements, streaming caps, fault markers, release network boundary and ledger closure are executable gates rather than narrative-only claims.

The final local replay passed `p35Check` over 1,806 tasks in 1m41s, assembled the unsigned release APK and regenerated the 886-component license inventory over 991 tasks in 1m14s, and passed all 219 script mutation/contract tests. The unsigned release APK is 133,935,569 bytes with SHA-256 `8b16909f549d326a0960ffa1b0511cb2910febb0ffe7a344c08459214ed6ed92`. Final P35/P00/P01 validators and repository hygiene checks also pass.

P35 closes `REQ-084` and the P35 non-functional/security acceptance scope. It does not claim a signed bundle, store signing, final NOTICE/privacy packaging or P36 release acceptance.

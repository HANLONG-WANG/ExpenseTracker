# P10 encrypted attachment, location and map mapping

This document maps P10 production behavior to REQ-053—REQ-057 and the allowed textual UI contracts. It does not use the excluded PNG/HTML visual drafts.

## Encrypted attachment lifecycle

| Contract | Production mapping | Automated evidence |
|---|---|---|
| Streaming import, progress and cancellation | `AttachmentContentSource.openStream` is reopenable; `EncryptedAttachmentObjectStore` copies in 64 KiB chunks, updates SHA-256 while Tink Streaming AEAD writes app-private staging, reports checked byte progress and propagates cancellation | `largeImportStreamsEncryptsDeduplicatesRenamesAndGarbageCollects`; `cancellationDatabaseFailureAndInterruptedProcessLeaveNoReferencedMissingObject`; `P10-E002`, `P10-E003` |
| Hash + size deduplication | The SQLCipher catalog looks up the exact `plaintext_sha256` + `plaintext_size` pair; an existing encrypted blob gains a new attachment reference, while a new blob moves atomically before its transaction commits | Same device tests; `P10-E003` |
| Random physical names and no plaintext copy | `PlatformCryptographicRandomSource` produces opaque 128-bit object/staging names under `noBackupFilesDir`; display names are separately sanitized and never used as paths | `AttachmentMetadataPolicyTest`; private-path and sensitive-sentinel device assertions; `P10-E002`, `P10-E003` |
| Reference-aware GC and failure cleanup | `blob_gc_candidate` is rechecked against attachments, revision history and backup objects; interrupted staging and unreferenced object families are recoverable, while committed metadata never points at a missing object | Failure/cancellation/interruption/GC device test; `P10-E003` |
| Encrypted thumbnails and Coil 3 | Thumbnails use a separate associated-data purpose; `EncryptedAttachmentFetcher` decrypts a stream only, disables disk/network caches, and `SecureAttachmentImageLoader` clears memory on lock | Thumbnail/Coil preview device tests; `P10-E003` |
| External opening | ATT-002 confirmation creates one 192-bit token for at most 60 seconds; the non-exported `SecureAttachmentProvider` consumes it once, revokes the grant and decrypts through a reliable pipe without a long-lived plaintext file | Provider query/open/reuse/lock tests; `P10-E003`, `P10-E005` |
| Metadata and rename | MIME, extension and display name are normalized; ATT-001 shows logical metadata only and ATT-003 writes only the encrypted-database display name | `AttachmentMetadataPolicyTest`; ATT required-state device test; `P10-E002`, `P10-E005` |

Associated data binds purpose, book ID, blob ID and encryption version independently for wrapped attachment keys, original content and thumbnails. Attachment paths, hashes and plaintext are absent from routes, SavedState, logs, telemetry and Compose semantics.

## Foreground location and map lifecycle

| Contract | Production mapping | Automated evidence |
|---|---|---|
| Foreground permission only | The manifest declares coarse/fine location and never background location; denied permission exits before either provider is touched | `deniedPermissionReturnsTypedFailureWithoutTouchingProviders`; static `PRIVACY-BACKGROUND-LOCATION`; `P10-E004`, `P10-E006` |
| Fused primary, platform fallback | Google Play services availability selects Fused Location Provider; missing/unusable fused capability falls back to `LocationManager` GPS/network | `missingPlayServicesUsesLocationManagerBoundaryAndFreezesGpsEvidence`; `P10-E004` |
| Maximum three-second save wait | `ForegroundLocationSaveSession` measures one monotonic budget across prefetch and save; timeout cancels the request, returns no location and schedules no supplement | JVM timeout test and expired-deadline device test; `P10-E002`, `P10-E004` |
| Frozen precise evidence | Latitude/longitude use checked E7 integers, accuracy uses integer millimeters, provider and capture instant are immutable application values | Location device test and `LedgerMapContractTest`; `P10-E002`, `P10-E004` |
| MapLibre lifecycle and style | `LedgerMapController` owns `MapView` create/start/resume/pause/stop/destroy, HTTPS/asset/JSON styles, SDK logo/attribution and explicit style/render failure callbacks | Actual MapLibre local-style API 36 test; `P10-E004` |
| Cluster/heat/single layers | GeoJSON uses opaque stable IDs and fixed-point input; cluster diameters, point sizes and the full heat sequence derive from `LedgerMapDesignContract` tokens | Actual MapLibre overlay-mode device test; `P10-E004` |
| Accessible fallback | Map semantics expose only a caller-supplied summary, never coordinates; unavailable map/style paths retain `AccessibleDataTable`, and a visible control exposes the same table beside a working map | Map and permission device tests; `P10-E004` |

No online place search or reverse geocoding exists in P10. REQ-053 remains `IN_PROGRESS` for P12 merchant/place recommendation, and REQ-056 remains `IN_PROGRESS` for the P27 analytics filters, aggregation and ANA-011/012 screens.

## Screen/state mapping

| Screen | Exact required states covered in P10 | Ledger state |
|---|---|---|
| REC-009 | `locating`, `located`, `permissionDenied`, `timeout`, `manual`, `mapUnavailable` | `IN_PROGRESS`: all infrastructure UI states render at 320dp/200%; P13 owns complete form/action wiring |
| REC-010 | `content`, `empty`, `importing`, `failed` | `IN_PROGRESS`: arbitrary list/progress/cancel/failure UI is verified; P13 owns complete record flow wiring |
| ATT-001 | `loading`, `image`, `unsupportedPreview`, `decryptError` | `VERIFIED` |
| ATT-002 | `content` | `VERIFIED` |
| ATT-003 | `editing`, `invalid` | `VERIFIED` |
| SYS-001 | `firstAsk`, `denied`, `permanentlyDenied` | `VERIFIED` |
| ANA-011/012 | P10 supplies only `LedgerMap`; no analytics screen is created | `NOT_STARTED` until P27 |

All visible strings are present symmetrically in Simplified Chinese, Japanese and English. Tests use stable design-system tags and generic content descriptions; filenames, paths, hashes and raw coordinates are not placed in semantics.

## Module and write boundaries

- `:core:files` owns SAF/Coil/Provider/object-store adapters and may call only its narrow attachment SQLCipher catalog. It does not create financial facts or bypass `FinancialMutationCoordinator`.
- `:core:geo` owns Fused Location Provider, `LocationManager` and MapLibre. Feature code receives typed application values or an injected map composable, never an SDK client.
- `:feature:record` renders REC state models and depends on `:finance:application`, `:core:designsystem` and `:core:navigation`; static rules reject direct `:core:files`, `:core:geo`, Coil, MapLibre and Fused SDK access.
- Financial submission remains exclusively the existing P08 command handler/coordinator path. Attachment object preparation is not a financial write and must be referenced by the later record command only after a successful object/catalog import.

## Deliberate later-stage boundary

P10 does not implement or claim P13 complete recording, P27 analytics map queries/screens, P28 bulk import, P29—P31 backup/restore/merge, background location, online geocoding, or foreground/UIDT service behavior. No later stage is promoted by this mapping.

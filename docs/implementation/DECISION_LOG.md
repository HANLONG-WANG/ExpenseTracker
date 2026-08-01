# Decision Log

Last updated: 2026-08-01 (Asia/Tokyo)

This log records interpretations; it does not modify frozen specifications. Later stages must append a dated entry before relying on any new conflict resolution.

## DL-001 — Visual review materials are excluded from implementation input

- Date/stage: 2026-07-31 / P00
- Surface issue: `README_交付说明.md` mentions visual review using HTML/PNG drafts, while the current execution contract and preserved execution plan absolutely prohibit opening or referencing those four drafts.
- Precedence applied: the explicit execution constraint governs this run; UI semantics come from the main contract, concrete values from JSON, coverage from YAML/CSV.
- Decision: never open, parse, hash, screenshot, sample, measure or compare the four visual files. The validator reads their names and manifest digests only from `MANIFEST.sha256` and deliberately does not access those paths.
- Consequence: manifest verification covers the five textual UI implementation inputs; excluded visual file contents are not claimed verified by P00.

## DL-002 — ADR-007A is the explicit resolution of append-only versus privacy purge

- Date/stage: 2026-07-31 / P00
- Surface conflict: architecture ADR-007 says Journal/Posting are never updated or deleted, while product requirements require permanent removal after trash retention.
- Higher-priority/spec resolution: domain/schema document §1 already defines ADR-007A.
- Decision: ADR-007 means append-only for ordinary operations. A controlled privacy purge is the sole physical deletion exception and requires a fully reversed zero-net chain, handled dependencies, elapsed retention, maintenance lock, non-sensitive purge tombstone and merge precedence.
- Consequence: no ordinary edit/delete/restore path may physically remove accounting facts. P31 must verify every ADR-007A eligibility and failure path.

## DL-003 — P00 creates no Android placeholder implementation

- Date/stage: 2026-07-31 / P00
- Observation: the repository has frozen documentation only; there is no Gradle project or source tree.
- Decision: P00 adds only audit ledgers and a reusable specification validator. It does not scaffold modules, empty screens, fake persistence or production stubs.
- Consequence: all REQ and screen implementation statuses remain `NOT_STARTED`; P01 owns the build/module baseline.

## DL-004 — Screen-to-requirement mapping is derived from the CSV, not invented in YAML

- Date/stage: 2026-07-31 / P00
- Observation: all 215 YAML `requirementTags` arrays are empty, while the 90-row traceability CSV supplies screen/flow mappings.
- Decision: preserve the frozen YAML unchanged. `SCREEN_COVERAGE.csv` reverse-maps explicit screen references and ranges from the CSV; screens with no explicit CSV screen reference remain registered with an empty `requirement_ids` cell.
- Consequence: a blank reverse mapping does not remove a screen or waive its `requiredStates`, params, result, components or notes. P02 should make drift in either contract fail CI.

## DL-005 — Status promotion requires separate implementation and verification evidence

- Date/stage: 2026-07-31 / P00
- Decision: `IMPLEMENTED` means compliant code exists; it is not a synonym for passing acceptance. `VERIFIED` requires an evidence ID with command/test, environment and result. `BLOCKED` requires a real external condition and cannot be used for code-solvable work.
- Consequence: P00 baseline registration does not promote REQ-001—090 or any screen.

## DL-006 — P01 is environmentally blocked, not scope-blocked

- Date/stage: 2026-07-31 / P00
- Observation: this host has OpenJDK 25.0.3 and adb 35.0.2, but no detected JDK 17, `sdkmanager`, Android SDK Platform 36 or Build Tools 36.
- Decision: finish the P00 documentation audit because those tools are not needed for P00. Do not start P01 until the frozen JDK/Android SDK prerequisites are installed and verified.
- Consequence: P00 can be VERIFIED independently; P01 remains `BLOCKED` from execution by missing SDK-level tools.

This was a P00-time observation. The required JDK 17 and Android SDK 36 toolchain was subsequently installed and verified by `P01-E001`; it is no longer a current blocker.

## DL-007 — Frozen version families resolve to exact stable patches

- Date/stage: 2026-08-01 / P01
- Surface issue: the frozen stack specifies patch families for Gradle 9.5.x, AGP 9.3.x and Kotlin 2.4.x but reproducible builds require exact versions.
- Decision: pin Gradle 9.5.1, AGP 9.3.1 and Kotlin 2.4.10; pin stable KSP 2.3.10. The same exact-version rule applies to the frozen library catalog, including Compose BOM 2026.06.01, Navigation3 1.1.5, Glance 1.1.1, Vico 3.2.3, MapLibre 13.4.1, Room 2.8.4, SQLCipher 4.17.0, Coil 3.5.0, Tink 1.23.0 and FastExcel 0.20.2.
- Precedence applied: the exact stable patch is selected within the frozen version family; no frozen specification is changed.
- Consequence: the catalog has no dynamic or direct alpha/beta/RC/SNAPSHOT version, and `verifyFrozenVersions` makes drift fail the build.

## DL-008 — Stable production dependency rule excludes AGP-internal tooling artifacts

- Date/stage: 2026-08-01 / P01
- Surface issue: stable AGP 9.3.1 transitively uses versioned Android test-platform/Jetifier tooling artifacts whose upstream coordinates contain alpha/beta labels. They are not selected application dependencies and are absent from production runtime classpaths.
- Decision: preserve the frozen stable AGP instead of overriding its internal toolchain. Prohibit prerelease versions in every direct catalog entry and in every `debugRuntimeClasspath`/`releaseRuntimeClasspath`; allow only the stable plugin's private build/test tooling graph.
- Consequence: `validate_p01_baseline.py` fails on any prerelease production-runtime lock entry. This interpretation does not permit prerelease application libraries.

## DL-009 — Gradle grouping projects are governed nodes, not architecture modules

- Date/stage: 2026-08-01 / P01
- Surface issue: including paths such as `:finance:domain` necessarily creates Gradle parent projects (`:finance`), although the frozen architecture names only leaf modules.
- Decision: register the five generated parent projects (`:core`, `:finance`, `:analytics`, `:transfer`, `:feature`) as zero-dependency grouping nodes. Treat the 35 prescribed leaf projects as the architecture modules and `:build-logic` as an included build. Give each family a distinct group namespace so same-named `domain`/`data` modules cannot collide by Gradle capability.
- Consequence: the exact project and edge validator governs both grouping and leaf projects; no parent project is an exception dependency channel.

## DL-010 — P01 provides a page-free shell and provisional build identity

- Date/stage: 2026-08-01 / P01
- Surface issue: P01 requires a buildable `:app`, but no business page is in scope and the final Play `applicationId` is an external P36 input.
- Decision: use `app.ledger.expensetracker` only as the reproducible build identity and provide a security-hardened application manifest without an Activity. Do not introduce a fake launcher, empty Compose page or placeholder feature.
- Consequence: debug/release APK assembly verifies the carrier project only. No screen or business requirement is promoted, and the final store identity remains explicitly external in `RELEASE_READINESS.md`.

## DL-011 — KSP's lazily resolved tooling is explicitly locked and verified

- Date/stage: 2026-08-01 / P01
- Surface issue: KSP 2.3.10 resolves compiler-embedded tooling lazily while Gradle serializes the configuration cache, after ordinary project dependency-report traversal. Strict dependency verification initially exposed those otherwise-unregistered artifacts.
- Decision: define an exact, resolvable root verification configuration for KSP's symbol-processing embeddable artifact and its required coroutine runtime. Make `p01Check` resolve it, lock it and include it in SHA-256 verification metadata.
- Consequence: configuration cache and strict dependency verification stay enabled; there is no unverified lazy-download path or weakened verification mode.

## DL-012 — Stable quality adapters preserve the frozen stable-only and warning-fail rules

- Date/stage: 2026-08-01 / P02
- Surface conflict: the stable detekt 1.23.8 Gradle adapter calls a Gradle API that is deprecated under the frozen Gradle 9.5 warning-as-error build. The stable AndroidX Baseline Profile Gradle plugin 1.4.1 expects an Android test extension removed by AGP 9.3, while its AGP-9-compatible successor is prerelease and therefore forbidden by the stable-only rule.
- Precedence applied: frozen stable versions and `org.gradle.warning.mode=fail` outrank convenience plugin adapters; performance behavior still belongs to the frozen Macrobenchmark/Baseline Profile stack.
- Decision: run the exact stable detekt CLI from a verified, locked configuration and publish the same HTML/SARIF/XML reports. Keep the stable Macrobenchmark and `BaselineProfileRule` libraries, ProfileInstaller, self-instrumenting `:benchmark` module and API 36 GMD entry; do not add a prerelease Baseline Profile plugin or fabricate an empty profile before real critical user journeys exist.
- Consequence: detekt is an enforced aggregate gate now. Actual Macrobenchmark measurements and a generated Baseline Profile remain P35/P36 evidence, not a P02 completion claim.

## DL-013 — P02 screenshot infrastructure has contract-only provenance and no fabricated golden

- Date/stage: 2026-08-01 / P02
- Surface issue: P02 must establish screenshot-test provenance, but the repository intentionally has no business UI before P04 and the four visual drafts are prohibited inputs.
- Decision: provide the AndroidX Compose UI test/capture-capable device harness and enforce all 215 screen IDs, states and token values as machine-readable inputs. Create no screenshot baseline for the page-free shell. Later goldens must be captured from implemented Compose UI whose source values trace to the main UI contract, token JSON and screen YAML/CSV only.
- Consequence: no visual draft was read or treated as an oracle; a later baseline cannot silently replace or bypass the textual contracts.

## DL-014 — Managed Devices use usable KVM and the stable host renderer

- Date/stage: 2026-08-01 / P02
- Surface issue: the initial container view did not expose `/dev/kvm`; after KVM became available, Emulator 36.6.11 and 37.1.11 still terminated with host SIGSEGV while creating an API 28 snapshot through the `software`/SwiftShader gfxstream path.
- Evidence: `/dev/kvm` is now accessible as character device `10,232`, Emulator 37.1.11 reports usable KVM 12, and a direct cold boot of the same API 28 AVD completes in 12.176 seconds with `-gpu host`. The three formal Gradle Managed Device tasks then pass with zero failures/errors/skips.
- Decision: keep hardware acceleration mandatory, use the stable Emulator 37.1.11 installed through `sdkmanager`, and set the supported Managed Device GPU mode to `host`. Do not disable KVM, replace device tests with Robolectric, or claim compile-only evidence.
- Consequence: P02 device execution is reproducible on the current accelerated host and CI retains the same API 28/API 36 GMD entry points. This decision governs only the test runner backend; it changes no UI token, screenshot source or product behavior.

## DL-015 — StableId uses defensive byte ownership instead of an inline ByteArray wrapper

- Date/stage: 2026-08-01 / P03
- Surface issue: the domain document §4.1 illustrates `@JvmInline value class StableId(val bytes: ByteArray)`, while P03 also requires immutable results. A public array property and an inline wrapper would allow callers to mutate an ID after construction and invalidate equality/hash behavior.
- Precedence applied: the stable 16-byte UUID/BLOB representation and dual-ID semantics are normative; the fenced Kotlin declaration is an illustrative shape. Immutability and merge identity safety take precedence over allocation-free wrapping.
- Decision: `StableId` owns a defensive 16-byte copy, returns copies, implements content equality/order, and round-trips UUID without exposing mutable storage. `InternalId` remains an inline positive `Long` created through a typed-result factory.
- Consequence: database adapters still persist exactly `BLOB(16)` and `INTEGER PRIMARY KEY`; callers cannot mutate identity bytes. No frozen specification is changed.

## DL-016 — Ambiguous local times require explicit deterministic policies

- Date/stage: 2026-08-01 / P03
- Surface issue: the frozen model requires `Instant`, `ZoneId`, local date and DST-safe testing but does not choose behavior for a nonexistent or duplicated local wall time entered by a user.
- Decision: an existing `Instant` is always authoritative. Resolving a local date/time exposes explicit policies: gaps either reject or shift by the exact transition duration, and overlaps select the earlier or later offset. Defaults are shift-forward and earlier-offset, while callers can request the alternatives.
- Consequence: DST handling is deterministic and testable, never silently uses the host default zone, and preserves the separately derived local date. Feature UI may explain a shifted time later without reimplementing zone rules.

## DL-017 — Formatting contracts stay exact and Android-independent

- Date/stage: 2026-08-01 / P03
- Surface issue: UI contract §§8.7, 10 and 15.5 require preformatted UI models, while architecture assigns exact money/time work to pure Kotlin core modules and no Compose page exists before P04.
- Decision: `CurrencyFormatter` produces `MoneyUiModel` from `Money`, currency metadata and an explicit `Locale`; `LedgerDateTimeFormatter` similarly produces formatted temporal models. Hidden-value text is supplied by the presentation resource layer, so the formatter cannot leak the underlying amount or hard-code user-visible translations. Neither formatter performs FX or authoritative business calculation.
- Consequence: later Composables consume formatted models and need no `BigDecimal`, FX, time-zone or locale calculation. P03 does not claim an `AmountText` Composable or any screen implementation.

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

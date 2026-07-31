# Decision Log

Last updated: 2026-07-31 (Asia/Tokyo)

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

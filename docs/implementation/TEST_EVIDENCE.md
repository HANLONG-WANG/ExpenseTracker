# Test Evidence

Last updated: 2026-07-31 (Asia/Tokyo)  
Evidence policy: a `VERIFIED` ledger row must cite an evidence ID below containing the exact command/test, environment and passing result. Implementation existence alone is not verification. Device-only claims cannot cite Robolectric or JVM-only evidence.

## Environment

- Repository baseline commit: `7b8021d41a51070bbfa15e948614948ed6e17519`
- Host: Fedora Linux, kernel `7.1.4-204.fc44.x86_64`
- Python: 3.14.6
- PyYAML: 6.0.3
- jq: 1.8.1
- Java: OpenJDK 25.0.3 (not the frozen P01 JDK 17 toolchain)
- adb: 35.0.2
- Android/Gradle project: absent at P00; therefore no Android build, unit, instrumented, UI, migration, screenshot, accessibility or performance test is applicable yet.

## P00 evidence

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P00-E001 | Frozen source parsing and structural completeness | `python3 scripts/validate_spec_baseline.py` | JSON, YAML and CSV parse; 434 token scalar paths; 90 sequential unique REQs; 215 unique screen IDs and routes | PASS, exit 0: 434 / 90 / 215 / 215 |
| P00-E002 | Manifest integrity without visual-draft reads | Same validator; it hashes only the five textual UI contract inputs and compares them with `MANIFEST.sha256` | 5 text entries match; 4 forbidden names are listed but their files are not opened | PASS: 5 textual entries matched; forbidden paths opened by validator: 0 |
| P00-E003 | Persistent ledger completeness | Same validator plus explicit CSV assertions | Exactly 90 requirement rows, 215 screen rows, ADR-001—020, ADR-007A, UI-ADR-001—012, INV-001—035, 94 logical tables, 12 schema families, 7 projection families and 21 operation/transfer records are present | PASS: all counts matched; 90 REQ and 215 screens remain `NOT_STARTED` |
| P00-E004 | Frozen files unchanged | `git diff --exit-code -- docs/规格冻结_v1.0 docs/UI设计稿与实现契约_v1.0` | Exit 0, no frozen tracked changes | PASS, exit 0 |
| P00-E005 | Repository baseline inventory | `git status --short --branch`; `rg --files` with all four forbidden paths excluded; explicit build/source/CI directory checks | Documentation-only baseline; preserved untracked plan; no Gradle/code/tests/resources/CI/build output | PASS; observations recorded in `PROJECT_STATE.md` |
| P00-E006 | Validator syntax | `PYTHONPYCACHEPREFIX=/tmp/expense_tracker_p00_pycache python3 -m py_compile scripts/validate_spec_baseline.py` | Exit 0 | PASS, exit 0 |

Additional hygiene command: `git diff --check` passed with exit 0 on 2026-07-31.

## Future mandatory evidence classes

| Class | Minimum acceptable evidence | Earliest phase |
|---|---|---|
| Build/static | Frozen Gradle aggregate task, lint, detekt, formatting, dependency locks/verification, architecture checks | P01/P02 |
| Domain | JUnit 5/Kotest property tests for all applicable accounting and deterministic invariants | P03/P06 |
| Database | Room migration helper plus actual-device SQLCipher/FTS5/R*Tree/WAL/integrity/projection evidence | P07/P08 |
| Security/device | Actual-device Keystore, BiometricPrompt, app lock, vault, clipboard and screenshot behavior | P09/P32 |
| Platform/device | Actual-device SAF, location timeout/permission and foreground/UIDT behavior | P10/P28—P31 |
| UI | Compose UI, screenshot/golden, three languages, width/font/theme matrix, TalkBack manual run and semantic leak audit | P04 and feature phases |
| Performance/fault | Macrobenchmark/profile, target-scale generators, query plans, streaming memory, cancellation, crash recovery and fault injection | P28—P35 |
| Release | Release AAB, Baseline Profile, SBOM, licenses/NOTICE, locks, signing/config checks and full acceptance report | P36 |

No future evidence is marked PASS in P00.

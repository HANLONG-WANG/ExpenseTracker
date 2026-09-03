# Release Readiness

Last updated: 2026-09-04 (Asia/Tokyo)

Overall release status: `VERIFIED`
The P36 repository/release baseline and P37 interactive-performance remediation are verified with same-candidate API 28/API 36 device evidence, final host/evidence aggregates and delivery hygiene. Play upload remains intentionally gated only by publisher-owned inputs.

## Final quality gates

| Gate | Final status | Evidence |
|---|---|---|
| Frozen specification integrity | VERIFIED | P00/P01 validators: 10 normative files, 90 requirements, 215 unique screens/routes, 35 invariants, 21 architecture ADR rows and 12 UI ADR rows; excluded visual drafts remain fail-closed |
| Requirements and screen closure | VERIFIED | `REQUIREMENT_COVERAGE.csv` 90/90 and `SCREEN_COVERAGE.csv` 215/215, every row with implementation and verification evidence |
| Domain, accounting and encrypted schema | VERIFIED | 35/35 invariants; coordinator-only finance writes; SQLCipher Schema v1→v4 migration, FTS5/R*Tree/WAL/integrity/projection replay on API 28/API 36 emulators |
| Localization, accessibility and visual governance | VERIFIED | Simplified Chinese/Japanese/English; widths/fonts/themes; real installed TalkBack on API 36; contract/token-derived goldens; sensitive semantic scans |
| Target scale, faults and privacy/security | VERIFIED | 500k current/2m history, 100k import, 48 GiB backup/20 GiB restore streams, failure matrix, Keystore/BiometricPrompt/SAF and security scans |
| Static/JVM/Android/release tests | VERIFIED | `p36Check`, 234 Python mutation/contract tests, API 28 104-case matrix plus enrolled-biometric 4/4, API 36 189/189 matrix plus R8 reflection 1/1 |
| Release hardening | VERIFIED | version 1.0.0/code 1, non-debuggable, optimized R8, resource shrinking, no release debug dependency, partial signing fails closed, no embedded secret |
| Supply chain and legal delivery | VERIFIED | exact locks/verification metadata, 886-component CycloneDX SBOM, 846 third-party licenses with zero unknown, NOTICE and artifact hashes; final OSV audit covers 248 release-runtime components with zero vulnerable components |
| Privacy/about/release delivery | VERIFIED | three long-form privacy policies and in-app locales, About/open-source, 1.0.0 release notes, reproducible-build guide and Play input checklist |
| Release artifact | VERIFIED | unsigned external-signing AAB candidate, 49,693,754 bytes, SHA-256 `0f70b2581ee3b147a210d985d124e65705ba422a7bc3f42aff8637cddbc2d415`; 14-artifact manifest with declared generated-artifact inputs |
| P37 interactive performance remediation | VERIFIED | One process-owned live primary, zero warm reopen/unwrap, one save transaction, bounded Journal queries, retained route loading, same-candidate API 28/API 36 latency evidence, final `p37HostCheck`/`p37Check` aggregates and ledger hygiene pass |

## P37 corrective-candidate evidence

Both accepted evidence files use fixture `P35_TARGET_SCALE_V1`, contain all raw samples with P50/P90/P95/max and zero timeouts/failed samples, and bind to identical release/R8 artifacts: target APK SHA-256 `fa808559a0a4ab324a19445786601af465c0baf5cebdbf99d917cf54298db17b`; benchmark APK SHA-256 `e82e091ba138752042c61bb85e3b2d2d2a4657e9cd62168ae28506127899b11e`. The result files are API 28 SHA-256 `ec98fd786d2d1cc6083a762538700ec9c4de3dd5be7f7752ef3619f217a41e3b` and API 36 SHA-256 `8e8ea76cc36fff6acbf047f3f3ca4d2c5756aa3bf3d3d0812c7e421d25ef6b78`.

| Frozen interaction gate | API 28 result | API 36 result | Status |
|---|---:|---:|---|
| Unlock to current-route content P95 <= 1,500 ms | 1,286.429852 ms | 962.274786 ms | PASS |
| Warm cached navigation P95 <= 250 ms | 0.101399 ms | 0.172393 ms | PASS |
| Warm uncached bounded destination P95 <= 750 ms | 0.137157 ms | 0.146510 ms | PASS |
| Blocking progress maximum <= 100 ms | 48.344222 ms | 48.266427 ms | PASS |
| Ordinary save P95 <= 750 ms | 275.812275 ms | 241.666452 ms | PASS |
| Search including debounce P95 <= 800 ms | 304.551199 ms | 305.754858 ms | PASS |
| Search after debounce P95 <= 500 ms | 3.886088 ms | 5.869988 ms | PASS |
| Frame P95 <= 32 ms | 3.268501 ms CPU duration | -11.795175 ms native overrun | PASS |

Deterministic counters match on both environments: unlock primary-open/key-unwrap `1/1`; every measured warm interaction delta `0/0`; ordinary save financial transaction count `1`; Journal page and page-with-running-balance SQL statements `2/2` against limits `3/4`. API 28 and API 36 are KVM-backed emulator evidence only. P37 does not claim a physical-device run, remote GitHub Actions execution, publisher signing or Play publication.

## Provenance and claims

The user explicitly authorized emulators instead of physical devices. API 28 x86 and API 36 x86_64 KVM-backed Android images supplied the device evidence; SQLCipher, Keystore, BiometricPrompt/CryptoObject, SAF, process/background and Macrobenchmark behavior were not replaced by Robolectric. These results are not represented as physical-device results.

The final replay found and fixed API 28 arithmetic, projection invalidation/rebuild, restore diagnostics, off-screen UI/TalkBack SDK scoping, release-only WorkManager/R8 reflection and stale release-manifest incrementality defects. The repaired release-equivalent target APK passed its dedicated reflection regression, and the manifest mutation test prevents an AAB/SBOM/license rebuild from retaining stale hashes. There are no unexplained `BLOCKED` rows, known high/medium defects, dynamic versions, production TODO/NotImplemented markers, anonymous pages, fake persistence or checked-in skipped tests.

Remote GitHub Actions has not been run from this workspace and is not claimed as passed. Its P36 workflow/task/artifact configuration is statically verified. The final local `p36Artifacts` run obtained a fresh OSV response for all 248 locked release-runtime components and found zero vulnerable release components; 18 non-release tooling/test findings remain visible in the report.

## Publisher-owned external inputs

These inputs are required for Play publication, not for repository correctness. Their absence produces an unsigned, non-uploadable candidate and never enables mock credentials or fake success.

| External input | Publication validation |
|---|---|
| Final `applicationId` and Play listing identity | Supply `-PledgerApplicationId`; match Play Console, policies and OAuth records |
| Upload key and Play App Signing | Supply all four `ledgerSigning*` values from a secret store; verify upload/distribution certificate fingerprints |
| Google Drive OAuth | Register final package and Play signing fingerprints; approve `drive.file` consent/API configuration |
| Telemetry/crash receiver | Approve HTTPS endpoint/certificate, fixed schema, 90/180-day retention and deletion behavior |
| Public URLs/contact | Publish three-language privacy URL plus support and source-repository URLs |
| Store metadata/assets | Approve listing, data safety, content rating, regions and screenshots generated from the shipped build |

The exact checklist is `docs/release/PLAY_RELEASE_INPUTS.md`. Store publication and signing are not claimed until the publisher supplies and validates these inputs.

## Reproducible release entry point

Use Temurin JDK 17, Android SDK Platform/Build Tools 36, Gradle 9.5.1, AGP 9.3.1, Kotlin 2.4.10, strict dependency verification and the documented KVM emulator environment:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_spec_baseline.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p01_baseline.py
./gradlew p36Check p36Artifacts --no-configuration-cache --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g --dependency-verification=strict --console=plain
```

The complete commands, signing parameters, outputs and hash verification are in `docs/release/REPRODUCIBLE_BUILD.md`; detailed acceptance is in `P36_FINAL_ACCEPTANCE_AND_RELEASE_DELIVERY.md` and `P36-E001`—`P36-E008`.

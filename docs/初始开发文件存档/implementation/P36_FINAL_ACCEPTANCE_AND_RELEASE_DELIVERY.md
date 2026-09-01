# P36 Final Acceptance and Release Delivery

Date: 2026-08-12 (Asia/Tokyo)

Stage: P36 — final full acceptance, release hardening and delivery
Status: VERIFIED for repository code and an unsigned external-signing release candidate

## Acceptance boundary and provenance

All normative requirement, stack, architecture, domain/schema, UI main-contract, token JSON, screen YAML, traceability CSV and delivery README sources were re-read or machine-queried before final promotion. The four expressly excluded visual PNG/HTML drafts were not opened, parsed, hashed, sampled, measured, compared or used. The requirement ledger is 90/90 `VERIFIED`, the screen ledger is 215/215 `VERIFIED`, and this domain ledger retains 35/35 permanent invariants, 21/21 architecture ADR rows (including ADR-007A) and 12/12 UI ADR rows as evidence-backed `VERIFIED`.

The user explicitly authorized Android emulator substitution for physical devices. Runtime evidence therefore uses KVM-backed API 28 x86 and API 36 x86_64 Android system images and is never described as physical-device evidence. SQLCipher, Android Keystore, BiometricPrompt/CryptoObject, SAF, process/background behavior and Macrobenchmark ran on Android system images, not Robolectric.

## Final defects found and closed

The full replay found and fixed defects rather than documenting them for later:

- API 28 exact analytics/settlement arithmetic now uses the shared checked conversion instead of an unavailable platform `BigInteger` method.
- incremental analytics now matches full rebuild grouping and does not create zero-only rows; refund invalidation includes the original transaction and every affected accrual date.
- merge restore performs the full projection rebuild inside the coordinator-owned transaction and preserves fixed, non-sensitive projection failure codes.
- off-screen P33 content is reached through semantics scrolling; API-36-specific golden/TalkBack cases are explicitly SDK-bounded and the API 36 test enables the installed TalkBack service.
- the release-equivalent R8 benchmark exposed WorkManager's reflectively created `OverwritingInputMerger` constructor being removed. A precise keep rule plus a target-APK reflection device test now closes and guards that release-only failure.
- the release manifest now declares the AAB, SBOM and generated license reports as Gradle inputs; rebuilding any published artifact invalidates and regenerates its hash metadata, guarded by a mutation test.

No new financial write path was introduced. All finance application writes continue through `FinancialMutationCoordinator`; restore/merge projection reconstruction remains inside the coordinator-owned SQLCipher transaction.

## Device and target-scale replay

`p36Api28ManagedDeviceCheck` completed 877 Gradle tasks at the minimum API boundary in 17m31s: 104 instrumented cases across app, analytics, database, design system, files, security, finance, transfer and widgets had zero failure/error; two cases that require enrolled biometrics were skipped by the disposable managed device. The persistent API 28 emulator with PIN and enrolled fingerprint separately passed all 4/4 `VaultPrivacyLifecycleDeviceTest` cases, covering fresh per-action CryptoObjects, background/timeout clearing, sensitive clipboard cleanup and `FLAG_SECURE`.

`p36Api36ManagedDeviceCheck` completed 2,295 Gradle tasks in 45m42s. It passed 189/189 cases with no failure/error/skip across the app and 21 library/feature/widget modules plus the five release-equivalent target-scale benchmark cases. This includes 25/25 app contract/golden/TalkBack tests, 30/30 encrypted finance tests, 16/16 transfer/import tests and the P35 500k-current/2m-history fixture, Baseline Profile and Macrobenchmarks. A separate rebuilt R8 target APK passed `P36ReleaseMinificationAuditDeviceTest` 1/1 in 2m29s.

The post-fix `p36Check` replay passed in 27s over 2,459 tasks (44 executed, 2,415 up-to-date), including architecture/source policy, Spotless, Detekt, Android Lint/LintVital, all JVM suites, P34/P35 regressions, 234/234 repository contract tests and the 15/15 P36 mutation suite. The final P00, P01 and P36 validators independently pass after the evidence ledgers were closed.

## Release candidate and supply chain

The release configuration is version `1.0.0` / code `1`, non-debuggable, minified and resource-shrunk with optimized R8 rules. `ledgerApplicationId` and all four `ledgerSigning*` properties are external, validated and fail closed on partial input; the repository contains no keystore or signing secret. Without publisher inputs, the result is deliberately an unsigned, non-uploadable external-signing candidate:

- AAB: `app/build/outputs/bundle/release/app-release.aab`
- size: 49,693,754 bytes
- SHA-256: `0f70b2581ee3b147a210d985d124e65705ba422a7bc3f42aff8637cddbc2d415`
- Baseline Profile: existing audited content, SHA-256 `dc45c91a232cadc1088737471afe3dbac078c3c6b890acd67de38e7185ac6e8c`
- aggregate CycloneDX SBOM: 886 components
- third-party license inventory: 846 rows, zero `UNKNOWN`
- release dependency lock: SHA-256 `a9f1266f3f27609cf9f34804e9619f971f08e6ab64407f14a4a4d3e976baa264`

`release-metadata.json` and `p36-artifacts.sha256` cover 14 artifacts: AAB, profile, SBOM, three license/NOTICE outputs, root NOTICE, About, Play input checklist, three privacy policies, reproducible-build guide and release notes. The final `p36Artifacts` run audited all 248 locked release-runtime components against OSV and found zero vulnerable release components; 18 findings confined to non-release tooling/test dependencies remain recorded and are not hidden. Remote GitHub Actions is configuration-verified only; no remote run is claimed.

## Delivery and external publication inputs

The repository contains root `NOTICE`, complete Simplified Chinese/Japanese/English privacy policies, in-app long-form three-language privacy text, About/open-source delivery notes, 1.0.0 release notes, reproducible-build instructions and the exact Play external-input checklist. Only publisher-owned inputs remain before Play upload: final application ID/listing identity, upload key and Play App Signing enrollment, Drive OAuth registration for the final identity, approved telemetry/crash HTTPS service policy, policy/support/source URLs, and store metadata/assets. Their absence does not enable mock credentials or fake success.

There are no unexplained repository `BLOCKED` rows, known high/medium defects, production TODO/NotImplemented markers, skipped checked-in tests, dynamic dependency versions, release debug dependencies, fake persistence or anonymous screen contracts. P36 closes repository implementation and delivery; store publication itself is intentionally not claimed.

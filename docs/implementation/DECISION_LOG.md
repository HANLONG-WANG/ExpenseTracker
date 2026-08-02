# Decision Log

Last updated: 2026-08-02 (Asia/Tokyo)

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

## DL-018 — P04 generated contracts and golden accept only explicit textual inputs

- Date/stage: 2026-08-01 / P04
- Surface issue: P04 needs machine-equal Kotlin tokens/routes and a screenshot regression base before feature pages exist, while review-rendering artifacts are prohibited implementation inputs.
- Decision: `scripts/generate_p04_contracts.py` names exactly the frozen token JSON and screen YAML; it does not enumerate the delivery directory. It emits all 434 scalar token values, all 215 route/state records and a 128×104 palette golden from 208 token color occurrences. Translucent colors are composited over the light-background token, and device comparison permits only one 8-bit channel level of renderer rounding.
- Consequence: `--check`, canonical hashes and the device golden fail on textual/token drift. The palette is a token regression fixture, not a page-design oracle; provenance and SHA-256 are recorded separately.

## DL-019 — Design system and navigation consume only the exact P03 core types they expose

- Date/stage: 2026-08-01 / P04
- Surface issue: P01 created empty `:core:designsystem` and `:core:navigation` projects, so their provisional edge allowlist was empty. P04 must consume the authoritative P03 `MoneyUiModel` and immutable `StableId` instead of duplicating either type in UI code.
- Precedence applied: frozen module direction and single-authority value semantics outrank the page-free P01 provisional edge set.
- Decision: allow only `:core:designsystem` → `:core:money` and `:core:navigation` → `:core:common`; both dependencies are API surfaces because consumers pass the exact formatted model/ID. No feature, application, data or Android-framework reverse edge is introduced.
- Consequence: amount rendering cannot recalculate money and routes cannot invent string IDs. `verifyArchitecture` continues to reject every unlisted edge.

## DL-020 — Missing semantic onBase is derived by the frozen contrast rule

- Date/stage: 2026-08-01 / P04
- Surface issue: positive/warning/danger/info tokens define `onBase`, but `neutralTransaction` defines only base/container/onContainer. Reusing `onContainer` on the base fails the contract's 3:1 graphical contrast threshold in both themes.
- Precedence applied: the UI main contract explicitly requires automatic accessible foreground replacement; the token JSON remains the concrete color source and is not modified.
- Decision: when and only when `onBase` is absent, `LedgerContrast.accessibleContent(base)` chooses black or white by the higher measured contrast. Explicit `onBase` values remain exact.
- Consequence: every semantic base/content and container/content pair passes the automated light/dark contrast suite without altering frozen token data.

## DL-021 — YAML String route keys are closed implementation keys, not arbitrary public strings

- Date/stage: 2026-08-01 / P04
- Surface issue: four frozen routes declare `topicKey`, `reportKey` or `forecastKey` as `String`, while the higher-priority privacy contract forbids routes from carrying names, notes, amounts or full objects.
- Precedence applied: preserve the YAML path shapes but interpret these fields as opaque implementation keys, not user text.
- Decision: `OpaqueKeyArgument` and the similarly open-ended named-enum wrapper have private constructors and internal validators. Feature code cannot construct arbitrary string arguments; future registered key constants must be exposed from `:core:navigation`. Stable IDs, exact contract enums, year-month, masks and positive widget IDs remain the only public argument constructors.
- Consequence: all 215 patterns remain machine-equal to YAML, while the public route type system cannot transport arbitrary sensitive strings.

## DL-022 — Runtime ISO currencies use a validated capability type at the command boundary

- Date/stage: 2026-08-01 / P05
- Surface issue: P05 requires compile-time prevention of wrong-currency account writes, while account/currency pairs are user-created runtime data and therefore cannot truthfully be represented by a finite compile-time generic currency parameter.
- Precedence applied: exact runtime account currency and immutable monetary evidence outrank a fictitious compile-time ISO currency universe.
- Decision: formal payloads cannot accept raw `Money` for an account. They require `AccountAmount`, whose real constructor and data-class copy are private; only `AccountAmount.create(AccountSnapshot, Money)` can produce it and the factory rejects inactive accounts, non-positive amounts and currency mismatch. Expense/income/category/project shapes are closed directly in their constructor types.
- Consequence: the compiler prevents bypassing currency validation in Kotlin command construction, while the required runtime comparison remains explicit and typed. No phantom type claims knowledge the application does not have.

## DL-023 — Lifecycle is a type parameter, not a mutable record flag

- Date/stage: 2026-08-01 / P05
- Surface issue: the domain document labels database tables C/R/F/P/K/O, while P05 has no Room schema and must still prevent lifecycle mixing in pure Kotlin.
- Decision: `LifecycleRecord<L>` uses six closed marker types: Current, Revision, Fact, Projection, Cache and Operation. Each production record fixes its lifecycle in its implemented type; callers cannot mutate a record from one lifecycle to another by changing a field.
- Consequence: P05 can prove model separation without prematurely creating P07 entities. Physical immutability, append-only DAO rules and rebuild behavior remain P07/P08 evidence.

## DL-024 — Transaction payload means a sealed subtype, never a generic property bag

- Date/stage: 2026-08-01 / P05
- Surface issue: the frozen `TransactionRevision` sketch names a `payload`, but the same document requires typed detail tables and prohibits a universal transaction object.
- Decision: `TransactionPayload` is sealed with exactly the eleven frozen transaction kinds. Ordinary expense/income payloads require a category; one payer/account/project/goal is structurally represented; subtype-only fields live only in their matching payload. A new static rule rejects `Map<String, Any?>` and JSON/property-bag types in all domain modules.
- Consequence: adding a transaction kind requires an explicit compiler-visible subtype and contract update. No core field is hidden in generic JSON.

## DL-025 — Ports follow the available frozen module boundaries

- Date/stage: 2026-08-01 / P05
- Surface issue: architecture places finance repository interfaces at the application boundary, but the frozen module graph provides no separate `:analytics:application` or `:transfer:application` module; their data modules depend directly on their domain modules.
- Precedence applied: preserve the frozen module graph and dependency direction rather than inventing new modules.
- Decision: all finance repositories and external finance capabilities live in `:finance:application`. Closed analytics query/algorithm ports live in `:analytics:domain`, and staging/backup/restore ports live in `:transfer:domain`, exactly opposite their frozen data-module dependency arrows. Cross-aggregate financial submission is still only through `FinancialMutationCoordinator`.
- Consequence: no Android/Room/Hilt/OkHttp dependency enters a domain module, and no feature receives a DAO/Entity or infrastructure client.

## DL-026 — Deterministic planning receives time, identities and evidence as explicit inputs

- Date/stage: 2026-08-02 / P06
- Surface issue: the frozen planning flow requires generated IDs, a real commit time, current reference data and frozen exchange evidence, while the same input must always produce the same plan and the domain cannot read platform services.
- Decision: `AccountingPlanningContext` is caller-owned and includes the complete ID sequence, commit time, device ID, typed reference snapshots, amount/FX evidence and optional current immutable facts. The planner contains no clock, random, database, network or Android access.
- Consequence: retries can reproduce a byte-identical plan and hash. P07/P08 adapters must gather the snapshot before planning and may not let DAOs invent accounting facts.

## DL-027 — Reversal preserves the original fact rule version and permits archived references

- Date/stage: 2026-08-02 / P06
- Surface issue: §12 requires exact reversal at the original effective time, while §33.3 prohibits reinterpreting old facts after a rule change. An account may also be archived after the original posting.
- Decision: every REVERSE Posting copies the original currencies, amounts, valuation rate and role with only side/identity changed; its Journal retains the original APPLY rule version and effective time. Reference status is not revalidated for exact reversal, but ID and currency still must match.
- Consequence: editing under rule version 2 can reverse a version-1 fact without relabeling it or requiring an archived account to become active. New APPLY facts use the current book rule version.

## DL-028 — FX evidence attaches to the representation it produced

- Date/stage: 2026-08-02 / P06
- Surface issue: `RevisionAmount.fxRateSnapshotId` is nullable for each USER_INPUT/ACCOUNT/BASE row, but the frozen sketch does not state which row owns a two-step conversion reference.
- Decision: USER_INPUT has no producing rate; ACCOUNT references only the USER_INPUT→ACCOUNT conversion; BASE references only the ACCOUNT→BASE conversion. Same-currency steps have no snapshot. Cross-currency account transfers use the frozen base-currency clearing bridge, with an exact residual classified as explicit cost, rounding or gain.
- Consequence: each representation can be reconstructed from its predecessor without attributing a later conversion to the wrong amount. Frozen evidence and all effect families enter the canonical commit root; no current rate can rewrite history.

## DL-029 — Formal financial facts are broader than Journal rows

- Date/stage: 2026-08-02 / P06
- Surface issue: an external-participant settlement payment is formal and changes settlement positions but, by INV-023, must not write a local account Journal. A Journal-required lifecycle check would incorrectly reject its create/edit/trash/restore paths.
- Decision: transaction lifecycle plans require at least one authoritative financial fact across Journal or the typed Effect families. Journal role checks remain exact when Journals exist; `ImmutableFactAudit` separately proves one-to-one effect reversals for journal-less operations.
- Consequence: external-only settlements use no placeholder Posting and still receive full immutable revision/reversal/hash treatment. Candidates continue to produce no formal fact of any family.

## DL-030 — Allocation lists are authoritative and must close exactly

- Date/stage: 2026-08-02 / P06
- Surface issue: refund and credit-payment payloads expose allocation lists, and accepting totals unrelated to the authoritative transaction amount would create internally inconsistent subledger effects.
- Decision: linked refund base allocations must sum exactly to the frozen refund base amount and match frozen refundable currency/balance evidence; independent refunds carry no allocations. Credit payments require one or more statement allocations (a null statement means unallocated prepayment), with matching currency and an exact sum to the credit-account amount. Candidate auto-payments are rejected before fact materialization.
- Consequence: StatementEffect and refund facts are complete and checked without inference in UI or persistence. Later repositories persist these already-validated allocations atomically.

## DL-031 — Room owns lifecycle and migration while canonical DDL owns unsupported SQLite features

- Date/stage: 2026-08-02 / P07
- Surface issue: Room 2.8.4 annotations cannot express FTS5, R*Tree virtual tables, arbitrary table `CHECK` clauses or the cross-row immutability constraints required by the frozen logical model. Treating Room's annotation schema as the entire database would silently lose mandatory SQLite behavior.
- Precedence applied: the frozen Room 2.8.4 + SQLCipher 4.17.0 stack, full logical constraints and FTS5/R*Tree requirements all remain mandatory.
- Decision: both encrypted databases are real `RoomDatabase` instances with exported Room v1 registry schemas and explicit migration registries. A deterministic callback installs versioned canonical SQL assets for the normalized schema, views, indexes and triggers. The complete raw-DDL catalogs are independently exported as checked-in JSON and are machine-compared with the 94-table frozen inventory.
- Consequence: Room still controls opening, version identity, migration registration and transaction integration; unsupported DDL is not weakened or hidden. P08 repositories may use the Room-owned connection but may not create a second SQLite path.

## DL-032 — Security pragmas run in the SQLCipher connection hook

- Date/stage: 2026-08-02 / P07
- Surface issue: `auto_vacuum=INCREMENTAL` must be set before the first table is created, while `RoomDatabase.Callback.onCreate` runs after Room creates its registry table. Foreign-key and temporary-storage settings also apply per connection.
- Decision: the official SQLCipher `SQLiteDatabaseHook.postKey` applies incremental auto-vacuum, foreign keys, memory-only temporary storage, secure delete, recursive triggers, cipher memory security and disabled cipher logging immediately after the key succeeds and before Room schema creation. `RoomDatabase.Callback.onOpen` fails closed if the persistent/connection values differ.
- Consequence: there is no transient unencrypted framework database and no file-backed SQLite temp downgrade. API 36 device tests prove the effective PRAGMAs, encrypted WAL behavior and close/reopen path.

## DL-033 — `rule_set_version` is an explicit v1 table in addition to the frozen 94-table §25 inventory

- Date/stage: 2026-08-02 / P07
- Surface issue: domain §25's heading-derived inventory contains 94 named tables but architecture §9 explicitly lists `rule_set_version`, and domain §33 requires Journal and Effect facts to preserve the rule version.
- Precedence applied: historical-fact interpretation and the architecture database family outrank a count-only interpretation of §25 headings.
- Decision: retain the exact 94 tables unchanged and add normalized `rule_set_version` as the 95th core schema table. `journal_entry` and every typed Effect table reference it; no rule metadata is stored in a universal JSON column.
- Consequence: old facts remain interpretable without rewriting history. Validators separately assert the exact frozen 94-table subset and the required additional rule table, so neither can mask the other.

## DL-034 — Schema v1 has explicit empty predecessor registries, not a synthetic migration

- Date/stage: 2026-08-02 / P07
- Surface issue: P07 must test every registered upgrade path, but v1 is the first schema and has no legitimate earlier production version.
- Decision: primary and staging databases each expose an independently versioned, explicitly empty migration registry for v1. Future migrations must be adjacent, registered with Room and carry ordered Expand → Backfill → Switch → Contract steps. Builders always call `addMigrations` and never call a destructive fallback.
- Consequence: v1 creation/reopen is device-tested and Room exports both v1 identities. No fake v0 schema or destructive rebuild is introduced merely to create a migration test case.

## DL-035 — Immutable facts allow physical deletion only behind the maintenance guard

- Date/stage: 2026-08-02 / P07
- Surface issue: ordinary operations must never update/delete Revision or Fact rows, while the higher-priority controlled privacy-purge rule permits deletion of a fully reversed closed chain during maintenance.
- Decision: every immutable table receives a trigger that always rejects updates and rejects deletes unless the book is in `MAINTENANCE` and the internal runtime purge guard is explicitly armed. P07 exposes no public method to arm that guard. One-to-one reversal indexes and historical foreign keys remain enforced independently.
- Consequence: ordinary DAO mistakes fail at SQLite rather than relying only on convention. P31 must implement the maintenance-lock eligibility audit before it can receive a narrowly scoped internal purge capability; P07 does not claim that later workflow.

## DL-036 — P08 corrects four Schema v1 nullability and XOR constraints before repository use

- Date/stage: 2026-08-02 / P08
- Surface issue: executing real P06 plans against the P07 schema exposed four mismatches that static inventory checks could not exercise: manual FX evidence may have no quote time; a GoalEffect may originate from a transaction revision or a goal movement; a SettlementEffect may originate from a transaction revision or a settlement payment; and the frozen `RevisionAmount` representation enum has five values while the physical check admitted only four. The related-account requirement also had to follow the representation owning the account amount.
- Precedence applied: frozen domain fields and accounting evidence semantics outrank the earlier physical interpretation.
- Decision: make `fx_rate_snapshot.quoted_at` nullable; enforce exact-one source XORs for goal and settlement effects; admit representation ordinals 0—4; require `related_account_id` exactly for the account representation. Regenerate the deterministic schema catalog after changing canonical DDL.
- Consequence: real deterministic plans now persist without discarding evidence or inventing foreign keys. P07 device capability/integrity tests were replayed and remain passing; no frozen specification was changed.

## DL-037 — Receipt-last and persisted rechecks define the atomic idempotency boundary

- Date/stage: 2026-08-02 / P08
- Surface issue: a coordinator-side receipt/revision check is necessary for fast rejection but cannot by itself close a race between planning and persistence.
- Decision: `RoomFinancialCommitRepository` rechecks an existing receipt, book state/head/local revision/rule version and the target transaction's current revision inside Room's sole SQLCipher transaction. It compare-and-sets the book head/revision after facts and projections, then inserts `CommandReceipt` last. A duplicate returns the stored receipt only when type and canonical payload hash match.
- Consequence: repeated commands are idempotent, stale revisions cannot silently overwrite, and a receipt cannot claim a partially committed mutation. The process-local mutex orders ordinary submissions; SQLite remains the atomicity authority.

## DL-038 — Only P08-owned synchronous projections advance during a financial commit

- Date/stage: 2026-08-02 / P08
- Surface issue: the physical v1 schema includes valuation, future-reservation/future-cashflow and analytics projections whose complete algorithms belong to later stages. Stamping those rows with the new revision without recomputing them would create false freshness.
- Decision: atomically rebuild the current transaction, balance/daily, refund, budget usage, project, goal, current credit/installment/loan, settlement, FTS/R*Tree and widget snapshot set. Leave later-owned projections and their `as_of_*` values untouched. The explicit projection date comes from commit/book-zone evidence; no ambient clock or current FX feed participates.
- Consequence: every P08 synchronous row matches `book.localRevision`, while deferred projections truthfully remain stale until their owning stage. `asOfValuationRevision` is preserved independently and historical facts cannot be revalued accidentally.

## DL-039 — Projection audit rebuilds in a savepoint and canonical hashes are type-delimited

- Date/stage: 2026-08-02 / P08
- Surface issue: comparing only stored version columns cannot detect a value corruption, and a naive concatenated-row digest permits ambiguous byte boundaries.
- Decision: serialize every P08 derived table in stable key order with table names, SQLite value types and length prefixes. During audit, compute the live hash, rebuild inside a savepoint, compute the rebuilt hash and roll the savepoint back. The explicit maintenance rebuild replaces derived state atomically and runs integrity/version/count checks before returning the book to ready state.
- Consequence: projections are reproducibly auditable from authoritative normalized facts without mutating the live database during an audit. Device tests corrupt one projection value, detect the mismatch, rebuild it and recover the original hash.

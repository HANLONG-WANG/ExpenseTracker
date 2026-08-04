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

## DL-040 — P09 pins stable security-library releases without changing the frozen stack

- Date/stage: 2026-08-02 / P09
- Surface issue: the frozen stack names Tink, Bouncy Castle and AndroidX Biometric but only freezes Tink's version; the other two release lines remain time-sensitive.
- Decision: retain frozen Tink `1.23.0`, select stable Bouncy Castle `1.84` and stable AndroidX Biometric `1.1.0`. Do not adopt the Biometric alpha line merely for newer API shape. All three enter strict dependency locking and SHA-256 verification.
- Consequence: P09 uses only the prescribed primitives with reproducible artifacts. A future version change requires an explicit dependency and device-security replay, not a silent catalog update.

## DL-041 — Auth-bound Keystore AAD starts only after the CryptoObject is authenticated

- Date/stage: 2026-08-02 / P09
- Surface issue: AES-GCM associated data must bind the Vault DEK envelope, but on the real API 36 Keystore an `updateAAD` call before `BiometricPrompt` starts the auth-required operation too early. The prompt can report success while `doFinal` then fails with `KEY_USER_NOT_AUTHENTICATED`.
- Decision: initialize the exact auth-per-use `Cipher`, pass it untouched in `BiometricPrompt.CryptoObject`, verify object identity after success, then submit canonical associated data and payload to that same authenticated operation. Request cancellation/consumption zeroizes its AAD and secret material.
- Consequence: neither AAD integrity nor per-action authentication is weakened. A device test first reproduced the failure and then proved two separate PIN-authenticated vault operations after the corrected ordering.

## DL-042 — App lock revokes UI access while headless work holds only a closed capability

- Date/stage: 2026-08-02 / P09
- Surface issue: architecture says app lock is UI access control and must not stop legitimate recurrence/backup work, while a background lease must not become a route to a database, DAO or key.
- Decision: UI and headless references share the manager-owned encrypted database resource but have independent counts. Locking clears vault plaintext and the UI reference; an already-authorized headless lease keeps only its opaque operation ID and closed capability. Maintenance admits only its explicit read/maintenance subset, and features are statically barred from `:core:security` capabilities.
- Consequence: background work can continue while the visible app is locked without converting app lock into cryptographic key policy or exposing general database authority. Later Workers must receive such leases from composition roots, never construct or persist them.

## DL-043 — Vault biometric changes preserve the frozen device-credential fallback

- Date/stage: 2026-08-02 / P09
- Surface issue: a real API 36 `KeyInfo` check reported `invalidatedByBiometricEnrollment=false` for the vault key despite requesting enrollment invalidation. Android does not apply biometric-enrollment invalidation when a key also authorizes `AUTH_DEVICE_CREDENTIAL`; asserting otherwise would record false platform evidence.
- Precedence applied: REQ-077 and architecture §16.1 explicitly permit every vault action through strong biometric **or** device credential. That frozen recovery path outranks an implementation preference to invalidate a biometric-only key on enrollment changes.
- Decision: keep a zero-second authentication window with both authenticator types, explicitly disable enrollment invalidation, and classify the framework's no-biometric result as the closed, non-sensitive `DEVICE_SECURITY_CHANGED` disposition. Actual-device tests inspect the combined key policy, perform two separately authenticated actions, and prove that removing the device credential blocks the next action. They do not claim a physical biometric enrollment transition that the managed device did not perform.
- Consequence: biometric configuration changes cannot silently authorize a vault action or leak framework details, while the required device-credential fallback remains usable. A future biometric-only policy would be a frozen-requirement change and would need separate enrollment/invalidation device evidence.

## DL-044 — Attachment ports carry reopenable streams, never byte arrays or physical paths

- Date/stage: 2026-08-02 / P10
- Surface issue: the P05 attachment port contained only logical metadata, while P10 must stream arbitrarily large SAF content, retry thumbnail decoding and avoid exposing a filesystem path or whole-file byte array across the application boundary.
- Decision: extend the existing application port with `AttachmentContentSource.openStream()` and optional declared size. The object-store adapter owns `ContentResolver` and app-private paths; callers receive only typed attachment/blob IDs, size and hash receipt evidence.
- Consequence: large content remains bounded-memory and testable, and no feature, route or SavedState gains a URI/path/plaintext capability. This completes the previously declared P05 external port without changing domain accounting semantics.

## DL-045 — Encrypted object publication precedes metadata commit and interrupted orphans are recoverable

- Date/stage: 2026-08-02 / P10
- Surface issue: filesystem rename and SQLCipher commit cannot share one transaction. Committing metadata first can create a missing-file reference, while moving the encrypted object first can leave an orphan after a database failure.
- Decision: stream to a private staging file, fsync, atomically move the encrypted object, then commit blob/attachment metadata. Any in-process failure deletes unpublished staging/final objects; startup recovery deletes staging and random object families absent from the encrypted catalog. GC independently rechecks current, historical and backup references.
- Consequence: the authoritative database never commits a reference to a missing object. A process death can leave only an unreferenced encrypted orphan, which is safe and deterministically reclaimable; no plaintext staging exists.

## DL-046 — External attachment access is a confirmation-bound one-time pipe capability

- Date/stage: 2026-08-02 / P10
- Surface issue: ordinary `FileProvider` sharing would require a decrypted file, and a reusable content URI would outlive the explicit risk confirmation required by ATT-002.
- Decision: a non-exported `SecureAttachmentProvider` issues a 192-bit opaque URI only from a consumed confirmation object, expires it after 60 seconds, consumes it on the first read, revokes the URI grant and decrypts directly into a reliable pipe. App lock invalidates every pending grant and clears Coil memory.
- Consequence: receiving apps obtain only the confirmed stream; the ledger retains no long-lived plaintext copy or reusable share link. Physical names, hashes and paths remain absent from the intent and UI.

## DL-047 — Location capture owns one monotonic save budget and never supplements later

- Date/stage: 2026-08-02 / P10
- Surface issue: REC-009 may prefetch before Save, but REQ-054 caps the save wait at three seconds and explicitly prohibits a later background location write.
- Decision: `ForegroundLocationSaveSession` starts one monotonic three-second budget, reuses a prefetched deferred result, waits only the remaining time and cancels on timeout. Fused Location Provider falls back to `LocationManager`; only the foreground coarse/fine permissions exist. Coordinates are frozen as checked E7 values before entering the application port.
- Consequence: denial, timeout, missing Play services and provider failure all continue saving without location. There is no Worker, alarm, background permission or supplement callback capable of mutating the saved transaction later.

## DL-048 — MapLibre remains a core implementation detail with deterministic local-style device tests

- Date/stage: 2026-08-02 / P10
- Surface issue: production maps need online HTTPS styles, while required lifecycle/overlay/accessibility tests must not depend on external network availability or leak MapLibre types into feature modules.
- Decision: `LedgerMapStyleConfiguration` accepts closed HTTPS/asset/inline-JSON style sources. Production defaults use OpenFreeMap with explicit attribution; API 36 tests use a minimal inline style while exercising the actual MapLibre renderer, lifecycle and all overlay modes. MapLibre is an `implementation` dependency of `:core:geo`; feature imports are statically rejected.
- Consequence: tests are deterministic without substituting a fake SDK, attribution remains visible, and later analytics features consume only `LedgerMap` plus accessible rows. The map failure path is always the contract data-table fallback.

## DL-049 — Lifecycle Compose stays on the compileSdk-36-compatible stable line

- Date/stage: 2026-08-02 / P10
- Surface issue: the current stable Lifecycle 2.11 line requires compileSdk 37, while the frozen project toolchain is compile/target SDK 36 and P10 needs `LocalLifecycleOwner` for MapLibre ownership.
- Precedence applied: the frozen SDK 36 toolchain outranks adopting a newer library that raises the compile SDK.
- Decision: pin stable Lifecycle 2.9.4 for `lifecycle-runtime-compose`; retain MapLibre 13.4.1, Coil 3.5.0 and Play Services Location 21.3.0. Do not raise compileSdk or use an alpha workaround.
- Consequence: the required lifecycle API is available without changing the frozen Android baseline. Any future Lifecycle upgrade must first be compatible with the owning toolchain stage and replay the MapLibre device lifecycle suite.

## DL-050 — First-run genesis is non-monetary metadata, not a second financial mutation path

- Date/stage: 2026-08-02 / P11
- Surface issue: onboarding must create a book and may create its first account/category, while every monetary write must pass through `FinancialMutationCoordinator`.
- Decision: expose a typed application `LedgerInitializationPort` whose SQLCipher adapter may append only book/system-ledger/account/category revisions and their empty projections. It cannot accept a transaction, Journal, Posting, Effect, amount or balance. Every monetary command and all later edits/deletes/restores remain behind `FinancialMutationCoordinator` and its session-aware write gate.
- Consequence: first launch creates no example or financial fact and does not give feature code DAO/Entity access. The distinction is enforced by port types, static scans and a real SQLCipher device test that asserts zero transactions, journals and postings.

## DL-051 — Proto DataStore uses the full protobuf runtime already required by Tink

- Date/stage: 2026-08-02 / P11
- Surface issue: Proto DataStore examples often select `protobuf-javalite`, but Tink already requires the full protobuf runtime; packaging both variants creates duplicate classes and incompatible generated APIs.
- Decision: use protobuf plugin 0.10.0 with `protobuf-java` 4.35.0 for the typed settings schema. The schema contains only non-sensitive preferences, a wrapped verifier, a book stable ID and contract-encoded navigation metadata.
- Consequence: the APK has one protobuf runtime and strict lock/verification metadata remains reproducible. Recovery password plaintext, form names, money, cards, attachments and coordinates have no Proto field.

## DL-052 — Hilt remains KSP-generated while its incompatible optional Java aggregation task is disabled

- Date/stage: 2026-08-02 / P11
- Surface issue: Hilt 2.59.2's optional Java aggregation task cannot read Kotlin 2.4 metadata, although the prescribed KSP processor generates and validates the component tree correctly.
- Precedence applied: the frozen Kotlin/KSP toolchain and required Hilt composition boundary outrank an optional incremental aggregation optimization.
- Decision: retain Hilt 2.59.2 and KSP, set `enableAggregatingTask=false`, and compile/test the generated application, Activity and ViewModel component graph on device.
- Consequence: dependency injection behavior is unchanged; only that optional optimization is disabled. A future Hilt/Kotlin update may re-enable it after compile and device-runtime evidence.

## DL-053 — App language is localized inside the single Compose root and updates immediately

- Date/stage: 2026-08-02 / P11
- Surface issue: ONB-001 requires subsequent pages to change language immediately, while P11 must not introduce an AppCompat Activity or a second UI shell.
- Decision: the root derives a configuration context from the selected/persisted BCP-47 tag and provides it through Compose's context/configuration locals around the one root tree. Date/number formatting can consume that same locale boundary in later features.
- Consequence: Simplified Chinese, Japanese and English resources switch without recreating a second Activity or storing a sensitive state. API 28/36 and three-locale UI tests cover the boundary.

## DL-054 — P11 goldens are contract-rendered artifacts, never measurements from review drafts

- Date/stage: 2026-08-02 / P11
- Decision: generate the four root/onboarding goldens directly from the governed Compose implementation at fixed 360×720 density on the API 36 managed device, freeze their SHA-256 values, and compare every pixel in ordinary test mode. Inputs are only the UI contract, token JSON, screen YAML and implementation resources.
- Consequence: the golden suite detects implementation drift without opening, parsing, measuring or deriving a baseline from any excluded visual review draft. API 28 uses semantic/layout matrix tests rather than pretending platform rasterization is pixel-identical.

## DL-055 — Preserve the frozen JDK 17 toolchain when the bundled Lint FIR frontend selects a Java 21 helper

- Date/stage: 2026-08-02 / P11
- Surface issue: AGP/Lint 9.3.1's bundled `intellij-core` FIR path invokes `java.util.List.removeLast()`, a Java 21 method, for the large root `when` dispatch even though the frozen Android toolchain runs on JDK 17. The failure occurs inside Lint analysis; the Kotlin compiler and application runtime are valid.
- Precedence applied: the frozen JDK/AGP/Kotlin baseline and mandatory Lint gate outrank changing the host JDK or suppressing a detector to accommodate an analyzer implementation detail.
- Decision: retain JDK 17 and all Lint detectors, and express the root language/session/destination dispatch as equivalent exhaustive `if` chains. No UI state, route, priority or behavior changes.
- Consequence: `lintDebug`, `lintVitalRelease`, the fresh cumulative gate and the API 36 seven-test application suite all pass on the frozen toolchain. During P12 the same analyzer path recurred after adding destination titles and a Protobuf-builder test chain; splitting title lookup into bounded `if` helpers and making the test builders explicit restored main, unit-test and AndroidTest Lint without disabling a detector or changing JDK. P13 likewise keeps the Ready scaffold, SAF launcher/fixed action and ordinary-record destination wiring in bounded files. In P14, direct `List.size`/`List.isEmpty` resolution in the place-map fallback reached the same bundled JavaDoc parser; equivalent Kotlin `count()`/`none()` calls retained behavior and restored complete app Lint. P16 keeps its refund route dispatch/title in `RefundRootDestination.kt` rather than enlarging `AppRootScreen.kt`, restoring app Lint without a detector or toolchain change. No stage changes JDK, suppresses a detector or weakens the gate. A future analyzer upgrade may restore more compact expressions only after the same static and device evidence is replayed.

## DL-056 — The P12 place map reuses the governed P10 network boundary

- Date/stage: 2026-08-02 / P12
- Surface issue: PLC-001—003 require the actual P10 `LedgerMap`, whose OpenFreeMap style is network-fetched, while P12 also forbids online place search and reverse geocoding.
- Precedence applied: the frozen P10 MapLibre/attribution contract permits a remote map style, but REQ-053 and the P12 place contract prohibit using a network geocoder or online place database.
- Decision: allow the app's existing `INTERNET` permission only through `:core:geo` and render P12 places with the P10 MapLibre wrapper plus its accessible list/failure fallback. No geocoding client, address lookup, background location or network-derived place suggestion is added.
- Consequence: map tiles/styles may load when available, while place name, merchant relation and fixed-point coordinates remain explicit offline user data. A missing style/network never blocks saving.

## DL-057 — Category reassignment and place split fail closed until the coordinator owns atomic batch edits

- Status: superseded by `DL-058` after the required atomic batch path was implemented and device-verified; retained as the auditable pre-completion safety decision.

- Date/stage: 2026-08-02 / P12
- Surface issue: REQ-022 permits historical category reassignment and PLC-003 permits splitting location records. Both change the current meaning of one or more formal transactions. The current production P06 planner/P08 snapshot adapter accepts one transaction lifecycle snapshot and does not materialize `BatchFinancialCommand`; direct SQL would mutate immutable revisions or bypass fact reversal/application.
- Precedence applied: immutable revision/fact rules and the single `FinancialMutationCoordinator` write boundary outrank screen-level completion and reference-data convenience.
- Decision: implement and test all validation/selection/UI surfaces, but reject before any commit with `category.reassignmentRequiresFinancialCoordinator` or `place.splitRequiresFinancialCoordinator` whenever a financial rewrite is required. Do not update old `transaction_revision` or `location_record` rows, perform non-atomic sequential writes, or report success.
- Consequence: P12 remains `IN_PROGRESS` rather than falsely `VERIFIED`. The SQLCipher device suite proves valid place-split input leaves `book.localRevision` and place rows unchanged. Completion requires the coordinator-owned atomic batch snapshot/planner/receipt path described in `P12_REFERENCE_DATA_MAPPING.md`.

## DL-058 — Reference-driven financial rewrites use one coordinator-owned immutable batch

- Date/stage: 2026-08-02 / P12
- Surface issue: category reassignment and referenced-place splitting can affect several current formal transactions, but the frozen architecture permits only one financial application entry and forbids mutable history or sequential partial commits.
- Precedence applied: the immutable revision/fact and single-`FinancialMutationCoordinator` invariants outrank a simpler reference-only SQL update. Existing P06 `BatchFinancialCommand` semantics are extended rather than creating a P12-specific financial writer.
- Decision: `RoomReferenceFinancialSnapshotMapper` reconstructs each typed current revision, immutable fact family, historical ledger reference and frozen amount/FX evidence. The adapter derives deterministic child identities, and `DeterministicFinancialPlanner` emits one canonical `BATCH_MUTATION` with REVERSE/APPLY facts for every EDIT child. `RoomFinancialCommitRepository` checks every child `expectedRevision`, writes one `CommandReceipt`, rebuilds synchronous projections and advances the book once. A narrowly typed `FinancialCommitSideEffect` may write only the associated category/place/location metadata after the commit header and before revision foreign keys, inside that same Room transaction; it cannot write financial facts.
- Consequence: category reassignment and place split are atomic with their reference metadata, deterministic and idempotent. Old revisions, location records, Journals, Postings, Effects and frozen historical currency amounts remain unchanged; any validation, persistence, audit or projection failure rolls back the entire batch. P12 can be `VERIFIED` without a direct DAO/SQL financial bypass.

## DL-059 — P13 entry origins reuse the frozen opaque StableId route slot

- Date/stage: 2026-08-03 / P13
- Surface issue: REC-003 exposes one optional `transactionId:StableId?`, while template, candidate, duplicate and batch-row entry need an opaque source identity but routes may not carry names, amounts, notes or full objects.
- Decision: use that optional stable-ID slot as the source identity for the closed `RecordEditorMode`; resolve the full encrypted snapshot through the application port after SessionGate. Amounts, entity names, notes, card data, attachments and location never enter the route or SavedState.
- Consequence: all entry origins share one editor and one safe route contract. Return behavior is selected by the closed origin policy without introducing sensitive route parameters.

## DL-060 — Foreign ordinary entry fails closed without frozen valuation evidence

- Date/stage: 2026-08-03 / P13
- Surface issue: account currency amount is authoritative, while base-currency evidence is also required and an offline account may lack a usable current valuation quote.
- Precedence applied: exact historical amount evidence and offline correctness outrank an invented or silently current online rate.
- Decision: same-currency entry uses identical user/account/base minor units. A foreign account may use only the encrypted current valuation evidence already associated with its projection; otherwise Save retains the form and returns `FX_EVIDENCE_UNAVAILABLE` for explicit correction. The adapter freezes derived rate snapshots inside the commit.
- Consequence: no network dependency, float, guessed rate or later revaluation rewrites a historical fact.

## DL-061 — P13 goldens originate only from governed Compose and token inputs

- Date/stage: 2026-08-03 / P13
- Decision: record four 360×720 API-36 baselines directly from the implemented `LedgerTheme`/governed Compose tree and compare every pixel. The only design inputs are the textual main contract, token JSON, screen YAML and localized resources.
- Consequence: screenshot regression evidence exists without opening, parsing, sampling, measuring or comparing any excluded PNG/HTML visual draft.

## DL-062 — Settlement-position ledger ownership uses the frozen schema's system code

- Date/stage: 2026-08-03 / P13
- Surface issue: the frozen Schema v1 ledger table has no nullable activity/participant ownership columns, while P06 settlement postings require a deterministic typed ledger per activity participant.
- Decision: resolve existing settlement-position ledgers by the canonical `SETTLEMENT:<activity StableId>:<participant StableId>` system-code convention already admitted by the typed mapper. P13 does not create or mutate settlement activities or ledgers.
- Consequence: planning remains type-safe and deterministic without adding a schema column, universal JSON payload or feature-side lookup. P22 owns creation and lifecycle validation of these ledgers.

## DL-063 — Historical quotes never replace current valuation

- Date/stage: 2026-08-03 / P14
- Surface issue: REC-013/021 can request a rate for an effective historical date, while `account_valuation_current` and `book.valuationRevision` describe the present valuation only.
- Precedence applied: immutable historical amount evidence and the domain's separate local/valuation revision clocks outrank reusing every network response as a current cache value.
- Decision: the OkHttp adapter sends only ISO source/target/date and parses an exact decimal response. A request whose effective date equals the fetch instant's UTC date may update encrypted current valuation and advance only `valuationRevision`. Any other dated response is labeled `HISTORICAL_FALLBACK`, may be frozen into a transaction revision, and cannot update current valuation. Offline lookup uses the encrypted current cache with its quote time; absence requires a positive manual rate and never produces zero.
- Consequence: cache refresh does not invalidate `localRevision`, create income/expense, or alter historical `fx_rate_snapshot` rows. Device evidence covers current, historical, cached and manual paths.

## DL-064 — Balance adjustment links to the immutable checkpoint from its revision detail

- Date/stage: 2026-08-03 / P14
- Surface issue: the logical checkpoint row includes an optional adjustment reference, but P07 classifies `account_balance_checkpoint` as an immutable Fact and installs a trigger rejecting updates. An initial attempt to backfill that column correctly failed on device with `SQLiteConstraintException: immutable table update rejected`.
- Precedence applied: the frozen Fact immutability invariant outranks a convenient reverse-link update.
- Decision: retain the checkpoint trigger unchanged. The authoritative association is the append-only `balance_adjustment_revision_detail.checkpoint_id`; `SecureRoomReferenceDataManagementPort` derives `CheckpointReferenceView.adjustmentTransactionId` by joining the current transaction revision. No checkpoint row is updated.
- Consequence: checkpoint creation remains balance-neutral and immutable, while REC-020 can still show and enforce one explicit adjustment association. The final SQLCipher test passes integrity/foreign-key checks and verifies the derived link.

## DL-065 — P14 rate service is a non-authoritative privacy-limited adapter

- Date/stage: 2026-08-03 / P14
- Decision: use the frozen OkHttp stack with the Frankfurter v1 HTTPS endpoint, bounded 5/10/15-second connect/read/call timeouts, no transparent OkHttp retry and at most one explicit retry. The request type can carry only a currency pair and date; it has no amount, book/account/card ID, name, note, attachment or location field and no logging interceptor.
- Consequence: network availability can improve a reference quote but cannot create a financial fact or become the ledger's source of truth. A static mutation suite rejects request widening, unbounded semantics and coordinator/cache revision regressions.

## DL-066 — P14 goldens originate only from governed Compose and token inputs

- Date/stage: 2026-08-03 / P14
- Decision: record four 360×720 API-36 baselines for transfer, adjustment, FX exchange and opening balance directly from `LedgerTheme` and the implemented governed Compose tree, then compare every pixel in ordinary test mode.
- Consequence: screenshot regression covers the specialized forms without opening, parsing, sampling, measuring or comparing any excluded PNG/HTML visual draft. The baseline inputs are only the textual main contract, token JSON, screen YAML and localized resources.

## DL-067 — Paging 3 resolves to stable 3.5.0 without changing the frozen family

- Date/stage: 2026-08-03 / P15
- Surface issue: the frozen stack requires Paging 3 but intentionally does not freeze a patch. P15 is the first feature that needs the runtime artifact.
- Decision: resolve the frozen Paging 3 family to stable AndroidX Paging 3.5.0, lock every artifact and checksum it in strict verification metadata. The data boundary still owns explicit keyset SQL; Paging only transports bounded pages and load states.
- Consequence: no preview/alpha dependency, deep OFFSET, unbounded in-memory list or Room/DAO exposure enters the feature.

## DL-068 — Search and geographic filters use bounded candidates plus exact predicates

- Date/stage: 2026-08-03 / P15
- Surface issue: FTS5 and R*Tree are candidate indexes, while the contract requires exact combined filters and nearby distance. Applying every predicate to only a prematurely truncated candidate subset could silently omit valid rows.
- Decision: all structured dimensions are parameterized in one query with OR inside each dimension and AND between dimensions. FTS text is reduced to safe bound prefix terms. Geographic lookup admits at most 2,000 bounding-box candidates, calculates Haversine distance in Kotlin, and then uses the exact accepted IDs with all other predicates. Pages are ordered by `(occurred_at, transaction_id)` and carry that exact cursor.
- Consequence: user input never becomes SQL syntax, location math is exact after the index prefilter, and a real 500,000-row SQLCipher test proves bounded non-overlapping pages without OFFSET.

## DL-069 — P15 confirms purge eligibility but P31 owns physical deletion

- Date/stage: 2026-08-03 / P15
- Surface issue: JRN-012 must explain and reconfirm permanent-delete eligibility, while the phase contract explicitly assigns the final physical purge transaction to P31 and ADR-007A restricts immutable-fact deletion to maintenance mode.
- Precedence applied: the immutable fact/security boundary and explicit P31 ownership outrank treating a confirmation dialog as authority to delete.
- Decision: P15 rechecks retention, current lifecycle, account/base and every typed effect net, dependencies, durable-operation references and backup-object references. Even an otherwise eligible row retains `PHYSICAL_PURGE_REQUIRES_MAINTENANCE`; no P15 adapter executes DELETE on transaction, revision, Journal or Posting tables.
- Consequence: the UI never reports fake success and gives a precise reason. P31 must consume the same assessment inside its maintenance transaction, write the purge tombstone and prove restore/merge behavior before physical removal can be claimed.

## DL-070 — P15 goldens originate only from governed Compose and textual contracts

- Date/stage: 2026-08-03 / P15
- Decision: record the 360×720dp list-light and detail-dark baselines directly from `LedgerTheme`, the implemented governed journal components and localized strings, then compare all 945×1890 physical pixels at the managed device density.
- Consequence: journal screenshot regression exists without opening, parsing, sampling, measuring or comparing an excluded PNG/HTML visual draft. The only design inputs are the UI main contract, token JSON, screen YAML and traceability matrix.

## DL-071 — Explicit excess stays immutable while the frozen status projection remains non-negative

- Date/stage: 2026-08-04 / P16
- Surface issue: the higher-priority refund requirement permits an explicitly confirmed excess refund, while the frozen Schema v1 `refund_status_projection` has `gross_refundable = refunded + remaining` and non-negative `remaining`. Storing the true excess in that projection would violate its CHECK constraint.
- Precedence applied: immutable accounting/audit facts and the explicit advanced override outrank the derived projection representation; the frozen schema itself cannot be changed in P16.
- Decision: append the full actual allocation amount and `allow_excess` revision evidence to immutable rows. Rebuild derives the true net for validation/audit, but caps the status projection at gross and zero remaining. Application query models additionally expose `excessRefundedMinor` from the immutable total.
- Consequence: no excess evidence is lost or rewritten, the projection remains valid/non-negative, default future refund validation still sees the true allocation total, and no frozen schema change is required.

## DL-072 — Original transactions with refunds require a complete atomic policy

- Date/stage: 2026-08-04 / P16
- Decision: moving an original transaction to trash requires exactly one closed resolution for each active linked refund: reverse the dependent transaction or append a replacement revision that makes it independent. The dependent commands and original reversal execute in one `BatchFinancialCommand`. An original edit with unresolved refund dependencies remains blocked.
- Consequence: cascade, independentization and prevent behavior are all explicit; old revisions/facts are never mutated, stale revisions cannot silently overwrite, and no partially converted dependency graph can commit.

## DL-073 — P16 goldens are full-pixel digests of governed Compose output

- Date/stage: 2026-08-04 / P16
- Decision: render linked-light and high-risk-excess-dark refund forms in a deterministic 360×720 Compose viewport and compare SHA-256 over width, height and every ARGB pixel. The two 64-hex digests are checked into the Android test source.
- Consequence: pixel drift is machine-detectable without adding an external bitmap input. The renderer uses only `LedgerTheme`, governed components, textual contracts/tokens/YAML/CSV and localized resources; no excluded PNG/HTML visual draft is opened, parsed, sampled, measured or compared.

## DL-074 — Budget retry hashes use the request's immutable expected revision

- Date/stage: 2026-08-04 / P17
- Surface issue: rebuilding a retry command from the mutable current budget revision changes its canonical payload after the first success, so receipt lookup would incorrectly reject an otherwise exact duplicate `commandId` before the idempotency boundary can return the original result.
- Decision: construct every budget command from the request's immutable `expectedRevisionId`. The repository still reads the current pointer for optimistic-concurrency validation, but it cannot rewrite the command payload or canonical hash.
- Consequence: an exact retry returns its first `CommandReceipt` without duplicate facts; a new command with a genuinely obsolete expected revision still fails before any write. The API 36 SQLCipher test covers both paths.

## DL-075 — A stale budget projection fails closed instead of displaying old values as current

- Date/stage: 2026-08-04 / P17
- Surface issue: a historical transaction or budget revision can invalidate a long natural-month rollover chain. Serving the previous cache during failed recomputation would make base, carry, usage and daily availability appear authoritative at different revisions.
- Precedence applied: synchronous projection consistency and explicit maintenance/failure state outrank best-effort display of stale derived values.
- Decision: rebuild the affected chain synchronously inside the financial commit and stamp every row with the resulting `book.localRevision`. The application compares all required projection versions; any mismatch returns `FAILED`, omits composition/daily values and exposes the existing operation-center recovery entry.
- Consequence: a ten-year edit either presents a completely rebuilt current chain or a clear failure state. It never labels old cache values current, while the prior database transaction remains atomically intact on failure.

## DL-076 — P17 goldens are full-pixel digests of governed Compose output

- Date/stage: 2026-08-04 / P17
- Decision: render configured-light and constraint-error-dark budget screens in a deterministic 360×720 Compose viewport and compare SHA-256 over width, height and every ARGB pixel. The two digests live in the Android test source.
- Consequence: budget pixel drift is machine-detectable using only `LedgerTheme`, governed components, the textual UI contract, token JSON, screen YAML, traceability CSV and localized resources. No excluded PNG/HTML visual draft is opened, parsed, sampled, measured or compared.

## DL-077 — Goal ADJUST keeps the positive-money model closed

- Date/stage: 2026-08-04 / P18
- Surface issue: the frozen `GoalMovement` stores a positive amount and the UI route exposes the closed `ALLOCATE|RELEASE|ADJUST` kind, while an unrestricted signed adjustment would hide direction inside a positive-money field.
- Precedence applied: the domain model's typed money and prohibited-state rules outrank a looser UI interpretation.
- Decision: `ADJUST` is an explicit positive reserve correction. A downward correction uses `RELEASE`; no negative amount or implicit sign is accepted by a goal command.
- Consequence: direction stays auditable in the immutable movement kind, canonical hashing remains unambiguous and compile/runtime validation rejects zero, negative and over-release inputs.

## DL-078 — RELEASE completion is a resumable two-commit workflow

- Date/stage: 2026-08-04 / P18
- Surface issue: completion combines a financial release, which requires a command receipt and the sole financial coordinator, with a goal-status entity revision. The frozen model does not define one cross-kind command that may bypass either boundary.
- Decision: commit the exact remaining reserve through `FinancialMutationCoordinator`, then append the goal completion revision. A retry first observes the rebuilt zero reserve and performs only the missing status transition.
- Consequence: failure between steps is visible and safely resumable; retries cannot release twice, real account balance is unchanged and the implementation does not claim unsupported cross-command atomicity.

## DL-079 — Project transaction paging uses the immutable occurrence/ID key

- Date/stage: 2026-08-04 / P18
- Decision: order project transactions by `(occurredAt DESC, StableId DESC)` and continue with an exclusive compound cursor. The overview is separately bounded to the first three rows.
- Consequence: PRJ-004 uses Paging 3 without deep OFFSET or retaining a full result-ID set; equal timestamps remain deterministic and newly appended history does not duplicate a page already consumed.

## DL-080 — P18 goldens are full-pixel digests of governed Compose output

- Date/stage: 2026-08-04 / P18
- Decision: render project-cash-flow light and underfunded-goal-detail dark states in a deterministic 360×720 Compose viewport and compare SHA-256 over width, height and every ARGB pixel. The two digests live in the Android test source.
- Consequence: P18 pixel drift is machine-detectable using only `LedgerTheme`, governed components, localized resources, the textual UI contract, token JSON, screen YAML and traceability CSV. No excluded PNG/HTML visual draft is opened, parsed, sampled, measured or compared.

# P32 Security, Vault and Privacy Diagnostics Mapping

P32 is `VERIFIED`. This mapping covers REQ-004, REQ-005 and REQ-077—REQ-080 plus VLT-001—004, SETG-006—011, CLR-001, SYS-004 and the P32 security completion of G-002. It uses only the frozen textual contract, token JSON, screen YAML and traceability CSV; the excluded visual drafts were not inspected.

## Vault and key boundary

| Contract | Production realization | Verification |
|---|---|---|
| Fresh authentication for every sensitive action | `VaultKeyHierarchy` creates a new auth-validity-zero Keystore cipher and `BiometricPrompt.CryptoObject` for PAN reveal, PAN copy, CVC reveal and edit. Request identity and one-shot consumption prevent reuse. App-lock authentication has no Vault key primitive, and Vault authentication never changes app-lock state. | `P32-E003` real credential prompts, wrong-object rejection and application/Vault state assertions |
| Ciphertext-only persistence | The feature passes plaintext only to a short-lived authenticated editor. `VaultSecretApplicationPort` can express only opaque `VaultCiphertext`; `SecureRoomVaultSecretApplicationPort` opens the primary SQLCipher database and touches only `card_vault_secret` plus payment-card identity lookup. | `P32-E005` SQLCipher save/read/list/delete and export/FTS/audit scan |
| Plaintext lifetime | Revealed values and editor keys expire after 30 seconds. Background, navigation away, explicit hide, application lock and controller close clear all exposures. Only PAN has a clipboard API; the sensitive clip is cleared after at most 30 seconds or immediately on background. | `P32-E003`; `P32-E006` semantic-tree sentinel scan |
| Screen and device security | Vault visibility always sets `FLAG_SECURE`, independent of the global toggle. Recent-task obscuring and optional global screenshot blocking share the screen-privacy controller. Missing device credentials block Vault provisioning and app-lock enablement. | `P32-E003`; `P32-E006` |
| Backup recovery rewrap | A recovery password opens only the recovery-wrapped Vault DEK. After successful restore it is immediately rebound under a fresh device-authentication Vault KEK through another CryptoObject; card fields are never background-decrypted. | `P32-E003` actual Keystore recovery/rewrap/decrypt proof |

## Diagnostics and crash boundary

| Contract | Production realization | Verification |
|---|---|---|
| Fixed schemas only | Feature names, entry points, result, duration, errors, crash kinds, stack frames and structured-log fields are closed enums/data classes. No generic telemetry `Map`, arbitrary attributes or user text API exists. | `P32-E001`; `P32-E002` |
| Consent and independent channels | Before privacy acceptance, record/send paths are inert. Feature and crash controls have separate queue and 128-bit installation IDs. Disabling a channel deletes its queue and ID; re-enabling creates a new ID. | `P32-E002`; `P32-E004` no-backup persistence/restart proof |
| Rotation and retention | IDs rotate at 30 days; feature events retain at most 90 days and crash events at most 180 days. Queue files are atomic, bounded to 2,048 entries and fail closed on corruption. | `P32-E002`; `P32-E004` |
| Network and upload scan | The client accepts a replaceable HTTPS endpoint and emits only two manually fixed JSON schemas. A final stack-symbol scan drops card-number-like content before network I/O; failure remains retryable without treating it as success. | `P32-E002` MockWebServer payload and zero-request rejection test |
| ACRA and system exits | ACRA 5.13.1 retains only Android version and sanitized stack input, disables framework forwarding and uses the custom sender factory. `ApplicationExitInfo` classifies ANR, native crash, excessive resource use and other system exits into the same fixed queue. Throwable messages, causes and arbitrary trace text have no report representation. | `P32-E004` real Android ACRA initialization and exit-history collector |
| Release diagnostics | `StructuredDiagnosticLog` exposes fixed phase/code/severity and strips bounded debug metadata in release mode. LeakCanary 2.14 is debug-only; release production code has no ordinary business-object/free-text logging path. | `P32-E001`; `P32-E007` Lint/source-policy scan |

The frozen technical stack deliberately leaves the telemetry receiver deployment outside the Android repository. The app therefore owns a replaceable HTTPS sender contract and a durable user-auditable queue; it does not invent or hard-code an unapproved production endpoint. See `DL-158`.

## Settings and clearing authority

- SETG-006 provides default-off app lock, immediate/1/5/15-minute/custom timeout, authenticated enablement, test-lock and missing-device-security recovery.
- SETG-007 controls global screenshot blocking and recent-task obscuring while disclosing that Vault security cannot be disabled.
- SETG-008 configures trash retention and delegates actual purge to the previously verified P31 closed-chain coordinator path.
- SETG-009—011 display independent consent switches and only fixed whitelist queue rows; each queue can be deleted independently.
- CLR-001 uses the governed typed high-risk confirmation and a fresh device authentication. It joins/cancels work, destroys the local ledger, keys, Vault envelope, diagnostics IDs/queues and app-owned derived artifacts, but does not traverse exports/SAF or call Drive.
- Cloud backup deletion remains the separate P31 CLR-002 reauthentication flow.

## Sensitive-data exclusion proof

| Destination | Exclusion mechanism |
|---|---|
| FTS and audit revision snapshots | Vault ciphertext is isolated in `card_vault_secret`; it has no FTS projection/audit writer. The device scan inserts a unique Vault sentinel and finds it in neither ordinary workbook pages, `transaction_fts` nor `entity_revision.canonical_snapshot_blob`. |
| Ordinary export and reports | The P29 typed export field model has no Vault representation and rejects PAN/CVC/vault/ciphertext names. P32 replays all fifteen workbook sheets against the stored sentinel. |
| Route and SavedState | Routes carry only `StableIdArgument(cardId)` or the closed authentication purpose. Sensitive submissions and exposure handles are memory-only non-serializable values. |
| Logs, telemetry and crashes | Production logging has fixed enums; feature events contain no text; ACRA discards exception messages; the final sender scan rejects a forged PAN-bearing stack symbol. |
| Semantics and screenshots | Sensitive value text clears its own unmerged semantics, leaving only “masked/revealed”; CVC has no copy semantics. Vault and background window policies are asserted against the real `FLAG_SECURE`. |
| Widgets | Widget contracts consume only ledger projections, and the static P32 scan finds no Vault/PAN/CVC dependency or model field. |

## Evidence index

- `P32-E001`: frozen contract/static validator and eight weakening mutations.
- `P32-E002`: JVM telemetry, sender, retention, structured logging and security regressions.
- `P32-E003`: API 36 Keystore/BiometricPrompt/CryptoObject, recovery rewrap, clipboard and `FLAG_SECURE`.
- `P32-E004`: API 36 no-backup queues, consent/identifier deletion, ACRA and `ApplicationExitInfo`.
- `P32-E005`: API 36 SQLCipher Vault boundary and sensitive sentinel scans.
- `P32-E006`: all 39 required UI states, three languages, accessibility sizes/themes, semantic privacy and 12 pixel baselines.
- `P32-E007`: aggregate architecture, formatting, Detekt, JVM, Lint and app integration regression.
- `P32-E008`: frozen baselines, complete validator mutation regression, supply-chain artifacts and repository hygiene.

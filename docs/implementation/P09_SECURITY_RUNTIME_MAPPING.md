# P09 Security Runtime Mapping

This ledger maps only the P09 security runtime foundation. It does not claim that G-001—G-005, G-002, SYS-004, VLT-*, backup/restore, clear-data or security-settings UI is implemented. All 215 screen rows remain `NOT_STARTED`.

## Frozen-source mapping

| Frozen contract | P09 implementation | Verification |
|---|---|---|
| 技术栈 §7.1 database-external encryption | Tink 1.23.0 `AES256_GCM` for bounded envelopes/settings and `AES256_GCM_HKDF_1MB` Streaming AEAD for attachment data; raw keysets exist only inside bounded `SecretBytes` | `SecurityPrimitivesTest`; `P09-E001` |
| 技术栈 §7.2 card vault | `VaultAuthenticationKEK` is a separate Android Keystore AES-256 key with authentication required for every operation; `Vault DEK` is separately wrapped and each reveal/copy/edit/export request owns one authenticated `CryptoObject` | `VaultAuthenticationDeviceTest`; `P09-E004` |
| 技术栈 §7.3 attachment encryption | Device bundle contains an attachment root keyset; each attachment receives a fresh streaming data key wrapped with book/blob/version associated data | `SecurityPrimitivesTest`; `DeviceKeyAndSessionDeviceTest` |
| 技术栈 §7.4 recovery password | Versioned Argon2id v1.3 parameters enforce at least 64 MiB, three iterations and 32-byte output; calibration targets 500 ms without lowering the floor; AES-GCM recovery wrapping has book/schema associated data and one indistinguishable wrong-password/corruption failure | `SecurityPrimitivesTest`; `P09-E001` |
| 架构 §5 runtime | `BookSessionManager` implements `Uninitialized`, `Locked`, `Opening`, `Maintenance`, `RecoveryRequired` and `Ready`; the SQLCipher database is opened/closed with reference-counted UI/headless ownership | `BookSessionManagerTest`; `DeviceKeyAndSessionDeviceTest` |
| 架构 §13 background tasks | `HeadlessBookLease` exposes only an opaque operation ID and closed capability; it never exposes a database, DAO, DEK or resource. App locking drops the UI lease without invalidating an already-authorized limited background lease; a fresh headless open runs startup inspection and any inspector exception closes the resource before entering sanitized recovery | `BookSessionManagerTest`; `ARCH-FEATURE-SECURITY` source policy |
| 架构 §16.1 three key layers | `DeviceLedgerKEK` wraps one atomic device bundle containing the database DEK, attachment root and security-settings key; `VaultAuthenticationKEK` wraps only the Vault DEK; a recovery-password KEK is derived independently by Argon2id | `validate_p09_security.py`; `DeviceKeyAndSessionDeviceTest`; `VaultAuthenticationDeviceTest` |
| 架构 §16.2 ledger key must not require each biometric | DeviceLedgerKEK has `setUserAuthenticationRequired(false)` and device tests create/reopen SQLCipher without device credentials; deleting the KEK never regenerates over an existing envelope | `DeviceKeyAndSessionDeviceTest`; `P09-E003` |
| 架构 §16.3 vault double wrapping | Vault fields use Tink AEAD under the Vault DEK; the Vault DEK is wrapped by the per-action authenticated Keystore key. AAD submission occurs only after `BiometricPrompt` authenticates the exact `Cipher` | `VaultAuthenticationDeviceTest`; first failing device run plus corrected passing rerun recorded in `P09-E004` |
| 架构 §21.3 biometric configuration change | The vault key admits both `AUTH_BIOMETRIC_STRONG` and `AUTH_DEVICE_CREDENTIAL` with a zero-second authentication window. Because the required credential fallback must survive biometric enrollment changes, enrollment invalidation is explicitly disabled; `ERROR_NO_BIOMETRICS` fails closed as the non-sensitive `DEVICE_SECURITY_CHANGED` disposition and removing the device credential blocks further vault actions | `VaultAuthenticationDeviceTest`; `DeviceKeyAndSessionDeviceTest`; `P09-E004`; DL-043 |
| 架构 §19 startup recovery | Missing/invalidated key, database open failure, schema failure and projection inspection failure map to closed non-sensitive recovery diagnostic codes | `BookSessionManagerTest`; device key-deletion case |
| 需求 REQ-077 | PAN reveal/copy and security-code reveal are closed actions; security-code copy does not exist; plaintext handles expire after 30 seconds and clear on background or app lock | `SecurityPrimitivesTest`; validator mutation test |
| 需求 REQ-078 | App lock defaults off, requires authentication to enable, supports immediate/1/5/15 minute and bounded custom timeouts using elapsed real time; recent-task obscuring and optional full-app `FLAG_SECURE` are independent from mandatory vault protection | `AppLockControllerTest`; source validator |
| 需求 REQ-086/087 | Passwords, complete card secrets, DEKs and plaintext wrappers are rejected from Route/SavedState; session states and secure process-return entry are explicit runtime values | build-logic `SourcePolicyEngineTest`; `BookSessionManagerTest` |

## Key separation and lifecycle

| Material | Protector | Authentication | Persisted form | Plaintext lifetime |
|---|---|---|---|---|
| SQLCipher database DEK | DeviceLedgerKEK | No per-action biometric; device-local Keystore availability | Inside atomic encrypted device bundle | Only a defensive copy during database open; zeroized on close |
| Attachment root key | DeviceLedgerKEK | Same device availability boundary as the database | Inside atomic encrypted device bundle | Tink keyset handle construction only |
| Security-settings key | DeviceLedgerKEK | Same device availability boundary as the database | Inside atomic encrypted device bundle | Tink keyset handle construction only |
| Per-attachment data key | Attachment root Tink AEAD | No UI authentication; required for background attachment work | AEAD-wrapped with book/blob/encryption-version AAD | Streaming operation only |
| Vault DEK | VaultAuthenticationKEK | Every provision/reveal/copy/edit/recovery-export action authenticates a fresh CryptoObject | Separate encrypted vault envelope | Single authenticated action; exported only as bounded `SecretBytes` for recovery wrapping |
| Recovery backup KEK | Argon2id(password, random salt, versioned parameters) | Recovery password | Never stored; only salt/parameters/nonce/ciphertext persist | Derivation/wrap or unwrap call only |

`SecurityEnvelopeStore` writes device and vault envelopes through `AtomicFile` below `noBackupFilesDir`. It has no plaintext fallback and treats “envelope exists but Keystore key is gone” as recovery-required rather than creating a replacement key.

## Platform evidence boundary

- Pure JVM tests prove deterministic envelope/AAD behavior, Tink AEAD/Streaming AEAD, Argon2id rejection, secret zeroization, application-lock timing and the session/lease state machine only.
- Android 16 x86_64 managed-device tests prove the actual Android Keystore policy, SQLCipher create/close/reopen, no-auth DeviceLedgerKEK, missing-key recovery, device-security absence, correct credential-only availability detection, combined strong-biometric/device-credential key policy, sanitized no-biometric disposition, PIN-backed `BiometricPrompt.CryptoObject` use for two independent vault actions, and rejection after the device credential is removed. The suite does not claim that the managed device physically enrolled and removed a biometric.
- No Robolectric result is used as evidence for Keystore, biometric/device credential or SQLCipher behavior.
- No screenshot/golden or visual implementation is created in P09, and none of the prohibited PNG/HTML visual drafts is an implementation input.

## Explicit later-stage boundary

P09 does not implement backup scheduling/retention/Drive transport (P30), restore/merge/clear workflows (P31/P32), security settings and vault screens (P32), SessionGate Compose UI (P11), app lifecycle wiring, telemetry consent/queue behavior, or production feature/Worker entrypoints. Those requirement rows remain `IN_PROGRESS`, and their screen rows remain `NOT_STARTED`.

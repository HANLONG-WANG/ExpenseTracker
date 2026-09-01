# P11 Application Shell, SessionGate and Onboarding Mapping

Last updated: 2026-08-02 (Asia/Tokyo)

This mapping covers P11 only. It does not promote later transaction, settings, background-operation, backup/restore, widget or release workflows. All rendered visual values originate in the frozen token JSON and governed `:core:designsystem` components; the excluded visual drafts were not opened or used.

## Runtime and ownership mapping

| Frozen responsibility | Production implementation | Automated evidence |
|---|---|---|
| Single Activity / one Compose root | `LedgerApplication`, `MainActivity`, `LedgerAppRoot`; Hilt composes dependencies only at `:app` | `P11RuntimeDeviceTest`; manifest/static validator |
| SessionGate | `AppRootState` plus `BookSessionState` render exactly Locked, Opening, Maintenance, RecoveryRequired and Ready; all business/navigation destinations are blocked unless Ready | `P11UiContractDeviceTest`; `FiveStackNavigatorP11Test`; `SessionAwareLedgerWriteGateTest` |
| RootScaffold and global messages | One Ready `LedgerScaffold` owns the app bar, five-entry bottom bar, governed global Snackbar controller and process-loss/operation banner slot | UI state matrix, API 28/36 UI tests, static validator |
| Navigation 3 | `FiveStackNavigator` owns five independent `NavBackStack`s; cold default is `REC-001` with the closed `EXPENSE` enum; repeat selection pops then scrolls root | `FiveStackNavigatorP11Test`; `P11RuntimeDeviceTest` |
| Safe restore and deep links | Proto DataStore persists only contract-encoded routes, stable keys and scroll offsets under the explicit always-last-page policy; deep links remain memory-only behind SessionGate | `AppSettingsContractTest`; route/YAML tests; static mutation tests |
| Secure book bootstrap | `LedgerInitializationPort` and `SecureRoomLedgerInitializationPort` create only book/system-ledger/reference metadata in SQLCipher; no Journal, Posting, effect or example transaction is created | `SecureLedgerInitializationDeviceTest`; `P11RuntimeDeviceTest` |
| Financial write blocking | `SessionAwareLedgerWriteGate` rechecks readiness both before and inside the serialized `FinancialMutationCoordinator` gate | `SessionAwareLedgerWriteGateTest`; P08 coordinator tests |
| App lock/privacy lifecycle | Activity foreground/background callbacks drive `AppLockController`; `AndroidScreenPrivacyController` obscures recent tasks; BiometricPrompt unlocks only the UI session | API 36 runtime launch plus inherited P09 device evidence |
| Typed first-run settings | Proto DataStore stores language/currency/zone/consents/lock policy/wrapped verifier/book ID and safe navigation metadata; it contains no amount, note, name, card, attachment, coordinate or recovery-password plaintext field | `AppSettingsContractTest`; P11 proto/static mutation gate |

Book, account and category bootstrap are non-monetary metadata operations: they never materialize a financial fact or balance. Every subsequent monetary mutation continues through `FinancialMutationCoordinator`; feature modules receive neither DAO nor Entity authority.

## Screen and required-state mapping

| Contract range | Implementation | Required states | Status |
|---|---|---:|---|
| G-001 | Root SessionGate | 6 | VERIFIED by `P11-E003`, `P11-E004`, `P11-E005` |
| G-002 | LockScreen and system authentication action | 5 | VERIFIED by `P11-E004`, `P11-E005` plus inherited P09 platform tests |
| G-003 | OpeningBookScreen with the 150 ms token delay | 3 | VERIFIED by `P11-E004`, `P11-E005`, `P11-E006` |
| G-004 | MaintenanceScreen / governed operation progress | 6 | VERIFIED by `P11-E002`, `P11-E004`, `P11-E005`, `P11-E006` |
| G-005 | RecoveryRequiredScreen, sanitized code and high-risk local clear | 5 | VERIFIED by `P11-E004`, `P11-E005`, `P11-E006` |
| G-006 | Shared More feature hub | 3 | VERIFIED by `P11-E004`, `P11-E005` |
| G-007 | Operation center shell | 5 | VERIFIED by `P11-E004`, `P11-E005`; durable Worker execution remains P28—P31 |
| G-008 | Offline allowlisted help | 2 | VERIFIED by `P11-E002`, `P11-E004`, `P11-E005` |
| ONB-001—ONB-010 | Exact ten-step OnboardingScaffold | 30 | VERIFIED by `P11-E002`—`P11-E006` |

The total is 18 screens and 65 required states. The device matrix covers 320/360/480 dp, 100/130/200% font, Simplified Chinese/Japanese/English, light/dark and the dynamic-color boundary. Four 360×720 API 36 goldens are generated exclusively from the Compose/token implementation and compared pixel-for-pixel.

## Requirement disposition

| Requirement | P11 disposition |
|---|---|
| REQ-070, REQ-071, REQ-081, REQ-085, REQ-087 | VERIFIED for the frozen root-navigation/startup/session contract |
| REQ-001, REQ-002, REQ-078, REQ-082, REQ-083, REQ-084, REQ-086 | IN_PROGRESS with P11 foundation evidence; widget, full offline feature, settings, complete accessibility/performance and feature-form process-death acceptance stay in their owning phases |

No online account/login flow and no sample data exist. Optional recovery password, first account and first category steps can be skipped; plaintext recovery input is cleared on every exit and never enters SavedState, route, log, telemetry or semantics.

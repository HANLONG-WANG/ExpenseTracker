# ExpenseTracker Manual Test Findings

This document records confirmed findings from interactive Android emulator testing. Relevant `logcat` evidence is captured from the live monitor and summarized here.

## Session

- Session ID: `MT-2026-08-14-01`
- Status: Stopped
- Started: 2026-08-14 22:46 JST
- Ended: 2026-08-14 22:57 JST
- Device: Android Emulator `emulator-5554`
- Android API: 36
- Display: 1080 x 2400
- Application ID: `app.ledger.expensetracker`
- Version: 1.0.0 (`versionCode` 1)
- Process at monitor start: PID 4026
- Log source: live `adb logcat` across the main, system, and crash buffers
- Monitoring scope: crashes, ANRs, fatal signals, uncaught exceptions, security exceptions, process deaths, and other high-confidence runtime failures
- Session result: 6 process-level crash occurrences representing 4 distinct confirmed findings; no ANR, native fatal signal, or `SecurityException` was observed
- Code changes: None; this session only added this findings document

## Confirmed findings

### MT-001 — Refund entry crashes the application

- Severity: High
- Status: Confirmed; not fixed
- Detected: 2026-08-14 22:48:25 JST; reproduced again at 22:50:04 and 22:57:42 JST
- Area: Record > Other transactions > Refund
- Environment: Android API 36, ExpenseTracker 1.0.0, PID 4026
- Reproduction:
  1. Open the ordinary record area.
  2. Select the Other transactions tab.
  3. Tap Refund.
- Expected: Navigate to the registered refund editor `REC-015` (`record/refund/{transactionId?}`).
- Actual: The application terminates immediately and Android reports a fatal main-thread exception.
- Failure signature: `java.lang.IllegalArgumentException: screen ID is not registered in the frozen contract`
- Runtime evidence:

  ```text
  FATAL EXCEPTION: main
  Process: app.ledger.expensetracker, PID: 4026
  java.lang.IllegalArgumentException: screen ID is not registered in the frozen contract
      at app.ledger.core.navigation.LedgerRouteContract.screen(NavigationContract.kt:190)
      at app.ledger.core.navigation.LedgerRouteContract.destination(NavigationContract.kt:213)
      at app.ledger.app.AppRootViewModel.navigateRecord(AppRootViewModel.kt:2205)
      at app.ledger.app.OrdinaryRecordRootDestinationKt...onNavigate(OrdinaryRecordRootDestination.kt:27)
      at app.ledger.feature.record.OrdinaryRecordScreensKt.OtherTransactionCards(OrdinaryRecordScreens.kt:257)
  ActivityManager: Process app.ledger.expensetracker (pid 4026) has died: fg TOP
  ```

- Root cause assessment: `OtherTransactionCards` sends the hard-coded ID `REF-001`, but that ID is absent from `GeneratedScreenContract`. The frozen contract and root destination both register the refund editor as `REC-015`. Of the seven card targets, `REF-001` is the only unregistered ID, which uniquely identifies the Refund card as this crash trigger.
- Relevant source:
  - `feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt:250`
  - `feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt:257`
  - `app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt:2195`
  - `app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt:2205`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/NavigationContract.kt:190`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/GeneratedScreenContract.kt:809`
- Missing regression coverage: Existing refund device tests exercise `REC-015` directly, but no discovered test activates the Refund card from `OtherTransactionCards` through the production root navigation path.

### MT-002 — Credit payment entry crashes because a transaction ID is required

- Severity: High
- Status: Confirmed; not fixed
- Detected: 2026-08-14 22:50:00 JST
- Area: Record > Other transactions > Credit payment
- Environment: Android API 36, ExpenseTracker 1.0.0, PID 4259
- Reproduction:
  1. Open the ordinary record area.
  2. Select the Other transactions tab.
  3. Tap Credit payment.
- Expected: Navigate to the new credit-payment editor `REC-014`, whose `transactionId` is optional.
- Actual: The application terminates immediately because navigation targets a detail allocation screen without its required transaction ID.
- Failure signature: `java.lang.IllegalArgumentException: missing required route argument transactionId`
- Runtime evidence:

  ```text
  FATAL EXCEPTION: main
  Process: app.ledger.expensetracker, PID: 4259
  java.lang.IllegalArgumentException: missing required route argument transactionId
      at app.ledger.core.navigation.LedgerRouteContract.destination(NavigationContract.kt:218)
      at app.ledger.app.AppRootViewModel.navigateRecord(AppRootViewModel.kt:2205)
      at app.ledger.app.OrdinaryRecordRootDestinationKt...onNavigate(OrdinaryRecordRootDestination.kt:27)
      at app.ledger.feature.record.OrdinaryRecordScreensKt.OtherTransactionCards(OrdinaryRecordScreens.kt:257)
  ActivityManager: Process app.ledger.expensetracker (pid 4259) has died: fg TOP
  ```

- Root cause assessment: The Credit payment card targets `CRD-007`, which is the allocation editor for an existing payment and requires `transactionId`. The contract provides `REC-014` as the new credit-payment entry and explicitly makes `transactionId` optional. The card supplies no arguments.
- Relevant source:
  - `feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt:249`
  - `feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt:257`
  - `app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt:2205`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/NavigationContract.kt:218`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/GeneratedScreenContract.kt:780`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/GeneratedScreenContract.kt:2314`
- Missing regression coverage: Credit UI tests render `REC-014` and `CRD-007` directly with suitable fixtures, but no discovered test activates the argument-free production card.

### MT-003 — Loan entry crashes because a contract ID is required

- Severity: High
- Status: Confirmed; not fixed
- Detected: 2026-08-14 22:50:08 JST
- Area: Record > Other transactions > Loan
- Environment: Android API 36, ExpenseTracker 1.0.0, PID 4447
- Reproduction:
  1. Open the ordinary record area.
  2. Select the Other transactions tab.
  3. Tap Loan.
- Expected: Navigate to the argument-free loan operation entry `REC-017`, where the user can choose a loan operation.
- Actual: The application terminates immediately because navigation targets a loan detail screen without its required contract ID.
- Failure signature: `java.lang.IllegalArgumentException: missing required route argument contractId`
- Runtime evidence:

  ```text
  FATAL EXCEPTION: main
  Process: app.ledger.expensetracker, PID: 4447
  java.lang.IllegalArgumentException: missing required route argument contractId
      at app.ledger.core.navigation.LedgerRouteContract.destination(NavigationContract.kt:218)
      at app.ledger.app.AppRootViewModel.navigateRecord(AppRootViewModel.kt:2205)
      at app.ledger.app.OrdinaryRecordRootDestinationKt...onNavigate(OrdinaryRecordRootDestination.kt:27)
      at app.ledger.feature.record.OrdinaryRecordScreensKt.OtherTransactionCards(OrdinaryRecordScreens.kt:257)
  ActivityManager: Process app.ledger.expensetracker (pid 4447) has died: fg TOP
  ```

- Root cause assessment: The Loan card targets `LOA-007`, the detail page for a specific loan and therefore requires `contractId`. The contract provides `REC-017` as the argument-free loan operation entry. The card supplies no arguments.
- Relevant source:
  - `feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt:251`
  - `feature/record/src/main/kotlin/app/ledger/feature/record/OrdinaryRecordScreens.kt:257`
  - `app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt:2205`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/NavigationContract.kt:218`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/GeneratedScreenContract.kt:861`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/GeneratedScreenContract.kt:2641`
- Missing regression coverage: Loan device tests render `REC-017` and `LOA-007` directly with appropriate parameters, but no discovered test activates the argument-free production card.

### MT-004 — More > Credit accounts crashes because no account ID is supplied

- Severity: High
- Status: Confirmed; not fixed
- Detected: 2026-08-14 22:51:17 JST
- Area: More > Credit accounts
- Environment: Android API 36, ExpenseTracker 1.0.0, PID 4497
- Reproduction:
  1. Open More.
  2. Tap Credit accounts.
- Expected: Open a credit-account selection/list flow, or open a credit-account detail only after a concrete account has been selected.
- Actual: The application terminates immediately because the route requires an account ID and the More entry supplies `null`.
- Failure signature: `java.lang.IllegalArgumentException: missing required route argument accountId`
- Runtime evidence:

  ```text
  FATAL EXCEPTION: main
  Process: app.ledger.expensetracker, PID: 4497
  java.lang.IllegalArgumentException: missing required route argument accountId
      at app.ledger.core.navigation.LedgerRouteContract.destination(NavigationContract.kt:218)
      at app.ledger.app.AppRootViewModel.navigateCredit(AppRootViewModel.kt:3334)
      at app.ledger.app.MoreRootScreenKt...onCredit(MoreRootScreen.kt:131)
  ActivityManager: Process app.ledger.expensetracker (pid 4497) has died: fg TOP
  ```

- Root cause assessment: `MoreRootDestination` calls `navigateCredit("CRD-001", null)`. `CRD-001` is a specific credit-account detail route (`credit/{accountId}`) with a non-optional `accountId`. `navigateCredit` only adds that argument when its `stableId` parameter is non-null. The unified liability entry `LIA-001` contains a `CreditAccountsSection`, but the final intended product route should be confirmed before fixing.
- Relevant source:
  - `app/src/main/kotlin/app/ledger/app/MoreRootScreen.kt:130`
  - `app/src/main/kotlin/app/ledger/app/MoreRootScreen.kt:131`
  - `app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt:3326`
  - `app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt:3334`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/NavigationContract.kt:218`
  - `core/navigation/src/main/kotlin/app/ledger/core/navigation/GeneratedScreenContract.kt:2165`
- Missing regression coverage: No discovered test activates the Credit accounts row through `MoreRootDestination`; credit device tests render `CRD-001` directly with a fixture account ID.

## Finding format

Each confirmed finding will include:

- Finding ID and severity
- Detection time and affected screen or flow
- Reproduction steps supplied during the manual test
- Expected and actual behavior
- Exception or failure signature
- Relevant stack trace and source location
- Evidence and current disposition

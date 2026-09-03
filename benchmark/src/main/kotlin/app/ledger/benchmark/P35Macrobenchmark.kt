@file:Suppress("MagicNumber", "TooManyFunctions")
@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)

package app.ledger.benchmark

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "app.ledger.expensetracker"
private const val PROVIDER_URI = "content://$PACKAGE_NAME.p35-benchmark"
private const val WAIT_MILLIS = 120_000L
private const val P37_WARM_ITERATIONS = 30
private const val P37_COLD_ITERATIONS = 5
private const val P37_EVIDENCE_TAG = "P37Evidence"
private const val P37_BLOCKING_PROGRESS_TRACE = "P37/blocking_progress_visible"
private const val P37_BLOCKING_PROGRESS_OBSERVER_DELAY_MILLIS = 150L
private const val P37_CACHED_CONTENT_OBSERVER_DELAY_MILLIS = 300L
private const val P37_UNCACHED_CONTENT_OBSERVER_DELAY_MILLIS = 800L
private const val P37_COLD_CONTENT_OBSERVER_DELAY_MILLIS = 1_600L
private const val P37_MAX_NESTED_BACK_STEPS = 3

@RunWith(AndroidJUnit4::class)
class P36ReleaseMinificationAuditDeviceTest {
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun workManagerInputMergerConstructorRemainsReflectivelyAvailable() {
        assertShellSuccess(
            device.executeShellCommand(
                "content call --uri $PROVIDER_URI --method release-reflection --arg androidx.work.OverwritingInputMerger",
            ),
        )
    }

    @Test
    fun providerSummaryCounterParserAcceptsAndroidContentBundleOutput() {
        val output =
            "Result: Bundle[{summary=primaryOpen=1,sqlStatements=31,financialTransactions=0," +
                "databaseKeyUnwrap=1,sessionAcquisitions=3, status=measured}]"
        check(counter(output, "primaryOpen") == 1L)
        check(counter(output, "sqlStatements") == 31L)
        check(counter(output, "financialTransactions") == 0L)
        check(counter(output, "databaseKeyUnwrap") == 1L)
        check(counter(output, "sessionAcquisitions") == 3L)
    }
}

@RunWith(AndroidJUnit4::class)
class P35TargetScaleAuditDeviceTest {
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun fixedTargetScaleSqlCipherFixturePassesBoundedAudit() {
        assertShellSuccess(device.executeShellCommand("content call --uri $PROVIDER_URI --method seed"))
        val audit = device.executeShellCommand("content call --uri $PROVIDER_URI --method audit")
        assertShellSuccess(audit)
        listOf("pagingMs", "recordDefaultsMs", "searchMs", "reportMs", "mapMs", "heapGrowthBytes", "fdGrowth", "nativeHeapBytes").forEach { metric ->
            assertTrue("missing $metric: $audit", audit.contains("$metric="))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class P35LedgerMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun ensureTargetScaleFixture() {
        assertShellSuccess(device.executeShellCommand("content call --uri $PROVIDER_URI --method seed"))
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
    }

    @Test
    fun coldStartAtTargetScale() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max), FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.COLD,
            iterations = 3,
            setupBlock = { killProcess() },
            measureBlock = {
                startActivityAndWait(benchmarkLaunchIntent())
                requireText(device, "Record")
            },
        )
    }

    @Test
    fun journalPagingAccountsReportsAndMapNavigation() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = 3,
            setupBlock = {
                pressHome()
                launchTargetForSetup(device)
            },
            measureBlock = {
                clickTopLevel(device, "Journal")
                requireText(device, "Benchmark food")
                repeat(8) { device.swipe(540, 1500, 540, 500, 12) }
                clickTopLevel(device, "Accounts")
                requireText(device, "Benchmark account")
                clickTopLevel(device, "Analysis")
                requireResource(device, "analysis_home")
            },
        )
    }

    @Test
    fun recordingSaveAndLongOperationDestinations() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = 2,
            setupBlock = {
                pressHome()
                launchTargetForSetup(device)
                clickText(device, "Benchmark food")
                requireResource(device, "record_editor")
                setFirstEditText(device, (100L + SystemClock.elapsedRealtime() % 10_000L).toString())
            },
            measureBlock = {
                clickResource(device, "ledger_save")
                requireResource(device, "record_root")
                clickText(device, "More")
                requireText(device, "More features")
                openScrolledUntil(device, "Data transfer center", "Import, export, and backup", "Allow operation notifications")
                if (waitForTextOrDescription(device, "Allow operation notifications", 2_000) != null) {
                    clickText(device, "Not now")
                }
                requireText(device, "Import, export, and backup")
                openAndReturn(device, "Import", "Choose import source", "Import, export, and backup")
                openAndReturn(device, "Export", "Export data", "Import, export, and backup")
                openAndReturn(device, "Backup", "Backup", "Import, export, and backup")
                openScrolledUntil(device, "Operations", "No background operations")
            },
        )
    }
}

@RunWith(AndroidJUnit4::class)
class P37ArchitectureCounterDeviceTest {
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun ensureTargetScaleFixture() {
        assertShellSuccess(device.executeShellCommand("content call --uri $PROVIDER_URI --method seed"))
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
    }

    @Test
    fun everyTopLevelDestinationReusesTheReadyPrimaryResource() {
        launchTargetForSetup(device)
        requireP37Counters(device, primaryOpen = 1, databaseKeyUnwrap = 1).logEvidence("uiUnlock")
        listOf(
            "Journal" to "Benchmark food",
            "Accounts" to "Benchmark account",
            "Budget" to "budget_home",
            "Analysis" to "analysis_home",
            "Record" to "record_root",
        ).forEach { (destination, contentSignal) ->
            resetP37Metrics(device)
            clickTopLevel(device, destination)
            if (destination == "Journal" || destination == "Accounts") {
                requireText(device, contentSignal)
            } else {
                requireResource(device, contentSignal)
            }
            requireP37Counters(device, primaryOpen = 0, databaseKeyUnwrap = 0).logEvidence("warmInteraction")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class P37InteractiveMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun ensureTargetScaleFixture() {
        assertShellSuccess(device.executeShellCommand("content call --uri $PROVIDER_URI --method seed"))
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
    }

    @Test
    fun coldUnlockToCurrentRouteContent() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                StartupTimingMetric(),
                TraceSectionMetric("P37/unlock", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/unlock_to_content", TraceSectionMetric.Mode.First),
                FrameTimingMetric(),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.COLD,
            iterations = P37_COLD_ITERATIONS,
            setupBlock = { killProcess() },
            measureBlock = {
                startActivityAndWait(benchmarkLaunchIntent())
                // Keep UIAutomator outside the complete 1,500 ms cold-content budget. The target
                // trace owns the latency; this delay only keeps Perfetto capture alive until its
                // asynchronous current-route content completion has been published and closed.
                SystemClock.sleep(P37_COLD_CONTENT_OBSERVER_DELAY_MILLIS)
                requireResource(device, "record_content")
                SystemClock.sleep(P37_CACHED_CONTENT_OBSERVER_DELAY_MILLIS)
            },
        )
    }

    @Test
    fun warmCachedTopLevelNavigation() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("P37/route_request", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/route_content", TraceSectionMetric.Mode.First),
                FrameTimingMetric(),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = P37_WARM_ITERATIONS,
            setupBlock = {
                pressHome()
                launchP37WarmTargetForSetup(device)
                clickTopLevel(device, "Accounts")
                requireText(device, "Benchmark account")
                clickTopLevel(device, "Record")
                requireResource(device, "record_root")
                resetP37Metrics(device)
            },
            measureBlock = {
                clickTopLevelWithoutIdle(device, "Accounts")
                SystemClock.sleep(P37_CACHED_CONTENT_OBSERVER_DELAY_MILLIS)
                requireText(device, "Benchmark account")
                requireP37Counters(device, primaryOpen = 0, databaseKeyUnwrap = 0)
            },
        )
    }

    @Test
    fun warmUncachedBoundedJournalNavigation() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("P37/route_request", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/route_content", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/journal_metadata", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/journal_page", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/journal_presentation", TraceSectionMetric.Mode.First),
                FrameTimingMetric(),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = P37_WARM_ITERATIONS,
            setupBlock = {
                pressHome()
                launchP37WarmTargetForSetup(device)
                resetP37Metrics(device)
            },
            measureBlock = {
                clickTopLevelWithoutIdle(device, "Journal")
                SystemClock.sleep(P37_UNCACHED_CONTENT_OBSERVER_DELAY_MILLIS)
                requireText(device, "Benchmark food")
                requireP37Counters(device, primaryOpen = 0, databaseKeyUnwrap = 0)
            },
        )
    }

    @Test
    fun blockingLoadingAffordanceBecomesVisible() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric(
                    P37_BLOCKING_PROGRESS_TRACE,
                    TraceSectionMetric.Mode.First,
                ),
                FrameTimingMetric(),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = P37_WARM_ITERATIONS,
            setupBlock = {
                pressHome()
                launchP37WarmTargetForSetup(device)
                resetP37Metrics(device)
            },
            measureBlock = {
                clickTopLevelWithoutIdle(device, "Journal")
                // API 28 accessibility-root reads can synchronously stall the target main thread.
                // Start semantic verification only after the fixed 100 ms trace gate has elapsed.
                SystemClock.sleep(P37_BLOCKING_PROGRESS_OBSERVER_DELAY_MILLIS)
                requireFirstJournalResponse(device)
                requireText(device, "Benchmark food")
                requireP37Counters(device, primaryOpen = 0, databaseKeyUnwrap = 0)
                    .logEvidence("warmInteraction")
            },
        )
    }

    @Test
    fun ordinarySaveToCommittedAcknowledgement() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("P37/save_request", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/save_commit", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/save_settled", TraceSectionMetric.Mode.First),
                FrameTimingMetric(),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = P37_WARM_ITERATIONS,
            setupBlock = {
                pressHome()
                launchP37WarmTargetForSetup(device)
                clickText(device, "Benchmark food")
                requireResource(device, "record_editor")
                setFirstEditText(device, (100L + SystemClock.elapsedRealtime() % 10_000L).toString())
                resetP37Metrics(device)
            },
            measureBlock = {
                clickResource(device, "ledger_save")
                requireResource(device, "record_root")
                requireP37Counters(
                    device,
                    primaryOpen = 0,
                    databaseKeyUnwrap = 0,
                    financialTransactions = 1,
                ).logEvidence("ordinarySave")
            },
        )
    }

    @Test
    fun journalSearchIncludingDebounce() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                TraceSectionMetric("P37/search_request", TraceSectionMetric.Mode.First),
                TraceSectionMetric("P37/search_content", TraceSectionMetric.Mode.First),
                FrameTimingMetric(),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = P37_WARM_ITERATIONS,
            setupBlock = {
                pressHome()
                launchP37WarmTargetForSetup(device)
                clickTopLevel(device, "Journal")
                requireText(device, "Benchmark food")
                clickDescription(device, "Search transactions")
                requireText(device, "Search transactions")
                resetP37Metrics(device)
            },
            measureBlock = {
                setFirstEditText(device, "needle")
                requireResource(device, "journal_search_results")
                requireP37Counters(device, primaryOpen = 0, databaseKeyUnwrap = 0)
            },
        )
    }
}

@RunWith(AndroidJUnit4::class)
class P35BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun ensureTargetScaleFixture() {
        assertShellSuccess(device.executeShellCommand("content call --uri $PROVIDER_URI --method seed"))
        // The seed provider starts the target process without an Activity. Stop that process so the
        // profile trace always begins from the launcher Activity and never waits behind provider-only state.
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
    }

    @Test
    fun startupRecordJournalAccountAndSaveProfile() {
        baselineProfileRule.collect(PACKAGE_NAME) {
            pressHome()
            startActivityAndWait(benchmarkLaunchIntent())
            requireResource(device, "record_root")
            clickTopLevel(device, "Journal")
            requireText(device, "Benchmark food")
            clickTopLevel(device, "Accounts")
            requireText(device, "Benchmark account")
            clickTopLevel(device, "Record")
            clickText(device, "Benchmark food")
            requireResource(device, "record_editor")
            setFirstEditText(device, "321")
            clickResource(device, "ledger_save")
            requireResource(device, "record_root")
        }
    }
}

private fun assertShellSuccess(output: String) {
    assertFalse(output, output.contains("error=", ignoreCase = true))
    assertTrue(
        output,
        listOf("seeded", "already-complete", "audited", "reset", "measured").any { status ->
            output.contains("status=$status")
        },
    )
}

private fun resetP37Metrics(device: UiDevice) {
    assertShellSuccess(device.executeShellCommand("content call --uri $PROVIDER_URI --method p37-reset"))
}

private fun requireP37Counters(
    device: UiDevice,
    primaryOpen: Long,
    databaseKeyUnwrap: Long,
    financialTransactions: Long? = null,
): P37CounterSnapshot {
    val output = device.executeShellCommand("content call --uri $PROVIDER_URI --method p37-metrics")
    assertShellSuccess(output)
    val snapshot = P37CounterSnapshot(
        primaryOpen = counter(output, "primaryOpen"),
        sqlStatements = counter(output, "sqlStatements"),
        financialTransactions = counter(output, "financialTransactions"),
        databaseKeyUnwrap = counter(output, "databaseKeyUnwrap"),
        sessionAcquisitions = counter(output, "sessionAcquisitions"),
    )
    check(snapshot.primaryOpen == primaryOpen) { output }
    check(snapshot.databaseKeyUnwrap == databaseKeyUnwrap) { output }
    if (financialTransactions != null) {
        check(snapshot.financialTransactions == financialTransactions) { output }
    }
    return snapshot
}

private data class P37CounterSnapshot(
    val primaryOpen: Long,
    val sqlStatements: Long,
    val financialTransactions: Long,
    val databaseKeyUnwrap: Long,
    val sessionAcquisitions: Long,
) {
    fun logEvidence(scenario: String) {
        Log.i(
            P37_EVIDENCE_TAG,
            "scenario=$scenario primaryOpen=$primaryOpen sqlStatements=$sqlStatements " +
                "financialTransactions=$financialTransactions databaseKeyUnwrap=$databaseKeyUnwrap " +
                "sessionAcquisitions=$sessionAcquisitions",
        )
    }
}

private fun counter(output: String, name: String): Long = Regex("(?:^|\\W)${Regex.escape(name)}=(\\d+)")
    .find(output)
    ?.groupValues
    ?.get(1)
    ?.toLong()
    ?: error("counter missing: $name; output=$output")

private fun benchmarkLaunchIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
    setClassName(PACKAGE_NAME, "app.ledger.app.MainActivity")
    addCategory(Intent.CATEGORY_LAUNCHER)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

private fun launchTargetForSetup(device: UiDevice) {
    val output = device.executeShellCommand(
        "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-f 0x10008000 -n $PACKAGE_NAME/app.ledger.app.MainActivity",
    )
    check(!output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)) {
        "target launch failed: $output"
    }
    requireResource(device, "record_root")
}

/**
 * Returns the retained warm Activity to authoritative Record content without creating another
 * Activity/Surface for every iteration. `CLEAR_TOP | SINGLE_TOP` reuses MainActivity when it is
 * already the task root; the real top-level action resets the in-app route deterministically.
 */
private fun launchP37WarmTargetForSetup(device: UiDevice) {
    val output = device.executeShellCommand(
        "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-f 0x34000000 -n $PACKAGE_NAME/app.ledger.app.MainActivity",
    )
    check(!output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)) {
        "target warm launch failed: $output"
    }
    unwindP37NestedDestinationForSetup(device)
    clickTopLevel(device, "Record")
    requireResource(device, "record_content")
}

private fun unwindP37NestedDestinationForSetup(device: UiDevice) {
    repeat(P37_MAX_NESTED_BACK_STEPS) {
        if (device.findObject(By.res("ledger_navigation_record")) != null) return
        check(device.pressBack()) { "could not unwind nested warm destination; visible=${visibleLabels(device)}" }
        device.waitForIdle()
    }
    checkNotNull(device.findObject(By.res("ledger_navigation_record"))) {
        "top-level navigation unavailable after bounded unwind; visible=${visibleLabels(device)}"
    }
}

private fun requireResource(device: UiDevice, resourceId: String, timeoutMillis: Long = WAIT_MILLIS) {
    checkNotNull(device.wait(Until.findObject(By.res(resourceId)), timeoutMillis)) {
        "resource not found: $resourceId; visible=${visibleLabels(device)}"
    }
    device.waitForIdle()
}

private fun clickResource(device: UiDevice, resourceId: String) {
    val tagged = device.wait(Until.findObject(By.res(resourceId)), WAIT_MILLIS)
    checkNotNull(tagged) { "resource click target not found: $resourceId; visible=${visibleLabels(device)}" }
    (clickableAncestor(tagged) ?: tagged).click()
    device.waitForIdle()
}

private fun requireText(device: UiDevice, text: String, timeoutMillis: Long = WAIT_MILLIS) {
    check(waitForTextOrDescription(device, text, timeoutMillis) != null) {
        "text or description not found: $text; visible=${visibleLabels(device)}"
    }
    device.waitForIdle()
}

private fun visibleLabels(device: UiDevice): String {
    val pending = ArrayDeque(device.findObjects(By.depth(0)))
    val labels = linkedSetOf<String>()
    while (pending.isNotEmpty() && labels.size < 60) {
        val node = pending.removeFirst()
        node.text?.takeIf(String::isNotBlank)?.let(labels::add)
        node.contentDescription?.takeIf(String::isNotBlank)?.let(labels::add)
        pending.addAll(node.children)
    }
    return labels.joinToString(" | ")
}

private fun clickText(device: UiDevice, text: String) {
    val target = waitForClickableTextOrDescription(device, text)
    checkNotNull(target) { "click target not found: $text; visible=${visibleLabels(device)}" }.click()
    device.waitForIdle()
}

@Suppress("NestedBlockDepth")
private fun clickDescription(device: UiDevice, description: String) {
    val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
    do {
        try {
            val pending = ArrayDeque(device.findObjects(By.depth(0)))
            while (pending.isNotEmpty()) {
                val candidate = pending.removeFirst()
                if (candidate.contentDescription?.contains(description) == true) {
                    clickableAncestor(candidate)?.let { clickable ->
                        clickable.click()
                        device.waitForIdle()
                        return
                    }
                    val center = candidate.visibleCenter
                    if (device.click(center.x, center.y)) {
                        device.waitForIdle()
                        return
                    }
                }
                pending.addAll(candidate.children)
            }
        } catch (stale: StaleObjectException) {
            Log.d(P37_EVIDENCE_TAG, "Description node became stale; retrying current tree", stale)
        }
        SystemClock.sleep(100)
    } while (SystemClock.elapsedRealtime() < deadline)
    error("description click target not found: $description; visible=${visibleLabels(device)}")
}

private fun clickTopLevel(device: UiDevice, destination: String) {
    clickTopLevelWithoutIdle(device, destination)
    device.waitForIdle()
}

private fun clickTopLevelWithoutIdle(device: UiDevice, destination: String) {
    val resourceId = when (destination) {
        "Record" -> "ledger_navigation_record"
        "Journal" -> "ledger_navigation_journal"
        "Accounts" -> "ledger_navigation_accounts"
        "Budget" -> "ledger_navigation_budget"
        "Analysis" -> "ledger_navigation_analysis"
        else -> error("unknown top-level destination: $destination")
    }
    val target = device.wait(Until.findObject(By.res(resourceId)), WAIT_MILLIS)
    checkNotNull(target) {
        "top-level target not found: $destination ($resourceId); visible=${visibleLabels(device)}"
    }
    val center = target.visibleCenter
    check(device.click(center.x, center.y)) { "top-level tap injection failed: $destination ($resourceId)" }
}

@Suppress("ReturnCount")
private fun requireFirstJournalResponse(device: UiDevice) {
    val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
    do {
        if (device.findObject(By.res("journal_loading")) != null) return
        if (device.findObject(By.text("Benchmark food")) != null) return
        if (device.findObject(By.descContains("Benchmark food")) != null) return
        SystemClock.sleep(5)
    } while (SystemClock.elapsedRealtime() < deadline)
    error("Journal produced neither progress nor content; visible=${visibleLabels(device)}")
}

private fun waitForTextOrDescription(
    device: UiDevice,
    value: String,
    timeoutMillis: Long = WAIT_MILLIS,
): androidx.test.uiautomator.UiObject2? {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    do {
        (device.findObject(By.text(value)) ?: device.findObject(By.descContains(value)))?.let { return it }
        SystemClock.sleep(100)
    } while (SystemClock.elapsedRealtime() < deadline)
    return null
}

private fun waitForClickableTextOrDescription(
    device: UiDevice,
    value: String,
    timeoutMillis: Long = WAIT_MILLIS,
): androidx.test.uiautomator.UiObject2? {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    do {
        (device.findObjects(By.text(value)) + device.findObjects(By.descContains(value))).forEach { candidate ->
            clickableAncestor(candidate)?.let { return it }
        }
        SystemClock.sleep(100)
    } while (SystemClock.elapsedRealtime() < deadline)
    return null
}

private fun clickableAncestor(candidate: androidx.test.uiautomator.UiObject2): androidx.test.uiautomator.UiObject2? {
    var current: androidx.test.uiautomator.UiObject2? = candidate
    while (current != null && !current.isClickable) current = current.parent
    return current
}

private fun openAndReturn(device: UiDevice, entry: String, title: String, returnTitle: String) {
    openScrolledUntil(device, entry, title)
    device.pressBack()
    requireText(device, returnTitle)
}

private fun openScrolledUntil(device: UiDevice, entry: String, vararg expected: String) {
    repeat(5) {
        scrollAndClickText(device, entry)
        expected.forEach { title ->
            if (waitForTextOrDescription(device, title, 2_000) != null) return
        }
    }
    error("entry did not navigate: $entry; expected=${expected.joinToString()}; visible=${visibleLabels(device)}")
}

private fun scrollAndClickText(device: UiDevice, value: String) {
    repeat(20) {
        (device.wait(Until.findObject(By.text(value)), 500) ?: device.findObject(By.descContains(value)))?.let { target ->
            val bounds = target.visibleBounds
            if (bounds.height() > 0 && bounds.top >= 160 && bounds.bottom <= 2_180) {
                // UiObject2.click() uses the unclipped ancestor centre for LazyColumn cards. A card
                // that is only partly materialized can therefore receive the click outside its visible
                // text even though the selector is on screen. Tap the verified visible label centre.
                Log.i("P35Benchmark", "tap '$value' at $bounds")
                device.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                device.waitForIdle()
                SystemClock.sleep(500)
                return
            }
        }
        device.swipe(540, 1_500, 540, 1_000, 12)
        device.waitForIdle()
        SystemClock.sleep(250)
    }
    error("scroll click target not found: $value")
}

private fun setFirstEditText(device: UiDevice, value: String) {
    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT_MILLIS)
    checkNotNull(field) { "amount editor unavailable" }
    field.click()
    field.text = value
    device.pressBack()
    device.waitForIdle()
}

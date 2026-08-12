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
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
                startActivityAndWait(benchmarkLaunchIntent())
                requireText(device, "Record")
            },
            measureBlock = {
                clickText(device, "Journal")
                requireText(device, "Benchmark food")
                repeat(8) { device.swipe(540, 1500, 540, 500, 12) }
                clickText(device, "Accounts")
                requireText(device, "Benchmark account")
                clickText(device, "Analysis")
                requireText(device, "Analysis")
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
                startActivityAndWait(benchmarkLaunchIntent())
                requireText(device, "Benchmark food")
            },
            measureBlock = {
                clickText(device, "Benchmark food")
                setFirstEditText(device, "123")
                clickText(device, "Save")
                requireText(device, "Benchmark food")
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
            requireText(device, "Record")
            clickText(device, "Journal")
            requireText(device, "Benchmark food")
            clickText(device, "Accounts")
            requireText(device, "Benchmark account")
            clickText(device, "Record")
            clickText(device, "Benchmark food")
            setFirstEditText(device, "321")
            clickText(device, "Save")
            requireText(device, "Benchmark food")
        }
    }
}

private fun assertShellSuccess(output: String) {
    assertFalse(output, output.contains("error=", ignoreCase = true))
    assertTrue(output, output.contains("status=seeded") || output.contains("status=already-complete") || output.contains("status=audited"))
}

private fun benchmarkLaunchIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
    setClassName(PACKAGE_NAME, "app.ledger.app.MainActivity")
    addCategory(Intent.CATEGORY_LAUNCHER)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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
    checkNotNull(target) { "click target not found: $text" }.click()
    device.waitForIdle()
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

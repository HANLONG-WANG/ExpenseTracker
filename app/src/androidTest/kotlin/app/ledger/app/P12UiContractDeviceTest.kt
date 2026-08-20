package app.ledger.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import app.ledger.feature.accounts.AccountsDataState
import app.ledger.feature.accounts.AccountsDestination
import app.ledger.feature.accounts.AccountsRequiredState
import app.ledger.feature.accounts.AccountsScreenAction
import app.ledger.feature.accounts.AccountsScreenUiState
import app.ledger.feature.settings.ManagementDataState
import app.ledger.feature.settings.ManagementRequiredState
import app.ledger.feature.settings.ManagementScreenAction
import app.ledger.feature.settings.ReferenceManagementDestination
import app.ledger.finance.application.AccountGoalReferenceView
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.AccountTransactionReferenceView
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.CheckpointReferenceView
import app.ledger.finance.application.LocationReferenceView
import app.ledger.finance.application.MerchantReferenceView
import app.ledger.finance.application.PlaceReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.UserAccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class P12UiContractDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allSixtySevenFrozenAccountAndReferenceStatesRenderWithStableTags() {
        val targets = AccountsRequiredState.entries.map(RenderTarget::Account) +
            ManagementRequiredState.entries.map(RenderTarget::Management)
        assertEquals(67, targets.size)
        val active = mutableStateOf(targets.first())
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                Box(Modifier.testTag(STATE_HOST_TAG)) { render(active.value) }
            }
        }
        targets.forEach { target ->
            composeRule.runOnIdle { active.value = target }
            composeRule.waitForIdle()
            val rootTag = if (target is RenderTarget.Account) LedgerTestTags.P12_ACCOUNTS_ROOT else LedgerTestTags.P12_MANAGEMENT_ROOT
            composeRule.onNodeWithTag(rootTag).assertExists()
            composeRule.onNodeWithTag(STATE_HOST_TAG).assertExists()
        }
    }

    @Test
    fun compactWidthsLargeFontsThemesAndDynamicColorBoundaryKeepManagementAccessible() {
        val cases = listOf(
            RenderCase(320, 1f, ThemeMode.LIGHT, false),
            RenderCase(320, 2f, ThemeMode.DARK, false),
            RenderCase(360, 1.3f, ThemeMode.LIGHT, true),
            RenderCase(480, 1f, ThemeMode.DARK, true),
        )
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, case.fontScale)) {
                LedgerTheme(case.theme, dynamicColor = case.dynamic, reduceMotion = case.fontScale == 2f) {
                    Box(Modifier.size(case.width.dp, 2200.dp).testTag(MATRIX_TAG)) {
                        ReferenceManagementDestination(
                            screenId = "CAT-002",
                            encodedArguments = mapOf("direction" to "EXPENSE"),
                            dataState = ManagementDataState.Content(representativeSnapshot()),
                            actions = managementActions,
                            placeMap = { _, _, _ -> },
                            pending = false,
                            stateOverride = ManagementRequiredState.CAT_002_VALIDATION_ERROR,
                        )
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            val bounds = composeRule.onNodeWithTag(MATRIX_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(case.width.toFloat(), bounds.width, .5f)
            assertTrue(bounds.height > 0f)
        }
    }

    @Test
    fun simplifiedChineseJapaneseAndEnglishReferenceResourcesRender() {
        val cases = listOf(
            Locale.SIMPLIFIED_CHINESE to "还没有账户",
            Locale.JAPANESE to "口座はまだありません",
            Locale.ENGLISH to "No accounts yet",
        )
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val context = localizedTargetContext(active.value.first)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                    AccountsDestination(
                        uiState = AccountsScreenUiState(
                            "ACC-001",
                            emptyMap(),
                            AccountsDataState.Content(representativeSnapshot()),
                            UserAccountType.CASH,
                            stateOverride = AccountsRequiredState.ACC_001_NO_ACCOUNTS,
                        ),
                        onAction = accountActions,
                    )
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(case.second).assertExists()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 36)
    fun accountHomeGoldenMatchesTokenAndYamlDerivedPixels() {
        composeRule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(ACCOUNT_GOLDEN_TAG)) {
                        AccountsDestination(
                            uiState = AccountsScreenUiState(
                                "ACC-001",
                                emptyMap(),
                                AccountsDataState.Content(representativeSnapshot()),
                                UserAccountType.BANK,
                                stateOverride = AccountsRequiredState.ACC_001_CONTENT,
                            ),
                            onAction = accountActions,
                        )
                    }
                }
            }
        }
        val digest = composeRule.onNodeWithTag(ACCOUNT_GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
        println("P34_GOLDEN_ACC_001=$digest")
        assertEquals(ACCOUNT_GOLDEN_SHA256, digest)
    }

    private fun Bitmap.pixelSha256(): String {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * (pixels.size + 2))
        buffer.putInt(width)
        buffer.putInt(height)
        pixels.forEach(buffer::putInt)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    @androidx.compose.runtime.Composable
    private fun render(target: RenderTarget) {
        when (target) {
            is RenderTarget.Account -> AccountsDestination(
                uiState = AccountsScreenUiState(
                    target.state.screenId,
                    accountArguments(target.state),
                    AccountsDataState.Content(representativeSnapshot()),
                    UserAccountType.BANK,
                    pending = target.state.contractName == "saving",
                    stateOverride = target.state,
                ),
                onAction = accountActions,
            )
            is RenderTarget.Management -> ReferenceManagementDestination(
                screenId = target.state.screenId,
                encodedArguments = managementArguments(target.state),
                dataState = ManagementDataState.Content(representativeSnapshot()),
                actions = managementActions,
                placeMap = { _, _, _ -> },
                pending = target.state.contractName in setOf("saving", "merging", "splitting", "processing"),
                stateOverride = target.state,
            )
        }
    }

    private fun accountArguments(state: AccountsRequiredState): Map<String, String> = when (state.screenId) {
        "ACC-004", "ACC-005", "ACC-006", "ACC-007", "ACC-009", "ACC-012" -> mapOf("accountId" to TEST_ID.toString())
        "ACC-008" -> mapOf("accountId" to TEST_ID.toString(), "checkpointId" to OTHER_ID.toString())
        "ACC-011" -> mapOf("cardId" to TEST_ID.toString())
        "ACC-003" -> if (state.contractName in setOf("edit", "currencyLocked")) mapOf("accountId" to TEST_ID.toString()) else emptyMap()
        "ACC-010" -> if (state.contractName == "edit") mapOf("cardId" to TEST_ID.toString()) else emptyMap()
        else -> emptyMap()
    }

    private fun managementArguments(state: ManagementRequiredState): Map<String, String> = when (state.screenId) {
        "CAT-001", "CAT-003" -> mapOf("direction" to "EXPENSE")
        "CAT-002" -> buildMap {
            put("direction", "EXPENSE")
            if (state.contractName in setOf("edit", "parentLocked", "contrastWarning")) put("categoryId", TEST_ID.toString())
        }
        "CAT-004" -> mapOf("direction" to "EXPENSE", "categoryId" to TEST_ID.toString())
        "MER-002" -> if (state.contractName == "edit") mapOf("merchantId" to TEST_ID.toString()) else emptyMap()
        "PLC-002" -> if (state.contractName == "edit") mapOf("placeId" to TEST_ID.toString()) else emptyMap()
        "PLC-003" -> mapOf("placeId" to TEST_ID.toString())
        else -> emptyMap()
    }

    private fun representativeSnapshot(): ReferenceDataSnapshot {
        val jpy = requireNotNull(CurrencyCode.parse("JPY").getOrNull())
        return ReferenceDataSnapshot(
            bookId = TEST_ID,
            baseCurrency = jpy,
            localRevision = 1,
            accounts = listOf(
                AccountReferenceView(
                    TEST_ID, UserAccountType.BANK, "Everyday", jpy, EntityStatus.ACTIVE, "Local bank", null,
                    LocalDate.of(2026, 1, 1), "account", 0xff374151.toInt(), 0, 1, 12_000, 12_000,
                    Instant.parse("2026-08-01T00:00:00Z"), true, 1,
                ),
            ),
            cards = listOf(
                CardReferenceView(
                    TEST_ID,
                    TEST_ID,
                    CardType.DEBIT,
                    "Debit",
                    "1234",
                    EntityStatus.ACTIVE,
                    null,
                    "account",
                    0xff374151.toInt(),
                    0,
                    1,
                    1,
                ),
            ),
            categories = listOf(
                CategoryReferenceView(
                    TEST_ID, CategoryDirection.EXPENSE, null, 1, "Food", "record", 0xff374151.toInt(), 0,
                    CategoryStatus.ACTIVE, StatisticalNature.CONSUMPTION_EXPENSE, TEST_ID, TEST_ID, TEST_ID, 1, 1, 0,
                ),
                CategoryReferenceView(
                    OTHER_ID, CategoryDirection.EXPENSE, null, 1, "Other", "budget", 0xff006c4c.toInt(), 1,
                    CategoryStatus.ACTIVE, StatisticalNature.NON_CONSUMPTION_EXPENSE, null, null, null, 1, 0, 0,
                ),
            ),
            merchants = listOf(
                MerchantReferenceView(TEST_ID, "Cafe", listOf("Coffee"), EntityStatus.ACTIVE, null, 1, 1, 1),
                MerchantReferenceView(OTHER_ID, "Bakery", emptyList(), EntityStatus.ACTIVE, null, 1, 0, 1),
            ),
            places = listOf(
                PlaceReferenceView(TEST_ID, "Station", 356_000_000, 1_397_000_000, TEST_ID, EntityStatus.ACTIVE, null, 1, 1),
                PlaceReferenceView(OTHER_ID, "Office", 357_000_000, 1_398_000_000, OTHER_ID, EntityStatus.ACTIVE, null, 1, 0),
            ),
            locations = listOf(
                LocationReferenceView(THIRD_ID, 356_000_000, 1_397_000_000, Instant.parse("2026-08-01T00:00:00Z"), TEST_ID, 1),
            ),
            checkpoints = listOf(
                CheckpointReferenceView(OTHER_ID, TEST_ID, Instant.parse("2026-08-01T00:00:00Z"), LocalDate.of(2026, 8, 1), 11_500, 12_000, -500, null),
            ),
            accountTransactions = listOf(
                AccountTransactionReferenceView(
                    THIRD_ID,
                    FOURTH_ID,
                    TEST_ID,
                    LocalDate.of(2026, 8, 1),
                    Instant.parse("2026-08-01T00:00:00Z"),
                    TransactionKind.EXPENSE,
                    -500,
                    12_000,
                    jpy,
                ),
            ),
            accountGoals = listOf(
                AccountGoalReferenceView(FOURTH_ID, TEST_ID, "Emergency", 2_000, 10_000, jpy),
            ),
            coreNetFinancialAssetsMinor = 12_000,
            adjustedNetFinancialPositionMinor = 12_000,
            valuationMissing = false,
        )
    }

    private fun localizedTargetContext(locale: Locale): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocales(LocaleList(locale)) })
    }

    private sealed interface RenderTarget {
        data class Account(val state: AccountsRequiredState) : RenderTarget
        data class Management(val state: ManagementRequiredState) : RenderTarget
    }

    private data class RenderCase(val width: Int, val fontScale: Float, val theme: ThemeMode, val dynamic: Boolean)

    private val accountActions: (AccountsScreenAction) -> Unit = {}
    private val managementActions: (ManagementScreenAction) -> Unit = {}

    private companion object {
        val TEST_ID: StableId = StableId.fromUuid(UUID(0x12, 1))
        val OTHER_ID: StableId = StableId.fromUuid(UUID(0x12, 2))
        val THIRD_ID: StableId = StableId.fromUuid(UUID(0x12, 3))
        val FOURTH_ID: StableId = StableId.fromUuid(UUID(0x12, 4))
        const val STATE_HOST_TAG = "p12_contract_state_host"
        const val MATRIX_TAG = "p12_layout_matrix"
        const val ACCOUNT_GOLDEN_TAG = "p34_account_home_golden"
        const val ACCOUNT_GOLDEN_SHA256 = "d335201e139a3632b4eb5777addf1eafdae5d78286b6501718251804ac9ffc93"
    }
}

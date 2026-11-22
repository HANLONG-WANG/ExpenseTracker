package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.BookEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.ui.features.command.CommandEffectsSheet
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CommandMoneyFactsUiTest {
    @get:Rule val ui=createComposeRule()

    @Test fun mixedCurrencyReviewAcrossLooks() {
        fun j(text:String)=commandJson.parseToJsonElement(text)
        val repayment=ObjectEffect("repayment","payment",
            j("""{"currency":"JPY","sourceCurrency":"USD","principalMinor":20000,"feeMinor":1000,"feeBaseMinor":10000,"sourceFeeMinor":100,"sourceFeeBaseMinor":700}"""),
            j("""{"currency":"JPY","sourceCurrency":"USD","principalMinor":21000,"feeMinor":2000,"feeBaseMinor":12000,"sourceFeeMinor":200,"sourceFeeBaseMinor":1500}"""))
        val contract=ObjectEffect("contract","contract",
            j("""{"currency":"JPY","fundingAccountId":"funds","fundingCurrency":"USD","separateFeeCurrency":"USD","receivedMinor":19000,"withheldFeeMinor":1000,"withheldFeeBaseMinor":5000,"separateFeeMinor":200,"separateFeeBaseMinor":1400}"""),
            j("""{"currency":"JPY","fundingAccountId":"funds","fundingCurrency":"USD","separateFeeCurrency":"USD","receivedMinor":20000,"withheldFeeMinor":2000,"withheldFeeBaseMinor":6000,"separateFeeMinor":300,"separateFeeBaseMinor":2100}"""))
        val details=ObjectEffect("entryDetails","payment",
            buildJsonObject{put("repayment",repayment.before)},buildJsonObject{put("repayment",repayment.after)})
        val userText=ObjectEffect("entry","entry",j("""{"name":"NORMAL","note":"@小王 代付午餐","zoneId":"Asia/Tokyo"}"""),
            j("""{"name":"ME","note":"INCOME","zoneId":"Asia/Shanghai"}"""))
        val cases=listOf(
            Triple("user-text",userText,listOf("NORMAL → ME","@小王 代付午餐 → INCOME","Asia/Tokyo → Asia/Shanghai")),
            Triple("repayment",repayment,listOf("CN¥100.00 → CN¥120.00","US$1.00 → US$2.00","CN¥7.00 → CN¥15.00")),
            Triple("borrowing",contract,listOf("US$190.00 → US$200.00","CN¥50.00 → CN¥60.00","US$2.00 → US$3.00","CN¥14.00 → CN¥21.00")),
            Triple("nested-repayment",details,listOf("¥20,000 → ¥21,000","CN¥100.00 → CN¥120.00","US$1.00 → US$2.00"))
        )
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val case=mutableIntStateOf(0)
        ui.setContent {
            YohakuTheme(theme.value,language.value) {
                key(theme.value,language.value,case.intValue) {
                    val effect=cases[case.intValue].second
                    val snapshot=BookSnapshot(BookEntity("book","跨币种","CNY","2026-11-01",theme.value,language.value),emptyList(),emptyList(),emptyList(),emptyList(),null)
                    val model=ConfirmationModel(ExecutionContext("2026-11-02","Asia/Tokyo","2027-11-03"),emptyList(),listOf(effect))
                    CommandEffectsSheet(listOf(effect),snapshot,model) {}
                }
            }
        }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for((t,l) in testLooks(this))for((index,fixture) in cases.withIndex()) {
            ui.runOnIdle{theme.value=t;language.value=l;case.intValue=index}
            for((field,text) in fixture.third.withIndex()) {
                ui.onNodeWithText(text).performScrollTo().assertIsDisplayed()
                if(visualEnabled()&&(field==0||field==fixture.third.lastIndex)) {
                    ui.waitForIdle()
                    val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
                    val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"command-money-facts").also{it.mkdirs()}
                    val bitmap=instrumentation.uiAutomation.takeScreenshot()
                    File(folder,"$t-$l-${fixture.first}-$field$suffix.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
            }
        }
    }
}

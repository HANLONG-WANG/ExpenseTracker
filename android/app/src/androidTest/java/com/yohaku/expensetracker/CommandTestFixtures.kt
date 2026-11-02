package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.cloud.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

internal fun commandCardPlan(book:String,bank:String,catalog:String,context:String,planId:String=UUID.randomUUID().toString()):CommandPlan {
    fun input(text:String)=commandJson.parseToJsonElement(text).jsonObject
    return CommandPlan("yohaku.command-plan",1,planId,book,catalog,context,"建立信用卡、规则及期初预测 / カード・条件・期首予測を作成",steps=listOf(
        CommandStep("card","account.save",1,input("""{"name":"生活信用卡","type":"CREDIT","currency":"JPY","openingMinor":30000,"openingDate":"2026-11-01"}""")),
        CommandStep("rule","financialRule.addVersion",1,buildJsonObject {
            put("account",input("""{"ref":"card","output":"account"}"""));put("paymentAccount",bank)
            put("rule",input("""{"mode":"SINGLE","effectiveFrom":"2026-11-01","zoneId":"Asia/Tokyo","billing":{"closingDay":31,"closingDayInCurrentBill":true,"paymentMonthOffset":1,"paymentDay":10}}"""))
        }),
        CommandStep("forecast","financialPrediction.save",1,buildJsonObject {
            put("account",input("""{"ref":"card","output":"account"}"""));put("name","期初已有欠款");put("mode","SINGLE");put("openingPrincipalMinor",30000);put("paymentAccount",bank)
            put("terms",input("""{"agreementDate":"2026-11-01","paymentDates":["2026-12-10"]}"""))
        })
    ))
}
internal fun commandClaim(plan:CommandPlan,resultId:String="result-1",version:Int=1,localId:String="local-1"):CloudClaim {
    val text=commandJson.encodeToString(plan)
    return CloudClaim(localId,plan.book_id,"general","task-1",resultId,version,sha256(text),File("unused-test-claim"),text,null,emptyList(),"command")
}
internal fun commandChecks(model:ConfirmationModel)=model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.map{it.key}.toSet()

internal fun CommandPlan.withCommandCreditLimit():CommandPlan {
    val limit=commandJson.parseToJsonElement("""{"totalMinor":100000,"installmentMinor":null,"revolvingMinor":null,"sharing":"TOTAL_ONLY","combinedMinor":null,"effectiveFrom":"2026-11-01","basisDate":"2026-11-01","usedTotalMinor":30000,"usedInstallmentMinor":null,"usedRevolvingMinor":null,"recovery":"PAYMENT_DATE","usageBasis":"PRINCIPAL_ONLY","zoneId":"Asia/Tokyo","usageStart":"TRANSACTION_DATE","recoveryDelayDays":0}""")
    return copy(steps=steps.mapIndexed{index,step->if(index==0)step.copy(input=JsonObject(step.input+("creditLimit" to limit)))else step})
}

package com.yohaku.expensetracker.ui.features.command

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.LocalYohaku
import kotlinx.serialization.json.*
import com.yohaku.expensetracker.ui.theme.word

internal val financeObjectTypes=setOf("revolvingBatchArchive","ruleValidation","contract","contractBalance","repayment","repaymentAllocation","contractPlan","arrangement","arrangementReconciliation","poolPaymentChange","billAdjustment","creditLimitConfig","creditLimitGroupSetting","creditLimitMember","creditLimitRecovery","creditLimitObservation","financialDraft")
internal val financeObjectLabels=mapOf("revolvingBatchArchive" to ("批量转换恢复范围" to "一括変更の復元範囲"),
    "ruleValidation" to ("规则核验" to "条件の検証"),"contract" to ("借款合同" to "借入契約"),"contractBalance" to ("借贷剩余本金" to "借入の残元金"),
    "repayment" to ("还款记录" to "返済の記録"),"repaymentAllocation" to ("还款分配" to "返済の充当"),"contractPlan" to ("合同与计划关联" to "契約とプランの関連"),
    "arrangement" to ("已办理安排" to "手続き済みの予定"),"arrangementReconciliation" to ("安排重核" to "予定の再確認"),"poolPaymentChange" to ("共同月付款变更" to "共通月払いの変更"),
    "billAdjustment" to ("原账期义务调整" to "元の請求債務の調整"),"creditLimitConfig" to ("额度配置版本" to "利用枠設定の版"),"creditLimitGroupSetting" to ("共享额度组" to "共通利用枠"),
    "creditLimitMember" to ("共享额度成员" to "共通利用枠の所属口座"),"creditLimitRecovery" to ("额度恢复确认" to "利用枠回復の確認"),"creditLimitObservation" to ("平台可用额度观测" to "返済先の利用可能枠の確認"),"financialDraft" to ("金融表单草稿" to "金融フォームの下書き")
)
internal val financeFieldLabels=mapOf(
    "paid" to ("实际扣款" to "実出金額"),"received" to ("实际到账／实收" to "実入金・受取額"),"principal" to ("本金" to "元金"),"interest" to ("利息" to "利息"),"fee" to ("费用" to "手数料"),"sourceFee" to ("付款端费用" to "出金側手数料"),"feeBase" to ("费用本位币金额" to "手数料の基準通貨額"),"sourceFeeBase" to ("付款端费用本位币金额" to "出金側手数料の基準通貨額"),
    "principalDifferenceMinor" to ("本金差额" to "元金の差額"),"feeDifferenceMinor" to ("费用差额" to "手数料の差額"),"totalDifferenceMinor" to ("账单合计差额" to "請求合計の差額"),"dateChanged" to ("付款日期不同" to "支払日の相違"),"billRevision" to ("核验所用账单版本" to "検証対象の請求版"),
    "contractId" to ("借款合同" to "借入契約"),"fundingEventId" to ("借入记录" to "借入記録"),"fundingAccountId" to ("到账账户" to "入金先口座"),"receivedMinor" to ("实际到账／实收" to "実入金・受取額"),"withheldFeeMinor" to ("预扣费用" to "天引手数料"),"withheldFeeBaseMinor" to ("预扣费用本位币金额" to "天引手数料の基準通貨額"),
    "separateFeeMinor" to ("另付费用" to "別払い手数料"),"separateFeeAccountId" to ("另付费用账户" to "別払い手数料の口座"),"separateFeeBaseMinor" to ("另付费用本位币金额" to "別払い手数料の基準通貨額"),"sourceAccountId" to ("实际付款账户" to "実際の出金口座"),"debtAccountId" to ("债务账户" to "債務口座"),"interestMinor" to ("利息" to "利息"),"feeBaseMinor" to ("费用本位币金额" to "手数料の基準通貨額"),
    "clearingDate" to ("平台清偿日期" to "返済先の返済確定日"),"platformConfirmed" to ("平台已确认清偿" to "返済先が返済を確定"),"paymentType" to ("还款类型" to "返済の種類"),"linkedTransferEventId" to ("关联换汇记录" to "関連する両替記録"),"sourceFeeMinor" to ("付款端费用" to "出金側の手数料"),"sourceFeeBaseMinor" to ("付款端费用本位币金额" to "出金側手数料の基準通貨額"),"originalInputJson" to ("最初录入的还款资料" to "最初の返済入力"),
    "arrangementId" to ("已办理安排" to "手続き済みの予定"),"originalPlanId" to ("原计划" to "元のプラン"),"targetPlanId" to ("变更后计划" to "変更後のプラン"),"effectiveDate" to ("生效日期" to "適用日"),"appliedAt" to ("实际办理时间" to "実際の手続き日時"),"changeFeeMinor" to ("变更手续费" to "変更手数料"),"feeTreatment" to ("原费用处理" to "元の手数料の扱い"),"originalFeeTreatment" to ("原费用处理" to "元の手数料の扱い"),"exceptionAccepted" to ("平台接受期限外办理" to "返済先が期限外手続きを受理"),
    "baselineJson" to ("办理前依据" to "手続き前の根拠"),"inputJson" to ("已办理变更内容" to "手続き済みの変更内容"),"comparisonJson" to ("办理前后比较" to "手続き前後の比較"),"reconciliationJson" to ("重新核对依据" to "再確認の根拠"),"parentReviewId" to ("前序核对" to "前の確認"),"physicalRefunds" to ("实际退款" to "実際の返金"),"verifiedBills" to ("已核对账期" to "確認済みの請求"),"unassignedOriginalMinor" to ("原计划未分配本金" to "元プランの未割当元金"),
    "pool" to ("循环还款计划" to "リボ・循環返済プラン"),"poolId" to ("循环还款计划" to "リボ・循環返済プラン"),"mergePoolId" to ("并入的循环还款计划" to "統合先のリボ・循環返済プラン"),"sourceDate" to ("来源日期口径" to "対象日付の基準"),"adoptExisting" to ("采纳已有安排" to "既存の予定を統合"),"unallocatedShares" to ("未分配还款的计划归属" to "未充当返済の循環返済プラン帰属"),"paymentChanges" to ("月付款变更历史" to "月払い変更履歴"),"adoptedFees" to ("采纳安排的费用" to "統合予定の手数料"),"includedInRetained" to ("已包含于保留账期" to "保持する請求に含む"),"waivedMinor" to ("已免除费用" to "免除済み手数料"),
    "setting" to ("月付款设置" to "月払い設定"),"fromPaymentDate" to ("从此付款日起生效" to "この支払日から適用"),"feeDate" to ("费用日期" to "手数料の日付"),"feeIncludedInRetained" to ("费用已含于保留账期" to "手数料を保持する請求に含む"),"mergeFeeDate" to ("合并费用日期" to "統合手数料の日付"),"mergeFeeIncludedInRetained" to ("合并费用已含于保留账期" to "統合手数料を保持する請求に含む"),"transferPrincipalMinor" to ("转入本金" to "移行元金"),"loanLimitDeltaMinor" to ("贷款额度占用变化" to "ローン利用枠使用額の変化"),
    "accountIds" to ("成员账户" to "所属口座"),"sourceProof" to ("额度恢复的还款依据" to "利用枠回復の返済根拠"),"observedAt" to ("平台额度核对时间" to "利用枠の確認日時"),"voidedAt" to ("已作废" to "無効化済み"),"contextJson" to ("草稿用途" to "下書きの用途"),"valuesJson" to ("草稿内容" to "下書きの内容"),"category" to ("分类" to "カテゴリ"),
    "original" to ("原对象" to "元の対象"),"proposed" to ("变更后安排" to "変更後の予定"),"firstDueDate" to ("首个到期日" to "最初の支払期日"),"currentReductionMinor" to ("本期付款减少" to "当期支払の減少額"),"feesInOriginalHorizonMinor" to ("原范围内费用" to "元の期間内の手数料"),"feesInProposedHorizonMinor" to ("变更后范围内费用" to "変更後の期間内の手数料"),"totalExtraFeeMinor" to ("全期新增费用" to "全期間の追加手数料"),"originalUnscheduledPrincipalMinor" to ("原未安排本金" to "元の未予定元金"),"proposedRemainingPrincipalMinor" to ("变更后剩余本金" to "変更後の残元金"),"funding" to ("付款账户资金比较" to "出金口座の資金比較"),"fundingThrough" to ("资金预测截止日" to "資金予測の終了日"),"fundingUnassigned" to ("存在未指定付款账户" to "未指定の出金口座あり"),"fundingRates" to ("资金换算依据" to "資金換算の根拠"),"originalComplete" to ("原安排覆盖全期" to "元の予定は全期間を含む"),"proposedComplete" to ("变更后覆盖全期" to "変更後は全期間を含む"),"beforeGapMinor" to ("原最大资金缺口" to "元の最大資金不足額"),"afterGapMinor" to ("变更后最大资金缺口" to "変更後の最大資金不足額"),"beforeIncomeGapMinor" to ("计入预计收入的原缺口" to "収入見込を含む元の不足額"),"afterIncomeGapMinor" to ("计入预计收入的新缺口" to "収入見込を含む新しい不足額"),"beforeRiskDate" to ("原风险日期" to "元の不足日"),"afterRiskDate" to ("变更后风险日期" to "変更後の不足日"),"referenceEstimated" to ("采用参考换算估值" to "参考換算による見込額"),"sourceGrossMinor" to ("来源本金总额" to "対象元金の総額"),"sourcePlanIds" to ("来源计划" to "対象プラン"),"retainedBillIds" to ("保留账期" to "保持する請求"),"exists" to ("来源仍存在" to "対象が存在する"),"grossMinor" to ("来源总额" to "対象の総額"),"contractPrincipalMinor" to ("合同本金" to "契約元金"),"contractMode" to ("合同方式" to "契約方式"),"openingSourceMinor" to ("核对后的期初本金" to "確認後の期首元金"),"manualComplete" to ("手工安排覆盖全期" to "手入力予定が全期間を含む"),"poolSourceDate" to ("循环还款计划来源日期口径" to "リボ・循環返済プランの対象日基準"),"poolUnallocatedShares" to ("循环还款计划未分配还款归属" to "リボ・循環返済プランの未充当返済の帰属"),"undoGroupId" to ("批量转换批次" to "一括リボ変更"),
    "sources" to ("本金来源" to "元金の対象"),"payments" to ("实际还款" to "実際の返済"),"clearings" to ("已清偿本金" to "返済済み元金"),"adjustments" to ("义务调整" to "債務の調整"),"billAdjustments" to ("账期义务调整" to "請求債務の調整"),"retained" to ("保留账期" to "保持する請求"),"arrangementFees" to ("已办理安排费用" to "手続き済み予定の手数料"),"explicitMinor" to ("明确归属金额" to "明示した帰属額"),"unallocatedMinor" to ("未分配本金" to "未充当元金"),"accountOpeningMinor" to ("账户期初欠款" to "口座の期首残高"),"targetMinor" to ("归属于本安排的本金" to "この予定に帰属する元金"),"allocationId" to ("清偿分配" to "返済の充当"),"clearingMoves" to ("清偿分配迁移" to "返済充当の移行"),"poolSelection" to ("从循环还款计划转出范围" to "リボ・循環返済プランからの移行範囲"),"poolBillId" to ("循环还款计划内账期" to "循環返済プラン内の請求"),"cancelledFeeMinor" to ("平台取消费用" to "返済先が取消した手数料"),"unbilledPrincipalMinor" to ("尚未出账本金" to "未請求の元金"),"paymentSettings" to ("逐期付款设置" to "各回の支払設定"),"source" to ("原始来源" to "元の対象"),"input" to ("核对内容" to "確認内容"),"comparison" to ("前后比较" to "変更前後の比較")
)
@Composable internal fun financeObjectLabel(type:String):String?=financeObjectLabels[type]?.let{word(it.first,it.second)}

/** Only step snapshots are used here; no current-state repayment inference or confirmation. */
@Composable internal fun CommandRepaymentSummary(step:StepEffect,s:BookSnapshot,model:ConfirmationModel) {
    val entryEffect=step.objects.single{it.type=="entry"}
    val payment=step.objects.first{it.type=="repayment"&&it.key==entryEffect.key}
    val after=payment.after.jsonObject
    val entry=entryEffect.after.jsonObject
    val currency=Currency.valueOf(entry.getValue("currency").jsonPrimitive.content)
    val debtCurrency=Currency.valueOf(after.getValue("currency").jsonPrimitive.content)
    val confirmed=after.getValue("platformConfirmed").jsonPrimitive.boolean
    val unknown=word("未知","不明")
    fun value(v:JsonElement?)=v?.jsonPrimitive?.longOrNull?.let{Money(debtCurrency,it).display()}?:unknown
    Column(Modifier.testTag("command-repayment-${step.stepId}"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        RecordFacts(listOf(word("实际付款账户","実際の支払口座") to resolveName(entry.getValue("accountId").jsonPrimitive.content,s,model),
            word("实际扣款","実出金額") to Money(currency,entry.getValue("amountMinor").jsonPrimitive.long).display()))
        YText(if(confirmed)word("平台已确认清偿","返済先が返済を確定")else word("实际已付款 · 清偿待确认","支払済み・返済確定待ち"),12,color=LocalYohaku.current.accent)
        step.objects.filter{it.type=="balance"&&it.before!=it.after}.forEach{EffectSummary(it,s,model)}
        YText(word("分配目标","充当先"),13)
        val targets=step.objects.filter{it.type=="repaymentAllocation"&&(it.after as? JsonObject)?.get("eventId")==JsonPrimitive(payment.key)}
        if(targets.isEmpty())YText(word("未分配到具体账单或合同","個別の請求・契約に未充当"),12)
        targets.forEach{effect->
            val a=effect.after.jsonObject
            YText(listOfNotNull(a["billId"]?.jsonPrimitive?.contentOrNull,a["contractId"]?.jsonPrimitive?.contentOrNull).joinToString(" · "){resolveName(it,s,model)},12)
            YText(word("分配本金 / 利息 / 费用","充当元金・利息・手数料"),11,color=LocalYohaku.current.muted)
            YText(listOf("principalMinor","interestMinor","feeMinor").joinToString(" / "){value(a[it])},12)
        }
        RecordFacts(listOf(word("平台实收","返済先受取額") to value(after["receivedMinor"]),word("清偿本金","返済元金") to value(after["principalMinor"])))
        YText(if(confirmed)word("仍需在下方确认已核实的平台事实。","下で確認済みの返済先の事実をチェックしてください。")else word("仅记录实际付款，不提前结清债务。","実支払のみを記録し、債務を精算済みとしません。"),11)
    }
}

/** Only the command's captured before/after facts are used, including earlier steps in this batch. */
@Composable internal fun CommandFinancialChanges(step:StepEffect,s:BookSnapshot,model:ConfirmationModel) {
    val effects=step.objects
    effects.filter{it.type=="revolvingBatchArchive"}.forEach{effect->
        val evidence=effect.before.jsonObject
        val decoder=Json { ignoreUnknownKeys=true }
        FinancialBatchSummary(decoder.decodeFromJsonElement(com.yohaku.expensetracker.data.repository.BatchRestorationFacts.serializer(),evidence))
        YText(word("这是撤销一次批量转换记录；整份云端方案的撤销是另一个操作。","これは一回の一括変更記録の取消です。クラウドプラン全体の取消とは別の操作です。"),12)
    }
    effects.filter{it.before!=it.after&&it.after is JsonObject&&it.type in setOf("arrangement","poolPaymentChange","creditLimitObservation")}.forEach{effect->
        val row=effect.after.jsonObject
        val observed=row["observedAt"]?.jsonPrimitive?.contentOrNull
        val raw=observed?:row["appliedAt"]?.jsonPrimitive?.contentOrNull
        if(raw!=null)FinancialTime(java.time.Instant.parse(raw),if(observed!=null)java.time.OffsetDateTime.parse(raw).offset else commandFinancialZone(row,effects)?:java.time.OffsetDateTime.parse(raw).offset,"command-${effect.key}")
    }
    val rules=if(step.command=="financialRule.addVersion")effects.filter{it.type=="rule"}else emptyList()
    rules.filter{it.before!=it.after&&it.after is JsonObject}.forEach{effect->
        val after=effect.after.jsonObject
        val account=after["accountId"]
        val mode=after["mode"]
        val previous=(effect.before as? JsonObject)?:rules.mapNotNull{it.before as? JsonObject}
            .filter{it["accountId"]==account&&it["mode"]==mode}
            .maxByOrNull{it["version"]!!.jsonPrimitive.int}
        val definition=after["json"] as? JsonObject
        if(definition!=null) {
            Column(Modifier.testTag("command-finance-rule-diff")) {
                com.yohaku.expensetracker.ui.features.FinancialRuleChangeSummary(
                    com.yohaku.expensetracker.ui.features.FinancialDisplayFacts.rule(previous?.get("json") as? JsonObject,definition),s,
                    Currency.valueOf(effects.firstOrNull{it.type=="account"&&it.key==account?.jsonPrimitive?.contentOrNull}?.let{(it.after as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull}?:s.book.currency),
                    referenceName={stepFinanceName(it,step,s.book.language)})
                YText(word("新版本","新バージョン")+" · "+after["version"]?.jsonPrimitive?.content.orEmpty(),12)
            }
        }
    }
    effects.filter{it.type=="prediction"&&it.after is JsonObject}.forEach{effect->
        val after=effect.after.jsonObject;val before=effect.before as? JsonObject
        val retainedBefore=FinancialDisplayFacts.poolRetainedBillIds(before)
        val retainedAfter=FinancialDisplayFacts.poolRetainedBillIds(after)
        fun belongs(row:JsonObject,retained:Set<String>)=row["planId"]==JsonPrimitive(effect.key)||row["id"]?.jsonPrimitive?.contentOrNull in retained
        val bills=effects.filter{it.type=="bill"&&((it.before as? JsonObject)?.let{v->belongs(v,retainedBefore)}==true||(it.after as? JsonObject)?.let{v->belongs(v,retainedAfter)}==true)}
        if(effect.before!=effect.after||bills.any{it.before!=it.after}) {
            val oldBills=bills.mapNotNull{it.before as? JsonObject}.filter{belongs(it,retainedBefore)}
            val newBills=bills.mapNotNull{it.after as? JsonObject}.filter{belongs(it,retainedAfter)}
            val currency=Currency.valueOf(after.getValue("currency").jsonPrimitive.content)
            Column(Modifier.testTag("command-finance-plan-${effect.key}"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                FinancialPlanChangeSummary(after.getValue("name").jsonPrimitive.content,FinancialDisplayFacts.plan(before,after,oldBills,newBills),currency)
                val oldPool=(before?.get("termsJson") as? JsonObject)?.get("pool") as? JsonObject
                val pool=(after["termsJson"] as? JsonObject)?.get("pool") as? JsonObject
                if(pool!=null) {
                    FinancialFieldChanges(FinancialDisplayFacts.fields(oldPool,pool,FinancialDisplayFacts.poolFields),currency,first=oldPool==null)
                    if(pool["facts"] is JsonObject)FinancialPoolScopeFacts(FinancialDisplayFacts.poolScope(pool,oldPool),currency)
                }
                YText(word("已列账单金额与实际待付分别核对；完整前后值保留在逐项详情。","記載済み請求額と実際の未払額は別に確認します。変更前後の全項目は詳細で確認できます。"),11)
            }
        }
    }
    effects.filter{it.type=="creditLimitConfig"&&it.before!=it.after&&it.after is JsonObject}.forEach{effect->
        val after=effect.after.jsonObject
        val capturedLimit=effects.firstOrNull{it.type=="creditLimit"&&JsonPrimitive(it.key)==after["accountId"]}?.before as? JsonObject
        val before=(effect.before as? JsonObject)?:effects.filter{it.type=="creditLimitConfig"}.mapNotNull{it.before as? JsonObject}
            .filter{it["accountId"]==after["accountId"]}.maxByOrNull{it.getValue("version").jsonPrimitive.int}
            ?:(capturedLimit?.get("config") as? JsonObject)
        val changes=FinancialDisplayFacts.limitChanges(before,after)
        FinancialLimitRelationship(FinancialDisplayFacts.limitRelationship(after),Currency.valueOf(after.getValue("currency").jsonPrimitive.content),omitCaps=changes.map{it.fieldKey}.toSet())
        FinancialChangeSummary {
            FinancialFieldChanges(changes,Currency.valueOf(after.getValue("currency").jsonPrimitive.content),first=before==null)
        }
    }
    effects.filter{it.type=="creditLimitGroupSetting"&&it.before!=it.after&&it.after is JsonObject}.forEach{effect->
        val after=effect.after.jsonObject;val before=effect.before as? JsonObject
        FinancialChangeSummary { FinancialFieldChanges(FinancialDisplayFacts.fields(before,after,FinancialDisplayFacts.limitGroupFields),Currency.valueOf(after.getValue("currency").jsonPrimitive.content),first=before==null) }
    }
    effects.filter{it.type=="arrangementReconciliation"&&it.before!=it.after&&it.after is JsonObject}.forEach{effect->
        val after=effect.after.jsonObject
        val before=(effect.before as? JsonObject)?:effects.filter{it.type=="arrangementReconciliation"}.mapNotNull{it.before as? JsonObject}.firstOrNull{it["planId"]==after["planId"]}
        val input=after.getValue("input").jsonObject;val old=before?.get("input") as? JsonObject
        val plan=effects.first{it.type=="prediction"&&JsonPrimitive(it.key)==after["planId"]}.after.jsonObject
        val loanAccount=effects.first{it.type=="account"&&JsonPrimitive(it.key)==plan["accountId"]}.after.jsonObject["type"]==JsonPrimitive("LOAN")
        FinancialChangeSummary {
            FinancialFieldChanges(FinancialDisplayFacts.reconciliation(old,input,loanAccount),Currency.valueOf(after.getValue("currency").jsonPrimitive.content),first=old==null)
            YText(word("保留原办理记录、原账单及实际付款；记录当前核对的义务与清偿归属。","元の手続き・請求・実支払を保持し、現在確認した債務・返済の帰属を記録します。"),11)
        }
    }
}

/** Detailed comparison follows the step's summary and visible blockers. */
@Composable internal fun CommandFinancialComparison(step:StepEffect,s:BookSnapshot,model:ConfirmationModel) {
    val effects=step.objects
    effects.filter{it.before!=it.after&&it.type in setOf("arrangement","poolPaymentChange")}.forEach{effect->
        val after=effect.after as? JsonObject
        val archived=(after?.get("comparisonJson")?:after?.get("comparison")) as? JsonObject
        if(archived!=null) {
            val comparison=Json { ignoreUnknownKeys=true }.decodeFromJsonElement(com.yohaku.expensetracker.data.repository.FinancialArrangementComparison.serializer(),archived)
            val currency=Currency.valueOf(after?.get("currency")?.jsonPrimitive?.contentOrNull?:s.book.currency)
            Column(Modifier.testTag("command-finance-comparison")) {
                com.yohaku.expensetracker.ui.features.FinancialDateComparison(comparison,currency,s,accountName={stepFinanceName(it,step,s.book.language)})
            }
        }
    }
}

/** Names, too, belong to this step; later steps/current data must not replace the baseline. */
private fun stepFinanceName(id:String,step:StepEffect,language:String):String {
    val effect=step.objects.firstOrNull{it.key==id}
    val row=(effect?.after as? JsonObject)?:effect?.before as? JsonObject
    row?.get("name")?.jsonPrimitive?.contentOrNull?.let{return it}
    if(effect?.type=="bill"&&row!=null)return listOfNotNull(row["number"]?.jsonPrimitive?.contentOrNull,row["confirmedDate"]?.jsonPrimitive?.contentOrNull?:row["predictedDate"]?.jsonPrimitive?.contentOrNull).joinToString(" · ")
    return if(language=="ja")"元の情報を要確認"else"原资料待核查"
}


/** Uses this step's captured rule versions, never today's database configuration. */
internal fun commandFinancialZone(row:JsonObject,effects:List<ObjectEffect>,after:Boolean=true):java.time.ZoneId? {
    fun text(v:JsonObject?,key:String)=(v?.get(key) as? JsonPrimitive)?.contentOrNull
    text(row,"zoneId")?.let{return java.time.ZoneId.of(it)}
    val input=row["inputJson"] as? JsonObject
    val planId=text(input,"mergePoolId")?:text(row,"planId")?:text(row,"targetPlanId")
    fun value(effect:ObjectEffect)=(if(after)effect.after else effect.before) as? JsonObject
    val plan=effects.firstOrNull{it.type=="prediction"&&it.key==planId}?.let(::value)?:row.takeIf{it["termsJson"] is JsonObject}
    val date=text(row,"effectiveDate")?:text(input?.get("terms") as? JsonObject,"agreementDate")
        ?:text((plan?.get("termsJson") as? JsonObject)?.get("terms") as? JsonObject,"agreementDate")?:"9999-12-31"
    val mode=text(input,"mode")?:text(plan,"mode")
    val accountId=text(plan,"accountId")?:text(row,"accountId")
    val stored=(((plan?.get("termsJson") as? JsonObject)?.get("pool") as? JsonObject)?.get("facts") as? JsonObject)?.get("rules") as? JsonArray
    val rules=stored?.map{it.jsonObject}?:effects.filter{it.type=="rule"}.mapNotNull(::value).filter{accountId!=null&&text(it,"accountId")==accountId}
    val rule=rules.filter{text(it,"mode")==mode&&(text(it,"effectiveFrom")?:"9999-12-31")<=date}
        .maxWithOrNull(compareBy<JsonObject>{text(it,"effectiveFrom")}.thenBy{it["version"]?.jsonPrimitive?.intOrNull?:0})
    return (rule?.get("json") as? JsonObject)?.let{text(it,"zoneId")}?.let(java.time.ZoneId::of)
}

/** A nested historical payment change uses its own effective date, not the outer plan's latest date. */
internal fun commandFieldZone(root:JsonObject,path:String,effects:List<ObjectEffect>,after:Boolean):java.time.ZoneId? {
    var value:JsonElement=root
    val parents=mutableListOf(root)
    for(part in path.split('.').dropLast(1)) {
        value=when(val current=value){is JsonObject->current[part];is JsonArray->part.toIntOrNull()?.let{current.getOrNull(it)};else->null}?:break
        if(value is JsonObject)parents+=value
    }
    val operation=parents.lastOrNull{it.containsKey("appliedAt")}?:root
    val plan=parents.lastOrNull{it.containsKey("termsJson")}
    val scoped=if(plan!=null)JsonObject(plan+operation+mapOf("planId" to plan.getValue("id")))else operation
    return commandFinancialZone(scoped,effects,after)
}

@Composable internal fun commandRequirementText(requirement:ConfirmationRequirement,model:ConfirmationModel):String {
    val language=com.yohaku.expensetracker.ui.theme.LocalLanguage.current
    val text=if(language=="ja")requirement.ja else requirement.zh
    val step=model.steps.firstOrNull{it.stepId==requirement.stepId}?:return text
    if(!step.command.startsWith("financial")&&!step.command.startsWith("creditLimit"))return text
    val effects=step.objects
    return Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})").replace(text){match->
        val raw=match.value
        val row=effects.mapNotNull{it.after as? JsonObject}.firstOrNull{it["appliedAt"]==JsonPrimitive(raw)||it["observedAt"]==JsonPrimitive(raw)}
        val zone=if(row?.get("observedAt")!=null)java.time.OffsetDateTime.parse(raw).offset else row?.let{commandFinancialZone(it,effects)}?:java.time.OffsetDateTime.parse(raw).offset
        FinancialDisplayFacts.time(java.time.Instant.parse(raw),zone,language)
    }
}

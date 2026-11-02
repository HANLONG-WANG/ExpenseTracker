package com.yohaku.expensetracker.ui.features.command

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.financeLabel
import com.yohaku.expensetracker.ui.features.cashIssue
import com.yohaku.expensetracker.ui.features.limitLabel
import com.yohaku.expensetracker.ui.features.RecordFacts
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.json.*

@Composable internal fun StepSummary(step:StepEffect,s:BookSnapshot,model:ConfirmationModel) {
    val changed=step.objects.filter{it.before!=it.after}
    if(step.command=="entry.update") {
        val entries=changed.filter{it.type=="entry"}
        val categories=entries.mapNotNull{(it.after as? JsonObject)?.get("categoryId")?.jsonPrimitive?.content}.distinct()
        YText(word("${entries.size} 笔记录 → ","${entries.size} 件 → ")+categories.joinToString{resolveName(it,s,model)},15)
        YText(word("保留金额、结算、付款方式和附件。分类预算会重新归属。","金額・決済・支払方法・添付は維持し、カテゴリ予算の集計先が変わります。"),11)
    }else changed.filter{it.type in setOf("account","rule","prediction","bill")}.take(3).forEach{EffectSummary(it,s,model)}
}

@Composable internal fun EffectSummary(effect:ObjectEffect,s:BookSnapshot,model:ConfirmationModel) {
    val before=effect.before as? JsonObject;val after=effect.after as? JsonObject
    val title=effectTitle(effect,s,model)
    val currency=(after?.get("currency")?:before?.get("currency"))?.jsonPrimitive?.contentOrNull?:s.book.currency
    fun amount(v:JsonElement?)=v?.jsonPrimitive?.longOrNull?.let{Money(Currency.valueOf(currency),it).display()}
    val none=word("无","なし");val unknown=word("未知","不明");val confirmedLabel=word("已确认","確定");val predictedLabel=word("预测","予測");val unset=word("未设定","未設定")
    val text=when(effect.type) {
        "balance"->(amount(before?.get("ledgerMinor"))?:word("无","なし"))+" → "+(amount(after?.get("ledgerMinor"))?:word("未知","不明"))
        "bill"->{
            fun bill(v:JsonObject?):String=if(v==null)none else {
                val confirmed=v["confirmed"]?.jsonPrimitive?.booleanOrNull==true
                val date=v[if(confirmed)"confirmedDate"else"predictedDate"]?.jsonPrimitive?.contentOrNull.orEmpty()
                date+" · "+(amount(v[if(confirmed)"confirmedTotalMinor"else"predictedTotalMinor"])?:unknown)+" · "+if(confirmed)confirmedLabel else predictedLabel
            }
            bill(before)+" → "+bill(after)
        }
        "budget"->{
            fun remaining(v:JsonObject?)=amount(v?.get("remainingMinor"))?:unset
            word("剩余 ","残り ")+remaining(before)+" → "+remaining(after)
        }
        "creditLimit"->{
            fun available(v:JsonObject?)=amount((v?.get("total") as? JsonObject)?.get("availableMinor"))?:unknown
            word("可用 ","利用可能 ")+available(before)+" → "+available(after)
        }
        "account"->(before?.get("name")?.jsonPrimitive?.contentOrNull?:word("新建","新規"))+" → "+after?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        "rule"->word("新增版本 ","新しいバージョン ")+(after?.get("version")?.jsonPrimitive?.content.orEmpty())
        "prediction"->after?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()+" · "+if(after?.get("complete")?.jsonPrimitive?.booleanOrNull==true)word("完整预测","全期間の予測")else word("部分预测","一部の予測")
        else->word("查看逐项前后值","各項目の変更前後を見る")
    }
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)){YText(title,12,color=LocalYohaku.current.muted);YText(text,14,number=effect.type in setOf("balance","bill","budget","creditLimit"))}
}

@Composable internal fun CommandEffectsSheet(effects:List<ObjectEffect>,s:BookSnapshot,model:ConfirmationModel,onClose:()->Unit) {
    var page by remember{mutableIntStateOf(0)}
    val pages=(effects.size+11)/12
    YSheet(word("逐项核对","項目ごとの確認"),onClose) {
        YText(word("显示绝对值；左侧为操作前，右侧为操作后。未变化的相关值也参与确认。","絶対値で表示します。左は変更前、右は変更後。変わらない関連値も確定時に照合します。"),11)
        effects.drop(page*12).take(12).forEach{effect->Panel {
            YText(effectTitle(effect,s,model),17)
            val before=flattenFacts(effect.before);val after=flattenFacts(effect.after)
            val fields=(before.keys+after.keys).toList()
            if(fields.isEmpty())YText(word("没有明细","明細なし"),12)
            for(path in fields) {
                val old=before[path];val new=after[path]
                val baseCurrency=(effect.after as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull
                    ?:(effect.before as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull
                    ?:s.accounts.firstOrNull{it.id==effect.key}?.currency?:s.book.currency
                val leaf=path.substringAfterLast('.')
                val key=if(effect.type=="saveOrder")"eventId"else if(leaf.toIntOrNull()!=null)path.substringBeforeLast('.').substringAfterLast('.')else leaf
                val label=path.split('.').map{part->fieldName(part)}.joinToString(" / ")
                val a=fieldValue(old,key,baseCurrency,s,model);val b=fieldValue(new,key,baseCurrency,s,model)
                RecordFacts(listOf(label to "$a → $b"),audit=true)
            }
        }}
        if(pages>1)YActionRow {
            YButton(word("上一页","前へ"),{page--},enabled=page>0)
            YText("${page+1} / $pages",12,number=true)
            YButton(word("下一页","次へ"),{page++},enabled=page+1<pages)
        }
    }
}

private fun flattenFacts(value:JsonElement,prefix:String="",currency:String?=null):Map<String,Pair<JsonElement,String?>> = buildMap {
    if(prefix.isEmpty()&&value==JsonNull)return@buildMap
    when(value) {
        is JsonObject->{
            val unit=value["currency"]?.jsonPrimitive?.contentOrNull?:value["accountCurrency"]?.jsonPrimitive?.contentOrNull?:currency
            value.filterKeys{it !in setOf("id","bookId","createdAt","explanation")}.forEach{(key,v)->putAll(flattenFacts(v,if(prefix.isEmpty())key else "$prefix.$key",if(key=="accountDeltaMinor")value["accountCurrency"]?.jsonPrimitive?.contentOrNull?:unit else unit))}
        }
        is JsonArray->if(value.isEmpty())put(prefix,JsonPrimitive("EMPTY") to currency)else value.forEachIndexed{i,v->putAll(flattenFacts(v,if(prefix.isEmpty())"${i+1}"else"$prefix.${i+1}",currency))}
        else->put(prefix.ifEmpty{"value"},value to currency)
    }
}
private fun resolveName(id:String,s:BookSnapshot,model:ConfirmationModel):String {
    fun label(zh:String,ja:String)=if(s.book.language=="ja")ja else zh
    val current=s.accounts.firstOrNull{it.id==id}?.name?:s.categories.firstOrNull{it.id==id}?.let{s.path(it.id)}
        ?:s.events.firstOrNull{it.id==id}?.let{"${it.date} ${it.merchant}"}
        ?:s.recurrences.firstOrNull{it.id==id}?.name?:s.reserves.firstOrNull{it.id==id}?.name
        ?:s.expectedIncomes.firstOrNull{it.id==id}?.name?:s.files.firstOrNull{it.id==id}?.originalName
    if(current!=null)return current
    return model.steps.flatMap{it.objects}.firstNotNullOfOrNull{effect->
        if(effect.key!=id)null else {
            val value=(effect.after as? JsonObject)?:effect.before as? JsonObject
            value?.get("name")?.jsonPrimitive?.contentOrNull?:(value?.get("group") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?:when(effect.type) {
                "rule"->value?.let{label("规则版本","条件バージョン")+" "+it["version"]?.jsonPrimitive?.content.orEmpty()+" · "+it["effectiveFrom"]?.jsonPrimitive?.content.orEmpty()}
                "bill"->value?.let{label("第","第")+it["number"]?.jsonPrimitive?.content.orEmpty()+label("期","回")+" · "+(it["confirmedDate"]?.jsonPrimitive?.contentOrNull?:it["predictedDate"]?.jsonPrimitive?.contentOrNull).orEmpty()}
                else->null
            }
        }
    }?:id
}
@Composable private fun fieldValue(value:Pair<JsonElement,String?>?,key:String,currency:String,s:BookSnapshot,model:ConfirmationModel):String {
    if(value==null)return word("无此项","項目なし")
    val raw=value.first
    if(raw==JsonNull)return word("未知 / 未设定","不明・未設定")
    val text=(raw as? JsonPrimitive)?.content?:raw.toString()
    if((key.endsWith("Minor")||key=="minor")&&raw.jsonPrimitive.longOrNull!=null)return Money(Currency.valueOf(if(key in setOf("baseMinor","expectedBaseMinor"))s.book.currency else value.second?:currency),raw.jsonPrimitive.long).display()
    if(key.endsWith("Id")||key.endsWith("Ids")||key in setOf("account","category","scope"))return resolveName(text,s,model)
    if(text.startsWith("limit."))return limitLabel(text)
    if(key=="issue")return cashIssue(text)
    if(key=="cardPaymentChoice")return when(text){"BONUS"->word("奖金月付款","ボーナス払い");"DEFERRED"->word("指定月付款","支払月を指定");"ACCOUNT_RULE"->word("按账户规则","口座の条件に従う");else->financeLabel(text)}
    return when(text){"true"->word("是","はい");"false"->word("否","いいえ");"EMPTY"->word("无","なし");"OPENING"->word("期初欠款","期首残高");"EVENT"->word("记录来源","記録が対象");"EXPENSE"->word("支出","支出");"INCOME"->word("收入","収入");"TRANSFER"->word("转账","振替");"REFUND"->word("退款","返金");"REPAYMENT"->word("还款","返済");"BORROWING"->word("借入","借入");"BILL"->word("账单","請求");"POSTING"->word("入账","計上");"RECURRENCE"->word("周期予定","定期予定");"RESERVE"->word("预留","確保");"EXPECTED_INCOME"->word("预计收入","入金予定");else->when {
        key in setOf("mode","method","loanMethod","frequency","balanceBasis","rounding","roundingMode","firstCharge","allocation","tierBalanceDate","feeMethod","principalRemainder","feeRemainder","loanPrincipalRemainder","cardPaymentMode") -> financeLabel(text)
        key in setOf("sharing","recovery","usageBasis","usageStart") -> limitLabel(text)
        key=="type"&&text in setOf("CASH","BANK","CREDIT","LOAN","IC") -> com.yohaku.expensetracker.ui.features.accountTypeName(com.yohaku.expensetracker.domain.model.AccountType.valueOf(text))
        else->text
    }}
}
@Composable private fun effectTitle(e:ObjectEffect,s:BookSnapshot,model:ConfirmationModel):String {
    val kind=when(e.type){"account"->word("账户","口座");"balance"->word("余额 / 欠款","残高・残債");"entry"->word("记录","記録");"entryDetails"->word("结算与关联资料","決済・関連情報");"entryCategory","category"->word("分类","カテゴリ");"budget","budgetForecast"->word("预算与预测","予算・予測");"rule"->word("金融规则","金融条件");"prediction"->word("预测计划","予測プラン");"predictionFacts"->word("预测依据","予測の根拠");"bill"->word("逐期付款","各回の支払");"cashForecast"->word("资金预测","資金予測");"creditLimit","creditLimitGroup"->word("信用额度","利用枠");"saveOrder","entryDefaults"->word("保存顺序 / 默认值依据","保存順・初期値の根拠");"unknownRuleFields"->word("规则缺项","条件の不足項目");else->word("连带影响","関連する影響")}
    val name=resolveName(e.key,s,model)
    // Opaque row IDs are available in the protocol, not useful as UI headings.
    return if(name!=e.key)"$kind · $name"else kind
}

@Composable private fun fieldName(key:String):String {
    if(key.toIntOrNull()!=null)return word("第 $key 项","$key 件目")
    val names=mapOf(
        "value" to ("内容" to "内容"),"name" to ("名称" to "名前"),"type" to ("类型" to "種類"),"kind" to ("种类" to "種類"),"currency" to ("币种" to "通貨"),"openingMinor" to ("期初余额 / 欠款" to "期首残高・残債"),"openingDate" to ("期初日期" to "期首日"),"active" to ("启用" to "有効"),
        "date" to ("日期" to "日付"),"amountMinor" to ("金额" to "金額"),"baseMinor" to ("账本币金额" to "基準通貨額"),"accountId" to ("账户" to "口座"),"categoryId" to ("分类" to "カテゴリ"),"merchant" to ("商户" to "お店"),"note" to ("备注" to "メモ"),"historicalIncluded" to ("包含于期初" to "期首に含む"),"deletedAt" to ("回收站时间" to "削除日時"),"purgedAt" to ("清理时间" to "消去日時"),"originalEventId" to ("原记录" to "元の記録"),"eventId" to ("记录" to "記録"),
        "accountMinor" to ("入账金额" to "計上額"),"accountCurrency" to ("账户币种" to "口座通貨"),"confirmed" to ("已确认" to "確定済み"),"settlementDate" to ("入账日" to "計上日"),"rateSource" to ("汇率来源" to "レートの出典"),"rateDate" to ("汇率日期" to "レートの日付"),"expectedAccountMinor" to ("预计入账" to "計上見込"),"expectedBaseMinor" to ("预计账本币金额" to "基準通貨の見込額"),"manualRate" to ("手填汇率" to "手入力レート"),"expectedAccountCurrency" to ("预计账户币种" to "見込口座通貨"),"referenceRatesJson" to ("参考汇率" to "参考レート"),
        "cardPaymentMode" to ("付款模式" to "支払方式"),"cardPaymentChoice" to ("付款选择" to "支払の選択"),"cardPaymentPeriods" to ("分期期数" to "分割回数"),"cardPaymentMonth" to ("指定付款月" to "指定支払月"),"cardPaymentFeeMinor" to ("付款费用" to "支払手数料"),"cardPaymentRuleId" to ("付款规则" to "支払条件"),
        "ledgerMinor" to ("已确认余额" to "確定残高"),"asOfMinor" to ("基准日余额" to "基準日残高"),"debt" to ("债务账户" to "借入口座"),"negative" to ("负余额" to "残高不足"),"parentId" to ("一级分类" to "親カテゴリ"),"favorite" to ("收藏" to "お気に入り"),"icon" to ("图标" to "アイコン"),
        "scope" to ("范围" to "範囲"),"projection" to ("预算计算" to "予算計算"),"month" to ("月份" to "月"),"limitMinor" to ("预算上限" to "予算上限"),"spentMinor" to ("已用" to "使用済み"),"allExpenseMinor" to ("全部支出" to "全支出"),"independentMinor" to ("独立活动支出" to "独立シーン支出"),"reserveMinor" to ("预留" to "確保額"),"pendingCount" to ("待处理数量" to "未処理件数"),"fixedReserveMinor" to ("周期预留" to "定期確保額"),"activityReserveMinor" to ("活动预留" to "シーン確保額"),"fundReserveMinor" to ("资金预留" to "資金確保額"),"remainingMinor" to ("剩余" to "残り"),"availableMinor" to ("可用" to "利用可能"),"overBudget" to ("超预算" to "予算超過"),"overAvailable" to ("可用不足" to "利用可能額不足"),
        "config" to ("额度配置" to "利用枠設定"),"total" to ("总额度" to "総枠"),"installment" to ("分期" to "分割"),"revolving" to ("循环还款" to "リボ"),"capacityMinor" to ("上限" to "上限"),"usedMinor" to ("占用" to "使用額"),"excessMinor" to ("超额" to "超過額"),"issues" to ("待核对" to "要確認"),"effectiveTotalMinor" to ("有效总额度" to "実効総枠"),"groupId" to ("共享额度组" to "共通枠"),"version" to ("版本" to "バージョン"),"effectiveFrom" to ("生效日期" to "適用日"),"totalMinor" to ("合计" to "合計"),"installmentMinor" to ("分期额度" to "分割枠"),"revolvingMinor" to ("循环额度" to "リボ枠"),"sharing" to ("额度共用方式" to "枠の共有方式"),"combinedMinor" to ("共用子额度" to "共有の内枠"),"basisDate" to ("占用基准日" to "使用額基準日"),"usedTotalMinor" to ("总占用" to "総使用額"),"usedInstallmentMinor" to ("分期占用" to "分割使用額"),"usedRevolvingMinor" to ("循环占用" to "リボ使用額"),"recovery" to ("额度恢复方式" to "枠の回復方式"),"recoveryDelayDays" to ("恢复延迟天数" to "回復までの日数"),"usageBasis" to ("占用口径" to "使用額の基準"),"usageStart" to ("占用起点" to "使用開始"),"zoneId" to ("时区" to "タイムゾーン"),
        "mode" to ("模式" to "方式"),"json" to ("规则内容" to "条件内容"),"billing" to ("账期" to "請求期間"),"closingDay" to ("结账日" to "締日"),"closingDayInCurrentBill" to ("结账日计入本期" to "締日を当期に含む"),"paymentMonthOffset" to ("付款月份偏移" to "支払月の繰越"),"paymentDay" to ("付款日" to "支払日"),"paymentAccountId" to ("付款账户" to "引落口座"),"bonusPayments" to ("奖金月付款" to "ボーナス払い"),"startMonth" to ("开始月份" to "開始月"),"startDay" to ("开始日" to "開始日"),"endMonth" to ("结束月份" to "終了月"),"endDay" to ("结束日" to "終了日"),"paymentMonth" to ("付款月份" to "支払月"),
        "interest" to ("计息" to "利息計算"),"annualRate" to ("年率（小数）" to "年利率（小数）"),"frequency" to ("频率" to "頻度"),"balanceBasis" to ("余额依据" to "残高の基準"),"includeStart" to ("包含开始日" to "開始日を含む"),"includeEnd" to ("包含结束日" to "終了日を含む"),"yearDays" to ("年度天数" to "年間日数"),"rounding" to ("取整时点" to "端数処理の時点"),"roundingMode" to ("取整方向" to "端数処理の方向"),"firstCharge" to ("首次计费" to "初回手数料"),"method" to ("还款方式" to "返済方式"),"paymentMinor" to ("月设定金额" to "月設定額"),"tiers" to ("阶梯" to "残高別コース"),"upToMinor" to ("余额上限" to "残高上限"),"tierPaymentIncludesFees" to ("阶梯金额含费用" to "コース額に手数料を含む"),"allocation" to ("分配顺序" to "充当順序"),"automatic" to ("自动循环" to "自動リボ"),"tierBalanceDate" to ("阶梯余额判定日" to "残高コース判定日"),
        "supportedPeriods" to ("支持期数" to "対応回数"),"feeMethod" to ("费用算法" to "手数料の計算方式"),"totalFeeMinor" to ("总费用" to "総手数料"),"totalRate" to ("总费率（小数）" to "総料率（小数）"),"feeTable" to ("费用表" to "手数料表"),"periods" to ("期数" to "回数"),"feePerHundred" to ("每100单位费用" to "100通貨単位当たり手数料"),"principalRemainder" to ("本金尾差" to "元金端数"),"feeRemainder" to ("费用尾差" to "手数料端数"),"loanMethod" to ("贷款算法" to "ローン計算方式"),"loanInterest" to ("贷款计息" to "ローン利息"),"loanPrincipalRemainder" to ("贷款本金尾差" to "ローン元金端数"),"limitRecovery" to ("历史恢复规则" to "旧回復条件"),"unsupportedReason" to ("不适用原因" to "未対応の理由"),"deadlines" to ("办理期限" to "手続き期限"),
        "planId" to ("计划" to "プラン"),"sourceEventId" to ("来源记录" to "対象記録"),"origin" to ("来源" to "対象"),"principalMinor" to ("本金" to "元金"),"feeMinor" to ("费用" to "手数料"),"complete" to ("完整预测" to "全期間の予測"),"termsJson" to ("预测条件" to "予測条件"),"reconciliationJson" to ("核对依据" to "照合の根拠"),"terms" to ("日期与金额条件" to "日付・金額の条件"),"agreementDate" to ("合同 / 期初基准日" to "契約・期首基準日"),"paymentDates" to ("付款日期" to "支払日"),"accrualWindows" to ("计息区间" to "計算期間"),"start" to ("开始" to "開始"),"end" to ("截止" to "終了"),"overrides" to ("平台逐期金额" to "返済先の各回金額"),"firstPeriodNumber" to ("合同起始期数" to "契約の開始回数"),"remainingFeeMinor" to ("剩余总费用" to "残手数料"),"currentArrangementConfirmed" to ("本机事实确认要求" to "端末での事実確認要件"),
        "number" to ("期数" to "回数"),"ruleVersionId" to ("规则版本" to "条件バージョン"),"predictedDate" to ("预测日期" to "予測日"),"predictedPrincipalMinor" to ("预测本金" to "予測元金"),"predictedFeeMinor" to ("预测费用" to "予測手数料"),"predictedTotalMinor" to ("预测合计" to "予測合計"),"confirmedDate" to ("确认日期" to "確定日"),"confirmedPrincipalMinor" to ("确认本金" to "確定元金"),"confirmedFeeMinor" to ("确认费用" to "確定手数料"),"confirmedTotalMinor" to ("确认合计" to "確定合計"),"basisJson" to ("计算依据" to "計算根拠"),"basis" to ("依据" to "根拠"),"facts" to ("实际事实" to "実際の事実"),"projections" to ("逐期预测" to "各回の予測"),"asOf" to ("基准日期" to "基準日"),"retainedBills" to ("保留账期" to "保持する請求"),"repayments" to ("实际还款" to "実返済"),"refunds" to ("退款" to "返金"),"revision" to ("事实修订" to "事実の改訂"),
        "item" to ("资金事项" to "資金項目"),"sourceId" to ("来源" to "対象"),"incoming" to ("流入" to "入金"),"accountDeltaMinor" to ("账户变动" to "口座の変動"),"estimated" to ("估算" to "見込"),"issue" to ("待核对" to "要確認"),"scheduledDate" to ("计划日期" to "予定日"),"referenceRate" to ("参考汇率" to "参考レート"),"baselineAfterMinor" to ("付款后基准余额" to "支払後の基準残高"),"includingIncomeAfterMinor" to ("包含预计收入后余额" to "入金見込を含む残高"),"baselineEstimated" to ("基准余额含估算" to "基準残高に見込を含む"),"includingEstimated" to ("合计余额含估算" to "合計残高に見込を含む")
    )
    return names[key]?.let{word(it.first,it.second)}?:word("补充依据","補足の根拠")
}

@Composable internal fun CommandRiskNotices(risks:List<CommandRisk>,s:BookSnapshot,model:ConfirmationModel) {
    for(risk in risks) {
        val subject=when {
            risk.code.startsWith("BUDGET")->risk.subject.substringBefore(':')+risk.subject.substringAfter(':',"").takeIf{it.isNotEmpty()}?.let{" · "+resolveName(it,s,model)}.orEmpty()
            risk.code=="RULE_INCOMPLETE"->model.steps.firstOrNull{it.stepId==risk.subject}?.targets?.joinToString{resolveName(it,s,model)}.orEmpty()
            else->resolveName(risk.subject,s,model)
        }
        val amount=if(risk.currency!=null&&risk.minor!=null)Money(Currency.valueOf(risk.currency),risk.minor).display()else ""
        val text=when(risk.code) {
            "NEGATIVE_BALANCE"->word("账户「$subject」余额为 $amount。","口座「$subject」の残高は $amount です。")
            "LIMIT_EXCESS"->{
                val scope=when(risk.detail){"installment"->word("分期额度","分割枠");"revolving"->word("循环额度","リボ枠");else->word("总额度","総枠")}
                word("「$subject」的${scope}预计超出 $amount。","「$subject」の${scope}は $amount の超過見込みです。")
            }
            "LIMIT_ISSUE"->"$subject · "+limitLabel(risk.detail)
            "BUDGET_EXCESS"->word("$subject 预算超出 $amount。","$subject の予算を $amount 超過します。")
            "BUDGET_FORECAST_EXCESS"->word("$subject 预算预计超出 $amount。","$subject の予算は $amount の超過見込みです。")
            "CASH_SHORTAGE"->word("账户「$subject」在 ${risk.date} 的预测余额为 $amount。","口座「$subject」の ${risk.date} の予測残高は $amount です。")
            "CASH_ISSUE"->"$subject · ${risk.date.orEmpty()} · "+cashIssue(risk.detail.orEmpty())
            else->"$subject · "+commandIssue(risk.detail.orEmpty())
        }
        YText(text,12,color=LocalYohaku.current.warm)
    }
}

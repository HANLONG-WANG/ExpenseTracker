package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.Duration
import java.util.UUID

data class AccountBalanceChange(val account:AccountEntity,val before:Long,val after:Long)
data class BudgetBalanceChange(val before:Long,val after:Long)
data class EntryImpact(val month:String,val currency:Currency,val expenseBefore:Long,val expenseAfter:Long,val incomeBefore:Long,val incomeAfter:Long,val balances:List<AccountBalanceChange>,val budgetExpenseBefore:Long=expenseBefore,val budgetExpenseAfter:Long=expenseAfter,
    val reserveBefore:Long?=0,val reserveAfter:Long?=0,val friendChanges:List<FriendBalanceChange> = emptyList()){
    fun remainingBudget(currentMonth:String,budgetMinor:Long?):BudgetBalanceChange? =
        if(month==currentMonth&&budgetMinor!=null)BudgetBalanceChange(Math.subtractExact(budgetMinor,budgetExpenseBefore),Math.subtractExact(budgetMinor,budgetExpenseAfter)) else null
}

data class BookSnapshot(val book:BookEntity,val accounts:List<AccountEntity>,val categories:List<CategoryEntity>,val events:List<EventEntity>,val postings:List<PostingEntity>,val draft:EntryDraft?, val storedPostings:List<PostingEntity> = postings, val settlements:List<SettlementEntity> = emptyList(),val transfers:List<TransferEntity> = emptyList(),val expenseComponents:List<ExpenseComponentEntity> = emptyList(),
    val budgets:List<BudgetEntity> = emptyList(),val activities:List<ActivityEntity> = emptyList(),val features:BookFeaturesEntity?=null,
    val eventActivities:List<EventActivityEntity> = emptyList(),val recurrences:List<RecurrenceEntity> = emptyList(),val occurrences:List<OccurrenceEntity> = emptyList(),
    val activityBudgets:List<ActivityBudgetEntity> = emptyList(),val reserves:List<ActivityReserveEntity> = emptyList(),val reserveUses:List<ReserveUseEntity> = emptyList(),
    val friends:List<FriendEntity> = emptyList(),val participants:List<ActivityParticipantEntity> = emptyList(),val treats:List<TreatEntity> = emptyList(),val allocations:List<TreatAllocationEntity> = emptyList(),val analysisPreferences:AnalysisPreferencesEntity?=null,val merchants:List<MerchantEntity> = emptyList(),val merchantLocations:List<MerchantLocationEntity> = emptyList(),val eventMerchants:List<EventMerchantEntity> = emptyList(),val files:List<FileBlobEntity> = emptyList(),val attachments:List<AttachmentLinkEntity> = emptyList(),val stagedMedia:List<DraftMediaEntity> = emptyList(),val receipts:List<ReceiptEntity> = emptyList(),val receiptImages:List<ReceiptImageEntity> = emptyList(),val contracts:List<DebtContractEntity> = emptyList(),val repayments:List<FinancialRepaymentEntity> = emptyList(),val repaymentAllocations:List<FinancialRepaymentAllocationEntity> = emptyList(),val arrangements:List<FinancialArrangementEntity> = emptyList(),val billAdjustments:List<FinancialBillAdjustmentEntity> = emptyList(),val expectedIncomes:List<ExpectedIncomeEntity> = emptyList()) {
    // Lookups are built once per snapshot; per-event scans over thousands of records made every screen quadratic.
    val visibleEvents by lazy{events.filter{it.deletedAt==null}}
    internal val visibleById by lazy{visibleEvents.associateBy{it.id}}
    internal val settlementByEvent by lazy{settlements.associateBy{it.eventId}}
    internal val transferByEvent by lazy{transfers.associateBy{it.eventId}}
    internal val storedPostingsByEvent by lazy{storedPostings.groupBy{it.eventId}}
    internal val categoryById by lazy{categories.associateBy{it.id}}
    internal val activityByEvent by lazy{eventActivities.associate{it.eventId to it.activityId}}
    internal val merchantByEvent by lazy{eventMerchants.associate{it.eventId to it.merchantId}}
    internal val merchantsByName by lazy{merchants.groupBy{it.name}}
    /** Visible refunds whose settlement is confirmed, keyed by the expense they return. */
    internal val confirmedRefunds by lazy{settlements.filter{it.confirmed&&it.originalEventId!=null}.mapNotNull{st->visibleById[st.eventId]?.let{st.originalEventId!! to it}}.groupBy({it.first},{it.second})}
    fun balance(account:AccountEntity):Long=postings.filter{it.accountId==account.id}.fold(account.openingMinor){a,p->Math.addExact(a,p.deltaMinor)}
    fun removalImpact(eventId:String)=removalImpacts(eventId).first()
    fun restorationImpact(eventId:String)=restorationImpacts(eventId).first()
    fun removalImpacts(eventId:String)=lifecycleImpacts(eventId,false)
    fun restorationImpacts(eventId:String)=lifecycleImpacts(eventId,true)
    private fun lifecycleImpacts(eventId:String,restore:Boolean):List<EntryImpact> {
        val event=events.first{it.id==eventId}
        val group=linkedSetOf(event.id)
        group.addAll(linkedLifecycleRefunds(event,restore).map{it.id})
        val nextEvents=events.map{if(it.id in group)it.copy(deletedAt=if(restore)null else 1L)else it}
        val ids=nextEvents.filter{it.deletedAt==null}.map{it.id}.toSet()
        val next=copy(events=nextEvents,postings=storedPostings.filter{it.confirmed&&it.eventId in ids})
        val original=settlements.find{it.eventId==event.id}?.originalEventId?.let{id->events.first{it.id==id}}
        val primary=expenseComponents.firstOrNull{it.eventId==event.id}?.date?.take(7) ?: (original?:event).date.take(7)
        val months=(listOf(primary)+events.filter{it.id in group}.map{it.date.take(7)}+expenseComponents.filter{it.eventId in group}.map{it.date.take(7)}+occurrences.filter{it.eventId in group}.map{it.scheduledDate.take(7)}+reserves.filter{r->reserveUses.any{it.eventId in group&&it.reserveId==r.id}}.map{it.date.take(7)}).distinct()
        val balances=accounts.mapNotNull{a->val before=balance(a);val after=next.balance(a);if(before==after)null else AccountBalanceChange(a,before,after)}
        fun income(s:BookSnapshot,month:String)=s.visibleEvents.filter{it.date.startsWith(month)&&it.kind==EntryKind.INCOME.name}.sumOf{it.baseMinor}
        return months.mapIndexed{index,month->
            val before=budgetProjection(month);val after=next.budgetProjection(month)
            EntryImpact(month,Currency.valueOf(book.currency),monthExpense(month),next.monthExpense(month),income(this,month),income(next,month),
                if(index==0)balances else emptyList(),before.spentMinor,after.spentMinor,before.reserveMinor,after.reserveMinor,if(index==0)friendChanges(next)else emptyList())
        }
    }
    fun entryAccounts(kind:EntryKind,preservedId:String?=null)=accounts.filter{(it.active||it.id==preservedId)&&(kind!=EntryKind.INCOME||!AccountType.valueOf(it.type).debt)}
    fun path(id:String?):String { val c=categoryById[id] ?: return ""; return c.parentId?.let{p->categoryById[p]?.name?.plus(" / ")} .orEmpty()+c.name }
}
class LedgerRepository(val db:YohakuDatabase, val clock:Clock=DeviceClock(),val mediaRoot:java.io.File?=null) {
    internal val mediaWrites=kotlinx.coroutines.sync.Mutex()
    val dao=db.dao()
    val json=Json { encodeDefaults=true }
    val books=dao.books()
    fun snapshot(book:BookEntity):Flow<BookSnapshot> = dao.graph(book.id).map { graph ->
        val g=graph ?: error("账本不存在 / 帳簿がありません")
        val liveIds=g.events.filter{it.deletedAt==null}.map{it.id}.toSet()
        BookSnapshot(g.book,g.accounts,g.categories,g.events.filter{it.purgedAt==null}.sortedWith(compareByDescending<EventEntity>{it.date}.thenByDescending{it.createdAt}.thenBy{it.id}),
            g.postings.filter{it.confirmed && it.eventId in liveIds},
            g.drafts.singleOrNull()?.let{json.decodeFromString<EntryDraft>(it.json)},g.postings,g.settlements,g.transfers,g.expenseComponents,g.budgets,g.activities,g.features.singleOrNull(),g.eventActivities,g.recurrences,g.occurrences,g.activityBudgets,g.reserves,g.reserveUses,g.friends,g.participants,g.treats,g.allocations,g.analysisPreferences.singleOrNull(),g.merchants,g.merchantLocations,g.eventMerchants,g.files,g.attachments,g.stagedMedia,g.receipts,g.receiptImages,g.contracts,g.repayments,g.repaymentAllocations,g.arrangements,g.billAdjustments,g.expectedIncomes)
    }
    fun id()=UUID.randomUUID().toString()
    suspend fun createBook(name:String,currency:Currency,theme:String="magazine",language:String="zh",startDate:String=LocalDate.now(clock).toString(),budgetMinor:Long?=null):String=db.withTransaction {
        require(name.isNotBlank()){ "请输入名称 / 名前を入力してください" }; LocalDate.parse(startDate);require(budgetMinor==null||budgetMinor>=0); val id=id()
        dao.putBook(BookEntity(id,name.trim(),currency.name,startDate,theme,language,budgetMinor=budgetMinor));if(budgetMinor!=null)dao.putBudget(BudgetEntity(id,startDate.take(7),"",null,budgetMinor));id
    }
    suspend fun updateBook(book:BookEntity) { require(book.name.isNotBlank()); db.withTransaction {
        val old=dao.book(book.id) ?: error("账本不存在 / 帳簿がありません")
        require(old.currency==book.currency || dao.eventCount(book.id)==0){"已有记录，本位币不可更改 / 記録済みの基準通貨は変更できません"}
        if(old.currency!=book.currency)require(dao.recurrences(book.id).isEmpty()&&dao.activityCount(book.id)==0&&!db.financialDao().hasBookData(book.id)&&snapshot(old).first().expectedIncomes.isEmpty()){"已有周期、活动或金融资料，本位币不可直接改写 / 定期・シーン・金融情報の基準通貨は直接変更できません"}
        if(old.currency!=book.currency&&old.initialized)require(dao.budgets(book.id).none{it.amountMinor!=null}&&dao.recurrences(book.id).isEmpty()){ "已有预算或周期资料，本位币不可直接改写 / 予算・定期情報の基準通貨は直接変更できません" }
        if(book.budgetMinor!=old.budgetMinor||!old.initialized&&book.currency!=old.currency) {
            if(!old.initialized&&book.currency!=old.currency)dao.clearBudgets(book.id)
            val month=if(old.initialized)java.time.YearMonth.now(clock).toString()else book.startDate.take(7)
            dao.putBudget(BudgetEntity(book.id,month,"",null,book.budgetMinor))
        }
        require(book.theme in listOf("magazine","journal","objects"));require(book.language in listOf("zh","ja"));dao.putBook(book.copy(archived=old.archived,deletedAt=old.deletedAt))
    } }
    suspend fun updateAppearance(bookId:String,theme:String?=null,language:String?=null) { db.withTransaction {
        val old=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
        updateBook(old.copy(theme=theme?:old.theme,language=language?:old.language))
    } }
    suspend fun setupStep(bookId:String,step:Int) { db.withTransaction {
        require(step in 0..4)
        val old=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
        dao.putBook(old.copy(setupStep=step))
    } }
    suspend fun saveAccount(bookId:String,name:String,type:AccountType,currency:Currency,opening:Money,date:String,accountId:String?=null):String = db.withTransaction {
        require(dao.book(bookId)!=null){"账本不可用 / 帳簿が利用できません"}
        require(name.isNotBlank()){ "请输入名称 / 名前を入力してください" };require(opening.currency==currency);LocalDate.parse(date)
        val old=accountId?.let{dao.account(bookId,it) ?: error("账户不存在 / 口座がありません")}
        require(old==null || old.type==type.name){"账户类型不可改写 / 口座の種類は変更できません"}
        require(old==null || old.currency==currency.name || !accountUsage(bookId,old.id).inUse){"已有交易、规划或金融资料的账户币种不可更改 / 記録・予定・金融情報がある口座の通貨は変更できません"}
        val account=AccountEntity(bookId,old?.id?:id(),name.trim(),type.name,currency.name,opening.minor,date,old?.active?:true)
        dao.putAccount(account);account.id
    }
    suspend fun saveCategory(bookId:String,name:String,kind:EntryKind,parentId:String?,categoryId:String?=null,favorite:Boolean=false):String = db.withTransaction {
        require(dao.book(bookId)!=null)
        require(name.isNotBlank()){ "请输入名称 / 名前を入力してください" };require(kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME))
        val parent=parentId?.let{dao.category(bookId,it) ?: error("父分类不存在 / 親カテゴリがありません")}
        require(parent==null || parent.parentId==null && parent.kind==kind.name){"最多两级且用途相同 / 同じ用途で2階層まで"}
        require(parentId!=categoryId || categoryId==null){"不能归属自己 / 自分を親にはできません"}
        val oldCategory=categoryId?.let{dao.category(bookId,it)}
        if(categoryId!=null) {
            val old=dao.category(bookId,categoryId) ?: error("分类不存在 / カテゴリがありません")
            require(old.parentId==parentId && old.kind==kind.name){"已有分类层级调整需影响预览 / 階層変更には影響確認が必要です"}
        }
        require(oldCategory!=null||parent==null||categoryEnabled(bookId,parent.id)){"父分类已停用 / 親カテゴリは停止済みです"}
        val savedId=categoryId?:id()
        dao.putCategory(CategoryEntity(bookId,savedId,name.trim(),kind.name,parentId,favorite,oldCategory?.active?:true))
        savedId
    }
    suspend fun toggleCategoryFavorite(bookId:String,categoryId:String)=db.withTransaction {
        val category=dao.category(bookId,categoryId) ?: error("分类不存在 / カテゴリがありません")
        dao.putCategory(category.copy(favorite=!category.favorite))
    }
    suspend fun saveEntry(bookId:String,draft:EntryDraft):String=db.withTransaction {
        writeChange(withMedia(prepareEntry(bookId,draft),draft),clearDraft=true)
    }
    internal suspend fun prepareEntry(bookId:String,draft:EntryDraft):PreparedLedgerChange {
        val b=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
        require(draft.kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME))
        val old=draft.id?.let{dao.event(bookId,it) ?: error("原记录不存在 / 元の記録がありません")}
        val a=dao.account(bookId,draft.accountId ?: error("请选择账户 / 口座を選択してください")) ?: error("账户不属于本账本 / 別の帳簿の口座です")
        require(a.active||old?.accountId==a.id){"请选择启用的付款账户 / 有効な支払口座を選択してください"}
        require(draft.kind!=EntryKind.INCOME||!AccountType.valueOf(a.type).debt){"收入请选择资金账户；还款或退款使用对应类型 / 収入は資金口座へ。返済・返金は専用の種類で記録してください"}
        val c=dao.category(bookId,draft.categoryId ?: error("请选择分类 / カテゴリを選択してください")) ?: error("分类不属于本账本 / 別の帳簿のカテゴリです")
        require(c.kind==draft.kind.name && (categoryEnabled(bookId,c.id)||old?.categoryId==c.id))
        val amount=Money.parse(draft.amount,draft.originalCurrency ?: Currency.valueOf(a.currency)); require(amount.minor>0){"金额应大于零 / 金額は0より大きくしてください"}
        val accountAmount=if(amount.currency.name==a.currency)amount else Money.parse(draft.accountAmount,Currency.valueOf(a.currency))
        require(accountAmount.minor>0)
        val base=if(a.currency==b.currency)accountAmount else Money.parse(draft.baseAmount,Currency.valueOf(b.currency))
        require(base.minor>0)
        LocalDate.parse(draft.settlementDate);LocalDate.parse(draft.rateDate)
        val date=LocalDate.parse(draft.date)
        require(!draft.historicalIncluded || date<=LocalDate.parse(a.openingDate)){"只有期初已包含的历史记录可使用此选项 / 期首以前の記録のみ指定できます"}
        require(old==null||old.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name))
        require(old?.deletedAt==null){"请先恢复记录 / 記録を復元してください"}
        val linkedRefunds=old?.let{dao.refunds(bookId,it.id).filter{r->r.deletedAt==null}}.orEmpty()
        require(linkedRefunds.isEmpty() || old?.currency==amount.currency.name && old.kind==draft.kind.name && linkedRefunds.sumOf{it.amountMinor}<=amount.minor){"修改不能使关联退款超过原消费 / 変更後の金額は返金総額以上にしてください"}
        val merchant=draft.merchantId?.let{dao.merchant(bookId,it)?:error("商户不属于本账本 / 別の帳簿のお店です")}
        require(merchant==null||merchant.active||old?.let{dao.eventMerchant(bookId,it.id)?.merchantId}==merchant.id)
        val event=cardPayment(EventEntity(bookId,old?.id?:id(),draft.kind.name,a.id,c.id,amount.minor,amount.currency.name,base.minor,date.toString(),draft.merchant.trim(),draft.note,draft.historicalIncluded,(old?.revision?:0)+1,createdAt=old?.createdAt ?: clock.millis()),draft,old,a)
        val expenseSign=if(draft.kind==EntryKind.EXPENSE)-1L else 1L
        val accountSign=if(AccountType.valueOf(a.type).debt)-expenseSign else expenseSign
        val postings=if(draft.historicalIncluded)emptyList()else listOf(PostingEntity(bookId,event.id+":settlement",event.id,a.id,
            Math.multiplyExact(accountAmount.minor,accountSign),a.currency,draft.settlementDate,draft.settlementConfirmed))
        val previousSettlement=old?.let{dao.settlement(bookId,it.id)}
        val settlement=SettlementEntity(bookId,event.id,accountAmount.minor,a.currency,draft.settlementConfirmed,draft.settlementDate,draft.rateSource,draft.rateDate,
            previousSettlement?.expectedAccountMinor ?: draft.referenceExpectedAccountMinor ?: accountAmount.minor,previousSettlement?.expectedBaseMinor ?: draft.referenceExpectedBaseMinor ?: base.minor,manualRate=draft.manualRate,expectedAccountCurrency=previousSettlement?.expectedMoney()?.currency?.name?:a.currency,referenceRatesJson=draft.referenceRates.takeIf{it.isNotEmpty()}?.let{json.encodeToString(it)})
        val occurrence=draft.occurrenceId?.let{dao.occurrence(bookId,it)?:error("周期实例不属于本账本 / 別の帳簿の定期予定です")}
        require(occurrence==null||draft.kind==EntryKind.EXPENSE&&(occurrence.status=="PENDING"||occurrence.status=="COMPLETED"&&occurrence.eventId==old?.id)){"周期实例已完成或取消，请编辑已关联记录 / 定期予定は完了・取消済みです。関連記録を編集してください"}
        if(old!=null&&dao.eventOccurrences(bookId,old.id).any{it.status in listOf("PENDING","COMPLETED")})require(draft.kind==EntryKind.EXPENSE){"请先解除周期关联再改变类型 / 種類を変える前に定期予定との関連を解除してください"}
        if(draft.activityId!=null) {
            val activity=dao.activity(bookId,draft.activityId)
            require(activity!=null){"活动不属于本账本 / 別の帳簿のシーンです"}
            require(activity.deletedAt==null||old?.let{dao.eventActivity(bookId,it.id)?.activityId}==activity.id){"不能向已删除活动添加记录 / 削除済みシーンには追加できません"}
            require(draft.kind==EntryKind.EXPENSE){"活动只关联支出 / シーンは支出に関連付けてください"}
            require(!activity.ended||draft.date in activity.startDate..activity.endDate){"结束活动只补录期间事实 / 終了済みシーンは期間内の事実のみ記録できます"}
            require(dao.features(bookId)?.activityMode==true||old?.let{dao.eventActivity(bookId,it.id)?.activityId}==draft.activityId||occurrence?.activityId==draft.activityId)
        }
        val snapshot=if(draft.reserveId!=null||occurrence!=null)snapshot(b).first()else null
        val reserveId=draft.reserveId?:occurrence?.let{o->snapshot!!.reserves.firstOrNull{it.occurrenceId==o.id&&it.active}?.id}
        val uses=reserveId?.let{rid->
            require(draft.kind==EntryKind.EXPENSE)
            val reserve=dao.reserve(bookId,rid)?:error("预留不存在 / 確保がありません")
            val previousUse=old?.let{dao.reserveUses(bookId,it.id).firstOrNull{u->u.reserveId==rid}}
            require(reserve.activityId==draft.activityId&&(reserve.active||previousUse!=null)){"请选择本活动的有效预留 / このシーンの有効な確保を選択してください"}
            val remaining=if(reserve.active)snapshot!!.reserveRemaining(reserve,old?.id)else previousUse!!.amountMinor
            val used=if(draft.reserveAmount.isBlank())minOf(base.minor,remaining)else Money.parse(draft.reserveAmount,Currency.valueOf(b.currency)).minor
            require(used>0&&used<=base.minor&&used<=remaining){"使用金额超过本次支出或剩余预留 / 使用額は今回の支出・確保残額以内にしてください"}
            listOf(ReserveUseEntity(bookId,event.id,rid,used))
        }.orEmpty()
        val treat=if(draft.treatEnabled)prepareTreat(bookId,draft.activityId?:error("互请必须关联活动 / ごちそうはシーンに関連付けてください"),event,null,draft.treatFriends,draft.treatMode,draft.treatWeights)else null
        return PreparedLedgerChange(old,event,settlement,postings,activityId=draft.activityId,occurrenceId=draft.occurrenceId,reserveUses=uses,treat=treat,merchantId=merchant?.id,cardRuleChecked=event.cardPaymentChoice==CardPaymentChoice.ACCOUNT_RULE.name&&!retainCardPayment(old,draft))
    }
    /** Foundation for transfers/borrowing/repayments. UI is implemented in subsequent phases. */
    suspend fun saveMovement(bookId:String,kind:EntryKind,fromId:String,toId:String,fromAmount:Money,toAmount:Money,date:String):String=db.withTransaction {
        require(kind in listOf(EntryKind.TRANSFER,EntryKind.REPAYMENT,EntryKind.BORROWING));require(fromId!=toId)
        val from=dao.account(bookId,fromId) ?: error("付款账户不存在")
        val to=dao.account(bookId,toId) ?: error("到账账户不存在")
        require(from.currency==fromAmount.currency.name && to.currency==toAmount.currency.name)
        require(fromAmount.minor>0 && toAmount.minor>0);LocalDate.parse(date)
        val fromDebt=AccountType.valueOf(from.type).debt;val toDebt=AccountType.valueOf(to.type).debt
        require(when(kind){EntryKind.TRANSFER->!fromDebt&&!toDebt;EntryKind.REPAYMENT->!fromDebt&&toDebt;else->fromDebt&&!toDebt})
        val event=EventEntity(bookId,id(),kind.name,fromId,null,fromAmount.minor,from.currency,0,date,"","",revision=1,createdAt=clock.millis())
        dao.putEvent(event)
        dao.putPostings(listOf(
            PostingEntity(bookId,id(),event.id,fromId,if(fromDebt)fromAmount.minor else -fromAmount.minor,from.currency,date),
            PostingEntity(bookId,id(),event.id,toId,if(toDebt)-toAmount.minor else toAmount.minor,to.currency,date)))
        dao.putRevision(RevisionEntity(bookId,id(),event.id,1,clock.millis(),null,json.encodeToString(event)));event.id
    }
    suspend fun saveDraft(bookId:String,draft:EntryDraft)=db.withTransaction {
        dao.storedDraft(bookId)?.let{stored->val old=json.decodeFromString<EntryDraft>(stored.json);if(old.mediaSession!=draft.mediaSession)dao.clearStagedMedia(bookId,old.mediaSession)}
        dao.putDraft(DraftEntity(bookId,json.encodeToString(draft),clock.millis()))
    }
    suspend fun clearDraft(bookId:String)=db.withTransaction {
        dao.storedDraft(bookId)?.let{stored->dao.clearStagedMedia(bookId,json.decodeFromString<EntryDraft>(stored.json).mediaSession)}
        dao.clearDraft(bookId)
    }
    suspend fun deleteEntry(bookId:String,id:String) { commitEntryLifecycle(previewEntryLifecycle(bookId,id,EntryLifecycleOperation.TRASH)).also{check(it)} }
    suspend fun restoreEntry(bookId:String,id:String) { commitEntryLifecycle(previewEntryLifecycle(bookId,id,EntryLifecycleOperation.RESTORE)).also{check(it)} }
    suspend fun purgeExpired()=db.withTransaction {
        val cutoff=clock.millis()-Duration.ofDays(30).toMillis()
        dao.books().first().filter{it.deletedAt?.let{date->date<=cutoff}==true}.forEach{purgeWholeBook(it.id)}
        dao.books().first().forEach{book->
            dao.events(book.id).first().filter{it.deletedAt?.let{date->date<=cutoff}==true}.forEach{event->
                if(dao.event(book.id,event.id)!=null)purgeEntryGroup(book.id,event.id)
            }
        }
    }
}

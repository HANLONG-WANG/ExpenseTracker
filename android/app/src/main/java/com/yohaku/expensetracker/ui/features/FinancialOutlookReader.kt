package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

internal sealed interface FinancialOutlookRead {
    data object Loading:FinancialOutlookRead
    data class Ready(val timeline:CashTimeline,val pace:BudgetPace,val risks:List<CashAccountRisk>,val attention:List<FinancialAttention>,val payments:List<ImportantPayment> = emptyList(),val deadlines:List<FinancialDeadline> = emptyList()):FinancialOutlookRead
    data class Failure(val message:String):FinancialOutlookRead
}

@Composable internal fun rememberFinancialOutlook(s:BookSnapshot,vm:AppViewModel,through:LocalDate,attempt:Int=0):State<FinancialOutlookRead> {
    val today by vm.today.collectAsStateWithLifecycle()
    val finance by vm.financialState.collectAsStateWithLifecycle()
    val rateRevision by vm.referenceRates.cacheRevision.collectAsStateWithLifecycle()
    return key(s.book.id) { produceState<FinancialOutlookRead>(FinancialOutlookRead.Loading,s,finance,today,through,attempt,rateRevision) {
        value=FinancialOutlookRead.Loading
        try {
            suspend fun read(rates:List<ReferenceRateSnapshot>)=vm.repository.db.withTransaction {
                val state=vm.repository.financialReviewState(s.book.id)
                val stale=state.finance.plans.filter{vm.repository.financialPredictionStale(state.ledger,state.finance,it)}.map{it.id}.toSet()
                val timeline=state.ledger.cashTimeline(state.finance,today,maxOf(today,through),stale,rates)
                FinancialOutlookRead.Ready(timeline,state.ledger.budgetPace(today,state.finance,rates),timeline.accountRisks(),state.ledger.financialAttention(state.finance,today.toString(),timeline),state.ledger.importantPayments(state.finance,timeline),state.ledger.financialDeadlines(state.finance,today.toString(),maxOf(today,through).toString()))
            }
            value=read(vm.referenceRates.store.referenceRateCache().rates)
            finance?.takeIf{it.book.id==s.book.id}?.let{f->
                if(cashReferenceRates(s,f,vm.referenceRates).isNotEmpty())value=read(vm.referenceRates.store.referenceRateCache().rates)
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){value=FinancialOutlookRead.Failure(e.message?:"资金资料读取失败 / 資金情報の読込に失敗しました")}
    }
    }
}

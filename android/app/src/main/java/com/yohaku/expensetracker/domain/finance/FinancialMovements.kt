package com.yohaku.expensetracker.domain.finance

import com.yohaku.expensetracker.domain.model.Money
import kotlinx.serialization.Serializable

@Serializable enum class RepaymentType { NORMAL, PARTIAL, EARLY, SETTLEMENT }
@Serializable data class FinancialBorrowingInput(
    val accountId:String,val name:String,val mode:FinanceMode,val principal:Money,val date:String,
    val fundingAccountId:String?=null,val received:Money?=null,val withheldFee:Money?=null,val withheldFeeBase:Money?=null,
    val separateFeeAccountId:String?=null,val separateFee:Money?=null,val separateFeeBase:Money?=null,
    val opening:Boolean=false,val eventId:String?=null,val contractId:String?=null,val note:String=""
)
@Serializable data class RepaymentAllocationInput(
    val billId:String?=null,val contractId:String?=null,val principalMinor:Long?=null,val interestMinor:Long?=null,val feeMinor:Long?=null
)
@Serializable data class FinancialRepaymentInput(
    val sourceAccountId:String,val debtAccountId:String,val paid:Money,val date:String,
    val received:Money?=null,val principal:Money?=null,val interest:Money?=null,val fee:Money?=null,val feeBase:Money?=null,
    val platformConfirmed:Boolean=false,val clearingDate:String?=null,val type:RepaymentType=RepaymentType.NORMAL,
    val sourceFee:Money?=null,val sourceFeeBase:Money?=null,
    val allocations:List<RepaymentAllocationInput> = emptyList(),val linkedTransferEventId:String?=null,
    val eventId:String?=null,val note:String=""
)

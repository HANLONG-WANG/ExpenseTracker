package com.yohaku.expensetracker.domain.finance

import com.yohaku.expensetracker.domain.model.SchemaRange
import com.yohaku.expensetracker.domain.model.SchemaFormat
import com.yohaku.expensetracker.domain.model.Money
import kotlinx.serialization.Serializable

@Serializable enum class RepaymentType { NORMAL, PARTIAL, EARLY, SETTLEMENT }
@Serializable data class FinancialBorrowingInput(
    val accountId:String,val name:String,val mode:FinanceMode,@SchemaRange(minimum=1,property="minor") val principal:Money,@SchemaFormat("date") val date:String,
    val fundingAccountId:String?=null,@SchemaRange(minimum=0,property="minor",condition="Actual borrowing (opening=false); ignored for opening contracts.") val received:Money?=null,@SchemaRange(minimum=0,property="minor",condition="Actual borrowing (opening=false); ignored for opening contracts.") val withheldFee:Money?=null,@SchemaRange(minimum=0,property="minor",condition="Actual borrowing with a nonzero fee in foreign currency; otherwise derived or ignored.") val withheldFeeBase:Money?=null,
    val separateFeeAccountId:String?=null,@SchemaRange(minimum=0,property="minor",condition="Actual borrowing (opening=false); ignored for opening contracts.") val separateFee:Money?=null,@SchemaRange(minimum=0,property="minor",condition="Actual borrowing with a nonzero fee in foreign currency; otherwise derived or ignored.") val separateFeeBase:Money?=null,
    val opening:Boolean=false,val eventId:String?=null,val contractId:String?=null,val note:String=""
)
@Serializable data class RepaymentAllocationInput(
    val billId:String?=null,val contractId:String?=null,@SchemaRange(minimum=0) val principalMinor:Long?=null,@SchemaRange(minimum=0) val interestMinor:Long?=null,@SchemaRange(minimum=0) val feeMinor:Long?=null
)
@Serializable data class FinancialRepaymentInput(
    val sourceAccountId:String,val debtAccountId:String,@SchemaRange(minimum=1,property="minor") val paid:Money,@SchemaFormat("date") val date:String,
    @SchemaRange(minimum=0,property="minor") val received:Money?=null,@SchemaRange(minimum=0,property="minor") val principal:Money?=null,@SchemaRange(minimum=0,property="minor") val interest:Money?=null,@SchemaRange(minimum=0,property="minor") val fee:Money?=null,@SchemaRange(minimum=0,property="minor",condition="Only when the corresponding fee is used and nonzero in foreign currency; otherwise derived or ignored. Platform feeBase also requires confirmation.") val feeBase:Money?=null,
    val platformConfirmed:Boolean=false,@SchemaFormat("date") val clearingDate:String?=null,val type:RepaymentType=RepaymentType.NORMAL,
    @SchemaRange(minimum=0,property="minor") val sourceFee:Money?=null,@SchemaRange(minimum=0,property="minor",condition="Only when the corresponding fee is used and nonzero in foreign currency; otherwise derived or ignored. Platform feeBase also requires confirmation.") val sourceFeeBase:Money?=null,
    val allocations:List<RepaymentAllocationInput> = emptyList(),val linkedTransferEventId:String?=null,
    val eventId:String?=null,val note:String=""
)

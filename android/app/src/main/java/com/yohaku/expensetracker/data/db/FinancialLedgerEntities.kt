package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName="debt_contracts",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","fundingAccountId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","fundingEventId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","separateFeeAccountId"])
],indices=[Index(value=["bookId","accountId"]),Index(value=["bookId","fundingAccountId"]),Index(value=["bookId","fundingEventId"],unique=true),Index(value=["bookId","separateFeeAccountId"])])
data class DebtContractEntity(
    val bookId:String,val id:String,val accountId:String,val name:String,val mode:String,val currency:String,
    val principalMinor:Long,val date:String,val fundingEventId:String?,val fundingAccountId:String?,
    val receivedMinor:Long?,val withheldFeeMinor:Long?,val withheldFeeBaseMinor:Long?,
    val separateFeeMinor:Long?,val separateFeeAccountId:String?,val separateFeeBaseMinor:Long?,
    val createdAt:Long,val revision:Int=1
)

@Serializable
@Entity(tableName="financial_repayments",primaryKeys=["bookId","eventId"],foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","debtAccountId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","linkedTransferEventId"])
],indices=[Index(value=["bookId","debtAccountId"]),Index(value=["bookId","linkedTransferEventId"])])
data class FinancialRepaymentEntity(
    val bookId:String,val eventId:String,val debtAccountId:String,val receivedMinor:Long?,val principalMinor:Long?,
    val interestMinor:Long?,val feeMinor:Long?,val feeBaseMinor:Long?,val clearingDate:String?,val platformConfirmed:Boolean,
    val paymentType:String,val linkedTransferEventId:String?,val originalInputJson:String,
    val sourceFeeMinor:Long?,val sourceFeeBaseMinor:Long?
)

@Serializable
@Entity(tableName="financial_repayment_allocations",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=FinancialRepaymentEntity::class,parentColumns=["bookId","eventId"],childColumns=["bookId","eventId"]),
    ForeignKey(entity=FinancialBillEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","billId"]),
    ForeignKey(entity=DebtContractEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","contractId"])
],indices=[Index(value=["bookId","eventId"]),Index(value=["bookId","billId"]),Index(value=["bookId","contractId"])])
data class FinancialRepaymentAllocationEntity(
    val bookId:String,val id:String,val eventId:String,val billId:String?,val contractId:String?,
    val principalMinor:Long?,val interestMinor:Long?,val feeMinor:Long?
)

@Entity(tableName="financial_contract_plans",primaryKeys=["bookId","planId"],foreignKeys=[
    ForeignKey(entity=FinancialPlanEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","planId"]),
    ForeignKey(entity=DebtContractEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","contractId"])
],indices=[Index(value=["bookId","contractId"])])
data class FinancialContractPlanEntity(val bookId:String,val planId:String,val contractId:String)

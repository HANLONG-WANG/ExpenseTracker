package com.yohaku.expensetracker.data.db

import androidx.room.*

/** Append-only versions. Names of institutions never select a calculation rule. */
@Entity(tableName="financial_rules", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"])
], indices=[Index(value=["bookId","accountId","mode","version"],unique=true)])
@kotlinx.serialization.Serializable
data class FinancialRuleEntity(
    val bookId:String, val id:String, val accountId:String, val mode:String,
    val version:Int, val effectiveFrom:String, val json:String, val createdAt:Long
)

/** A schedule is an obligation, never a second balance or an automatic ledger event. */
@Entity(tableName="financial_plans", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","sourceEventId"])
], indices=[Index(value=["bookId","accountId"]),Index(value=["bookId","sourceEventId"])])
@kotlinx.serialization.Serializable
data class FinancialPlanEntity(
    val bookId:String, val id:String, val accountId:String, val name:String, val mode:String,
    val origin:String, val sourceEventId:String?, val currency:String, val principalMinor:Long?,
    val complete:Boolean, val createdAt:Long, val revision:Int=1, val active:Boolean=true,
    @ColumnInfo(defaultValue="NULL") val termsJson:String?=null,
    @ColumnInfo(defaultValue="NULL") val reconciliationJson:String?=null
)

/** Original prediction survives platform confirmation; null is unknown, not zero. */
@Entity(tableName="financial_bills", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=FinancialPlanEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","planId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","paymentAccountId"]),
    ForeignKey(entity=FinancialRuleEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","ruleVersionId"])
], indices=[Index(value=["bookId","planId","number"],unique=true),Index(value=["bookId","paymentAccountId"]),Index(value=["bookId","ruleVersionId"])])
@kotlinx.serialization.Serializable
data class FinancialBillEntity(
    val bookId:String, val id:String, val planId:String, val number:Int, val paymentAccountId:String?,
    val ruleVersionId:String?, val predictedDate:String?, val predictedPrincipalMinor:Long?,
    val predictedFeeMinor:Long?, val predictedTotalMinor:Long?, val basisJson:String?,
    val confirmedDate:String?, val confirmedPrincipalMinor:Long?, val confirmedFeeMinor:Long?,
    val confirmedTotalMinor:Long?, val confirmedAt:Long?, val revision:Int=1,
    @ColumnInfo(defaultValue="1") val active:Boolean=true
) {
    val date:String get()=confirmedDate ?: requireNotNull(predictedDate)
    val totalMinor:Long get()=confirmedTotalMinor ?: requireNotNull(predictedTotalMinor)
    val principalMinor:Long? get()=if(confirmedAt!=null)confirmedPrincipalMinor else predictedPrincipalMinor
    val feeMinor:Long? get()=if(confirmedAt!=null)confirmedFeeMinor else predictedFeeMinor
    val confirmed:Boolean get()=confirmedAt!=null
}

/** Validation records an exact comparison, independently of rule completeness. */
@Entity(tableName="financial_rule_validations",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=FinancialRuleEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","ruleVersionId"]),
    ForeignKey(entity=FinancialBillEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","billId"])
],indices=[Index(value=["bookId","ruleVersionId"]),Index(value=["bookId","billId"])])
data class FinancialRuleValidationEntity(
    val bookId:String,val id:String,val ruleVersionId:String,val billId:String,val billRevision:Int,
    val principalDifferenceMinor:Long?,val feeDifferenceMinor:Long?,val totalDifferenceMinor:Long,
    val dateChanged:Boolean,val checkedAt:Long
)

/** An immutable whole-plan snapshot; confirmation and recalculation do not erase older versions. */
@Entity(tableName="financial_plan_revisions",primaryKeys=["bookId","planId","version"],foreignKeys=[
    ForeignKey(entity=FinancialPlanEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","planId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","causeEventId"])
],indices=[Index(value=["bookId","causeEventId"])])
data class FinancialPlanRevisionEntity(
    val bookId:String,val planId:String,val version:Int,val timestamp:Long,val reason:String,val causeEventId:String?,val json:String
)

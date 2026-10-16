package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

/** A recorded platform arrangement; neither this row nor its bill adjustments are cash postings. */
@Serializable
@Entity(tableName="financial_arrangements",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=FinancialPlanEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","originalPlanId"]),
    ForeignKey(entity=FinancialPlanEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","targetPlanId"])
],indices=[Index(value=["bookId","originalPlanId"]),Index(value=["bookId","targetPlanId"],unique=true)])
data class FinancialArrangementEntity(
    val bookId:String,val id:String,val originalPlanId:String,val targetPlanId:String,val effectiveDate:String,
    val appliedAt:String,val principalMinor:Long,val changeFeeMinor:Long,val feeTreatment:String,
    val exceptionAccepted:Boolean,val baselineJson:String,val inputJson:String,val comparisonJson:String,val createdAt:Long
)

/** Removes transferred principal or explicitly cancelled fees from the old obligation, not debt. */
@Serializable
@Entity(tableName="financial_bill_adjustments",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=FinancialArrangementEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","arrangementId"]),
    ForeignKey(entity=FinancialBillEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","billId"])
],indices=[Index(value=["bookId","arrangementId"]),Index(value=["bookId","billId"])])
data class FinancialBillAdjustmentEntity(
    val bookId:String,val id:String,val arrangementId:String,val billId:String,val principalMinor:Long,val feeMinor:Long
)

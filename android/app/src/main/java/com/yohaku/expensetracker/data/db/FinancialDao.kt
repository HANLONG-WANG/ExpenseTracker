package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FinancialDao {
    @Query("SELECT * FROM financial_drafts WHERE bookId=:bookId ORDER BY slot") suspend fun bookDrafts(bookId:String):List<FinancialDraftEntity>
    @Upsert suspend fun putDraft(draft:FinancialDraftEntity)
    @Query("SELECT * FROM financial_drafts WHERE bookId=:bookId AND slot=:slot") suspend fun draft(bookId:String,slot:String):FinancialDraftEntity?
    @Query("SELECT * FROM financial_drafts WHERE bookId=:bookId AND accountId IS :accountId ORDER BY updatedAt DESC") fun drafts(bookId:String,accountId:String?):Flow<List<FinancialDraftEntity>>
    @Query("DELETE FROM financial_drafts WHERE bookId=:bookId AND slot=:slot") suspend fun clearDraft(bookId:String,slot:String)

    @Insert suspend fun insertArrangement(arrangement:FinancialArrangementEntity)
    @Insert suspend fun insertBillAdjustments(adjustments:List<FinancialBillAdjustmentEntity>)

    @Insert suspend fun insertPlanRevision(revision:FinancialPlanRevisionEntity)
    @Query("SELECT * FROM financial_plan_revisions WHERE bookId=:bookId AND planId=:planId ORDER BY version DESC")
    fun planRevisions(bookId:String,planId:String):Flow<List<FinancialPlanRevisionEntity>>
    @Query("SELECT * FROM financial_plan_revisions WHERE bookId=:bookId AND planId=:planId AND version=:version")
    suspend fun planRevision(bookId:String,planId:String,version:Int):FinancialPlanRevisionEntity?

    @Upsert suspend fun putContract(contract:DebtContractEntity)
    @Query("SELECT * FROM debt_contracts WHERE bookId=:bookId AND fundingEventId=:eventId")
    suspend fun fundingContract(bookId:String,eventId:String):DebtContractEntity?
    @Upsert suspend fun putRepayment(repayment:FinancialRepaymentEntity)
    @Query("SELECT * FROM financial_repayments WHERE bookId=:bookId AND eventId=:eventId")
    suspend fun repayment(bookId:String,eventId:String):FinancialRepaymentEntity?
    @Query("SELECT * FROM financial_repayment_allocations WHERE bookId=:bookId AND eventId=:eventId")
    suspend fun repaymentAllocations(bookId:String,eventId:String):List<FinancialRepaymentAllocationEntity>
    @Query("DELETE FROM financial_repayment_allocations WHERE bookId=:bookId AND eventId=:eventId")
    suspend fun clearRepaymentAllocations(bookId:String,eventId:String)
    @Insert suspend fun insertRepaymentAllocations(allocations:List<FinancialRepaymentAllocationEntity>)
    @Upsert suspend fun putContractPlan(link:FinancialContractPlanEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM credit_limit_configs WHERE bookId=:bookId) OR EXISTS(SELECT 1 FROM credit_limit_groups WHERE bookId=:bookId) OR EXISTS(SELECT 1 FROM credit_limit_observations WHERE bookId=:bookId) OR EXISTS(SELECT 1 FROM financial_rules WHERE bookId=:bookId) OR EXISTS(SELECT 1 FROM financial_plans WHERE bookId=:bookId) OR EXISTS(SELECT 1 FROM debt_contracts WHERE bookId=:bookId)")
    suspend fun hasBookData(bookId:String):Boolean
    @Insert suspend fun insertRule(rule:FinancialRuleEntity)
    @Query("SELECT * FROM financial_rules WHERE bookId=:bookId AND accountId=:accountId ORDER BY mode,effectiveFrom,version")
    suspend fun rules(bookId:String,accountId:String):List<FinancialRuleEntity>
    @Query("SELECT * FROM financial_rules WHERE bookId=:bookId AND id=:id")
    suspend fun rule(bookId:String,id:String):FinancialRuleEntity?
    @Insert suspend fun insertPlan(plan:FinancialPlanEntity)
    @Update suspend fun updatePlan(plan:FinancialPlanEntity)
    @Query("SELECT * FROM financial_plans WHERE bookId=:bookId AND id=:id")
    suspend fun plan(bookId:String,id:String):FinancialPlanEntity?
    @Query("SELECT * FROM financial_plans WHERE bookId=:bookId AND accountId=:accountId ORDER BY createdAt,id")
    suspend fun plans(bookId:String,accountId:String):List<FinancialPlanEntity>
    @Insert suspend fun insertBills(bills:List<FinancialBillEntity>)
    @Update suspend fun updateBill(bill:FinancialBillEntity)
    @Query("SELECT * FROM financial_bills WHERE bookId=:bookId AND id=:id")
    suspend fun bill(bookId:String,id:String):FinancialBillEntity?
    @Query("SELECT * FROM financial_bills WHERE bookId=:bookId AND planId=:planId ORDER BY number")
    suspend fun bills(bookId:String,planId:String):List<FinancialBillEntity>
    @Insert suspend fun insertValidation(validation:FinancialRuleValidationEntity)
    @Query("SELECT * FROM financial_rule_validations WHERE bookId=:bookId AND ruleVersionId=:ruleVersionId ORDER BY checkedAt,id")
    suspend fun validations(bookId:String,ruleVersionId:String):List<FinancialRuleValidationEntity>
    @Transaction
    @Query("SELECT * FROM books WHERE id=:bookId")
    fun graph(bookId:String):Flow<FinancialGraph?>
}

data class FinancialGraph(
    @Embedded val book:BookEntity,
    @Relation(parentColumn="id",entityColumn="bookId") val rules:List<FinancialRuleEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val plans:List<FinancialPlanEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val bills:List<FinancialBillEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val validations:List<FinancialRuleValidationEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val contracts:List<DebtContractEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val repayments:List<FinancialRepaymentEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val repaymentAllocations:List<FinancialRepaymentAllocationEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val contractPlans:List<FinancialContractPlanEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val arrangements:List<FinancialArrangementEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val billAdjustments:List<FinancialBillAdjustmentEntity>
)

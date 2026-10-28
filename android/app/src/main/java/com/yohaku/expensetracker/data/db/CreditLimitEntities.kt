package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

/** User-confirmed usage anchors and capacity rules are append-only, independent of cash. */
@Serializable
@Entity(tableName="credit_limit_configs",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"])],indices=[Index(value=["bookId","accountId","version"],unique=true)])
data class CreditLimitConfigEntity(val bookId:String,val id:String,val accountId:String,val version:Int,val effectiveFrom:String,
    val currency:String,val totalMinor:Long?,val installmentMinor:Long?,val revolvingMinor:Long?,val sharing:String?,val combinedMinor:Long?,
    val basisDate:String,val usedTotalMinor:Long?,val usedInstallmentMinor:Long?,val usedRevolvingMinor:Long?,val basisJson:String,
    val recovery:String?,val usageBasis:String?,val usageStart:String?,val zoneId:String,val createdAt:Long,@ColumnInfo(defaultValue="0") val recoveryDelayDays:Int=0)

@Serializable
@Entity(tableName="credit_limit_groups",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])],indices=[Index("bookId")])
data class CreditLimitGroupEntity(val bookId:String,val id:String,val name:String,val currency:String,val totalMinor:Long?,val usedMinor:Long?,val basisDate:String,val basisJson:String,val revision:Int,val createdAt:Long)
@Entity(tableName="credit_limit_group_members",primaryKeys=["bookId","accountId"],foreignKeys=[
    ForeignKey(entity=CreditLimitGroupEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","groupId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"])
],indices=[Index(value=["bookId","groupId"])])
data class CreditLimitGroupMemberEntity(val bookId:String,val accountId:String,val groupId:String)
@Entity(tableName="credit_limit_group_revisions",primaryKeys=["bookId","groupId","version"],foreignKeys=[ForeignKey(entity=CreditLimitGroupEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","groupId"])])
data class CreditLimitGroupRevisionEntity(val bookId:String,val groupId:String,val version:Int,val json:String,val createdAt:Long)

/** Each row is an actual platform recovery, not a repayment or an expense. */
@Serializable
@Entity(tableName="credit_limit_recoveries",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"])
],indices=[Index(value=["bookId","accountId"]),Index(value=["bookId","eventId"])])
data class CreditLimitRecoveryEntity(val bookId:String,val id:String,val accountId:String,val eventId:String,val date:String,val principalMinor:Long,
    val installmentMinor:Long?,val revolvingMinor:Long?,val sourceProof:String,val createdAt:Long,val voidedAt:Long?=null)

@Serializable
@Entity(tableName="credit_limit_observations",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=CreditLimitGroupEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","groupId"])
],indices=[Index(value=["bookId","accountId"]),Index(value=["bookId","groupId"])])
data class CreditLimitObservationEntity(val bookId:String,val id:String,val accountId:String?,val groupId:String?,val scope:String,val availableMinor:Long,val observedAt:String,val basisJson:String,val createdAt:Long)

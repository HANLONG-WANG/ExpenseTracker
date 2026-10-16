package com.yohaku.expensetracker.data.db

import androidx.room.*

@Entity(tableName="expected_incomes",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"])
],indices=[Index(value=["bookId","accountId"]),Index(value=["bookId","eventId"],unique=true)])
data class ExpectedIncomeEntity(val bookId:String,val id:String,val name:String,val amountMinor:Long,val currency:String,
    val date:String,val accountId:String,val accountMinor:Long?,val status:String="PENDING",val eventId:String?=null,val revision:Int=1,val updatedAt:Long,@ColumnInfo(defaultValue="NULL") val accountEstimateJson:String?=null)

package com.yohaku.expensetracker.data.db

import androidx.room.*

@Entity(tableName="financial_drafts",primaryKeys=["bookId","slot"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"])],indices=[Index(value=["bookId","accountId"])])
data class FinancialDraftEntity(val bookId:String,val slot:String,val accountId:String?,val contextJson:String,val valuesJson:String,val basis:String,val updatedAt:Long)

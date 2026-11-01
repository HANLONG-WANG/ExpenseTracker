package com.yohaku.expensetracker.data.db

import androidx.room.*

@Entity(tableName="entry_templates",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","categoryId"]),
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"])
],indices=[Index(value=["bookId","accountId"]),Index(value=["bookId","categoryId"]),Index(value=["bookId","activityId"])])
data class EntryTemplateEntity(val bookId:String,val id:String,val name:String,val kind:String,val categoryId:String,val accountId:String,val currency:String,
    val amountMinor:Long?=null,val merchant:String="",val merchantId:String?=null,val note:String="",val cardPaymentChoice:String?=null,val cardPaymentPeriods:Int?=null,
    val activityId:String?=null,val sortOrder:Int=0,val createdAt:Long)

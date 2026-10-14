package com.yohaku.expensetracker.data.db

import androidx.room.*

@Entity(tableName="books")
data class BookEntity(@PrimaryKey val id: String, val name: String, val currency: String, val startDate: String,
    val theme: String="magazine", val language: String="zh", val initialized: Boolean=false, val budgetMinor: Long?=null, @ColumnInfo(defaultValue="2") val setupStep: Int=2)

data class BookOverview(@Embedded val book:BookEntity,val accountCount:Int,val recordCount:Int)

@Entity(tableName="accounts", primaryKeys=["bookId","id"], foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])], indices=[Index("bookId")])
data class AccountEntity(val bookId: String, val id: String, val name: String, val type: String, val currency: String,
    val openingMinor: Long, val openingDate: String, val active: Boolean=true)

@Entity(tableName="categories", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","parentId"])
], indices=[Index("bookId"),Index(value=["bookId","parentId"])])
data class CategoryEntity(val bookId: String, val id: String, val name: String, val kind: String,
    val parentId: String?=null, val favorite: Boolean=false, val active: Boolean=true)

@Entity(tableName="events", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","categoryId"])
], indices=[Index("bookId"),Index(value=["bookId","accountId"]),Index(value=["bookId","categoryId"])])
@kotlinx.serialization.Serializable
data class EventEntity(val bookId: String, val id: String, val kind: String, val accountId: String, val categoryId: String?,
    val amountMinor: Long, val currency: String, val baseMinor: Long, val date: String, val merchant: String,
    val note: String, val historicalIncluded: Boolean=false, val revision: Int=1, val deletedAt: Long?=null, @ColumnInfo(defaultValue="0") val createdAt: Long=0)

@Entity(tableName="postings", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"])
], indices=[Index(value=["bookId","eventId"]),Index(value=["bookId","accountId"])])
data class PostingEntity(val bookId: String,val id: String,val eventId: String,val accountId: String,
    val deltaMinor: Long,val currency: String,val date: String,val confirmed: Boolean=true)

@Entity(tableName="revisions", primaryKeys=["bookId","id"], foreignKeys=[ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["bookId","eventId"])])
data class RevisionEntity(val bookId:String,val id:String,val eventId:String,val version:Int,val timestamp:Long,val beforeJson:String?,val afterJson:String)
@Entity(tableName="drafts", foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])])
data class DraftEntity(@PrimaryKey val bookId:String,val json:String,val updatedAt:Long)
@Entity(tableName="file_blobs",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])],indices=[Index("bookId")])
data class FileBlobEntity(val bookId:String,val id:String,val path:String,val originalName:String,val mime:String,val size:Long,val checksum:String)
@Entity(tableName="attachment_links",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=FileBlobEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","fileId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE)
],indices=[Index(value=["bookId","fileId"]),Index(value=["bookId","eventId"])])
data class AttachmentLinkEntity(val bookId:String,val id:String,val eventId:String,val fileId:String)

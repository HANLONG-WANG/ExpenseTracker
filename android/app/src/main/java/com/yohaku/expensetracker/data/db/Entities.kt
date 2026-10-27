package com.yohaku.expensetracker.data.db

import androidx.room.*

@Entity(tableName="books")
data class BookEntity(@PrimaryKey val id: String, val name: String, val currency: String, val startDate: String,
    val theme: String="magazine", val language: String="zh", val initialized: Boolean=false, val budgetMinor: Long?=null, @ColumnInfo(defaultValue="2") val setupStep: Int=2,
    @ColumnInfo(defaultValue="0") val archived:Boolean=false,@ColumnInfo(defaultValue="NULL") val deletedAt:Long?=null)

data class BookOverview(@Embedded val book:BookEntity,val accountCount:Int,val recordCount:Int,val monthlyBudgetMinor:Long?=book.budgetMinor,val planningCount:Int=0,val fileCount:Int=0,val financialPlanCount:Int=0,val statementCount:Int=0)

@Entity(tableName="accounts", primaryKeys=["bookId","id"], foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])], indices=[Index("bookId")])
data class AccountEntity(val bookId: String, val id: String, val name: String, val type: String, val currency: String,
    val openingMinor: Long, val openingDate: String, val active: Boolean=true)

@Entity(tableName="categories", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","parentId"])
], indices=[Index("bookId"),Index(value=["bookId","parentId"])])
data class CategoryEntity(val bookId: String, val id: String, val name: String, val kind: String,
    val parentId: String?=null, val favorite: Boolean=false, val active: Boolean=true,
    @ColumnInfo(defaultValue="NULL") val icon: String?=null)

@Entity(tableName="events", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","categoryId"])
], indices=[Index("bookId"),Index(value=["bookId","accountId"]),Index(value=["bookId","categoryId"])])
@kotlinx.serialization.Serializable
data class EventEntity(val bookId: String, val id: String, val kind: String, val accountId: String, val categoryId: String?,
    val amountMinor: Long, val currency: String, val baseMinor: Long, val date: String, val merchant: String,
    val note: String, val historicalIncluded: Boolean=false, val revision: Int=1, val deletedAt: Long?=null, @ColumnInfo(defaultValue="0") val createdAt: Long=0,
    @ColumnInfo(defaultValue="NULL") val cardPaymentChoice:String?=null,
    @ColumnInfo(defaultValue="NULL") val cardPaymentMode:String?=null,
    @ColumnInfo(defaultValue="NULL") val cardPaymentRuleId:String?=null,
    @ColumnInfo(defaultValue="NULL") val cardPaymentPeriods:Int?=null,
    @ColumnInfo(defaultValue="NULL") val deletionGroupId:String?=null,
    @ColumnInfo(defaultValue="NULL") val purgedAt:Long?=null)

@Entity(tableName="postings", primaryKeys=["bookId","id"], foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"])
], indices=[Index(value=["bookId","eventId"]),Index(value=["bookId","accountId"])])
@kotlinx.serialization.Serializable
data class PostingEntity(val bookId: String,val id: String,val eventId: String,val accountId: String,
    val deltaMinor: Long,val currency: String,val date: String,val confirmed: Boolean=true)

@Entity(tableName="revisions", primaryKeys=["bookId","id"], foreignKeys=[ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["bookId","eventId"])])
data class RevisionEntity(val bookId:String,val id:String,val eventId:String,val version:Int,val timestamp:Long,val beforeJson:String?,val afterJson:String,val beforeDetailsJson:String?=null,val afterDetailsJson:String?=null)
@Entity(tableName="drafts", foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])])
data class DraftEntity(@PrimaryKey val bookId:String,val json:String,val updatedAt:Long)
@Entity(tableName="file_blobs",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])],indices=[Index("bookId")])
data class FileBlobEntity(val bookId:String,val id:String,val path:String,val originalName:String,val mime:String,val size:Long,val checksum:String)
@Entity(tableName="attachment_links",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=FileBlobEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","fileId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE)
],indices=[Index(value=["bookId","fileId"]),Index(value=["bookId","eventId"])])
data class AttachmentLinkEntity(val bookId:String,val id:String,val eventId:String,val fileId:String)

/** Historical conversion and refund linkage belong to the event, never to a live rate cache. */
@Entity(tableName="settlements", primaryKeys=["bookId","eventId"], foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","originalEventId"])
], indices=[Index(value=["bookId","originalEventId"])])
@kotlinx.serialization.Serializable
data class SettlementEntity(val bookId:String,val eventId:String,val accountMinor:Long,val accountCurrency:String,
    val confirmed:Boolean,val settlementDate:String,val rateSource:String,val rateDate:String,
    val expectedAccountMinor:Long,val expectedBaseMinor:Long,val originalEventId:String?=null,@ColumnInfo(defaultValue="''") val manualRate:String="",@ColumnInfo(defaultValue="''") val expectedAccountCurrency:String="",@ColumnInfo(defaultValue="NULL") val referenceRatesJson:String?=null)

@Entity(tableName="transfers",primaryKeys=["bookId","eventId"],foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","toAccountId"])
],indices=[Index(value=["bookId","toAccountId"])])
@kotlinx.serialization.Serializable
data class TransferEntity(val bookId:String,val eventId:String,val toAccountId:String,val toMinor:Long,
    val fromCurrency:String,val toCurrency:String,val fromDate:String,val toDate:String,val fromConfirmed:Boolean,val toConfirmed:Boolean,
    val expectedToMinor:Long,val expectedToCurrency:String,val manualRate:String,val rateDate:String)

@Entity(tableName="expense_components",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"])
],indices=[Index(value=["bookId","eventId"]),Index(value=["bookId","accountId"])])
@kotlinx.serialization.Serializable
data class ExpenseComponentEntity(val bookId:String,val id:String,val eventId:String,val accountId:String,
    val amountMinor:Long,val currency:String,val baseMinor:Long,val date:String,val confirmed:Boolean,val position:String,val originalMinor:Long,val originalCurrency:String)

@Entity(tableName="budgets",primaryKeys=["bookId","month","scope"],foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","categoryId"])
],indices=[Index(value=["bookId","categoryId"])])
data class BudgetEntity(val bookId:String,val month:String,val scope:String,val categoryId:String?,val amountMinor:Long?)

@Entity(tableName="activities",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])],indices=[Index("bookId")])
data class ActivityEntity(val bookId:String,val id:String,val name:String,val startDate:String,val endDate:String,val budgetMinor:Long?,val excludeMonthly:Boolean=false,val ended:Boolean=false,@ColumnInfo(defaultValue="NULL") val deletedAt:Long?=null)

@Entity(tableName="book_features",foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activeActivityId"])],indices=[Index(value=["bookId","activeActivityId"])])
data class BookFeaturesEntity(@PrimaryKey val bookId:String,val activityMode:Boolean=false,val activeActivityId:String?=null)

@Entity(tableName="event_activities",primaryKeys=["bookId","eventId"],foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"])
],indices=[Index(value=["bookId","activityId"])])
data class EventActivityEntity(val bookId:String,val eventId:String,val activityId:String)

@Entity(tableName="recurrences",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","categoryId"]),
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"])
],indices=[Index(value=["bookId","accountId"]),Index(value=["bookId","categoryId"]),Index(value=["bookId","activityId"])])
data class RecurrenceEntity(val bookId:String,val id:String,val name:String,val accountId:String,val categoryId:String,val currency:String,
    val amountMinor:Long?,val baseMinor:Long?,val estimated:Boolean,val frequency:String,val anchorDate:String,val endDate:String?,val active:Boolean=true,val activityId:String?=null)

@Entity(tableName="occurrences",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=RecurrenceEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","recurrenceId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","categoryId"]),
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"])
],indices=[Index(value=["bookId","recurrenceId"]),Index(value=["bookId","eventId"]),Index(value=["bookId","accountId"]),Index(value=["bookId","categoryId"]),Index(value=["bookId","activityId"])])
data class OccurrenceEntity(val bookId:String,val id:String,val recurrenceId:String,val scheduledDate:String,val name:String,val accountId:String,val categoryId:String,
    val currency:String,val amountMinor:Long?,val baseMinor:Long?,val estimated:Boolean,val status:String="PENDING",val eventId:String?=null,val activityId:String?=null)

@Entity(tableName="activity_budgets",primaryKeys=["bookId","activityId","categoryId"],foreignKeys=[
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"]),
    ForeignKey(entity=CategoryEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","categoryId"])
],indices=[Index(value=["bookId","categoryId"])])
data class ActivityBudgetEntity(val bookId:String,val activityId:String,val categoryId:String,val amountMinor:Long)

@Entity(tableName="activity_reserves",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"]),
    ForeignKey(entity=AccountEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","accountId"]),
    ForeignKey(entity=OccurrenceEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","occurrenceId"])
],indices=[Index(value=["bookId","activityId"]),Index(value=["bookId","accountId"]),Index(value=["bookId","occurrenceId"],unique=true)])
data class ActivityReserveEntity(val bookId:String,val id:String,val activityId:String,val name:String,val date:String,val amountMinor:Long,
    val accountId:String?,val accountMinor:Long?,val accountCurrency:String?,val occurrenceId:String?,val releasedMinor:Long=0,val active:Boolean=true)

@Entity(tableName="reserve_uses",primaryKeys=["bookId","eventId","reserveId"],foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=ActivityReserveEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","reserveId"])
],indices=[Index(value=["bookId","reserveId"])])
@kotlinx.serialization.Serializable
data class ReserveUseEntity(val bookId:String,val eventId:String,val reserveId:String,val amountMinor:Long)

@Entity(tableName="friends",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])],indices=[Index("bookId")])
data class FriendEntity(val bookId:String,val id:String,val name:String,val active:Boolean=true)

@Entity(tableName="activity_participants",primaryKeys=["bookId","activityId","friendId"],foreignKeys=[
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"]),
    ForeignKey(entity=FriendEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","friendId"])
],indices=[Index(value=["bookId","friendId"])])
data class ActivityParticipantEntity(val bookId:String,val activityId:String,val friendId:String,val position:Int)

@Entity(tableName="treats",primaryKeys=["bookId","id"],foreignKeys=[
    ForeignKey(entity=ActivityEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","activityId"]),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=FriendEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","payerFriendId"])
],indices=[Index(value=["bookId","activityId"]),Index(value=["bookId","eventId"],unique=true),Index(value=["bookId","payerFriendId"])])
@kotlinx.serialization.Serializable
data class TreatEntity(val bookId:String,val id:String,val activityId:String,val eventId:String?,val payerFriendId:String?,
    val amountMinor:Long,val currency:String,val baseMinor:Long,val date:String,val name:String,val mode:String,val weightsJson:String,
    val revision:Int=1,val deletedAt:Long?=null)

@Entity(tableName="treat_allocations",primaryKeys=["bookId","treatId","participant"],foreignKeys=[
    ForeignKey(entity=TreatEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","treatId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=FriendEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","friendId"])
],indices=[Index(value=["bookId","friendId"])])
@kotlinx.serialization.Serializable
data class TreatAllocationEntity(val bookId:String,val treatId:String,val participant:String,val friendId:String?,val amountMinor:Long,val baseMinor:Long)

@Entity(tableName="analysis_preferences",foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])])
data class AnalysisPreferencesEntity(@PrimaryKey val bookId:String,val modulesJson:String,val pinnedJson:String)

@Entity(tableName="merchants",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])],indices=[Index("bookId")])
data class MerchantEntity(val bookId:String,val id:String,val name:String,val note:String="",val active:Boolean=true)

@Entity(tableName="merchant_locations",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=MerchantEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","merchantId"])],indices=[Index(value=["bookId","merchantId"])])
data class MerchantLocationEntity(val bookId:String,val id:String,val merchantId:String,val name:String,val latitude:Double?,val longitude:Double?,val radiusMeters:Int=100)

@Entity(tableName="event_merchants",primaryKeys=["bookId","eventId"],foreignKeys=[
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=MerchantEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","merchantId"])
],indices=[Index(value=["bookId","merchantId"])])
@kotlinx.serialization.Serializable
data class EventMerchantEntity(val bookId:String,val eventId:String,val merchantId:String)

@Entity(tableName="draft_media",primaryKeys=["bookId","fileId"],foreignKeys=[ForeignKey(entity=FileBlobEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","fileId"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["bookId","sessionId"])])
data class DraftMediaEntity(val bookId:String,val fileId:String,val sessionId:String)
@Entity(tableName="receipts",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["bookId","eventId"],unique=true)])
data class ReceiptEntity(val bookId:String,val id:String,val eventId:String,val revision:Int=1,val status:String="UNSORTED")
@Entity(tableName="receipt_images",primaryKeys=["bookId","receiptId","revision","position"],foreignKeys=[
    ForeignKey(entity=ReceiptEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","receiptId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=FileBlobEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","fileId"])
],indices=[Index(value=["bookId","fileId"])])
data class ReceiptImageEntity(val bookId:String,val receiptId:String,val revision:Int,val position:Int,val fileId:String)

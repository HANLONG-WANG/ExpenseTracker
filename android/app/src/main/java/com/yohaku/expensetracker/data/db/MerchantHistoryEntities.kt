package com.yohaku.expensetracker.data.db

import androidx.room.*
import com.yohaku.expensetracker.domain.merchant.*

/** Merchant IDs, display names and profiles are historical values, not live foreign keys. */
@Entity(tableName="merchant_operations",primaryKeys=["bookId","id"],
    foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"])],
    indices=[Index(value=["bookId","timestamp","id"]),Index(value=["bookId","sourceMerchantId"]),
        Index(value=["bookId","targetMerchantId"]),Index(value=["bookId","cloudExecutionId"])])
data class MerchantOperationEntity(
    val bookId:String,
    val id:String,
    val timestamp:Long,
    val type:MerchantOperationType,
    val source:MerchantOperationSource,
    val sourceMerchantId:String?=null,
    val sourceMerchantName:String?=null,
    val targetMerchantId:String?=null,
    val targetMerchantName:String?=null,
    val originalName:String?=null,
    @Embedded(prefix="sourceBefore_") val sourceBefore:MerchantProfile?=null,
    @Embedded(prefix="sourceAfter_") val sourceAfter:MerchantProfile?=null,
    @Embedded(prefix="targetBefore_") val targetBefore:MerchantProfile?=null,
    @Embedded(prefix="targetAfter_") val targetAfter:MerchantProfile?=null,
    @Embedded(prefix="count_") val counts:MerchantImpactCounts=MerchantImpactCounts(),
    val cloudPlanId:String?=null,
    val cloudExecutionId:String?=null,
    val cloudCommandId:String?=null,
    val undoOfOperationId:String?=null,
    val undoneByOperationId:String?=null,
    val undoneAt:Long?=null,
)

@Entity(tableName="merchant_operation_impacts",primaryKeys=["bookId","operationId","objectType","objectId"],
    foreignKeys=[ForeignKey(entity=MerchantOperationEntity::class,parentColumns=["bookId","id"],
        childColumns=["bookId","operationId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["objectType","objectId"]),Index(value=["bookId","objectType","objectId"])])
data class MerchantOperationImpactEntity(
    val bookId:String,
    val operationId:String,
    val objectType:MerchantObjectType,
    val objectId:String,
    // Null means no object at that side for creation/deletion; unlinked existing text may be empty.
    val oldMerchantName:String?=null,
    val newMerchantName:String?=null,
    val oldMerchantId:String?=null,
    val newMerchantId:String?=null,
)

/** On-demand pages only; deliberately absent from BookGraph / BookSnapshot. */
@Dao
interface MerchantHistoryDao {
    @Insert suspend fun insert(operation:MerchantOperationEntity)
    @Insert suspend fun insertImpacts(impacts:List<MerchantOperationImpactEntity>)
    @Query("SELECT * FROM merchant_operations WHERE bookId=:bookId AND id=:operationId")
    suspend fun operation(bookId:String,operationId:String):MerchantOperationEntity?
    @Query("SELECT * FROM merchant_operations WHERE bookId=:bookId ORDER BY timestamp DESC,id DESC LIMIT :limit OFFSET :offset")
    suspend fun page(bookId:String,limit:Int,offset:Int=0):List<MerchantOperationEntity>
    @Query("""SELECT * FROM merchant_operations WHERE bookId=:bookId
        AND (sourceMerchantId=:merchantId OR targetMerchantId=:merchantId)
        ORDER BY timestamp DESC,id DESC LIMIT :limit OFFSET :offset""")
    suspend fun forMerchant(bookId:String,merchantId:String,limit:Int,offset:Int=0):List<MerchantOperationEntity>
    @Query("""SELECT o.* FROM merchant_operations o JOIN merchant_operation_impacts i
        ON i.bookId=o.bookId AND i.operationId=o.id
        WHERE i.bookId=:bookId AND i.objectType=:objectType AND i.objectId=:objectId
        ORDER BY o.timestamp DESC,o.id DESC LIMIT :limit OFFSET :offset""")
    suspend fun forObject(bookId:String,objectType:MerchantObjectType,objectId:String,limit:Int,offset:Int=0):List<MerchantOperationEntity>
    @Query("""SELECT * FROM merchant_operation_impacts WHERE bookId=:bookId AND operationId=:operationId
        ORDER BY objectType,objectId LIMIT :limit OFFSET :offset""")
    suspend fun impacts(bookId:String,operationId:String,limit:Int,offset:Int=0):List<MerchantOperationImpactEntity>
    // Batch C calls this with the inverse operation insert in the same outer undo transaction.
    @Query("""UPDATE merchant_operations SET undoneByOperationId=:undoOperationId,undoneAt=:timestamp
        WHERE bookId=:bookId AND id=:operationId AND undoneByOperationId IS NULL""")
    suspend fun markUndone(bookId:String,operationId:String,undoOperationId:String,timestamp:Long):Int
}

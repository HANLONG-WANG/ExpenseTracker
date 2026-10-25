package com.yohaku.expensetracker.data.cloud

import androidx.room.*
import com.yohaku.expensetracker.data.db.BookEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName="cloud_tasks", foreignKeys=[ForeignKey(entity=BookEntity::class, parentColumns=["id"], childColumns=["bookId"])], indices=[Index("bookId")])
data class CloudTaskEntity(@PrimaryKey val localId:String, val bookId:String, val type:String, val title:String,
    val accountId:String?, val bundleId:String?, val origin:String, val idempotencyKey:String, val requestJson:String,
    val taskId:String?=null, val stage:String="PREPARED", val remoteStatus:String?=null, val inputRevision:Int?=null,
    val remoteJson:String?=null, val lastError:String?=null, val createdAt:Long, val updatedAt:Long, val attempts:Int=0)

@Entity(tableName="cloud_transfers", primaryKeys=["localId","position"], foreignKeys=[
    ForeignKey(entity=CloudTaskEntity::class,parentColumns=["localId"],childColumns=["localId"],onDelete=ForeignKey.CASCADE)])
data class CloudTransferEntity(val localId:String, val position:Int, val name:String, val path:String,
    val size:Long, val sha256:String, val mime:String, val manifestJson:String?=null, val fileId:String?=null,
    val attempts:Int=0, val uploadedAt:Long?=null, val supplement:Boolean=false)

// Import identities deliberately have no cascading foreign key: they are permanent deduplication records.
@Entity(tableName="cloud_imports", indices=[Index("localId")])
data class CloudImportEntity(@PrimaryKey val importKey:String, val localId:String, val taskId:String,
    val resultId:String, val resultVersion:Int, val sha256:String, val localRef:String, val importedAt:Long)

@Entity(tableName="cloud_acks", primaryKeys=["localId","resultId","status"])
data class CloudAckEntity(val localId:String, val resultId:String, val status:String, val taskId:String,
    val createdAt:Long, val sentAt:Long?=null, val lastError:String?=null)

data class CloudTaskRelations(@Embedded val task:CloudTaskEntity,
    @Relation(parentColumn="localId",entityColumn="localId") val transfers:List<CloudTransferEntity>,
    @Relation(parentColumn="localId",entityColumn="localId") val imports:List<CloudImportEntity>,
    @Relation(parentColumn="localId",entityColumn="localId") val acks:List<CloudAckEntity>)

@Dao
interface CloudDao {
    @Transaction @Query("SELECT * FROM cloud_tasks WHERE bookId=:bookId ORDER BY createdAt DESC")
    fun tasks(bookId:String):Flow<List<CloudTaskRelations>>
    @Query("SELECT * FROM cloud_tasks WHERE localId=:localId") suspend fun task(localId:String):CloudTaskEntity?
    @Query("SELECT * FROM cloud_tasks WHERE bookId=:bookId AND taskId IS NOT NULL AND (remoteStatus IS NULL OR remoteStatus!='archived')")
    suspend fun active(bookId:String):List<CloudTaskEntity>
    @Query("SELECT * FROM cloud_tasks WHERE stage IN ('PREPARED','UPLOADING')") suspend fun unfinished():List<CloudTaskEntity>
    @Insert suspend fun insertTask(task:CloudTaskEntity)
    @Update suspend fun updateTask(task:CloudTaskEntity)
    @Query("DELETE FROM cloud_tasks WHERE localId=:localId") suspend fun deleteTask(localId:String)
    @Query("SELECT * FROM cloud_transfers WHERE localId=:localId ORDER BY position") suspend fun transfers(localId:String):List<CloudTransferEntity>
    @Insert suspend fun insertTransfers(transfers:List<CloudTransferEntity>)
    @Update suspend fun updateTransfer(transfer:CloudTransferEntity)
    @Query("SELECT * FROM cloud_imports WHERE importKey=:key") suspend fun imported(key:String):CloudImportEntity?
    @Insert suspend fun insertImport(value:CloudImportEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertAck(value:CloudAckEntity)
    @Update suspend fun updateAck(value:CloudAckEntity)
    @Query("SELECT * FROM cloud_acks WHERE sentAt IS NULL ORDER BY createdAt") suspend fun pendingAcks():List<CloudAckEntity>
}

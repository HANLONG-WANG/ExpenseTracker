package com.yohaku.expensetracker.data.command

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Deliberately no cascading book/task FK: execution identities survive deletion and reconnects.
@Entity(tableName="command_plans", indices=[Index("bookId"),Index(value=["sourceKey"],unique=true)])
data class CommandPlanEntity(@PrimaryKey val planId:String,val bookId:String,val localId:String,val origin:String,
    val taskId:String,val resultId:String,val resultVersion:Int,val sha256:String,val sourceKey:String,
    val planJson:String,val contextDigest:String,val status:String="PENDING",val normalizedJson:String?=null,
    val confirmationJson:String?=null,val executionContextJson:String?=null,val checksJson:String="[]",
    val error:String?=null,val createdAt:Long,val executedAt:Long?=null,val executionJson:String?=null)

@Entity(tableName="command_effects",indices=[Index("planId")])
data class CommandEffectEntity(@PrimaryKey val id:String,val planId:String,val stepId:String,val mode:String,
    val payloadJson:String,val status:String="PENDING",val attempts:Int=0,val lastError:String?=null)

@Dao interface CommandDao {
    @Query("SELECT * FROM command_plans WHERE bookId=:bookId ORDER BY createdAt DESC") fun history(bookId:String):Flow<List<CommandPlanEntity>>
    @Query("SELECT * FROM command_plans WHERE planId=:id") suspend fun plan(id:String):CommandPlanEntity?
    @Query("SELECT * FROM command_plans WHERE sourceKey=:key") suspend fun source(key:String):CommandPlanEntity?
    @Insert suspend fun insert(plan:CommandPlanEntity)
    @Update suspend fun update(plan:CommandPlanEntity)
    @Insert suspend fun enqueue(effect:CommandEffectEntity)
    @Query("SELECT * FROM command_effects WHERE status IN ('PENDING','RETRY')") suspend fun pendingEffects():List<CommandEffectEntity>
    @Update suspend fun updateEffect(effect:CommandEffectEntity)
}

/** Stage 1 only registers transactional adapters. Durable queue contract for later workers. */
class CommandEffectQueue(private val dao:CommandDao) {
    suspend fun pending()=dao.pendingEffects()
    suspend fun retry(effect:CommandEffectEntity,error:String)=dao.updateEffect(effect.copy(status="RETRY",attempts=effect.attempts+1,lastError=error))
}

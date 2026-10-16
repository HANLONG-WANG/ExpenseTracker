package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface CreditLimitDao {
    @Transaction @Query("SELECT * FROM books WHERE id=:bookId") fun graph(bookId:String):Flow<CreditLimitGraph?>
    @Insert suspend fun insertConfig(config:CreditLimitConfigEntity)
    @Upsert suspend fun putGroup(group:CreditLimitGroupEntity)
    @Query("DELETE FROM credit_limit_group_members WHERE bookId=:bookId AND groupId=:groupId") suspend fun clearMembers(bookId:String,groupId:String)
    @Insert suspend fun insertMembers(members:List<CreditLimitGroupMemberEntity>)
    @Insert suspend fun insertGroupRevision(revision:CreditLimitGroupRevisionEntity)
    @Query("SELECT * FROM credit_limit_group_revisions WHERE bookId=:bookId AND groupId=:groupId ORDER BY version DESC") fun groupRevisions(bookId:String,groupId:String):Flow<List<CreditLimitGroupRevisionEntity>>
    @Insert suspend fun insertRecovery(recovery:CreditLimitRecoveryEntity)
    @Query("UPDATE credit_limit_recoveries SET voidedAt=:time WHERE bookId=:bookId AND id=:id AND voidedAt IS NULL") suspend fun voidRecovery(bookId:String,id:String,time:Long)
    @Insert suspend fun insertObservation(observation:CreditLimitObservationEntity)
}
data class CreditLimitGraph(@Embedded val book:BookEntity,
    @Relation(parentColumn="id",entityColumn="bookId") val configs:List<CreditLimitConfigEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val groups:List<CreditLimitGroupEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val members:List<CreditLimitGroupMemberEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val recoveries:List<CreditLimitRecoveryEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val observations:List<CreditLimitObservationEntity>)

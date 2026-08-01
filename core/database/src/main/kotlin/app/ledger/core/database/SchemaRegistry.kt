package app.ledger.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "_room_schema_registry")
internal data class PrimarySchemaRegistryEntity(
    @PrimaryKey val id: Int,
    val logicalSchemaVersion: Int,
    val contractSha256: String,
)

@Entity(tableName = "_staging_room_schema_registry")
internal data class StagingSchemaRegistryEntity(
    @PrimaryKey val id: Int,
    val logicalSchemaVersion: Int,
    val contractSha256: String,
)

@Dao
internal interface PrimarySchemaRegistryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: PrimarySchemaRegistryEntity)

    @Query(
        "UPDATE _room_schema_registry " +
            "SET logicalSchemaVersion = :version, contractSha256 = :sha256 WHERE id = 1",
    )
    suspend fun update(version: Int, sha256: String): Int
}

@Dao
internal interface StagingSchemaRegistryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: StagingSchemaRegistryEntity)

    @Query(
        "UPDATE _staging_room_schema_registry " +
            "SET logicalSchemaVersion = :version, contractSha256 = :sha256 WHERE id = 1",
    )
    suspend fun update(version: Int, sha256: String): Int
}

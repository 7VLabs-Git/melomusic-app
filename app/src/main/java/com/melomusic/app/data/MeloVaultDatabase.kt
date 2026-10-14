// File: app/src/main/java/com/melomusic/app/data/MeloVaultDatabase.kt
package com.melomusic.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "vault_kv_store")
data class VaultEntryEntity(
    @PrimaryKey val key: String,
    val jsonPayload: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface VaultDao {
    @Query("SELECT jsonPayload FROM vault_kv_store WHERE `key` = :key LIMIT 1")
    suspend fun getPayload(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPayload(entry: VaultEntryEntity)
}

@Database(entities = [VaultEntryEntity::class], version = 1, exportSchema = false)
abstract class MeloVaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: MeloVaultDatabase? = null

        fun getInstance(context: Context): MeloVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeloVaultDatabase::class.java,
                    "melo_sanctuary_vault.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
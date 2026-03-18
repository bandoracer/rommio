package io.github.mattsays.rommnative.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.SaveStateEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey val id: String,
    val label: String,
    val baseUrl: String,
    val edgeAuthMode: String,
    val originAuthMode: String,
    val capabilitiesJson: String,
    val serverAccessJson: String,
    val sessionStateJson: String,
    val isActive: Boolean,
    val status: String,
    val lastValidationAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Dao
interface DownloadedRomDao {
    @Query("SELECT * FROM downloaded_roms WHERE romId = :romId ORDER BY downloadedAtEpochMs DESC")
    fun observeByRomId(romId: Int): Flow<List<DownloadedRomEntity>>

    @Query("SELECT * FROM downloaded_roms WHERE romId = :romId AND fileId = :fileId LIMIT 1")
    suspend fun getByIds(romId: Int, fileId: Int): DownloadedRomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedRomEntity)

    @Query("DELETE FROM downloaded_roms WHERE romId = :romId AND fileId = :fileId")
    suspend fun delete(romId: Int, fileId: Int)
}

@Dao
interface SaveStateDao {
    @Query("SELECT * FROM save_states WHERE romId = :romId ORDER BY slot ASC")
    fun observeByRomId(romId: Int): Flow<List<SaveStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SaveStateEntity)

    @Query("DELETE FROM save_states WHERE romId = :romId AND slot = :slot")
    suspend fun delete(romId: Int, slot: Int)
}

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    fun observeActive(): Flow<ServerProfileEntity?>

    @Query("SELECT * FROM server_profiles WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActive(): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles ORDER BY updatedAt DESC")
    suspend fun listAll(): List<ServerProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ServerProfileEntity)

    @Query("UPDATE server_profiles SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END, updatedAt = :updatedAt")
    suspend fun setActiveOnly(id: String, updatedAt: String)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        DownloadedRomEntity::class,
        SaveStateEntity::class,
        ServerProfileEntity::class,
        TouchLayoutProfileEntity::class,
        HardwareBindingProfileEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadedRomDao(): DownloadedRomDao
    abstract fun saveStateDao(): SaveStateDao
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun touchLayoutProfileDao(): TouchLayoutProfileDao
    abstract fun hardwareBindingProfileDao(): HardwareBindingProfileDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS server_profiles (
                      id TEXT NOT NULL PRIMARY KEY,
                      label TEXT NOT NULL,
                      baseUrl TEXT NOT NULL,
                      edgeAuthMode TEXT NOT NULL,
                      originAuthMode TEXT NOT NULL,
                      capabilitiesJson TEXT NOT NULL,
                      serverAccessJson TEXT NOT NULL,
                      sessionStateJson TEXT NOT NULL,
                      isActive INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      lastValidationAt TEXT,
                      createdAt TEXT NOT NULL,
                      updatedAt TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_server_profiles_isActive_updatedAt ON server_profiles(isActive, updatedAt)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS touch_layout_profiles (
                      platformFamilyId TEXT NOT NULL PRIMARY KEY,
                      presetId TEXT NOT NULL,
                      layoutJson TEXT NOT NULL,
                      opacity REAL NOT NULL,
                      globalScale REAL NOT NULL,
                      leftHanded INTEGER NOT NULL,
                      updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hardware_binding_profiles (
                      platformFamilyId TEXT NOT NULL PRIMARY KEY,
                      controllerTypeId INTEGER,
                      deadzone REAL NOT NULL,
                      bindingsJson TEXT NOT NULL,
                      updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}

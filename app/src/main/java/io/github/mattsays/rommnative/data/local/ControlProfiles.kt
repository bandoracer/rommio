package io.github.mattsays.rommnative.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "touch_layout_profiles")
data class TouchLayoutProfileEntity(
    @PrimaryKey val platformFamilyId: String,
    val presetId: String,
    val layoutJson: String,
    val opacity: Float,
    val globalScale: Float,
    val leftHanded: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "hardware_binding_profiles")
data class HardwareBindingProfileEntity(
    @PrimaryKey val platformFamilyId: String,
    val controllerTypeId: Int?,
    val deadzone: Float,
    val bindingsJson: String,
    val updatedAtEpochMs: Long,
)

@Dao
interface TouchLayoutProfileDao {
    @Query("SELECT * FROM touch_layout_profiles WHERE platformFamilyId = :platformFamilyId LIMIT 1")
    fun observeByFamilyId(platformFamilyId: String): Flow<TouchLayoutProfileEntity?>

    @Query("SELECT * FROM touch_layout_profiles WHERE platformFamilyId = :platformFamilyId LIMIT 1")
    suspend fun getByFamilyId(platformFamilyId: String): TouchLayoutProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TouchLayoutProfileEntity)

    @Query("DELETE FROM touch_layout_profiles WHERE platformFamilyId = :platformFamilyId")
    suspend fun delete(platformFamilyId: String)
}

@Dao
interface HardwareBindingProfileDao {
    @Query("SELECT * FROM hardware_binding_profiles WHERE platformFamilyId = :platformFamilyId LIMIT 1")
    fun observeByFamilyId(platformFamilyId: String): Flow<HardwareBindingProfileEntity?>

    @Query("SELECT * FROM hardware_binding_profiles WHERE platformFamilyId = :platformFamilyId LIMIT 1")
    suspend fun getByFamilyId(platformFamilyId: String): HardwareBindingProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HardwareBindingProfileEntity)
}

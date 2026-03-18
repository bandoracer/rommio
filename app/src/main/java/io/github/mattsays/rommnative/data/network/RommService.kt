package io.github.mattsays.rommnative.data.network

import io.github.mattsays.rommnative.model.DeviceRegistrationRequest
import io.github.mattsays.rommnative.model.DeviceRegistrationResponse
import io.github.mattsays.rommnative.model.HeartbeatDto
import io.github.mattsays.rommnative.model.ItemsResponse
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.SaveDto
import io.github.mattsays.rommnative.model.StateDto
import io.github.mattsays.rommnative.model.UserDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Url

interface RommService {
    @GET("api/users/me")
    suspend fun getCurrentUser(): UserDto

    @GET("api/heartbeat")
    suspend fun getHeartbeat(): HeartbeatDto

    @GET("api/platforms")
    suspend fun getPlatforms(): List<PlatformDto>

    @GET("api/roms?order_by=id&order_dir=desc&limit=15&group_by_meta_id=1")
    suspend fun getRecentlyAdded(): ItemsResponse<RomDto>

    @GET("api/roms")
    suspend fun getRomsByPlatform(
        @Query("platform_ids") platformIds: Int,
        @Query("platform_id") legacyPlatformId: Int,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("group_by_meta_id") groupByMetaId: Int = 1,
    ): ItemsResponse<RomDto>

    @GET("api/roms/{romId}")
    suspend fun getRomById(@retrofit2.http.Path("romId") romId: Int): RomDto

    @GET("api/saves")
    suspend fun listSaves(
        @Query("rom_id") romId: Int,
        @Query("device_id") deviceId: String? = null,
    ): List<SaveDto>

    @GET("api/states")
    suspend fun listStates(
        @Query("rom_id") romId: Int,
    ): List<StateDto>

    @POST("api/devices")
    suspend fun registerDevice(
        @Body request: DeviceRegistrationRequest,
    ): DeviceRegistrationResponse

    @POST
    suspend fun markSaveDownloaded(
        @Url url: String,
        @Body request: RequestBody,
    ): SaveDto

    @Multipart
    @POST("api/saves")
    suspend fun uploadSave(
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String?,
        @Query("slot") slot: String?,
        @Query("device_id") deviceId: String?,
        @Query("overwrite") overwrite: Boolean?,
        @Part saveFile: MultipartBody.Part,
    ): SaveDto

    @Multipart
    @POST("api/states")
    suspend fun uploadState(
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String?,
        @Part stateFile: MultipartBody.Part,
    ): StateDto
}

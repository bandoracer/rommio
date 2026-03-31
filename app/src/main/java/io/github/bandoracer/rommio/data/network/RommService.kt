package io.github.bandoracer.rommio.data.network

import io.github.bandoracer.rommio.model.DeviceRegistrationRequest
import io.github.bandoracer.rommio.model.DeviceRegistrationResponse
import io.github.bandoracer.rommio.model.HeartbeatDto
import io.github.bandoracer.rommio.model.ItemsResponse
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.CollectionResponseDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.SaveDto
import io.github.bandoracer.rommio.model.SmartCollectionResponseDto
import io.github.bandoracer.rommio.model.StateDto
import io.github.bandoracer.rommio.model.UserDto
import io.github.bandoracer.rommio.model.VirtualCollectionResponseDto
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
    suspend fun getRoms(
        @Query("platform_ids") platformIds: Int? = null,
        @Query("platform_id") legacyPlatformId: Int? = null,
        @Query("collection_id") collectionId: Int? = null,
        @Query("smart_collection_id") smartCollectionId: Int? = null,
        @Query("virtual_collection_id") virtualCollectionId: String? = null,
        @Query("last_played") lastPlayed: Boolean? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("group_by_meta_id") groupByMetaId: Int = 1,
        @Query("order_by") orderBy: String? = null,
        @Query("order_dir") orderDir: String? = null,
    ): ItemsResponse<RomDto>

    @GET("api/roms/{romId}")
    suspend fun getRomById(@retrofit2.http.Path("romId") romId: Int): RomDto

    @GET("api/collections")
    suspend fun getCollections(): List<CollectionResponseDto>

    @GET("api/collections/smart")
    suspend fun getSmartCollections(): List<SmartCollectionResponseDto>

    @GET("api/collections/virtual")
    suspend fun getVirtualCollections(
        @Query("type") type: String = "all",
        @Query("limit") limit: Int? = null,
    ): List<VirtualCollectionResponseDto>

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

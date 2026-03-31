package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import io.github.bandoracer.rommio.domain.input.HotkeyAction
import java.io.File
import kotlinx.coroutines.flow.Flow

enum class PlayerCapability {
    READY,
    MISSING_CORE,
    MISSING_BIOS,
    UNSUPPORTED,
}

enum class EmbeddedSupportTier {
    TOUCH_SUPPORTED,
    CONTROLLER_SUPPORTED,
    UNSUPPORTED,
}

enum class PlayerShader {
    DEFAULT,
    CRT,
    LCD,
    SHARP,
}

enum class CoreDistributionProvider {
    LIBRETRO_BUILDBOT,
}

enum class AndroidCoreAbi(val folderName: String, private val androidAbi: String) {
    ARM64_V8A("arm64-v8a", "arm64-v8a"),
    ARMEABI_V7A("armeabi-v7a", "armeabi-v7a"),
    X86_64("x86_64", "x86_64"),
    X86("x86", "x86");

    companion object {
        fun fromDeviceAbis(deviceAbis: Array<String>): AndroidCoreAbi? {
            return deviceAbis.firstNotNullOfOrNull { abi ->
                entries.firstOrNull { it.androidAbi == abi }
            }
        }
    }
}

data class CoreDownloadDescriptor(
    val provider: CoreDistributionProvider,
    val artifactBaseName: String,
    val documentationUrl: String? = null,
) {
    fun archiveFileName(): String = "${artifactBaseName}_libretro_android.so.zip"

    fun extractedFileName(): String = "${artifactBaseName}_libretro_android.so"

    fun archiveUrl(abi: AndroidCoreAbi): String {
        return when (provider) {
            CoreDistributionProvider.LIBRETRO_BUILDBOT ->
                "https://buildbot.libretro.com/nightly/android/latest/${abi.folderName}/${archiveFileName()}"
        }
    }
}

data class RuntimeProfile(
    val runtimeId: String,
    val displayName: String,
    val platformSlugs: Set<String>,
    val libraryFileName: String,
    val defaultVariables: Map<String, String> = emptyMap(),
    val supportedExtensions: Set<String> = emptySet(),
    val requiredBiosFiles: List<String> = emptyList(),
    val supportsSaveStates: Boolean = true,
    val shader: PlayerShader = PlayerShader.DEFAULT,
    val download: CoreDownloadDescriptor? = null,
)

data class PlatformRuntimeFamily(
    val familyId: String,
    val displayName: String,
    val platformSlugs: Set<String>,
    val supportTier: EmbeddedSupportTier,
    val defaultRuntimeId: String,
    val runtimeOptions: List<RuntimeProfile>,
)

data class CoreResolution(
    val capability: PlayerCapability,
    val platformFamily: PlatformRuntimeFamily? = null,
    val runtimeProfile: RuntimeProfile? = null,
    val availableProfiles: List<RuntimeProfile> = emptyList(),
    val coreLibrary: File? = null,
    val missingBios: List<String> = emptyList(),
    val message: String? = null,
)

data class PlayerSession(
    val romId: Int,
    val romTitle: String,
    val romPath: File,
    val coreLibrary: File,
    val runtimeProfile: RuntimeProfile,
    val systemDirectory: File,
    val savesDirectory: File,
    val saveRamFile: File,
    val saveStatesDirectory: File,
    val variables: Map<String, String> = emptyMap(),
    val initialSaveRam: ByteArray? = null,
)

enum class PlayerMotionSource {
    DPAD,
    ANALOG_LEFT,
    ANALOG_RIGHT,
    POINTER,
}

data class PlayerInputConfiguration(
    val deadzone: Float = 0.2f,
)

data class PlayerControllerDescriptor(
    val id: Int,
    val description: String,
)

data class PlayerRumbleSignal(
    val port: Int,
    val weakStrength: Float,
    val strongStrength: Float,
)

interface CoreResolver {
    fun resolve(platformSlug: String, fileExtension: String?): CoreResolution
    fun platformSupport(platformSlug: String): PlatformRuntimeFamily?
    fun supportedPlatforms(): List<PlatformRuntimeFamily>
}

interface PlayerEngine {
    fun createOrAttachView(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        session: PlayerSession,
    ): View

    suspend fun persistSaveRam(): File?
    suspend fun saveState(slot: Int): File
    suspend fun loadState(file: File): Boolean
    suspend fun setPaused(paused: Boolean)
    suspend fun reset()
    suspend fun updateVariables(variables: Map<String, String>)
    suspend fun dispatchDigital(keyCode: Int, pressed: Boolean, port: Int = 0)
    suspend fun dispatchMotion(source: PlayerMotionSource, x: Float, y: Float, port: Int = 0)
    suspend fun updateInputConfiguration(configuration: PlayerInputConfiguration)
    suspend fun availableControllerTypes(port: Int = 0): List<PlayerControllerDescriptor>
    suspend fun setControllerType(port: Int, controllerTypeId: Int)
    fun rumbleSignals(): Flow<PlayerRumbleSignal>
    fun hotkeySignals(): Flow<HotkeyAction>
    fun detach()
}

package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.domain.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

sealed interface CorePersistenceSupport {
    data class Qualified(val contract: MemoryOnlyPersistence) : CorePersistenceSupport
    data class Unsupported(val reason: String) : CorePersistenceSupport
}

/** Explicit audited cartridge layouts; exposing SRAM does not qualify arbitrary boards or disk saves. */
class FceummPersistenceRegistry(private val packaged: PackagedCoreArtifacts) {
    suspend fun qualify(session: PlayerSession, actual: CoreMemorySnapshot): CorePersistenceSupport = withContext(Dispatchers.IO) {
        val core = session.coreIdentity ?: return@withContext CorePersistenceSupport.Unsupported("A verified core build is required.")
        val binding = session.progressBinding ?: return@withContext CorePersistenceSupport.Unsupported("Map legacy progress to a verified account and content before using the new save format.")
        require(actual.coreIdentity == core && actual.binding == binding && actual.firmwareSha256 == session.firmwareSha256)
        if (core.runtimeId != "fceumm") return@withContext qualifyCartridge(session, actual)
        if (packaged.verifiedMemoryInventory(session.runtimeProfile,
                InstalledCore(session.coreLibrary, core)) != PersistentMemory.FCEUMM_SEGMENTS_V2) {
            return@withContext CorePersistenceSupport.Unsupported("This core build has no qualified persistence descriptor.")
        }
        val file = binding.files.singleOrNull()
        if (file == null || file.discIndex != 0 || file.containerSha256 != null || file.memberPath != null) {
            return@withContext CorePersistenceSupport.Unsupported("Archive members and disc sets need their own persistence qualification.")
        }
        ProgressProtocol.validateBinding(binding)
        require(binding.romId == session.romId.toLong() && session.romPath.isFile && !Files.isSymbolicLink(session.romPath.toPath()))
        require(CoreBinary.sha256(session.romPath) == file.contentSha256) { "Installed content changed before persistence qualification." }
        val magic = session.romPath.inputStream().use { input -> ByteArray(4).also { require(input.read(it) == 4) } }
        if (!magic.contentEquals(byteArrayOf(0x4e, 0x45, 0x53, 0x1a))) {
            return@withContext CorePersistenceSupport.Unsupported("This content format is not qualified for cartridge memory saves.")
        }
        // This comes from the active iNES loader after header/database correction, not the filename/header mapper.
        val descriptor = actual.persistenceDescriptor.orEmpty()
        val match = Regex("fceumm-ines-v1:([12]):([0-9]{1,4}):([0-9]{1,2}):([01]):([0-9]{1,9}):([0-9]{1,9})").matchEntire(descriptor)
            ?: return@withContext CorePersistenceSupport.Unsupported("The loaded core did not report a recognized cartridge layout.")
        val version = match.groupValues[1].toInt()
        val mapper = match.groupValues[2].toInt()
        val submapper = match.groupValues[3].toInt()
        val battery = match.groupValues[4] == "1"
        val prgNonvolatile = match.groupValues[5].toInt()
        val chrNonvolatile = match.groupValues[6].toInt()
        if (chrNonvolatile != 0 || prgNonvolatile > 8192 || (!battery && prgNonvolatile > 0)) {
            return@withContext CorePersistenceSupport.Unsupported("Declared nonvolatile cartridge memory is not covered by the qualified regions.")
        }
        if (mapper !in setOf(0, 19) || submapper != 0) {
            return@withContext CorePersistenceSupport.Unsupported("Mapper $mapper, submapper $submapper needs persistence qualification before using the new save format.")
        }
        val sizes = PersistentMemory.FCEUMM_SEGMENTS_V2.associateWith { region ->
            when {
                battery && region == PersistentMemory.SRAM -> 8192
                battery && mapper == 19 && region == PersistentMemory.BATTERY_1 -> 128
                else -> 0
            }
        }
        val contract = MemoryOnlyPersistence(core.runtimeId, core.sha256, core.abi,
            ProgressSaveCompatibility("nes", "fceumm-cart", "ines$version-m$mapper-s$submapper-b${if (battery) 1 else 0}-p$prgNonvolatile-c$chrNonvolatile",
                core.runtimeId, allowsEmpty = !battery),
            sizes.filterValues { it > 0 }.keys, "fceumm-native-v1", MemoryRestoreOrder.STATE_THEN_MEMORY,
            PersistentMemory.FCEUMM_SEGMENTS_V2, descriptor, sizes)
        // A recognized mapper with an unexpected region or length is an error, never a smaller successful save.
        contract.validateMemory(actual)
        CorePersistenceSupport.Qualified(contract)
    }
    private fun qualifyCartridge(session: PlayerSession, actual: CoreMemorySnapshot): CorePersistenceSupport {
        val core = requireNotNull(session.coreIdentity)
        val abi = AndroidCoreAbi.entries.singleOrNull { it.folderName == core.abi }
            ?: return CorePersistenceSupport.Unsupported("Unsupported runtime architecture")
        val pin = packaged.find(session.runtimeProfile, abi)
            ?: return CorePersistenceSupport.Unsupported("This core has no pinned persistence adapter")
        require(core == pin.identity && CoreBinary.validate(session.coreLibrary, abi) == core.sha256)
        val file = requireNotNull(session.progressBinding).files.singleOrNull()
        if (file == null || file.discIndex != 0 || file.containerSha256 != null || file.memberPath != null)
            return CorePersistenceSupport.Unsupported("Archive and disc installs need separate persistence adapters")
        require(CoreBinary.sha256(session.romPath) == file.contentSha256)
        val descriptor = actual.persistenceDescriptor.orEmpty()
        if (descriptor == "rommio-files-v1:ppsspp:psp" && core.runtimeId == "ppsspp") {
            val contract = MemoryOnlyPersistence(core.runtimeId, core.sha256, core.abi,
                ProgressSaveCompatibility("psp", "ppsspp-memory-stick", "v1", core.runtimeId, false),
                setOf(PersistentMemory.SRAM), "ppsspp-native-v1", MemoryRestoreOrder.FILES_THEN_STATE,
                PersistentMemory.STANDARD, descriptor)
            contract.validateMemory(actual)
            return CorePersistenceSupport.Qualified(contract)
        }
        val fields = descriptor.split(':')
        if (fields.size != 5 || fields[0] != "rommio-cart-v1" || fields[1] != core.runtimeId)
            return CorePersistenceSupport.Unsupported("This cartridge layout needs persistence qualification")
        val ram = fields[3].toIntOrNull() ?: return CorePersistenceSupport.Unsupported("Invalid save layout")
        val rtc = fields[4].toIntOrNull() ?: return CorePersistenceSupport.Unsupported("Invalid clock layout")
        val valid = when (core.runtimeId to fields[2]) {
            "gambatte" to "gb" -> ram in setOf(0, 512, 2048, 8192, 32768, 65536, 131072) && rtc in setOf(0, 24)
            "mgba" to "gba" -> ram == 131080 && rtc == 24
            "snes9x" to "snes" -> ram in 0..131072 && (ram == 0 || Integer.bitCount(ram) == 1) && rtc == 0
            "melonds_ds" to "nds" -> (ram - 2308) in setOf(512, 8192, 65536, 131072, 262144, 524288, 1048576, 8388608) && rtc == 0
            "genesis_plus_gx" to "genesis", "genesis_plus_gx" to "sms", "genesis_plus_gx" to "gamegear" -> ram in setOf(0, 65536) && rtc == 0
            "genesis_plus_gx" to "segacd" -> (ram - 8192) in setOf(0, 16384, 32768, 65536, 131072, 262144, 524288) && rtc == 0
            "mupen64plus_next_gles3" to "n64" -> ram == 296960 && rtc == 24
            "pcsx_rearmed" to "psx" -> ram == 262144 && rtc == 0
            else -> false
        }
        if (!valid) return CorePersistenceSupport.Unsupported("Unrecognized complete cartridge save format")
        val sizes = mapOf(PersistentMemory.SRAM to ram, PersistentMemory.RTC to rtc)
        val contract = MemoryOnlyPersistence(core.runtimeId, core.sha256, core.abi,
            ProgressSaveCompatibility(fields[2], "${core.runtimeId}-cart", "v1-s${ram}-r${rtc}", core.runtimeId, ram == 0 && rtc == 0),
            sizes.filterValues { it > 0 }.keys, "${core.runtimeId}-native-v1", MemoryRestoreOrder.STATE_THEN_MEMORY,
            PersistentMemory.STANDARD, descriptor, sizes)
        contract.validateMemory(actual)
        return CorePersistenceSupport.Qualified(contract)
    }

}

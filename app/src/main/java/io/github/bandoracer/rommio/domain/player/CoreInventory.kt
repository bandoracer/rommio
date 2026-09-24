package io.github.bandoracer.rommio.domain.player

/** Where one emulator core stands on this device (Settings › Cores and BIOS; iOS `CoreInventoryEntry`). */
enum class CoreInventoryStatus(val label: String) {
    READY("Ready"),
    /** Not on the device yet; the app installs it the first time a game needs it. */
    SETS_UP_ON_FIRST_PLAY("Sets up on first play"),
    NEEDS_BIOS("Needs BIOS"),
    NOT_INCLUDED("Not included"),
    UNSUPPORTED("Unsupported"),
    ;

    /** Shipped in (or installable by) this build; the rest sit under "Not included in this build". */
    val inBuild: Boolean get() = this != NOT_INCLUDED && this != UNSUPPORTED
}

/** One platform family's core and its status. */
data class CoreInventoryEntry(
    val familyId: String,
    val familyName: String,
    val runtimeName: String,
    val supportTier: EmbeddedSupportTier,
    val status: CoreInventoryStatus,
    val missingBios: List<String> = emptyList(),
)

/** The runtime inventory: every family the player knows, resolved against this device. */
object CoreInventory {
    fun entry(family: PlatformRuntimeFamily, resolution: CoreResolution): CoreInventoryEntry {
        val profile = resolution.runtimeProfile
            ?: family.runtimeOptions.firstOrNull { it.runtimeId == family.defaultRuntimeId }
            ?: family.runtimeOptions.firstOrNull()
        val status = when (resolution.capability) {
            PlayerCapability.READY -> CoreInventoryStatus.READY
            PlayerCapability.MISSING_BIOS -> CoreInventoryStatus.NEEDS_BIOS
            PlayerCapability.MISSING_CORE ->
                if (profile?.download != null) CoreInventoryStatus.SETS_UP_ON_FIRST_PLAY else CoreInventoryStatus.NOT_INCLUDED
            PlayerCapability.UNSUPPORTED -> CoreInventoryStatus.UNSUPPORTED
        }
        return CoreInventoryEntry(
            familyId = family.familyId,
            familyName = family.displayName,
            runtimeName = profile?.displayName ?: "No core",
            supportTier = family.supportTier,
            status = status,
            missingBios = resolution.missingBios,
        )
    }

    /** Families resolved on their first platform (sorted for a stable pick), in catalogue order. */
    fun resolve(resolver: CoreResolver): List<CoreInventoryEntry> = resolver.supportedPlatforms().map { family ->
        entry(family, resolver.resolve(family.platformSlugs.minOrNull().orEmpty(), null))
    }

    /** The Settings row's subtitle: "5 ready · 2 need BIOS", or "5 ready" when nothing needs BIOS. */
    fun summary(entries: List<CoreInventoryEntry>): String {
        val ready = entries.count { it.status == CoreInventoryStatus.READY }
        val needsBios = entries.count { it.status == CoreInventoryStatus.NEEDS_BIOS }
        return if (needsBios > 0) "$ready ready · $needsBios need BIOS" else "$ready ready"
    }
}

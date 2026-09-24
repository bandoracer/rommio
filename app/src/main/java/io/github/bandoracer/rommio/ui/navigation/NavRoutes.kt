package io.github.bandoracer.rommio.ui.navigation

import io.github.bandoracer.rommio.model.InteractiveSessionProvider

object NavRoutes {
    const val GATE = "gate"
    const val ONBOARDING_WELCOME = "onboarding/welcome"
    const val ONBOARDING_SERVER = "onboarding/server"
    const val ONBOARDING_LOGIN = "onboarding/login"
    const val ONBOARDING_SUCCESS = "onboarding/success"
    const val INTERACTIVE = "interactive/{provider}"
    const val APP = "app"

    // Tabs: Play · Library · Search · Stats · Settings.
    const val PLAY = "app/play"
    const val LIBRARY = "app/library"
    const val SEARCH = "app/search"
    const val STATS = "app/stats"
    const val SETTINGS = "app/settings"

    // Pushed pages.
    const val DOWNLOADS = "app/downloads"
    const val SETTINGS_PROFILES = "app/settings/profiles"
    const val SETTINGS_PLAYER = "app/settings/player"
    const val SETTINGS_CORES = "app/settings/cores"
    const val SETTINGS_BACKUP = "app/settings/backup"
    const val PLATFORM = "app/platform/{platformId}/{platformName}"
    const val COLLECTION_DETAIL = "app/collection/{kind}/{collectionId}/{collectionName}"
    const val GAME = "app/game/{romId}"
    /**
     * `keep`: a head's checkpoint id chosen on the Conflict sheet (optional). `start`: how the Launch
     * choice sheet starts the game (`resume` or `gameSave`, optional).
     */
    const val PLAYER = "player/{romId}/{fileId}?keep={keep}&start={start}"

    fun interactive(provider: InteractiveSessionProvider): String = "interactive/${provider.name.lowercase()}"
    fun platform(platformId: Int, platformName: String): String = "app/platform/$platformId/${platformName.encode()}"
    fun collection(kind: String, collectionId: String, collectionName: String): String {
        return "app/collection/${kind.encode()}/${collectionId.encode()}/${collectionName.encode()}"
    }
    fun game(romId: Int): String = "app/game/$romId"
    fun player(romId: Int, fileId: Int, keep: String? = null, start: String? = null): String {
        val query = listOfNotNull(keep?.let { "keep=${it.encode()}" }, start?.let { "start=${it.encode()}" })
        return "player/$romId/$fileId" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }
}

private fun String.encode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

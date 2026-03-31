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
    const val HOME = "app/home"
    const val LIBRARY = "app/library"
    const val COLLECTIONS = "app/collections"
    const val SETTINGS = "app/settings"
    const val DOWNLOADS = "app/downloads"
    const val PLATFORM = "app/platform/{platformId}/{platformName}"
    const val COLLECTION_DETAIL = "app/collection/{kind}/{collectionId}/{collectionName}"
    const val GAME = "app/game/{romId}"
    const val PLAYER = "player/{romId}/{fileId}"

    fun interactive(provider: InteractiveSessionProvider): String = "interactive/${provider.name.lowercase()}"
    fun platform(platformId: Int, platformName: String): String = "app/platform/$platformId/${platformName.encode()}"
    fun collection(kind: String, collectionId: String, collectionName: String): String {
        return "app/collection/${kind.encode()}/${collectionId.encode()}/${collectionName.encode()}"
    }
    fun game(romId: Int): String = "app/game/$romId"
    fun player(romId: Int, fileId: Int): String = "player/$romId/$fileId"
}

private fun String.encode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

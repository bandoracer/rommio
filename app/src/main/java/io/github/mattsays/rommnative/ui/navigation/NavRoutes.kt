package io.github.mattsays.rommnative.ui.navigation

import io.github.mattsays.rommnative.model.InteractiveSessionProvider

object NavRoutes {
    const val GATE = "gate"
    const val SERVER_ACCESS = "server-access"
    const val LOGIN = "login"
    const val INTERACTIVE = "interactive/{provider}"
    const val HOME = "home"
    const val PLATFORM = "platform/{platformId}/{platformName}"
    const val GAME = "game/{romId}"
    const val PLAYER = "player/{romId}/{fileId}"

    fun interactive(provider: InteractiveSessionProvider): String = "interactive/${provider.name.lowercase()}"
    fun platform(platformId: Int, platformName: String): String = "platform/$platformId/${platformName.encode()}"
    fun game(romId: Int): String = "game/$romId"
    fun player(romId: Int, fileId: Int): String = "player/$romId/$fileId"
}

private fun String.encode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

package io.github.bandoracer.rommio.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * The status bar's text over art a page draws under it (Aurora's Home hero and the Game page; iOS
 * `statusBarText(over:)`). The app sets one appearance for its window from the scheme
 * ([RommioTheme]); a page showing art under the bar asks for light or dark text while it is on
 * screen. The newest request decides (when one page replaces another, the new one asks before the
 * old one stands down); with none, the scheme's appearance comes back.
 */
@Stable
class StatusBarTone {
    private val requests = mutableStateListOf<Pair<Any, Boolean>>()

    /** Light text (a dark picture under the bar), dark text, or null for the scheme's. */
    val lightText: Boolean? get() = requests.lastOrNull()?.second

    fun request(owner: Any, lightText: Boolean) {
        val index = requests.indexOfFirst { it.first === owner }
        if (index >= 0 && requests[index].second == lightText) return
        if (index >= 0) requests.removeAt(index)
        requests.add(owner to lightText)
    }

    fun withdraw(owner: Any) {
        requests.removeAll { it.first === owner }
    }
}

val LocalStatusBarTone = staticCompositionLocalOf<StatusBarTone?> { null }

/**
 * Asks for the status bar's text over this page's art: [darkArt] true (a dark picture) gives light
 * text, false dark text, null nothing. Only while the page is resumed (its tab or push on top; not
 * under another page or the player), and withdrawn when it leaves.
 */
@Composable
fun StatusBarOverArt(darkArt: Boolean?) {
    val tone = LocalStatusBarTone.current ?: return
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val owner = remember { Any() }
    val asked = darkArt?.takeIf { state.isAtLeast(Lifecycle.State.RESUMED) }
    DisposableEffect(tone, asked) {
        if (asked != null) tone.request(owner, lightText = asked) else tone.withdraw(owner)
        onDispose { tone.withdraw(owner) }
    }
}

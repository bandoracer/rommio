package io.github.bandoracer.rommio.domain.player

/** Selection is frozen before attachment. Gameplay remains paused until readiness and restore finish. */
internal suspend fun startPreparedPlayer(
    awaitReady: suspend () -> Unit,
    restore: (suspend () -> Boolean)?,
    start: suspend () -> Unit,
) {
    awaitReady()
    if (restore != null) check(restore()) { "Unable to restore the selected state. The player remains paused; your saved files are unchanged." }
    start()
}

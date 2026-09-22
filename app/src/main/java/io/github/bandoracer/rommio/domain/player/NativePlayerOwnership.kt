package io.github.bandoracer.rommio.domain.player

/** LibretroDroid's JNI runtime is a process singleton, including across different games. */
internal object NativePlayerOwnership {
    private var occupied = false
    @Synchronized fun acquire(): AutoCloseable {
        check(!occupied) { "Another native player is still active or closing." }
        occupied = true
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) { closed = true; occupied = false }
        } }
    }
}

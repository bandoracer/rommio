package io.github.bandoracer.rommio.data.input

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.domain.input.HardwareBindingProfile
import io.github.bandoracer.rommio.domain.input.TouchElementState

class ControlsJsonCodec {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val touchElementStateAdapter = moshi.adapter<List<TouchElementState>>(
        Types.newParameterizedType(List::class.java, TouchElementState::class.java),
    )

    private val bindingsAdapter = moshi.adapter<Map<String, Int>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Int::class.javaObjectType,
        ),
    )

    fun encodeTouchElementStates(states: List<TouchElementState>): String {
        return touchElementStateAdapter.toJson(states)
    }

    fun decodeTouchElementStates(raw: String): List<TouchElementState> {
        return touchElementStateAdapter.fromJson(raw).orEmpty()
    }

    fun encodeBindingMap(profile: HardwareBindingProfile): String {
        return bindingsAdapter.toJson(emptyMap())
    }

    fun decodeBindingMap(raw: String): Map<String, Int> {
        return bindingsAdapter.fromJson(raw).orEmpty()
    }
}

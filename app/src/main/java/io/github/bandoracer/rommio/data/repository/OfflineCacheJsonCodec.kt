package io.github.bandoracer.rommio.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.model.RomFileDto
import io.github.bandoracer.rommio.model.RomSiblingDto

class OfflineCacheJsonCodec {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val filesAdapter = moshi.adapter<List<RomFileDto>>(
        Types.newParameterizedType(List::class.java, RomFileDto::class.java),
    )
    private val siblingsAdapter = moshi.adapter<List<RomSiblingDto>>(
        Types.newParameterizedType(List::class.java, RomSiblingDto::class.java),
    )
    private val stringListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java),
    )

    fun encodeFiles(files: List<RomFileDto>): String = filesAdapter.toJson(files)
    fun decodeFiles(raw: String): List<RomFileDto> = filesAdapter.fromJson(raw).orEmpty()

    fun encodeSiblings(siblings: List<RomSiblingDto>): String = siblingsAdapter.toJson(siblings)
    fun decodeSiblings(raw: String): List<RomSiblingDto> = siblingsAdapter.fromJson(raw).orEmpty()

    fun encodeStringList(values: List<String>): String = stringListAdapter.toJson(values)
    fun decodeStringList(raw: String): List<String> = stringListAdapter.fromJson(raw).orEmpty()
}

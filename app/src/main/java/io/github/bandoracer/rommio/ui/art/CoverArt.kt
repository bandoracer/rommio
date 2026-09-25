package io.github.bandoracer.rommio.ui.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Cover art treatments, computed off the main thread and cached:
 * - palettes per cover URL, in memory and as `<sha256(url)>.palette.json` in the app's cache
 *   (docs/theme-layouts.md §1.1);
 * - Aurora backdrops (the blurred cover, §1.3) per URL + scheme;
 * - Riso prints per URL + pixel size + inks (§1.5).
 * Covers load through the app's Coil image loader, like every cover on screen.
 */
object CoverArt {
    /** Prints are rendered at the displayed size, capped, in steps so nearby sizes share a print. */
    private const val PRINT_MAX_SIDE = 720
    private const val PRINT_STEP = 16

    private val palettes = LruCache<String, List<Int>>(256)
    private val backdrops = LruCache<String, ImageBitmap>(24)
    private val prints = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    /** Riso's paper grain tile (§1.4), built once. */
    val grain: ImageBitmap by lazy {
        val size = ArtBake.GRAIN_SIZE
        Bitmap.createBitmap(ArtBake.grainTile(), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    fun cachedPalette(url: String): List<Int>? = palettes.get(url)

    /**
     * Palettes warmed ahead of need are worked out one at a time on a background-priority thread,
     * so a grid of covers scrolling by never competes with the UI and render threads for the CPU.
     */
    private val warming: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "cover-palettes").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Riso prints are worked out two at a time, so a list printing the covers it scrolls into view
     * leaves the rest of the CPU to the UI and render threads.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val printing: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /**
     * Works out a cover's palette in the background when it is not in memory yet (Aurora calls it
     * whenever a cover is shown), so a page about that game opens lit on its first frame.
     */
    fun warmPalette(context: Context, url: String) {
        if (palettes.get(url) != null || inFlight.containsKey("palette:$url")) return
        val app = context.applicationContext
        scope.launch(warming) { palette(app, url, warming) }
    }

    /** A cover's palette: from memory, the palette file, or worked out on [dispatcher]. */
    suspend fun palette(context: Context, url: String, dispatcher: CoroutineDispatcher = Dispatchers.Default): List<Int>? {
        palettes.get(url)?.let { return it }
        return dedupe("palette:$url", dispatcher) {
            readPaletteFile(context, url)?.let { return@dedupe it.also { palettes.put(url, it) } }
            val bitmap = load(context, url, CoverPalette.DECODE_SIZE, CoverPalette.DECODE_SIZE, Scale.FIT) ?: return@dedupe null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            CoverPalette.extract(pixels, bitmap.width, bitmap.height).also {
                palettes.put(url, it)
                writePaletteFile(context, url, it)
            }
        }
    }

    fun cachedBackdrop(url: String, dark: Boolean): ImageBitmap? = backdrops.get(backdropKey(url, dark))

    /** The cover baked into Aurora page light at 96 px (§1.3). */
    suspend fun backdrop(context: Context, url: String, dark: Boolean): ImageBitmap? {
        val key = backdropKey(url, dark)
        backdrops.get(key)?.let { return it }
        return dedupe("backdrop:$key") {
            val bitmap = load(context, url, CoverPalette.DECODE_SIZE, CoverPalette.DECODE_SIZE, Scale.FIT) ?: return@dedupe null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            Bitmap.createBitmap(ArtBake.backdrop(pixels, w, h, dark), w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
                .also { backdrops.put(key, it) }
        }
    }

    private fun backdropKey(url: String, dark: Boolean) = "$url#${if (dark) "d" else "l"}"

    /** Rounds a requested print size to the cache step, keeping the aspect ratio. */
    fun printSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val scale = minOf(1f, PRINT_MAX_SIDE / maxOf(widthPx, heightPx).coerceAtLeast(1).toFloat())
        val w = ((widthPx * scale / PRINT_STEP).toInt().coerceAtLeast(1)) * PRINT_STEP
        val h = ((w.toFloat() * heightPx / widthPx.coerceAtLeast(1)).toInt()).coerceAtLeast(1)
        return w to h
    }

    fun printKey(url: String, width: Int, height: Int, inks: RisoInks): String =
        "$url|${width}x$height|${inks.dark}|${inks.paper}|${inks.plateA}|${inks.plateB}|${inks.halftone}|${inks.cell}|${inks.misregistration}"

    fun cachedPrint(key: String): ImageBitmap? = prints.get(key)

    suspend fun print(context: Context, url: String, width: Int, height: Int, inks: RisoInks): ImageBitmap? {
        val key = printKey(url, width, height, inks)
        prints.get(key)?.let { return it }
        return dedupe("print:$key", printing) {
            val source = load(context, url, width, height, Scale.FILL) ?: return@dedupe null
            val cropped = centerCrop(source, width, height)
            val pixels = IntArray(width * height)
            cropped.getPixels(pixels, 0, width, 0, 0, width, height)
            val printed = RisoPrint.print(pixels, width, height, inks)
            Bitmap.createBitmap(printed, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
                .also { prints.put(key, it) }
        }
    }

    /**
     * Runs [block] once per key on a scope of its own, so a caller leaving composition (a row
     * scrolled away) never cancels work another caller is waiting for.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> dedupe(key: String, dispatcher: CoroutineDispatcher = Dispatchers.Default, block: suspend () -> T?): T? {
        val deferred = inFlight.computeIfAbsent(key) {
            scope.async(dispatcher) {
                try {
                    block()
                } catch (error: Throwable) {
                    null
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return deferred.await() as T?
    }

    private suspend fun load(context: Context, url: String, width: Int, height: Int, scale: Scale): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(width, height)
            .scale(scale)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val drawable = result.drawable
        return (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap()
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height && source.config == Bitmap.Config.ARGB_8888) return source
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = maxOf(width / source.width.toFloat(), height / source.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---- Palette files ----------------------------------------------------------------------

    private fun paletteFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "cover-palettes"), "$digest.palette.json")
    }

    private fun readPaletteFile(context: Context, url: String): List<Int>? = runCatching {
        val file = paletteFile(context, url)
        if (!file.isFile) return null
        decodePalette(file.readText())
    }.getOrNull()

    private fun writePaletteFile(context: Context, url: String, palette: List<Int>) {
        runCatching {
            val file = paletteFile(context, url)
            file.parentFile?.mkdirs()
            val staged = File(file.parentFile, file.name + ".tmp")
            staged.writeText(encodePalette(palette))
            staged.renameTo(file)
        }
    }

    /** `{"colors":["#rrggbb", …]}` (the file format is platform-local, like the image cache). */
    fun encodePalette(palette: List<Int>): String =
        palette.joinToString(prefix = "{\"colors\":[", postfix = "]}") { "\"${AuroraLight.hex(it)}\"" }

    fun decodePalette(json: String): List<Int>? {
        val hexes = Regex("#([0-9a-fA-F]{6})").findAll(json).map { it.groupValues[1].toInt(16) }.toList()
        return hexes.takeIf { it.size == 5 }
    }
}

/**
 * The cover's five-colour palette (null while loading or without art). Synchronous when the
 * palette is in memory, so a page opens with its light on the first frame; otherwise it is read
 * (disk cache) or worked out once, off the main thread.
 */
@Composable
fun rememberCoverPalette(url: String?): List<Int>? {
    val context = LocalContext.current
    val state = remember(url) { mutableStateOf(url?.let(CoverArt::cachedPalette)) }
    if (url != null && state.value == null) {
        LaunchedEffect(url) { state.value = CoverArt.palette(context.applicationContext, url) }
    }
    return state.value
}

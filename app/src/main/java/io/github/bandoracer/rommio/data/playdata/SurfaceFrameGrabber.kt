package io.github.bandoracer.rommio.data.playdata

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Copies the game's current frame from the player's GL surface with [PixelCopy] (API 24+). The
 * copy is scaled straight into a thumbnail-sized bitmap. Any failure (no surface yet, surface
 * torn down in the background, unsupported buffer) completes with null; it never throws.
 */
object SurfaceFrameGrabber {
    private val callbacks: Handler by lazy { Handler(HandlerThread("rommio-frame-copy").apply { start() }.looper) }

    fun request(host: View?): Deferred<Bitmap?> {
        val result = CompletableDeferred<Bitmap?>()
        try {
            val surface = host?.let(::findSurfaceView)
            if (surface == null || surface.width <= 0 || surface.height <= 0 || surface.holder.surface?.isValid != true) {
                result.complete(null)
                return result
            }
            val (width, height) = ThumbnailSizing.target(surface.width, surface.height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surface, bitmap, { code ->
                if (code == PixelCopy.SUCCESS) result.complete(bitmap)
                else { bitmap.recycle(); result.complete(null) }
            }, callbacks)
        } catch (_: Throwable) {
            result.complete(null)
        }
        return result
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurfaceView(view.getChildAt(index))?.let { return it }
        return null
    }
}

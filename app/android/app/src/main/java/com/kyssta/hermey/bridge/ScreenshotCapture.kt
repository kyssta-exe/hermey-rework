package com.kyssta.hermey.bridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * MediaProjection-backed screen capture for the screenshot channel. The
 * renderer's screenshot API expects a data URL back; here the captured
 * frame is PNG-encoded and base64-wrapped into that same shape.
 */
object ScreenshotCapture {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null

    fun onConsentGranted(context: Context, resultCode: Int, data: Intent) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        HermeyBridge.notifyScreenshotStatus(granted = true)
    }

    fun onConsentDenied() {
        HermeyBridge.notifyScreenshotStatus(granted = false)
    }

    fun captureOnce(context: Context): String? {
        val proj = projection ?: return null
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        proj.createVirtualDisplay(
            "hermey-capture",
            width,
            height,
            density,
            android.view.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            Handler(Looper.getMainLooper())
        )
        reader = imageReader

        // Grab one frame after the display produces it.
        val latch = java.util.concurrent.CountDownLatch(1)
        var bitmap: Bitmap? = null

        imageReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val bmp = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            image.close()
            bitmap = bmp
            latch.countDown()
        }, Handler(Looper.getMainLooper()))

        return try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            val bmp = bitmap ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)

            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            imageReader.close()
            reader = null
        }
    }

    fun release() {
        projection?.stop()
        projection = null
        reader?.close()
        reader = null
    }
}

package com.kyssta.hermey.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Screen-capture consent host for the screenshot channel. The desktop app
 * answers `hermes:screenshot:permission` with the OS privacy-panel; on
 * Android the equivalent is the MediaProjection consent dialog, launched
 * here and reported back over the bridge event channel.
 */
class ScreenshotActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            ScreenshotCapture.onConsentGranted(this, resultCode, data)
        } else {
            ScreenshotCapture.onConsentDenied()
        }

        finish()
    }

    private companion object {
        const val REQUEST_CAPTURE = 9001
    }
}

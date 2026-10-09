package com.kyssta.hermey

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.kyssta.hermey.bridge.HermeyBridgePlugin

/**
 * Hermey main activity — a Capacitor bridge activity that registers the
 * Hermey native bridge.
 *
 * The renderer is a byte-identical copy of the Hermes Desktop renderer;
 * the ONLY difference between the two apps is which bridge backs
 * `window.hermesDesktop`: Electron's preload on desktop, the Capacitor
 * HermeyBridgePlugin here. No renderer code was changed, which is what
 * guarantees the 1:1 experience.
 *
 * registerPlugin() MUST run before super.onCreate() so the plugin is in the
 * bridge before the WebView loads the renderer (which calls into
 * window.hermesDesktop immediately). Without it the renderer sees
 * "HermeyBridge is not implemented on Android".
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(HermeyBridgePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}

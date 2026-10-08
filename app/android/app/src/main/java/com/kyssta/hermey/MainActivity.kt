package com.kyssta.hermey

import android.os.Bundle
import com.getcapacitor.BridgeActivity

/**
 * Hermey main activity — a plain Capacitor bridge activity.
 *
 * The renderer is a byte-identical copy of the Hermes Desktop renderer;
 * the ONLY difference between the two apps is which bridge backs
 * `window.hermesDesktop`: Electron's preload on desktop, the Capacitor
 * HermeyBridgePlugin here. No renderer code was changed, which is what
 * guarantees the 1:1 experience.
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}

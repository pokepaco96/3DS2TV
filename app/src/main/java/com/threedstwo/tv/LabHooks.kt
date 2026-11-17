package com.threedstwo.tv

import java.lang.ref.WeakReference

/**
 * Minimal hooks for the hardware-lab receiver (present only in debug builds, app/src/debug).
 * No per-frame work: only a weak reference updated in onStart/onStop.
 */
object LabHooks {
    @Volatile var stream: WeakReference<StreamActivity>? = null
    const val EXTRA_AUTOSTART = "lab_autostart"
}

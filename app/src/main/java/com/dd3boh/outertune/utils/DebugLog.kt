package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.outertune.BuildConfig

/**
 * Debug-only logger for the real-time mixing / transition path.
 *
 * In release builds every call is a no-op, so the audio control loops carry no logcat traffic
 * and non-error diagnostics never reach crash reporting. Import it aliased so existing call sites
 * are untouched:
 *
 * ```
 * import com.dd3boh.outertune.utils.DebugLog as Log
 * ```
 *
 * Note: string arguments are still built at the call site even when disabled. Keep heavy
 * interpolation out of per-tick loop bodies.
 */
object DebugLog {
    private val ENABLED = BuildConfig.DEBUG

    fun v(tag: String, msg: String) { if (ENABLED) Log.v(tag, msg) }
    fun d(tag: String, msg: String) { if (ENABLED) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (ENABLED) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (ENABLED) Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable?) { if (ENABLED) Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String) { if (ENABLED) Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?) { if (ENABLED) Log.e(tag, msg, tr) }
}

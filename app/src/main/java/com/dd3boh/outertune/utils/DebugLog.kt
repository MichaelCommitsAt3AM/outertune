package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.outertune.BuildConfig

/**
 * Logger for the mix engine and audio analysis.
 *
 * Verbose/debug/info messages are debug-build only, so the audio control loops carry no logcat
 * traffic in release; warnings and errors always log. Import it aliased so call sites read like
 * android.util.Log:
 *
 * ```
 * import com.dd3boh.outertune.utils.DebugLog as Log
 * ```
 *
 * Prefer the lambda overloads (`Log.d(TAG) { "..." }`) for messages with interpolation: the
 * string is then only built when it will actually be logged.
 */
object DebugLog {
    @PublishedApi
    internal val ENABLED = BuildConfig.DEBUG

    fun v(tag: String, msg: String) { if (ENABLED) Log.v(tag, msg) }
    fun d(tag: String, msg: String) { if (ENABLED) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (ENABLED) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable?) { Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String) { Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?) { Log.e(tag, msg, tr) }

    inline fun d(tag: String, msg: () -> String) { if (ENABLED) Log.d(tag, msg()) }
    inline fun i(tag: String, msg: () -> String) { if (ENABLED) Log.i(tag, msg()) }
}

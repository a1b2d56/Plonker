package com.music.bitchord.ui.components.backdrop

import android.os.Process
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.components.backdrop.internal.AmbientHighlightShaderString
import com.music.bitchord.ui.components.backdrop.internal.DefaultHighlightShaderString
import com.music.bitchord.ui.components.backdrop.internal.RoundedRectRefractionShaderString
import com.music.bitchord.ui.components.backdrop.internal.RoundedRectRefractionWithDispersionShaderString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Compiles every AGSL shader the glass engine can reach, once per process, at
 * startup. Ported from SpotiFLAC's `MornyeGlassWarmup` (MIT) — the technique,
 * not the code: their warmup exists because their glass shaders also compiled
 * lazily, on first use, inside the first gesture.
 *
 * All AGSL shaders compile once in Application.onCreate on a background thread,
 * each with the uniforms the draw path will set, so the first swipe never pays
 * the SkSL compile. Below API 33 it no-ops. Failure is non-fatal; the lazy path
 * remains the fallback.
 */
object GlassShaderWarmup {

    private const val TAG = "GlassShaderWarmup"

    /** Application.onCreate is the only intended call site; this keeps a repeat call a no-op. */
    private val started = AtomicBoolean(false)

    /**
     * Compiles the engine's shaders once, off the main thread. Safe to call
     * from any thread; does nothing below API 33 and nothing after the first
     * call.
     */
    fun warmUp() {
        if (!isRuntimeShaderSupported()) return
        if (!started.compareAndSet(false, true)) return
        thread(name = "glass-shader-warmup") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val cache = RuntimeShaderCacheImpl()
            try {
                for (shader in shaders) {
                    try {
                        cache.obtainRuntimeShader(shader.key, shader.source).shaderConfigure(shader)
                    } catch (t: Throwable) {
                        Log.w(TAG, "warmup failed for '${shader.key}'; lazy compile stays the fallback", t)
                    }
                }
            } finally {
                cache.clear()
            }
        }
    }

    private fun RuntimeShader.shaderConfigure(shader: WarmupShader) = shader.configure(this)

    private class WarmupShader(
        val key: String,
        val source: String,
        val configure: RuntimeShader.() -> Unit,
    )

    private val shaders = listOf(
        WarmupShader(
            key = "Refraction",
            source = RoundedRectRefractionShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setFloatUniform("refractionHeight", 8f)
            setFloatUniform("refractionAmount", -12f)
            setFloatUniform("depthEffect", 0f)
            setIntUniform("circleMapMode", 0)
        },
        WarmupShader(
            key = "RefractionWithDispersion",
            source = RoundedRectRefractionWithDispersionShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setFloatUniform("refractionHeight", 8f)
            setFloatUniform("refractionAmount", -12f)
            setFloatUniform("depthEffect", 0f)
            setFloatUniform("chromaticAberration", 0.5f)
            setIntUniform("circleMapMode", 0)
        },
        WarmupShader(
            key = "Default",
            source = DefaultHighlightShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setColorUniform("color", Color.White)
            setFloatUniform("lightDir", 0.70710677f, 0.70710677f)
            setFloatUniform("falloff", 1f)
        },
        WarmupShader(
            key = "Ambient",
            source = AmbientHighlightShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setFloatUniform("lightDir", 0.70710677f, 0.70710677f)
            setFloatUniform("falloff", 1f)
        },
    )
}

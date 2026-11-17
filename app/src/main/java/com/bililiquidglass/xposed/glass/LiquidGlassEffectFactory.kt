package io.github.tomorisuki2333.bililiquidglass.glass

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.abs

internal data class BackdropEffectParams(
    val width: Float,
    val height: Float,
    val paddingPx: Int,
    val cornerRadius: Float,
    val blurRadiusPx: Float,
    val refractionHeightPx: Float,
    val refractionAmountPx: Float,
    val vibrancy: Float,
    val chromaticAberration: Boolean,
    val depthEffect: Boolean
)

/**
 * Caches compiled AGSL RuntimeShaders and chains Android RenderEffect filters without per-frame allocation.
 */
internal class LiquidGlassEffectCache {

    private var lastPreFilterKey: Long = -1L
    private var cachedPreFilterEffect: Any? = null

    private var lastBackdropParams: BackdropEffectParams? = null
    private var cachedLensEffect: Any? = null

    private var refractionShader: Any? = null
    private var dispersionShader: Any? = null
    private var highlightShader: Any? = null
    private var interactiveShader: Any? = null

    fun getOrCreatePreFilterEffect(vibrancy: Float, blurRadiusPx: Float): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val key = (vibrancy.toBits().toLong() shl 32) xor (blurRadiusPx.toBits().toLong() and 0xFFFFFFFFL)
        if (key == lastPreFilterKey) return cachedPreFilterEffect
        val built = LiquidGlassEffectApi31.buildPreFilterEffect(vibrancy, blurRadiusPx)
        lastPreFilterKey = key
        cachedPreFilterEffect = built
        return built
    }

    fun getOrCreateLensEffect(params: BackdropEffectParams): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (params == lastBackdropParams) return cachedLensEffect
        val built = LiquidGlassEffectApi33.createLensEffectIfActive(this, params)
        lastBackdropParams = params
        cachedLensEffect = built
        return built
    }

    fun getOrCreateBackdropEffect(params: BackdropEffectParams): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (params == lastBackdropParams) return cachedLensEffect
        val built = LiquidGlassEffectApi31.buildEffect(this, params)
        lastBackdropParams = params
        cachedLensEffect = built
        return built
    }

    fun getOrCreateHighlightShader(
        width: Float,
        height: Float,
        cornerRadius: Float,
        colorArgb: Int,
        angleDeg: Float = 45f,
        falloff: Float = 1f
    ): Shader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return LiquidGlassEffectApi33.obtainHighlightShader(
            this, width, height, cornerRadius, colorArgb, angleDeg, falloff
        )
    }

    fun getOrCreateInteractiveShader(
        width: Float,
        height: Float,
        radiusPx: Float,
        centerX: Float,
        centerY: Float,
        colorArgb: Int
    ): Shader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return LiquidGlassEffectApi33.obtainInteractiveShader(
            this, width, height, radiusPx, centerX, centerY, colorArgb
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object LiquidGlassEffectApi31 {
        fun buildPreFilterEffect(vibrancy: Float, blurRadiusPx: Float): RenderEffect? {
            var effect: RenderEffect? = null

            if (abs(vibrancy - 1f) > 0.01f) {
                val matrix = LiquidGlassShaders.buildColorControlsMatrix(saturation = vibrancy)
                val colorFilter = ColorMatrixColorFilter(matrix)
                effect = RenderEffect.createColorFilterEffect(colorFilter)
            }

            if (blurRadiusPx > 0.5f) {
                effect = if (effect != null) {
                    RenderEffect.createBlurEffect(
                        blurRadiusPx,
                        blurRadiusPx,
                        effect,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    RenderEffect.createBlurEffect(
                        blurRadiusPx,
                        blurRadiusPx,
                        Shader.TileMode.CLAMP
                    )
                }
            }

            return effect
        }

        fun buildEffect(cache: LiquidGlassEffectCache, params: BackdropEffectParams): RenderEffect? {
            var effect = buildPreFilterEffect(params.vibrancy, params.blurRadiusPx)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                params.refractionHeightPx > 0.5f &&
                params.refractionAmountPx > 0.5f &&
                params.width > 0f &&
                params.height > 0f
            ) {
                val lensEffect = LiquidGlassEffectApi33.createLensEffect(cache, params)
                effect = if (effect != null) {
                    RenderEffect.createChainEffect(lensEffect, effect)
                } else {
                    lensEffect
                }
            }

            return effect
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object LiquidGlassEffectApi33 {
        fun createLensEffectIfActive(cache: LiquidGlassEffectCache, params: BackdropEffectParams): RenderEffect? {
            if (params.refractionHeightPx <= 0.5f ||
                params.refractionAmountPx <= 0.5f ||
                params.width <= 0f ||
                params.height <= 0f
            ) {
                return null
            }
            return createLensEffect(cache, params)
        }

        fun createLensEffect(cache: LiquidGlassEffectCache, params: BackdropEffectParams): RenderEffect {
            val shader = if (params.chromaticAberration) {
                (cache.dispersionShader as? RuntimeShader)
                    ?: RuntimeShader(LiquidGlassShaders.ROUNDED_RECT_REFRACTION_DISPERSION_SHADER).also {
                        cache.dispersionShader = it
                    }
            } else {
                (cache.refractionShader as? RuntimeShader)
                    ?: RuntimeShader(LiquidGlassShaders.ROUNDED_RECT_REFRACTION_SHADER).also {
                        cache.refractionShader = it
                    }
            }

            val r = params.cornerRadius
            val pad = params.paddingPx.toFloat()
            shader.setFloatUniform("size", params.width, params.height)
            shader.setFloatUniform("offset", -pad, -pad)
            shader.setFloatUniform("cornerRadii", floatArrayOf(r, r, r, r))
            shader.setFloatUniform("refractionHeight", params.refractionHeightPx)
            shader.setFloatUniform("refractionAmount", -params.refractionAmountPx)
            shader.setFloatUniform("depthEffect", if (params.depthEffect) 1f else 0f)
            if (params.chromaticAberration) {
                shader.setFloatUniform("chromaticAberration", 1f)
            }
            return RenderEffect.createRuntimeShaderEffect(shader, "content")
        }

        fun obtainHighlightShader(
            cache: LiquidGlassEffectCache,
            width: Float,
            height: Float,
            cornerRadius: Float,
            colorArgb: Int,
            angleDeg: Float,
            falloff: Float
        ): RuntimeShader {
            val shader = (cache.highlightShader as? RuntimeShader)
                ?: RuntimeShader(LiquidGlassShaders.DEFAULT_HIGHLIGHT_SHADER).also {
                    cache.highlightShader = it
                }
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("cornerRadii", floatArrayOf(cornerRadius, cornerRadius, cornerRadius, cornerRadius))
            shader.setColorUniform("color", colorArgb)
            shader.setFloatUniform("angle", angleDeg * (PI / 180.0).toFloat())
            shader.setFloatUniform("falloff", falloff)
            return shader
        }

        fun obtainInteractiveShader(
            cache: LiquidGlassEffectCache,
            width: Float,
            height: Float,
            radiusPx: Float,
            centerX: Float,
            centerY: Float,
            colorArgb: Int
        ): RuntimeShader {
            val shader = (cache.interactiveShader as? RuntimeShader)
                ?: RuntimeShader(LiquidGlassShaders.INTERACTIVE_HIGHLIGHT_SHADER).also {
                    cache.interactiveShader = it
                }
            shader.setFloatUniform("size", width, height)
            shader.setColorUniform("color", colorArgb)
            shader.setFloatUniform("radius", radiusPx)
            shader.setFloatUniform("position", centerX, centerY)
            return shader
        }
    }
}

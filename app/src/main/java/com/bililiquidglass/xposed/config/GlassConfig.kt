package io.github.tomorisuki2333.bililiquidglass.config

import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Immutable configuration snapshot for the floating Liquid Glass bottom bar.
 */
data class GlassConfig(
    val enabled: Boolean = true,
    val barHeightDp: Float = 58f,
    val horizontalMarginDp: Float = 16f,
    val bottomMarginDp: Float = 12f,
    val cornerRadiusFraction: Float = 1.0f,
    val blurRadiusDp: Float = 6.0f,
    val refractionHeightDp: Float = 22f,
    val refractionAmountDp: Float = 28f,
    val vibrancy: Float = 1.45f,
    val chromaticAberration: Boolean = true,
    val depthEffect: Boolean = true,
    val highlightIntensity: Float = 0.50f,
    val tintOpacity: Float = 0.27f,
    val shadowAlpha: Float = 0.14f,
    val activePillEnabled: Boolean = true,
    val interactiveHighlightEnabled: Boolean = true,
    val adjustFeedPadding: Boolean = true
) {
    fun clamped(): GlassConfig = copy(
        barHeightDp = barHeightDp.coerceIn(48f, 76f),
        horizontalMarginDp = horizontalMarginDp.coerceIn(0f, 40f),
        bottomMarginDp = bottomMarginDp.coerceIn(0f, 40f),
        cornerRadiusFraction = cornerRadiusFraction.coerceIn(0.2f, 1.0f),
        blurRadiusDp = blurRadiusDp.coerceIn(0f, 24f),
        refractionHeightDp = refractionHeightDp.coerceIn(0f, 32f),
        refractionAmountDp = refractionAmountDp.coerceIn(0f, 40f),
        vibrancy = vibrancy.coerceIn(0.8f, 2.0f),
        highlightIntensity = highlightIntensity.coerceIn(0f, 1.0f),
        tintOpacity = tintOpacity.coerceIn(0.04f, 0.85f),
        shadowAlpha = shadowAlpha.coerceIn(0f, 0.4f)
    )

    companion object {
        const val PREF_GROUP = "liquid_glass_prefs"
        private const val CURRENT_SCHEMA_VERSION = 3
        private const val KEY_SCHEMA_VERSION = "config_schema_version"

        const val KEY_ENABLED = "enabled"
        const val KEY_BAR_HEIGHT_DP = "bar_height_dp"
        const val KEY_HORIZONTAL_MARGIN_DP = "horizontal_margin_dp"
        const val KEY_BOTTOM_MARGIN_DP = "bottom_margin_dp"
        const val KEY_CORNER_RADIUS_FRACTION = "corner_radius_fraction"
        const val KEY_BLUR_RADIUS_DP = "blur_radius_dp"
        const val KEY_REFRACTION_HEIGHT_DP = "refraction_height_dp"
        const val KEY_REFRACTION_AMOUNT_DP = "refraction_amount_dp"
        const val KEY_VIBRANCY = "vibrancy"
        const val KEY_CHROMATIC_ABERRATION = "chromatic_aberration"
        const val KEY_DEPTH_EFFECT = "depth_effect"
        const val KEY_HIGHLIGHT_INTENSITY = "highlight_intensity"
        const val KEY_TINT_OPACITY = "tint_opacity"
        const val KEY_SHADOW_ALPHA = "shadow_alpha"
        const val KEY_ACTIVE_PILL_ENABLED = "active_pill_enabled"
        const val KEY_INTERACTIVE_HIGHLIGHT_ENABLED = "interactive_highlight_enabled"
        const val KEY_ADJUST_FEED_PADDING = "adjust_feed_padding"

        fun fromPreferences(prefs: SharedPreferences?): GlassConfig {
            if (prefs == null) return GlassConfig()
            val schema = prefs.getInt(KEY_SCHEMA_VERSION, 1)
            val rawBlur = prefs.getFloat(KEY_BLUR_RADIUS_DP, 6.0f)
            val rawRefHeight = prefs.getFloat(KEY_REFRACTION_HEIGHT_DP, 22f)
            val rawRefAmount = prefs.getFloat(KEY_REFRACTION_AMOUNT_DP, 28f)
            val rawVibrancy = prefs.getFloat(KEY_VIBRANCY, 1.45f)
            val rawHighlight = prefs.getFloat(KEY_HIGHLIGHT_INTENSITY, 0.50f)
            val rawTint = prefs.getFloat(KEY_TINT_OPACITY, 0.27f)

            val isUntouchedV1 = schema < 2 &&
                kotlin.math.abs(rawBlur - 8f) < 0.01f &&
                kotlin.math.abs(rawTint - 0.36f) < 0.01f &&
                kotlin.math.abs(rawHighlight - 0.55f) < 0.01f
            val isUntouchedV2 = schema < 3 &&
                kotlin.math.abs(rawBlur - 3.5f) < 0.01f &&
                kotlin.math.abs(rawTint - 0.17f) < 0.01f &&
                kotlin.math.abs(rawHighlight - 0.45f) < 0.01f
            val migrateDefaults = isUntouchedV1 || isUntouchedV2

            return GlassConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                barHeightDp = prefs.getFloat(KEY_BAR_HEIGHT_DP, 58f),
                horizontalMarginDp = prefs.getFloat(KEY_HORIZONTAL_MARGIN_DP, 16f),
                bottomMarginDp = prefs.getFloat(KEY_BOTTOM_MARGIN_DP, 12f),
                cornerRadiusFraction = prefs.getFloat(KEY_CORNER_RADIUS_FRACTION, 1.0f),
                blurRadiusDp = if (migrateDefaults) 6.0f else rawBlur,
                refractionHeightDp = if (migrateDefaults) 22f else rawRefHeight,
                refractionAmountDp = if (migrateDefaults) 28f else rawRefAmount,
                vibrancy = if (migrateDefaults) 1.45f else rawVibrancy,
                chromaticAberration = prefs.getBoolean(KEY_CHROMATIC_ABERRATION, true),
                depthEffect = prefs.getBoolean(KEY_DEPTH_EFFECT, true),
                highlightIntensity = if (migrateDefaults) 0.50f else rawHighlight,
                tintOpacity = if (migrateDefaults) 0.27f else rawTint,
                shadowAlpha = prefs.getFloat(KEY_SHADOW_ALPHA, 0.14f),
                activePillEnabled = prefs.getBoolean(KEY_ACTIVE_PILL_ENABLED, true),
                interactiveHighlightEnabled = prefs.getBoolean(KEY_INTERACTIVE_HIGHLIGHT_ENABLED, true),
                adjustFeedPadding = prefs.getBoolean(KEY_ADJUST_FEED_PADDING, true)
            ).clamped()
        }

        fun writeTo(prefs: SharedPreferences, config: GlassConfig) {
            val c = config.clamped()
            prefs.edit()
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .putBoolean(KEY_ENABLED, c.enabled)
                .putFloat(KEY_BAR_HEIGHT_DP, c.barHeightDp)
                .putFloat(KEY_HORIZONTAL_MARGIN_DP, c.horizontalMarginDp)
                .putFloat(KEY_BOTTOM_MARGIN_DP, c.bottomMarginDp)
                .putFloat(KEY_CORNER_RADIUS_FRACTION, c.cornerRadiusFraction)
                .putFloat(KEY_BLUR_RADIUS_DP, c.blurRadiusDp)
                .putFloat(KEY_REFRACTION_HEIGHT_DP, c.refractionHeightDp)
                .putFloat(KEY_REFRACTION_AMOUNT_DP, c.refractionAmountDp)
                .putFloat(KEY_VIBRANCY, c.vibrancy)
                .putBoolean(KEY_CHROMATIC_ABERRATION, c.chromaticAberration)
                .putBoolean(KEY_DEPTH_EFFECT, c.depthEffect)
                .putFloat(KEY_HIGHLIGHT_INTENSITY, c.highlightIntensity)
                .putFloat(KEY_TINT_OPACITY, c.tintOpacity)
                .putFloat(KEY_SHADOW_ALPHA, c.shadowAlpha)
                .putBoolean(KEY_ACTIVE_PILL_ENABLED, c.activePillEnabled)
                .putBoolean(KEY_INTERACTIVE_HIGHLIGHT_ENABLED, c.interactiveHighlightEnabled)
                .putBoolean(KEY_ADJUST_FEED_PADDING, c.adjustFeedPadding)
                .apply()
        }

        fun computeSurfaceTintColor(isDarkTheme: Boolean, tintOpacity: Float): Int {
            val alpha = (tintOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val baseRgb = if (isDarkTheme) 0x16171A else 0xFAFAFA
            return (alpha shl 24) or baseRgb
        }

        fun computeIndicatorTintColor(isDarkTheme: Boolean, pressProgress: Float): Int {
            val restingAlpha = if (isDarkTheme) 0.14f else 0.28f
            val pressedAlpha = if (isDarkTheme) 0.22f else 0.40f
            val alphaFloat = restingAlpha + (pressedAlpha - restingAlpha) * pressProgress.coerceIn(0f, 1f)
            val alpha = (alphaFloat * 255f).roundToInt().coerceIn(0, 255)
            return (alpha shl 24) or 0xFFFFFF
        }

        fun isColorDark(argb: Int): Boolean {
            val r = ((argb ushr 16) and 0xFF) / 255.0
            val g = ((argb ushr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return luminance < 0.45
        }
    }
}

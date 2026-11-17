package io.github.tomorisuki2333.bililiquidglass.glass

import kotlin.math.ceil
import kotlin.math.min

/**
 * AGSL shader definitions and optical/geometric helpers ported from Kyant0/AndroidLiquidGlass
 * (Apache-2.0) for direct use on Android RenderNode + RenderEffect + RuntimeShader.
 */
object LiquidGlassShaders {

    private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 centeredCoord, float4 radii) {
    if (centeredCoord.x >= 0.0) {
        if (centeredCoord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (centeredCoord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 clamped = max(cornerCoord, 0.0);
        float len = length(clamped);
        return len > 0.0001 ? sign(coord) * (clamped / len) : float2(0.0, 0.0);
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

    const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    float cx = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(0.0, 1.0 - cx * cx));
}

float meniscusCurve(float x) {
    float cx = clamp(x, 0.0, 1.0);
    return 0.38 * cx * cx + 0.62 * circleMap(cx);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float depth = -sd;
    float2 safeMax = max(size - float2(0.5), float2(1.0));
    if (depth >= refractionHeight && depthEffect <= 0.001) {
        return content.eval(clamp(coord, float2(0.5), safeMax));
    }

    float centerLen = length(centeredCoord);
    float2 centerDir = centerLen > 0.0001 ? centeredCoord / centerLen : float2(0.0, 0.0);

    // Subtle interior dome magnification when depthEffect is enabled.
    float2 baseCoord = coord;
    if (depthEffect > 0.001) {
        float interiorRatio = clamp(depth / max(halfSize.y, 1.0), 0.0, 1.0);
        float domeShift = (1.0 - interiorRatio * interiorRatio) * refractionAmount * 0.08 * depthEffect;
        baseCoord = coord + domeShift * centerDir;
    }

    if (depth >= refractionHeight) {
        return content.eval(clamp(baseCoord, float2(0.5), safeMax));
    }

    sd = min(sd, 0.0);
    float edgeProgress = 1.0 - (-sd / max(refractionHeight, 0.001));
    float d = meniscusCurve(edgeProgress) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 baseGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 combined = baseGrad + depthEffect * centerDir;
    float combLen = length(combined);
    float2 grad = combLen > 0.0001 ? combined / combLen : baseGrad;

    float2 refractedCoord = clamp(baseCoord + d * grad, float2(0.5), safeMax);
    return content.eval(refractedCoord);
}
"""

    const val ROUNDED_RECT_REFRACTION_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    float cx = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(0.0, 1.0 - cx * cx));
}

float meniscusCurve(float x) {
    float cx = clamp(x, 0.0, 1.0);
    return 0.38 * cx * cx + 0.62 * circleMap(cx);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float depth = -sd;
    float2 safeMax = max(size - float2(0.5), float2(1.0));
    if (depth >= refractionHeight && depthEffect <= 0.001) {
        return content.eval(clamp(coord, float2(0.5), safeMax));
    }

    float centerLen = length(centeredCoord);
    float2 centerDir = centerLen > 0.0001 ? centeredCoord / centerLen : float2(0.0, 0.0);

    float2 baseCoord = coord;
    if (depthEffect > 0.001) {
        float interiorRatio = clamp(depth / max(halfSize.y, 1.0), 0.0, 1.0);
        float domeShift = (1.0 - interiorRatio * interiorRatio) * refractionAmount * 0.08 * depthEffect;
        baseCoord = coord + domeShift * centerDir;
    }

    if (depth >= refractionHeight) {
        return content.eval(clamp(baseCoord, float2(0.5), safeMax));
    }

    sd = min(sd, 0.0);
    float edgeProgress = clamp(1.0 - (-sd / max(refractionHeight, 0.001)), 0.0, 1.0);
    float d = meniscusCurve(edgeProgress) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 baseGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 combined = baseGrad + depthEffect * centerDir;
    float combLen = length(combined);
    float2 grad = combLen > 0.0001 ? combined / combLen : baseGrad;

    float2 refractedCoord = clamp(baseCoord + d * grad, float2(0.5), safeMax);
    float xFactor = 0.45 + 0.55 * abs(centeredCoord.x / max(halfSize.x, 1.0));
    float dispersionIntensity = chromaticAberration * 0.42 * xFactor * edgeProgress;
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(clamp(refractedCoord + dispersedCoord, float2(0.5), safeMax));
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(clamp(refractedCoord + dispersedCoord * (2.0 / 3.0), float2(0.5), safeMax));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(clamp(refractedCoord + dispersedCoord * (1.0 / 3.0), float2(0.5), safeMax));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(clamp(refractedCoord - dispersedCoord * (1.0 / 3.0), float2(0.5), safeMax));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(clamp(refractedCoord - dispersedCoord * (2.0 / 3.0), float2(0.5), safeMax));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(clamp(refractedCoord - dispersedCoord, float2(0.5), safeMax));
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}
"""

    const val DEFAULT_HIGHLIGHT_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    float outAlpha = color.a * intensity;
    return half4(color.rgb * outAlpha, outAlpha);
}
"""

    // Soft premultiplied radial sheen so BlendMode.PLUS adds gentle shimmer without white blowout.
    const val INTERACTIVE_HIGHLIGHT_SHADER = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float t = clamp(dist / max(radius, 1.0), 0.0, 1.0);
    float falloff = 1.0 - smoothstep(0.0, 1.0, t);
    float intensity = falloff * falloff * (0.55 + 0.45 * falloff);
    float outAlpha = color.a * intensity;
    return half4(color.rgb * outAlpha, outAlpha);
}
"""

    fun buildColorControlsMatrix(
        brightness: Float = 0f,
        contrast: Float = 1f,
        saturation: Float = 1.45f
    ): FloatArray {
        val invSat = 1f - saturation
        val r = 0.213f * invSat
        val g = 0.715f * invSat
        val b = 0.072f * invSat

        val t = (0.5f - contrast * 0.5f + brightness) * 255f
        val cr = contrast * r
        val cg = contrast * g
        val cb = contrast * b
        val cs = contrast * saturation

        return floatArrayOf(
            cr + cs, cg, cb, 0f, t,
            cr, cg + cs, cb, 0f, t,
            cr, cg, cb + cs, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    }
}

/**
 * Pure geometry helpers shared by Android rendering code and JVM unit tests.
 */
object LiquidGlassGeometry {
    fun clampCornerRadius(width: Float, height: Float, fraction: Float): Float {
        if (width <= 0f || height <= 0f) return 0f
        val maxRadius = min(width, height) * 0.5f
        return (maxRadius * fraction.coerceIn(0f, 1f)).coerceIn(0f, maxRadius)
    }

    /**
     * Padding around the pill bounds recorded in the offscreen RenderNode so blur edges do not
     * sample outside the backdrop boundary.
     */
    fun computeBackdropPaddingPx(blurRadiusPx: Float): Int {
        return if (blurRadiusPx > 0.5f) ceil(blurRadiusPx).toInt() else 0
    }

    /**
     * Maps a finger X coordinate inside the pill content area (`0..contentWidth`) to a continuous
     * slot position in `[0f, tabCount - 1]` centered over each tab's visual center.
     */
    fun computeHoveredSlot(relX: Float, contentWidth: Float, tabCount: Int): Float {
        if (tabCount <= 1 || contentWidth <= 0f) return 0f
        return ((relX / contentWidth) * tabCount - 0.5f).coerceIn(0f, (tabCount - 1).toFloat())
    }

    /**
     * Resolves the discrete tab index landed on when releasing a drag gesture across the pill.
     */
    fun computeTargetSlotIndex(relX: Float, contentWidth: Float, tabCount: Int): Int {
        if (tabCount <= 1 || contentWidth <= 0f) return 0
        return ((relX / contentWidth) * tabCount).toInt().coerceIn(0, tabCount - 1)
    }
}

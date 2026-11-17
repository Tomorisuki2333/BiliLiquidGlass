package com.bililiquidglass.xposed.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bililiquidglass.xposed.config.GlassConfig
import com.bililiquidglass.xposed.glass.LiquidGlassPillLayout
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure JVM-testable helper for Compose packed `ULong` (`Color`) decoding and suppression rules.
 */
object ComposeColorSuppressor {
    /** `Color.Transparent.value` = `ColorKt.Color(0)` in Compose UI (`0L`). */
    const val TRANSPARENT_COLOR: Long = 0L

    /** `ColorKt.UnspecifiedColor` / `Color.Unspecified.value` (`0x10L` in Compose UI). */
    const val UNSPECIFIED_COLOR: Long = 16L

    /**
     * Decodes a packed Compose `Color` (`ULong` stored in JVM `long`) to 32-bit ARGB when it uses
     * the default sRGB color space (`(packedColor and 0x3FL) == 0L`).
     * Returns `null` for extended / non-sRGB color spaces.
     */
    fun decodeComposeSrgbArgb(packedColor: Long): Int? {
        if (packedColor == 0L) return 0
        if ((packedColor and 0x3FL) != 0L) return null
        return (packedColor ushr 32).toInt()
    }

    /**
     * Returns true when a `BackgroundKt.background-bw27NRU(Modifier, long, Shape)` call inside
     * the bottom navigation container represents a non-transparent, specified rectangular background.
     */
    fun shouldSuppressBarBackgroundColor(packedColor: Long, isRectangleShape: Boolean): Boolean {
        if (!isRectangleShape) return false
        if (packedColor == TRANSPARENT_COLOR || packedColor == UNSPECIFIED_COLOR) return false
        val srgbArgb = decodeComposeSrgbArgb(packedColor) ?: return true
        return (srgbArgb ushr 24) > 0
    }
}

/**
 * Layer 1 (Composition-time) suppressor for Bilibili's Jetpack Compose bottom navigation bar.
 *
 * Unlike draw-time hooks alone, intercepting `BackgroundKt.background-bw27NRU` during composition
 * replaces `Bg1` (bar fill) and `Ga2` (0.5dp top divider) with `Color.Transparent` before any
 * hardware `RenderNode` display list is recorded, while preserving Compose's slot table structure
 * and leaving the Gradient `Brush` publish button (`f1o0`) and child tab items/badges intact.
 */
class ComposeCompositionSuppressor(private val xposed: XposedInterface) {

    private val tag = "BiliLiquidGlass"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installed = AtomicBoolean(false)
    private val loggedFirstSuppression = AtomicBoolean(false)

    private val pills = CopyOnWriteArrayList<WeakReference<LiquidGlassPillLayout>>()
    private val lastDetectedBgArgb = AtomicReference<Int?>(null)

    private val barLambdaDepth = ThreadLocal<Int>()
    private val fallbackCompDepth = ThreadLocal<Int>()
    private val excludedChildDepth = ThreadLocal<Int>()
    private val capturedBgInFrame = ThreadLocal<Boolean>()

    @Volatile
    private var hasExplicitBarLambda = false

    @Volatile
    private var rectangleShapeInstance: Any? = null

    private val hookedMethods = Collections.synchronizedSet(HashSet<Method>())

    fun registerPill(pillLayout: LiquidGlassPillLayout) {
        pills.removeIf { ref ->
            val target = ref.get()
            target == null || target === pillLayout
        }
        pills.add(WeakReference(pillLayout))
        lastDetectedBgArgb.get()?.let { argb ->
            applyDetectedColorToPill(pillLayout, argb)
        }
    }

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        resolveRectangleShape(classLoader)
        val alphaMethod = resolveAlphaModifierMethod(classLoader)

        val bgColorHooked = hookBackgroundColor(classLoader)
        val barLambdaHooked = hookHomeBottomBarLambda(classLoader)
        hasExplicitBarLambda = barLambdaHooked

        val fallbackHooked = hookBottomTabComponentCompose(classLoader)
        val exclusionsHooked = hookChildExclusions(classLoader)
        val garbImageHooked = hookGarbBottomImage(classLoader, alphaMethod)

        xposed.log(
            Log.WARN,
            tag,
            "Composition suppressors installed (bgColor=$bgColorHooked, barLambda=$barLambdaHooked, " +
                "fallbackComp=$fallbackHooked, exclusions=$exclusionsHooked, garbAlpha=$garbImageHooked)"
        )
    }

    private fun resolveRectangleShape(classLoader: ClassLoader) {
        runCatching {
            val rectKt = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.graphics.RectangleShapeKt"
            ) ?: return
            val getter = rectKt.getDeclaredMethod("getRectangleShape")
            getter.isAccessible = true
            rectangleShapeInstance = getter.invoke(null)
        }
    }

    private fun resolveAlphaModifierMethod(classLoader: ClassLoader): Method? {
        return runCatching {
            val modifierClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.Modifier"
            ) ?: return null
            val alphaKt = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.draw.AlphaKt"
            ) ?: return null
            alphaKt.getDeclaredMethod("alpha", modifierClass, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private fun hookBackgroundColor(classLoader: ClassLoader): Boolean {
        val backgroundKt = HostReflection.findClassOrNull(
            classLoader,
            "androidx.compose.foundation.BackgroundKt"
        ) ?: return false

        val modifierClass = HostReflection.findClassOrNull(classLoader, "androidx.compose.ui.Modifier")
        val shapeClass = HostReflection.findClassOrNull(classLoader, "androidx.compose.ui.graphics.Shape")

        for (m in backgroundKt.declaredMethods) {
            if (m.name.startsWith("background-bw27NRU") && m.name.contains("default")) {
                deoptimizeOnce(m)
            }
        }

        val targetMethod = if (modifierClass != null && shapeClass != null) {
            HostReflection.findMethodExact(
                backgroundKt,
                "background-bw27NRU",
                modifierClass,
                Long::class.javaPrimitiveType!!,
                shapeClass
            )
        } else {
            null
        } ?: backgroundKt.declaredMethods.firstOrNull { m ->
            m.name == "background-bw27NRU" &&
                m.parameterCount == 3 &&
                m.parameterTypes[1] == Long::class.javaPrimitiveType
        } ?: return false

        return runCatching {
            deoptimizeOnce(targetMethod)
            xposed.hook(targetMethod).intercept { chain ->
                val inTarget = isInsideTargetBarBackgroundScope()
                if (!inTarget) {
                    return@intercept chain.proceed()
                }
                val args = chain.getArgs()
                if (args.size < 3) {
                    return@intercept chain.proceed()
                }
                val packedColor = (args[1] as? Number)?.toLong()
                    ?: return@intercept chain.proceed()
                val shape = args[2]
                val isRect = isRectangleShape(shape)
                if (!ComposeColorSuppressor.shouldSuppressBarBackgroundColor(packedColor, isRect)) {
                    return@intercept chain.proceed()
                }

                if (capturedBgInFrame.get() != true) {
                    capturedBgInFrame.set(true)
                    ComposeColorSuppressor.decodeComposeSrgbArgb(packedColor)?.let { argb ->
                        if ((argb ushr 24) >= 0x40) {
                            notifyDetectedBarBgColor(argb)
                        }
                    }
                }

                if (loggedFirstSuppression.compareAndSet(false, true)) {
                    xposed.log(
                        Log.WARN,
                        tag,
                        "Suppressed Compose bottom bar background during composition (color=0x${packedColor.toULong().toString(16)})"
                    )
                }

                val newArgs = args.toTypedArray()
                newArgs[1] = ComposeColorSuppressor.TRANSPARENT_COLOR
                chain.proceed(newArgs)
            }
            true
        }.getOrElse { t ->
            xposed.log(Log.WARN, tag, "BackgroundKt hook failed: ${t.message}")
            false
        }
    }

    private fun hookHomeBottomBarLambda(classLoader: ClassLoader): Boolean {
        // Bilibili 9.19 HomeBottomTabContainer's BoxWithConstraints content lambda (`r0o0`).
        // Guarded structurally by WindowSizeClass field + SnapshotStateMap + 3-arg invoke.
        val candidates = listOf("r0o0")
        var hooked = false
        for (name in candidates) {
            val clazz = HostReflection.findClassOrNull(classLoader, name) ?: continue
            val looksLikeHomeBarLambda = clazz.declaredFields.any { f ->
                f.type.name.contains("WindowSizeClass")
            } && clazz.declaredFields.any { f ->
                f.type.name.contains("SnapshotStateMap")
            }
            if (!looksLikeHomeBarLambda) continue

            val invokeMethod = HostReflection.findMethodExact(
                clazz,
                "invoke",
                Any::class.java,
                Any::class.java,
                Any::class.java
            ) ?: clazz.declaredMethods.firstOrNull {
                it.name == "invoke" && it.parameterCount == 3
            } ?: continue

            val ok = runCatching {
                deoptimizeOnce(invokeMethod)
                xposed.hook(invokeMethod).intercept { chain ->
                    val prev = barLambdaDepth.get() ?: 0
                    barLambdaDepth.set(prev + 1)
                    if (prev == 0) {
                        capturedBgInFrame.set(false)
                    }
                    try {
                        chain.proceed()
                    } finally {
                        if (prev <= 0) {
                            barLambdaDepth.remove()
                            capturedBgInFrame.remove()
                        } else {
                            barLambdaDepth.set(prev)
                        }
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) {
                hooked = true
            }
        }

        // Deoptimize caller wrappers (`u0o0`, `w0o0`) so AOT cannot inline past our hooks.
        for (callerName in listOf("u0o0", "w0o0")) {
            HostReflection.findClassOrNull(classLoader, callerName)?.let { callerClass ->
                for (m in callerClass.declaredMethods) {
                    deoptimizeOnce(m)
                }
            }
        }
        return hooked
    }

    private fun hookBottomTabComponentCompose(classLoader: ClassLoader): Boolean {
        val btcClass = HostReflection.findClassOrNull(
            classLoader,
            "tv.danmaku.bili.home.components.bottomtab.BottomTabComponent"
        ) ?: return false

        var anyHooked = false
        for (m in btcClass.declaredMethods) {
            if (m.parameterTypes.any { it.name == "androidx.compose.runtime.Composer" }) {
                val ok = runCatching {
                    deoptimizeOnce(m)
                    xposed.hook(m).intercept { chain ->
                        val prev = fallbackCompDepth.get() ?: 0
                        fallbackCompDepth.set(prev + 1)
                        if (prev == 0 && !hasExplicitBarLambda) {
                            capturedBgInFrame.set(false)
                        }
                        try {
                            chain.proceed()
                        } finally {
                            if (prev <= 0) {
                                fallbackCompDepth.remove()
                            } else {
                                fallbackCompDepth.set(prev)
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
                if (ok) anyHooked = true
            }
        }
        return anyHooked
    }

    private fun hookChildExclusions(classLoader: ClassLoader): Int {
        // Tab items (`tv.danmaku.bili.khome.widget.bottomtab.a`), publish button (`f1o0`),
        // and publish bubble (`jlu1`, `tv.danmaku.bili.khome.widget.bubble.b`) must never have
        // any rectangular backgrounds or BiliImage icons/badges altered.
        val exclusionClasses = listOf(
            "tv.danmaku.bili.khome.widget.bottomtab.a",
            "tv.danmaku.bili.khome.widget.bubble.b",
            "jlu1",
            "f1o0"
        )
        var count = 0
        for (className in exclusionClasses) {
            val clazz = HostReflection.findClassOrNull(classLoader, className) ?: continue
            for (m in clazz.declaredMethods) {
                if (m.parameterTypes.any { it.name == "androidx.compose.runtime.Composer" }) {
                    val ok = runCatching {
                        deoptimizeOnce(m)
                        xposed.hook(m).intercept { chain ->
                            val prev = excludedChildDepth.get() ?: 0
                            excludedChildDepth.set(prev + 1)
                            try {
                                chain.proceed()
                            } finally {
                                if (prev <= 0) {
                                    excludedChildDepth.remove()
                                } else {
                                    excludedChildDepth.set(prev)
                                }
                            }
                        }
                        true
                    }.getOrDefault(false)
                    if (ok) count++
                }
            }
        }
        return count
    }

    private fun hookGarbBottomImage(classLoader: ClassLoader, alphaMethod: Method?): Boolean {
        if (alphaMethod == null) return false
        val biliImageKt = HostReflection.findClassOrNull(
            classLoader,
            "kntr.base.imageloader.BiliImageKt"
        ) ?: return false
        val target = biliImageKt.declaredMethods.firstOrNull { m ->
            m.name == "BiliImage" &&
                m.parameterCount >= 2 &&
                m.parameterTypes[1].name == "androidx.compose.ui.Modifier" &&
                m.parameterTypes.any { it.name == "androidx.compose.runtime.Composer" }
        } ?: return false

        return runCatching {
            deoptimizeOnce(target)
            xposed.hook(target).intercept { chain ->
                // Only suppress Garb bottom background image drawn directly inside `r0o0`
                // (outside tab items / bubble exclusions).
                val isDirectBarGarbBg = hasExplicitBarLambda &&
                    (barLambdaDepth.get() ?: 0) > 0 &&
                    (excludedChildDepth.get() ?: 0) == 0
                if (!isDirectBarGarbBg) {
                    return@intercept chain.proceed()
                }
                val args = chain.getArgs()
                val modifier = args.getOrNull(1) ?: return@intercept chain.proceed()
                val hiddenModifier = runCatching {
                    alphaMethod.invoke(null, modifier, 0f)
                }.getOrNull() ?: return@intercept chain.proceed()
                val newArgs = args.toTypedArray()
                newArgs[1] = hiddenModifier
                chain.proceed(newArgs)
            }
            true
        }.getOrElse { false }
    }

    private fun isInsideTargetBarBackgroundScope(): Boolean {
        if ((excludedChildDepth.get() ?: 0) > 0) return false
        if ((barLambdaDepth.get() ?: 0) > 0) return true
        return !hasExplicitBarLambda && (fallbackCompDepth.get() ?: 0) > 0
    }

    private fun isRectangleShape(shape: Any?): Boolean {
        if (shape == null) return true
        val rect = rectangleShapeInstance
        if (rect != null && shape === rect) return true
        return shape.javaClass.name.contains("Rectangle", ignoreCase = true)
    }

    private fun notifyDetectedBarBgColor(argb: Int) {
        lastDetectedBgArgb.set(argb)
        for (ref in pills) {
            val pill = ref.get() ?: continue
            applyDetectedColorToPill(pill, argb)
        }
    }

    private fun applyDetectedColorToPill(pill: LiquidGlassPillLayout, argb: Int) {
        val apply = {
            pill.detectedBaseBgColor = argb
            pill.isNightTheme = GlassConfig.isColorDark(argb)
        }
        if (Looper.getMainLooper().isCurrentThread) {
            apply()
        } else {
            mainHandler.post(apply)
        }
    }

    private fun deoptimizeOnce(method: Method) {
        if (hookedMethods.add(method)) {
            runCatching { xposed.deoptimize(method) }
        }
    }
}

package io.github.tomorisuki2333.bililiquidglass.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.tomorisuki2333.bililiquidglass.config.GlassConfig
import io.github.tomorisuki2333.bililiquidglass.glass.LiquidGlassPillLayout
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal object HostReflection {
    fun findClassOrNull(classLoader: ClassLoader, name: String): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    fun findFieldByType(clazz: Class<*>, targetType: Class<*>): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (targetType.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    fun findMethodExact(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }
}

/**
 * Keeps each floating LiquidGlassPillLayout's backdrop synced with its sibling content ViewGroup
 * without running an idle frame loop.
 */
internal class CoordinatorInvalidationWatcher(
    private val xposed: XposedInterface
) {
    private data class PairEntry(
        val coordinator: ViewGroup,
        val contentView: View,
        val pillLayout: LiquidGlassPillLayout
    )

    private val entries = CopyOnWriteArrayList<WeakReference<PairEntry>>()
    private val hookInstalled = AtomicBoolean(false)

    fun attach(coordinator: ViewGroup, contentView: View, pillLayout: LiquidGlassPillLayout) {
        ensureHookInstalled()
        cleanupStale()
        entries.removeAll { it.get()?.pillLayout === pillLayout }
        entries.add(WeakReference(PairEntry(coordinator, contentView, pillLayout)))

        val scrollListener = ViewTreeObserver.OnScrollChangedListener {
            pillLayout.invalidateGlassBackdrop()
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            pillLayout.invalidateGlassBackdrop()
        }

        coordinator.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        contentView.addOnLayoutChangeListener(layoutListener)

        pillLayout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!coordinator.viewTreeObserver.isAlive) return
                coordinator.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                coordinator.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (coordinator.viewTreeObserver.isAlive) {
                    coordinator.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                }
                contentView.removeOnLayoutChangeListener(layoutListener)
                entries.removeAll { it.get()?.pillLayout === pillLayout || it.get() == null }
            }
        })
    }

    private fun cleanupStale() {
        entries.removeAll { it.get() == null }
    }

    private fun ensureHookInstalled() {
        if (!hookInstalled.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val method = HostReflection.findMethodExact(
            ViewGroup::class.java,
            "onDescendantInvalidated",
            View::class.java,
            View::class.java
        ) ?: return

        xposed.hook(method).intercept { chain ->
            val result = chain.proceed()
            if (entries.isEmpty()) return@intercept result
            val self = chain.thisObject
            val child = chain.getArg(0)
            for (ref in entries) {
                val entry = ref.get()
                if (entry != null && entry.coordinator === self && entry.contentView === child) {
                    entry.pillLayout.invalidateGlassBackdrop()
                }
            }
            result
        }
    }
}

/**
 * Suppresses only the full-width opaque background rect and top hairline divider drawn inside
 * Bilibili's Home Compose bottom bar, preserving icons, labels, badges, and publish button.
 *
 * Uses two independent interception layers:
 * 1) BackgroundNode.draw(ContentDrawScope) resolved dynamically via BackgroundKt (immune to drawRect AOT inlining).
 * 2) AndroidCanvas.drawRect(...) paired with deoptimize() on CanvasDrawScope callers as fallback.
 */
internal class ComposeBarBackgroundSuppressor(
    private val xposed: XposedInterface
) {
    private val tag = "BiliLiquidGlass"

    private data class DrawContext(
        val pillLayout: LiquidGlassPillLayout,
        val barWidth: Float,
        val barHeight: Float,
        val maxDividerPx: Float
    )

    private val registeredComposeViews = Collections.synchronizedMap(
        WeakHashMap<View, LiquidGlassPillLayout>()
    )
    private val activeContext = ThreadLocal<DrawContext?>()
    private val androidComposeHooked = AtomicBoolean(false)
    private val drawHooksInstalled = AtomicBoolean(false)

    @Volatile
    private var composeCanvasInterface: Class<*>? = null

    @Volatile
    private var paintFrameworkField: Field? = null

    fun registerBottomBar(composeView: ViewGroup, pillLayout: LiquidGlassPillLayout, classLoader: ClassLoader) {
        composeView.setBackgroundColor(Color.TRANSPARENT)
        registeredComposeViews[composeView] = pillLayout
        ensureAndroidComposeHook(classLoader)
        clearComposeHierarchyBackgrounds(composeView, classLoader)
        composeView.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                if (child != null) {
                    clearComposeHierarchyBackgrounds(child, classLoader)
                }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        ensureComposeDrawHooks(composeView, classLoader)
        composeView.postInvalidate()
    }

    private fun clearComposeHierarchyBackgrounds(view: View, classLoader: ClassLoader) {
        if (view.background != null) {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
        val clsName = view.javaClass.name
        if (clsName == "androidx.compose.ui.platform.AndroidComposeView" || clsName.contains("ComposeView")) {
            ensureComposeDrawHooks(view, classLoader)
        }
        view.invalidate()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearComposeHierarchyBackgrounds(view.getChildAt(i), classLoader)
            }
        }
    }

    private fun ensureAndroidComposeHook(classLoader: ClassLoader) {
        if (!androidComposeHooked.compareAndSet(false, true)) return
        HostReflection.findClassOrNull(
            classLoader,
            "androidx.compose.ui.platform.AbstractComposeView"
        )?.let { acvBase ->
            for (m in acvBase.declaredMethods) {
                if (m.name == "onAttachedToWindow" || m.name == "dispatchDraw" || m.name == "onLayout") {
                    runCatching { xposed.deoptimize(m) }
                }
            }
        }
        val androidComposeViewClass = HostReflection.findClassOrNull(
            classLoader,
            "androidx.compose.ui.platform.AndroidComposeView"
        ) ?: return

        composeCanvasInterface = HostReflection.findClassOrNull(
            classLoader,
            "androidx.compose.ui.graphics.Canvas"
        )

        val dispatchDrawMethod = HostReflection.findMethodExact(
            androidComposeViewClass,
            "dispatchDraw",
            Canvas::class.java
        ) ?: return

        runCatching { xposed.deoptimize(dispatchDrawMethod) }
        for (m in androidComposeViewClass.declaredMethods) {
            if (m.name == "onDraw" || m.name == "dispatchGetDisplayList") {
                runCatching { xposed.deoptimize(m) }
            }
        }

        xposed.hook(dispatchDrawMethod).intercept { chain ->
            val acv = chain.thisObject as? View
            val pillLayout = findRegisteredPill(acv)
            if (acv == null || pillLayout == null) {
                return@intercept chain.proceed()
            }

            if (acv.background != null) {
                acv.setBackgroundColor(Color.TRANSPARENT)
            }
            ensureComposeDrawHooks(acv, classLoader)

            val prev = activeContext.get()
            val density = acv.resources.displayMetrics.density
            activeContext.set(
                DrawContext(
                    pillLayout = pillLayout,
                    barWidth = acv.width.toFloat(),
                    barHeight = acv.height.toFloat(),
                    maxDividerPx = max(4f, 2.5f * density)
                )
            )
            try {
                chain.proceed()
            } finally {
                activeContext.set(prev)
            }
        }
    }

    private fun findRegisteredPill(view: View?): LiquidGlassPillLayout? {
        var current: View? = view
        var depth = 0
        while (current != null && depth < 8) {
            val found = registeredComposeViews[current]
            if (found != null) return found
            current = current.parent as? View
            depth++
        }
        return null
    }

    private fun ensureComposeDrawHooks(androidComposeView: View, classLoader: ClassLoader) {
        if (!drawHooksInstalled.compareAndSet(false, true)) return

        val cl = androidComposeView.javaClass.classLoader ?: classLoader
        val bgNodeHooked = installBackgroundNodeHook(cl)
        val canvasDrawHooked = installCanvasDrawRectHook(androidComposeView, cl)
        xposed.log(
            Log.WARN,
            tag,
            "Compose suppressors installed: backgroundNode=$bgNodeHooked, canvasDrawRect=$canvasDrawHooked"
        )
    }

    private fun installBackgroundNodeHook(classLoader: ClassLoader): Boolean {
        return try {
            val bgKtClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.foundation.BackgroundKt"
            ) ?: return false
            val modifierClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.Modifier"
            ) ?: return false
            val shapeClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.graphics.Shape"
            ) ?: return false
            val rectShapeKtClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.graphics.RectangleShapeKt"
            ) ?: return false
            val delegatableNodeClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.node.DelegatableNode"
            ) ?: return false
            val drawScopeClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.graphics.drawscope.DrawScope"
            ) ?: return false
            val contentDrawScopeClass = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.graphics.drawscope.ContentDrawScope"
            ) ?: return false

            val companion = modifierClass.getField("Companion").get(null) ?: return false
            val rectShape = rectShapeKtClass.getDeclaredMethod("getRectangleShape").apply {
                isAccessible = true
            }.invoke(null) ?: return false

            val bgModifierFn = bgKtClass.declaredMethods.firstOrNull { m ->
                JavaModifier.isStatic(m.modifiers) &&
                    m.parameterCount == 3 &&
                    m.parameterTypes[0] == modifierClass &&
                    m.parameterTypes[1] == Long::class.javaPrimitiveType &&
                    m.parameterTypes[2] == shapeClass
            } ?: return false
            bgModifierFn.isAccessible = true

            // Sentinel sRGB color lets us pinpoint BackgroundNode's color field without relying on R8 names.
            val sentinelColorLong = 0xFF123456L shl 32
            val bgElement = bgModifierFn.invoke(null, companion, sentinelColorLong, rectShape) ?: return false
            val createMethod = bgElement.javaClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 0 && delegatableNodeClass.isAssignableFrom(m.returnType)
            } ?: return false
            createMethod.isAccessible = true

            val sampleNode = createMethod.invoke(bgElement) ?: return false
            val nodeClass = sampleNode.javaClass

            val drawMethod = nodeClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 &&
                    contentDrawScopeClass.isAssignableFrom(m.parameterTypes[0]) &&
                    m.returnType == Void.TYPE
            } ?: return false
            drawMethod.isAccessible = true

            val colorField = nodeClass.declaredFields.firstOrNull { f ->
                f.type == Long::class.javaPrimitiveType && runCatching {
                    f.isAccessible = true
                    f.getLong(sampleNode) == sentinelColorLong
                }.getOrDefault(false)
            }

            val drawContentMethod = contentDrawScopeClass.getDeclaredMethod("drawContent").apply {
                isAccessible = true
            }
            val getSizeMethod = drawScopeClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 0 &&
                    m.returnType == Long::class.javaPrimitiveType &&
                    m.name.startsWith("getSize")
            } ?: return false
            getSizeMethod.isAccessible = true

            val drawContentInvoker = xposed.getInvoker(drawContentMethod)
            val getSizeInvoker = xposed.getInvoker(getSizeMethod)

            // Deoptimize LayoutNodeDrawScope callers so ART cannot inline drawMethod or drawRect.
            for (m in contentDrawScopeClass.declaredMethods) {
                runCatching { xposed.deoptimize(m) }
            }
            val scopeClasses = LinkedHashSet<Class<*>>()
            sampleNode.javaClass.classLoader?.let { cl ->
                HostReflection.findClassOrNull(cl, "wpy0")?.let { scopeClasses.add(it) }
                HostReflection.findClassOrNull(cl, "LayoutNodeDrawScope")?.let { scopeClasses.add(it) }
                HostReflection.findClassOrNull(cl, "androidx.compose.ui.node.LayoutNodeDrawScope")?.let { scopeClasses.add(it) }
            }
            for (nm in sampleNode.javaClass.declaredMethods) {
                for (pt in nm.parameterTypes) {
                    if (!pt.isPrimitive && pt != Any::class.java && pt != String::class.java) {
                        scopeClasses.add(pt)
                    }
                }
            }
            for (lnds in scopeClasses) {
                for (m in lnds.declaredMethods) {
                    if (m.name == "a" || m.name == "drawContent" || m.name.contains("drawRect", ignoreCase = true)) {
                        runCatching { xposed.deoptimize(m) }
                    }
                }
            }

            xposed.hook(drawMethod).intercept { chain ->
                val ctx = activeContext.get() ?: return@intercept chain.proceed()
                val scope = chain.getArg(0) ?: return@intercept chain.proceed()
                val sizeLong = (getSizeInvoker.invoke(scope) as? Long) ?: return@intercept chain.proceed()

                val nodeW = java.lang.Float.intBitsToFloat((sizeLong ushr 32).toInt())
                val nodeH = java.lang.Float.intBitsToFloat(sizeLong.toInt())

                val isFullSpan = ctx.barWidth > 0f && nodeW >= ctx.barWidth * 0.90f
                val isFullBg = isFullSpan && ctx.barHeight > 0f && nodeH >= ctx.barHeight * 0.80f
                val isTopDivider = isFullSpan && nodeH in 0.25f..ctx.maxDividerPx

                if (isFullBg || isTopDivider) {
                    if (isFullBg && colorField != null) {
                        val colorLong = colorField.getLong(chain.thisObject)
                        if ((colorLong and 0x3FL) == 0L) {
                            val argb = (colorLong ushr 32).toInt()
                            if ((argb ushr 24) >= 0x40) {
                                ctx.pillLayout.detectedBaseBgColor = argb or (0xFF shl 24)
                                ctx.pillLayout.isNightTheme = GlassConfig.isColorDark(argb)
                            }
                        }
                    }
                    drawContentInvoker.invoke(scope)
                    return@intercept null
                }

                chain.proceed()
            }
            true
        } catch (t: Throwable) {
            xposed.log(Log.WARN, tag, "BackgroundNode hook failed: ${t.message}")
            false
        }
    }

    private fun installCanvasDrawRectHook(androidComposeView: View, classLoader: ClassLoader): Boolean {
        return try {
            val canvasIntf = composeCanvasInterface ?: HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.graphics.Canvas"
            ) ?: return false
            val paintIntf = HostReflection.findClassOrNull(
                classLoader,
                "androidx.compose.ui.graphics.Paint"
            ) ?: return false

            val concreteCanvasClass = resolveAndroidCanvasClass(androidComposeView, canvasIntf) ?: return false
            val drawRectMethod = findDrawRectMethod(concreteCanvasClass, paintIntf) ?: return false

            deoptimizeDrawScopeCallers(classLoader)

            xposed.hook(drawRectMethod).intercept { chain ->
                val ctx = activeContext.get() ?: return@intercept chain.proceed()
                val left = (chain.getArg(0) as? Number)?.toFloat() ?: return@intercept chain.proceed()
                val top = (chain.getArg(1) as? Number)?.toFloat() ?: return@intercept chain.proceed()
                val right = (chain.getArg(2) as? Number)?.toFloat() ?: return@intercept chain.proceed()
                val bottom = (chain.getArg(3) as? Number)?.toFloat() ?: return@intercept chain.proceed()

                val rectW = right - left
                val rectH = bottom - top
                val isFullSpan = ctx.barWidth > 0f &&
                    left <= 2f &&
                    top <= 2f &&
                    rectW >= ctx.barWidth * 0.90f

                if (isFullSpan && rectH >= ctx.barHeight * 0.80f && ctx.barHeight > 0f) {
                    val colorArgb = extractPaintColor(chain.getArg(4))
                    if (colorArgb != null && (colorArgb ushr 24) >= 0x40) {
                        ctx.pillLayout.detectedBaseBgColor = colorArgb or (0xFF shl 24)
                        ctx.pillLayout.isNightTheme = GlassConfig.isColorDark(colorArgb)
                    }
                    return@intercept null
                }

                if (isFullSpan && rectH in 0.25f..ctx.maxDividerPx) {
                    return@intercept null
                }

                chain.proceed()
            }
            true
        } catch (t: Throwable) {
            xposed.log(Log.WARN, tag, "Canvas drawRect hook failed: ${t.message}")
            false
        }
    }

    private fun deoptimizeDrawScopeCallers(classLoader: ClassLoader) {
        val canvasDrawScope = HostReflection.findClassOrNull(
            classLoader,
            "androidx.compose.ui.graphics.drawscope.a"
        ) ?: HostReflection.findClassOrNull(
            classLoader,
            "androidx.compose.ui.graphics.drawscope.CanvasDrawScope"
        ) ?: return

        for (m in canvasDrawScope.declaredMethods) {
            if (m.name.contains("drawRect", ignoreCase = true)) {
                runCatching { xposed.deoptimize(m) }
            }
        }
    }

    private fun resolveAndroidCanvasClass(androidComposeView: View, canvasIntf: Class<*>): Class<*>? {
        val direct = HostReflection.findClassOrNull(
            androidComposeView.javaClass.classLoader!!,
            "androidx.compose.ui.graphics.a"
        )
        if (direct != null && canvasIntf.isAssignableFrom(direct)) {
            return direct
        }
        val directFull = HostReflection.findClassOrNull(
            androidComposeView.javaClass.classLoader!!,
            "androidx.compose.ui.graphics.AndroidCanvas"
        )
        if (directFull != null && canvasIntf.isAssignableFrom(directFull)) {
            return directFull
        }

        var current: Class<*>? = androidComposeView.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (field.type.isPrimitive) continue
                try {
                    field.isAccessible = true
                    val holder = field.get(androidComposeView) ?: continue
                    val holderField = HostReflection.findFieldByType(holder.javaClass, canvasIntf)
                    val canvasImpl = holderField?.get(holder)
                    if (canvasImpl != null) {
                        return canvasImpl.javaClass
                    }
                } catch (_: Throwable) {
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun findDrawRectMethod(concreteCanvasClass: Class<*>, paintIntf: Class<*>): Method? {
        try {
            return concreteCanvasClass.getDeclaredMethod(
                "drawRect",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                paintIntf
            ).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
        }
        for (m in concreteCanvasClass.declaredMethods) {
            val p = m.parameterTypes
            if (p.size == 5 &&
                p[0] == Float::class.javaPrimitiveType &&
                p[1] == Float::class.javaPrimitiveType &&
                p[2] == Float::class.javaPrimitiveType &&
                p[3] == Float::class.javaPrimitiveType &&
                p[4].isAssignableFrom(paintIntf) &&
                m.name.contains("Rect", ignoreCase = true)
            ) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    private fun extractPaintColor(composePaint: Any?): Int? {
        if (composePaint == null) return null
        return try {
            var field = paintFrameworkField
            if (field == null) {
                field = HostReflection.findFieldByType(composePaint.javaClass, Paint::class.java)
                paintFrameworkField = field
            }
            val nativePaint = field?.get(composePaint) as? Paint
            nativePaint?.color
        } catch (_: Throwable) {
            null
        }
    }
}

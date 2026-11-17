package com.bililiquidglass.xposed.glass

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RecordingCanvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.bililiquidglass.xposed.config.GlassConfig
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating pill container implementing Kyant0/AndroidLiquidGlass aesthetics for an arbitrary
 * child navigation bar view, capturing a sibling backdrop ViewGroup via RenderNode.
 */
class LiquidGlassPillLayout(context: Context) : FrameLayout(context) {

    var config: GlassConfig = GlassConfig()
        set(value) {
            field = value.clamped()
            applyConfigMetrics()
        }

    var backdropSourceView: View? = null
    var targetBarView: View? = null
        private set

    var onTabDragSelectedListener: ((Int) -> Unit)? = null

    var isNightTheme: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                backdropView.invalidate()
                indicatorView.invalidate()
            }
        }

    var detectedBaseBgColor: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                backdropView.invalidate()
            }
        }

    private val density: Float = resources.displayMetrics.density
    private val backdropView: LiquidGlassBackdropView
    private val indicatorView: LiquidTabIndicatorView
    private val shadowDrawer: DropShadowDrawer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DropShadowApi29() else null

    private var isRecordingBackdrop = false
    private val reusableSrcLoc = IntArray(2)
    private val reusableSelfLoc = IntArray(2)
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var isDraggingTabPill = false
    private var verticalScrollYielded = false
    private var lastDragHapticSlot = -1
    private var isDispatchingSyntheticTap = false
    private var lastDwellTargetSlot = -1
    private var dwellTriggeredSlot = -1
    private val dwellClickRunnable = Runnable {
        if (isDraggingTabPill && lastDwellTargetSlot >= 0) {
            dwellTriggeredSlot = lastDwellTargetSlot
            onTabDragSelectedListener?.invoke(lastDwellTargetSlot)
        }
    }
    private val shadowRect = RectF()
    private val shadowClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillClipRect = RectF()
    private val pillClipPath = Path()
    private var lastShadowRadius = -1f
    private var lastShadowColor = 0

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        backdropView = LiquidGlassBackdropView(context)
        indicatorView = LiquidTabIndicatorView(context)

        addView(backdropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(indicatorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyConfigMetrics()
    }

    fun attachTargetBar(barView: View) {
        if (targetBarView === barView) return
        targetBarView?.let { removeView(it) }
        targetBarView = barView

        val sideInsetPx = dpToPx(6f)
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
            leftMargin = sideInsetPx
            rightMargin = sideInsetPx
        }
        val indicatorIdx = indexOfChild(indicatorView)
        if (indicatorIdx >= 0) {
            addView(barView, indicatorIdx, lp)
        } else {
            addView(barView, lp)
            bringChildToFront(indicatorView)
        }
    }

    fun updateSelection(selectedIndex: Int, tabCount: Int) {
        indicatorView.updateTabs(selectedIndex, tabCount)
    }

    /**
     * Simulates a real native touch tap (`ACTION_DOWN` -> `ACTION_UP`) directly on `targetBarView`
     * at the center of [slotIndex], bypassing drag interception.
     */
    fun dispatchSyntheticTapAtSlot(slotIndex: Int, tabCount: Int): Boolean {
        val bar = targetBarView ?: return false
        val count = tabCount.coerceIn(1, 8)
        if (slotIndex !in 0 until count || bar.width <= 0 || bar.height <= 0) return false
        val tapX = ((slotIndex + 0.5f) * (bar.width.toFloat() / count)).coerceIn(1f, (bar.width - 1).toFloat())
        val tapY = (bar.height * 0.5f).coerceIn(1f, (bar.height - 1).toFloat())
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
        val up = MotionEvent.obtain(now, now + 18L, MotionEvent.ACTION_UP, tapX, tapY, 0)
        isDispatchingSyntheticTap = true
        return try {
            val downHandled = bar.dispatchTouchEvent(down)
            val upHandled = bar.dispatchTouchEvent(up)
            downHandled || upHandled
        } finally {
            isDispatchingSyntheticTap = false
            down.recycle()
            up.recycle()
        }
    }

    fun invalidateGlassBackdrop() {
        if (!isRecordingBackdrop) {
            backdropView.invalidate()
            indicatorView.invalidate()
        }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (!isRecordingBackdrop && (child === targetBarView || child === backdropView)) {
            indicatorView.invalidate()
        }
    }

    fun syncWithTargetVisibility() {
        val bar = targetBarView ?: return
        val visible = bar.visibility == VISIBLE && bar.alpha > 0.02f
        val glassVisibility = if (visible) VISIBLE else GONE
        if (backdropView.visibility != glassVisibility) {
            backdropView.visibility = glassVisibility
        }
        if (indicatorView.visibility != glassVisibility) {
            indicatorView.visibility = glassVisibility
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        isNightTheme = night
        detectedBaseBgColor = if (night) 0xFF17181A.toInt() else Color.WHITE
        backdropView.invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isDispatchingSyntheticTap || !config.enabled) return super.dispatchTouchEvent(ev)
        val sideInset = dpToPxFloat(6f)
        val pillOn = config.activePillEnabled
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(dwellClickRunnable)
                dragDownX = ev.x
                dragDownY = ev.y
                isDraggingTabPill = false
                verticalScrollYielded = false
                lastDragHapticSlot = -1
                lastDwellTargetSlot = -1
                dwellTriggeredSlot = -1
                if (pillOn) parent?.requestDisallowInterceptTouchEvent(true)
                indicatorView.onHostPressStart(ev.x, ev.y, sideInset)
                val handled = super.dispatchTouchEvent(ev)
                // Always keep the gesture stream so we receive MOVE/UP even over gaps.
                return handled || pillOn
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pillOn) return super.dispatchTouchEvent(ev)
                if (isDraggingTabPill) {
                    val hoveredSlot = indicatorView.onHostDragMove(ev.x, ev.y, sideInset)
                    val roundedSlot = hoveredSlot.roundToInt()
                    if (roundedSlot != lastDragHapticSlot) {
                        lastDragHapticSlot = roundedSlot
                        runCatching { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    }
                    val targetSlot = indicatorView.resolveTargetSlot(ev.x, sideInset)
                    if (targetSlot != lastDwellTargetSlot) {
                        lastDwellTargetSlot = targetSlot
                        removeCallbacks(dwellClickRunnable)
                        postDelayed(dwellClickRunnable, 150L)
                    }
                    return true
                }
                if (!verticalScrollYielded) {
                    val dx = abs(ev.x - dragDownX)
                    val dy = abs(ev.y - dragDownY)
                    if (dy > touchSlop && dy > dx * 1.35f) {
                        verticalScrollYielded = true
                        parent?.requestDisallowInterceptTouchEvent(false)
                        indicatorView.resetPressState()
                    } else if (dx > touchSlop * 0.65f && dx >= dy * 0.7f) {
                        isDraggingTabPill = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // Cancel child press/click so the tab under the finger doesn't fire.
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        lastDragHapticSlot = indicatorView.onHostDragMove(ev.x, ev.y, sideInset).roundToInt()
                        val initialTarget = indicatorView.resolveTargetSlot(ev.x, sideInset)
                        lastDwellTargetSlot = initialTarget
                        removeCallbacks(dwellClickRunnable)
                        postDelayed(dwellClickRunnable, 150L)
                        return true
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(dwellClickRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isDraggingTabPill) {
                    val targetSlot = indicatorView.resolveTargetSlot(ev.x, sideInset)
                    isDraggingTabPill = false
                    lastDragHapticSlot = -1
                    lastDwellTargetSlot = -1
                    dwellTriggeredSlot = -1
                    indicatorView.finishDragToSlot(targetSlot)
                    onTabDragSelectedListener?.invoke(targetSlot)
                    return true
                }
                indicatorView.resetPressState()
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                removeCallbacks(dwellClickRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasDragging = isDraggingTabPill
                isDraggingTabPill = false
                verticalScrollYielded = false
                lastDragHapticSlot = -1
                lastDwellTargetSlot = -1
                dwellTriggeredSlot = -1
                indicatorView.resetPressState()
                if (wasDragging) return true
                return super.dispatchTouchEvent(ev)
            }
        }
        if (isDraggingTabPill) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePillClipPath(w, h)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            indicatorView.resetPressState()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dwellClickRunnable)
        indicatorView.resetPressState()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        syncWithTargetVisibility()
        if (config.enabled && backdropView.visibility == VISIBLE && width > 0 && height > 0) {
            drawDropShadow(canvas)
        }
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        syncWithTargetVisibility()
        if (width > 0 && height > 0 && !pillClipPath.isEmpty) {
            val saveCount = canvas.save()
            canvas.clipPath(pillClipPath)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    private fun updatePillClipPath(w: Int, h: Int) {
        pillClipPath.reset()
        if (w > 0 && h > 0) {
            val r = LiquidGlassGeometry.clampCornerRadius(w.toFloat(), h.toFloat(), config.cornerRadiusFraction)
            pillClipRect.set(0f, 0f, w.toFloat(), h.toFloat())
            pillClipPath.addRoundRect(pillClipRect, r, r, Path.Direction.CW)
        }
    }

    private fun applyConfigMetrics() {
        updatePillClipPath(width, height)
        val radiusPx = LiquidGlassGeometry.clampCornerRadius(
            width.toFloat().coerceAtLeast(1f),
            dpToPx(config.barHeightDp).toFloat(),
            config.cornerRadiusFraction
        )
        backdropView.onConfigChanged()
        indicatorView.visibility = if (config.activePillEnabled) VISIBLE else GONE
        indicatorView.onConfigChanged(radiusPx)
        invalidate()
    }

    private fun drawDropShadow(canvas: Canvas) {
        val alphaInt = (config.shadowAlpha * 255f).roundToInt().coerceIn(0, 255)
        if (alphaInt == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = LiquidGlassGeometry.clampCornerRadius(w, h, config.cornerRadiusFraction)
        val shadowRadius = dpToPx(18f).toFloat()
        val shadowDy = dpToPx(3f).toFloat()
        val color = (alphaInt shl 24)

        val drawer = shadowDrawer
        if (drawer != null) {
            drawer.draw(
                canvas,
                w,
                h,
                cornerRadius,
                shadowRadius,
                shadowDy,
                color,
                shadowPaint,
                shadowClearPaint,
                shadowRect
            )
            return
        }

        if (lastShadowRadius != shadowRadius || lastShadowColor != color) {
            shadowPaint.color = color
            shadowPaint.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
            lastShadowRadius = shadowRadius
            lastShadowColor = color
        }
        shadowRect.set(0f, shadowDy, w, h + shadowDy)
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)
    }

    private fun recordBackdropContent(
        recCanvas: Canvas,
        padPx: Int,
        extraTranslateX: Float = 0f,
        extraTranslateY: Float = 0f
    ) {
        val source = backdropSourceView
        val offsetX: Float
        val offsetY: Float
        if (source != null && source.parent === parent) {
            offsetX = source.x - this.x
            offsetY = source.y - this.y
        } else if (source != null) {
            source.getLocationInWindow(reusableSrcLoc)
            getLocationInWindow(reusableSelfLoc)
            offsetX = (reusableSrcLoc[0] - reusableSelfLoc[0]).toFloat()
            offsetY = (reusableSrcLoc[1] - reusableSelfLoc[1]).toFloat()
        } else {
            offsetX = 0f
            offsetY = 0f
        }

        recCanvas.drawColor(detectedBaseBgColor)

        if (source != null && source.visibility == VISIBLE && source.width > 0 && source.height > 0 && !isRecordingBackdrop) {
            isRecordingBackdrop = true
            try {
                val saveCount = recCanvas.save()
                recCanvas.translate(padPx + offsetX + extraTranslateX, padPx + offsetY + extraTranslateY)
                source.draw(recCanvas)
                recCanvas.restoreToCount(saveCount)
            } finally {
                isRecordingBackdrop = false
            }
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * density).roundToInt()

    private fun dpToPxFloat(dp: Float): Float = dp * density

    private inner class LiquidGlassBackdropView(context: Context) : View(context) {

        private val effectCache = LiquidGlassEffectCache()
        private val backdropRender: BackdropRenderDelegate? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) BackdropRenderApi29() else null
        private val compositeNode: RenderNode? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("LiquidGlassBarComposite") else null

        private val pillRect = RectF()
        private val clipPath = Path()
        private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private var currentCornerRadius: Float = 0f
        private var lastSheenW = -1
        private var lastSheenH = -1
        private var lastSheenDark = false

        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = view.width
                    val h = view.height
                    if (w > 0 && h > 0) {
                        val r = LiquidGlassGeometry.clampCornerRadius(
                            w.toFloat(),
                            h.toFloat(),
                            config.cornerRadiusFraction
                        )
                        outline.setRoundRect(0, 0, w, h, r)
                    }
                }
            }
        }

        fun onConfigChanged() {
            invalidateOutline()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateShape(w, h)
            invalidateOutline()
        }

        private fun updateShape(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            pillRect.set(0f, 0f, w.toFloat(), h.toFloat())
            currentCornerRadius = LiquidGlassGeometry.clampCornerRadius(
                w.toFloat(),
                h.toFloat(),
                config.cornerRadiusFraction
            )
            clipPath.reset()
            clipPath.addRoundRect(pillRect, currentCornerRadius, currentCornerRadius, Path.Direction.CW)
        }

        fun hasCachedComposite(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && compositeNode?.hasDisplayList() == true

        fun drawCachedComposite(targetCanvas: Canvas) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val node = compositeNode
                if (node != null && node.hasDisplayList() && targetCanvas.isHardwareAccelerated) {
                    targetCanvas.drawRenderNode(node)
                    return
                }
            }
            draw(targetCanvas)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            if (currentCornerRadius <= 0f) {
                updateShape(w, h)
            }

            val dark = isNightTheme
            val cfg = config
            val node = compositeNode
            if (node != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
                node.setPosition(0, 0, w, h)
                val recCanvas = node.beginRecording(w, h)
                try {
                    drawGlassLayers(recCanvas, w, h, dark, cfg)
                } finally {
                    node.endRecording()
                }
                canvas.drawRenderNode(node)
            } else {
                drawGlassLayers(canvas, w, h, dark, cfg)
            }
        }

        private fun drawGlassLayers(
            canvas: Canvas,
            w: Int,
            h: Int,
            dark: Boolean,
            cfg: GlassConfig
        ) {
            val delegate = backdropRender
            if (delegate != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurPx = dpToPxFloat(cfg.blurRadiusDp)
                val padPx = LiquidGlassGeometry.computeBackdropPaddingPx(blurPx)
                val preFilterEffect = effectCache.getOrCreatePreFilterEffect(cfg.vibrancy, blurPx)
                val lensParams = BackdropEffectParams(
                    width = w.toFloat(),
                    height = h.toFloat(),
                    paddingPx = 0,
                    cornerRadius = currentCornerRadius,
                    blurRadiusPx = 0f,
                    refractionHeightPx = dpToPxFloat(cfg.refractionHeightDp),
                    refractionAmountPx = dpToPxFloat(cfg.refractionAmountDp),
                    vibrancy = cfg.vibrancy,
                    chromaticAberration = cfg.chromaticAberration,
                    depthEffect = cfg.depthEffect
                )
                val lensEffect = effectCache.getOrCreateLensEffect(lensParams)
                delegate.drawBackdropNode(canvas, w, h, padPx, preFilterEffect, lensEffect) { recCanvas ->
                    recordBackdropContent(recCanvas, padPx)
                }
            }

            // Glass surface tint (subtle clear tint on API 31+, translucent fallback pre-API 31)
            val surfaceTint = GlassConfig.computeSurfaceTintColor(
                dark,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cfg.tintOpacity
                else (cfg.tintOpacity + 0.56f).coerceAtMost(0.92f)
            )
            surfacePaint.color = surfaceTint
            canvas.drawRoundRect(pillRect, currentCornerRadius, currentCornerRadius, surfacePaint)

            // Vertical curved glass Fresnel sheen
            drawGlassSheen(canvas, dark, w, h)

            // Crisp hairline inner refraction depth (kept narrow so edge lens warping remains clear)
            drawInnerRimDepth(canvas, dark)

            // Specular highlight rim (Kyant0 DefaultHighlight AGSL on API 33+, dual-tone gradient fallback)
            drawSpecularHighlight(canvas, dark, w.toFloat(), h.toFloat())
        }

        private fun drawGlassSheen(canvas: Canvas, dark: Boolean, w: Int, h: Int) {
            if (lastSheenW != w || lastSheenH != h || lastSheenDark != dark || sheenPaint.shader == null) {
                lastSheenW = w
                lastSheenH = h
                lastSheenDark = dark
                val topColor = if (dark) 0x16FFFFFF else 0x34FFFFFF
                val midColor = if (dark) 0x00FFFFFF else 0x06FFFFFF
                val bottomColor = if (dark) 0x0AFFFFFF else 0x14FFFFFF
                sheenPaint.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(topColor, midColor, bottomColor),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(pillRect, currentCornerRadius, currentCornerRadius, sheenPaint)
        }

        private fun drawInnerRimDepth(canvas: Canvas, dark: Boolean) {
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            val shadowStroke = max(1f, dpToPxFloat(0.75f))
            innerShadowPaint.strokeWidth = shadowStroke * 2f
            innerShadowPaint.maskFilter = BlurMaskFilter(dpToPxFloat(0.9f), BlurMaskFilter.Blur.NORMAL)
            innerShadowPaint.color = if (dark) 0x1A000000 else 0x0E000000
            canvas.drawRoundRect(pillRect, currentCornerRadius, currentCornerRadius, innerShadowPaint)
            canvas.restoreToCount(saveCount)
        }

        private fun drawSpecularHighlight(canvas: Canvas, dark: Boolean, w: Float, h: Float) {
            val intensity = config.highlightIntensity
            if (intensity <= 0.01f) return

            val baseAlpha = (intensity * (if (dark) 0.40f else 0.70f) * 255f).roundToInt().coerceIn(0, 255)
            val rimColor = (baseAlpha shl 24) or 0xFFFFFF
            val rimWidthPx = max(1.6f, dpToPxFloat(0.95f))

            val shader = effectCache.getOrCreateHighlightShader(
                width = w,
                height = h,
                cornerRadius = currentCornerRadius,
                colorArgb = Color.WHITE,
                angleDeg = 45f,
                falloff = 1.35f
            )

            val delegate = backdropRender
            if (shader != null && delegate != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                delegate.drawHighlightNode(
                    canvas = canvas,
                    width = width,
                    height = height,
                    clipPath = clipPath,
                    pillRect = pillRect,
                    cornerRadius = currentCornerRadius,
                    strokeWidth = ceil(rimWidthPx) * 2f,
                    blurRadius = dpToPxFloat(0.4f),
                    alpha = intensity * (if (dark) 0.40f else 0.62f),
                    shader = shader
                )
                return
            }

            // Fallback gradient rim pre-API 33
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            rimPaint.strokeWidth = rimWidthPx * 2f
            rimPaint.maskFilter = null
            rimPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(rimColor, (baseAlpha / 3 shl 24) or 0xFFFFFF, rimColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(pillRect, currentCornerRadius, currentCornerRadius, rimPaint)
            rimPaint.shader = null
            canvas.restoreToCount(saveCount)
        }
    }

    private inner class LiquidTabIndicatorView(context: Context) : View(context) {

        private val effectCache = LiquidGlassEffectCache()
        private val indicatorBackdropRender: BackdropRenderDelegate? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) BackdropRenderApi29() else null
        private val pillRect = RectF()
        private val pillClipPath = Path()
        private val localRect = RectF()
        private val localClipPath = Path()
        private val shadowRect = RectF()
        private val indicatorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val indicatorSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val indicatorRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var lastPillShadowDark = false
        private var lastPillShadowAlpha = -1
        private var lastSheenTop = Float.NaN
        private var lastSheenBottom = Float.NaN
        private var lastSheenDark = false

        private var tabCount = 5
        private var targetSlotIndex = 0
        private var animatedSlot = 0f
        private var slotVelocity = 0f
        private var targetPressed = 0f
        private var pressProgress = 0f
        private var touchX = 0f
        private var touchY = 0f
        private var outerCornerRadius = 0f
        private var animScheduled = false
        private var lastFrameNanos = 0L
        private var lastDragMoveNanos = 0L

        private val animRunnable = Runnable { stepAnimation() }

        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = view.width
                    val h = view.height
                    if (w > 0 && h > 0) {
                        val r = LiquidGlassGeometry.clampCornerRadius(
                            w.toFloat(),
                            h.toFloat(),
                            config.cornerRadiusFraction
                        )
                        outline.setRoundRect(0, 0, w, h, r)
                    }
                }
            }
        }

        fun onConfigChanged(cornerRadiusPx: Float) {
            outerCornerRadius = cornerRadiusPx
            invalidateOutline()
            invalidate()
        }

        fun updateTabs(selectedIndex: Int, count: Int) {
            val safeCount = count.coerceIn(1, 8)
            val safeIndex = selectedIndex.coerceIn(0, safeCount - 1)
            val changed = safeCount != tabCount || safeIndex != targetSlotIndex
            tabCount = safeCount
            targetSlotIndex = safeIndex
            if (changed) {
                scheduleAnim()
            }
        }

        fun resetPressState() {
            targetPressed = 0f
            if (pressProgress > 0.005f) {
                scheduleAnim()
            } else {
                pressProgress = 0f
            }
        }

        fun onHostPressStart(x: Float, y: Float, sideInsetPx: Float) {
            touchX = x
            touchY = y
            targetPressed = 1f
            scheduleAnim()
        }

        fun onHostDragMove(x: Float, y: Float, sideInsetPx: Float): Float {
            val contentW = (width - sideInsetPx * 2f).coerceAtLeast(1f)
            val relX = (x - sideInsetPx).coerceIn(0f, contentW)
            touchX = x
            touchY = y
            targetPressed = 1f
            pressProgress = max(pressProgress, 0.85f)
            val hoveredSlot = LiquidGlassGeometry.computeHoveredSlot(relX, contentW, tabCount)
            val now = System.nanoTime()
            val dt = if (lastDragMoveNanos > 0L) {
                ((now - lastDragMoveNanos) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
            } else {
                0.016f
            }
            lastDragMoveNanos = now
            val prevSlot = animatedSlot
            animatedSlot = hoveredSlot
            slotVelocity = ((hoveredSlot - prevSlot) / dt).coerceIn(-10f, 10f)
            invalidate()
            return hoveredSlot
        }

        fun resolveTargetSlot(x: Float, sideInsetPx: Float): Int {
            val contentW = (width - sideInsetPx * 2f).coerceAtLeast(1f)
            val relX = (x - sideInsetPx).coerceIn(0f, contentW)
            return LiquidGlassGeometry.computeTargetSlotIndex(relX, contentW, tabCount)
        }

        fun finishDragToSlot(slotIndex: Int) {
            targetSlotIndex = slotIndex.coerceIn(0, (tabCount - 1).coerceAtLeast(0))
            targetPressed = 0f
            scheduleAnim()
        }

        private fun scheduleAnim() {
            if (!animScheduled) {
                animScheduled = true
                lastFrameNanos = System.nanoTime()
                postOnAnimation(animRunnable)
            }
        }

        private fun stepAnimation() {
            animScheduled = false
            val now = System.nanoTime()
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
            lastFrameNanos = now

            // Critical-damped spring toward target tab slot (matching Kyant0 DampedDragAnimation stiffness/damping)
            val stiffness = 280f
            val damping = 30f
            val displacement = animatedSlot - targetSlotIndex
            val accel = -stiffness * displacement - damping * slotVelocity
            slotVelocity += accel * dt
            animatedSlot += slotVelocity * dt

            // Fast approach for press progress
            val pressStep = (targetPressed - pressProgress) * min(1f, dt * 16f)
            pressProgress = (pressProgress + pressStep).coerceIn(0f, 1f)

            val slotSettled = abs(animatedSlot - targetSlotIndex) < 0.002f && abs(slotVelocity) < 0.01f
            val pressSettled = abs(pressProgress - targetPressed) < 0.005f
            if (slotSettled) {
                animatedSlot = targetSlotIndex.toFloat()
                slotVelocity = 0f
            }
            if (pressSettled) {
                pressProgress = targetPressed
            }

            invalidate()
            if (!slotSettled || !pressSettled) {
                animScheduled = true
                postOnAnimation(animRunnable)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || tabCount <= 0) return

            val sideInset = dpToPxFloat(6f)
            val vertPad = dpToPxFloat(4f)
            val availW = (w - sideInset * 2f).coerceAtLeast(1f)
            val slotW = availW / tabCount
            val innerH = (h - vertPad * 2f).coerceAtLeast(1f)

            val pressScale = 1f + 0.06f * pressProgress
            val velocityStretch = (abs(slotVelocity) * 0.020f).coerceAtMost(0.14f)
            val drawW = slotW * (pressScale + velocityStretch)
            val drawH = innerH * pressScale
            val centerX = sideInset + (animatedSlot + 0.5f) * slotW
            val centerY = h * 0.5f

            pillRect.set(
                centerX - drawW * 0.5f,
                centerY - drawH * 0.5f,
                centerX + drawW * 0.5f,
                centerY + drawH * 0.5f
            )
            val r = LiquidGlassGeometry.clampCornerRadius(drawW, drawH, config.cornerRadiusFraction)

            if (config.activePillEnabled) {
                drawGlassIndicatorPill(canvas, r)
            }

            if (config.interactiveHighlightEnabled && pressProgress > 0.02f) {
                val baseGlow = if (isNightTheme) 0.13f else 0.08f
                val highlightScale = config.highlightIntensity.coerceIn(0.25f, 1.0f)
                val glowAlpha = (baseGlow * pressProgress * highlightScale * 255f)
                    .roundToInt().coerceIn(0, 255)
                val glowColor = (glowAlpha shl 24) or 0xFFFFFF
                val shader = effectCache.getOrCreateInteractiveShader(
                    width = w,
                    height = h,
                    radiusPx = min(w, h) * 0.95f,
                    centerX = touchX.coerceIn(0f, w),
                    centerY = touchY.coerceIn(0f, h),
                    colorArgb = glowColor
                )
                if (shader != null) {
                    glowPaint.shader = shader
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        glowPaint.blendMode = android.graphics.BlendMode.PLUS
                    }
                    canvas.drawRect(0f, 0f, w, h, glowPaint)
                    glowPaint.shader = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        glowPaint.blendMode = null
                    }
                }
            }
        }

        private fun drawGlassIndicatorPill(canvas: Canvas, r: Float) {
            val pw = pillRect.width().roundToInt().coerceAtLeast(1)
            val ph = pillRect.height().roundToInt().coerceAtLeast(1)
            val dark = isNightTheme
            val cfg = config

            // 1. Subtle micro-elevation drop shadow underneath the floating tab capsule
            val shadowAlpha = ((if (dark) 0.28f else 0.07f) * (0.7f + 0.3f * pressProgress) * 255f)
                .roundToInt().coerceIn(0, 255)
            if (lastPillShadowDark != dark || lastPillShadowAlpha != shadowAlpha) {
                lastPillShadowDark = dark
                lastPillShadowAlpha = shadowAlpha
                indicatorShadowPaint.color = shadowAlpha shl 24
                indicatorShadowPaint.maskFilter = BlurMaskFilter(dpToPxFloat(4.5f), BlurMaskFilter.Blur.NORMAL)
            }
            shadowRect.set(
                pillRect.left,
                pillRect.top + dpToPxFloat(1.5f),
                pillRect.right,
                pillRect.bottom + dpToPxFloat(1.5f)
            )
            canvas.drawRoundRect(shadowRect, r, r, indicatorShadowPaint)

            // 2. Refractive Liquid Glass lens droplet over the bottom bar itself (backdrop + tab content)
            val delegate = indicatorBackdropRender
            if (delegate != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                canvas.isHardwareAccelerated && pw > 4 && ph > 4
            ) {
                // Zero extra blur inside the droplet keeps icons & labels crisp while preserving vibrancy
                val preEffect = effectCache.getOrCreatePreFilterEffect(
                    (cfg.vibrancy + 0.08f).coerceAtMost(1.85f),
                    0f
                )
                val lensParams = BackdropEffectParams(
                    width = pw.toFloat(),
                    height = ph.toFloat(),
                    paddingPx = 0,
                    cornerRadius = r,
                    blurRadiusPx = 0f,
                    refractionHeightPx = min(ph * 0.36f, dpToPxFloat(10f)),
                    refractionAmountPx = dpToPxFloat(8f + 11f * pressProgress),
                    vibrancy = cfg.vibrancy,
                    chromaticAberration = cfg.chromaticAberration,
                    depthEffect = true
                )
                val lensEffect = effectCache.getOrCreateLensEffect(lensParams)

                pillClipPath.reset()
                pillClipPath.addRoundRect(pillRect, r, r, Path.Direction.CW)
                val saveClip = canvas.save()
                canvas.clipPath(pillClipPath)
                canvas.translate(pillRect.left, pillRect.top)
                delegate.drawBackdropNode(canvas, pw, ph, 0, preEffect, lensEffect) { recCanvas ->
                    recCanvas.drawColor(detectedBaseBgColor)
                    val saveRec = recCanvas.save()
                    recCanvas.translate(-pillRect.left, -pillRect.top)
                    if (backdropView.hasCachedComposite()) {
                        backdropView.drawCachedComposite(recCanvas)
                    } else {
                        backdropView.draw(recCanvas)
                    }
                    val bar = targetBarView
                    if (bar != null && !isRecordingBackdrop && bar.visibility == VISIBLE && bar.width > 0 && bar.height > 0) {
                        isRecordingBackdrop = true
                        try {
                            val barSave = recCanvas.save()
                            recCanvas.translate(bar.x, bar.y)
                            bar.draw(recCanvas)
                            recCanvas.restoreToCount(barSave)
                        } finally {
                            isRecordingBackdrop = false
                        }
                    }
                    recCanvas.restoreToCount(saveRec)
                }
                canvas.restoreToCount(saveClip)
            }

            // 3. Glass dome tint + curved top-to-bottom specular sheen
            indicatorPaint.color = GlassConfig.computeIndicatorTintColor(dark, pressProgress)
            canvas.drawRoundRect(pillRect, r, r, indicatorPaint)

            if (lastSheenTop != pillRect.top || lastSheenBottom != pillRect.bottom ||
                lastSheenDark != dark || indicatorSheenPaint.shader == null
            ) {
                lastSheenTop = pillRect.top
                lastSheenBottom = pillRect.bottom
                lastSheenDark = dark
                val topGloss = if (dark) 0x26FFFFFF else 0x54FFFFFF
                val midClear = if (dark) 0x04FFFFFF else 0x0CFFFFFF
                val botBounce = if (dark) 0x12FFFFFF else 0x20FFFFFF
                indicatorSheenPaint.shader = LinearGradient(
                    0f, pillRect.top, 0f, pillRect.bottom,
                    intArrayOf(topGloss, midClear, botBounce),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(pillRect, r, r, indicatorSheenPaint)

            // 4. Crisp 45-degree specular rim highlight on the sliding capsule
            val rimShader = effectCache.getOrCreateHighlightShader(
                width = pw.toFloat(),
                height = ph.toFloat(),
                cornerRadius = r,
                colorArgb = Color.WHITE,
                angleDeg = 45f,
                falloff = 1.35f
            )
            if (rimShader != null && delegate != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                canvas.isHardwareAccelerated
            ) {
                localRect.set(0f, 0f, pw.toFloat(), ph.toFloat())
                localClipPath.reset()
                localClipPath.addRoundRect(localRect, r, r, Path.Direction.CW)
                val rimSave = canvas.save()
                canvas.translate(pillRect.left, pillRect.top)
                delegate.drawHighlightNode(
                    canvas = canvas,
                    width = pw,
                    height = ph,
                    clipPath = localClipPath,
                    pillRect = localRect,
                    cornerRadius = r,
                    strokeWidth = ceil(max(1.2f, dpToPxFloat(0.85f))) * 2f,
                    blurRadius = dpToPxFloat(0.35f),
                    alpha = (if (dark) 0.36f else 0.56f) + 0.16f * pressProgress,
                    shader = rimShader
                )
                canvas.restoreToCount(rimSave)
            } else {
                val restingRim = if (dark) 0.18f else 0.34f
                val activeRim = if (dark) 0.34f else 0.50f
                val rimAlpha = ((restingRim + (activeRim - restingRim) * pressProgress) * 255f)
                    .roundToInt().coerceIn(0, 255)
                indicatorRimPaint.strokeWidth = max(1f, dpToPxFloat(0.85f))
                indicatorRimPaint.color = (rimAlpha shl 24) or 0xFFFFFF
                canvas.drawRoundRect(pillRect, r, r, indicatorRimPaint)
            }
        }
    }

    private interface DropShadowDrawer {
        fun draw(
            canvas: Canvas,
            w: Float,
            h: Float,
            cornerRadius: Float,
            shadowRadius: Float,
            shadowDy: Float,
            color: Int,
            shadowPaint: Paint,
            clearPaint: Paint,
            tmpRect: RectF
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class DropShadowApi29 : DropShadowDrawer {
        private val node = RenderNode("LiquidGlassShadow")
        private var lastKey = 0

        override fun draw(
            canvas: Canvas,
            w: Float,
            h: Float,
            cornerRadius: Float,
            shadowRadius: Float,
            shadowDy: Float,
            color: Int,
            shadowPaint: Paint,
            clearPaint: Paint,
            tmpRect: RectF
        ) {
            if (!canvas.isHardwareAccelerated) return
            val pad = ceil(shadowRadius * 2f).toInt()
            val iw = w.roundToInt()
            val ih = h.roundToInt()
            val key = ((iw * 31 + ih) * 31 + color) * 31 + shadowRadius.roundToInt()
            if (key != lastKey || !node.hasDisplayList()) {
                lastKey = key
                node.setUseCompositingLayer(true, null)
                shadowPaint.color = color
                shadowPaint.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
                clearPaint.blendMode = android.graphics.BlendMode.CLEAR
                node.setPosition(-pad, -pad, iw + pad, ih + pad)
                val rec = node.beginRecording(iw + pad * 2, ih + pad * 2)
                try {
                    rec.translate(pad.toFloat(), pad.toFloat())
                    tmpRect.set(0f, shadowDy, w, h + shadowDy)
                    rec.drawRoundRect(tmpRect, cornerRadius, cornerRadius, shadowPaint)
                    tmpRect.set(0f, 0f, w, h)
                    rec.drawRoundRect(tmpRect, cornerRadius, cornerRadius, clearPaint)
                } finally {
                    node.endRecording()
                }
            }
            canvas.drawRenderNode(node)
        }
    }

    private interface BackdropRenderDelegate {
        fun drawBackdropNode(
            canvas: Canvas,
            width: Int,
            height: Int,
            padPx: Int,
            preFilterEffect: Any?,
            lensEffect: Any?,
            recorder: (RecordingCanvas) -> Unit
        )

        fun drawHighlightNode(
            canvas: Canvas,
            width: Int,
            height: Int,
            clipPath: Path,
            pillRect: RectF,
            cornerRadius: Float,
            strokeWidth: Float,
            blurRadius: Float,
            alpha: Float,
            shader: Shader
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class BackdropRenderApi29 : BackdropRenderDelegate {
        private val preFilterNode = RenderNode("LiquidGlassPreFilter")
        private val backdropNode = RenderNode("LiquidGlassBackdrop")
        private val highlightNode = RenderNode("LiquidGlassHighlight")
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val plusCompositePaint = Paint().apply {
            blendMode = android.graphics.BlendMode.PLUS
        }
        private var highlightKey = 0

        override fun drawBackdropNode(
            canvas: Canvas,
            width: Int,
            height: Int,
            padPx: Int,
            preFilterEffect: Any?,
            lensEffect: Any?,
            recorder: (RecordingCanvas) -> Unit
        ) {
            if (!canvas.isHardwareAccelerated) return
            val nodeW = width + padPx * 2
            val nodeH = height + padPx * 2
            preFilterNode.setPosition(-padPx, -padPx, width + padPx, height + padPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BackdropEffectApplierApi31.apply(preFilterNode, preFilterEffect as? RenderEffect)
            }
            val preCanvas = preFilterNode.beginRecording(nodeW, nodeH)
            try {
                recorder(preCanvas)
            } finally {
                preFilterNode.endRecording()
            }

            backdropNode.setPosition(0, 0, width, height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BackdropEffectApplierApi31.apply(backdropNode, lensEffect as? RenderEffect)
            }
            val recCanvas = backdropNode.beginRecording(width, height)
            try {
                recCanvas.drawRenderNode(preFilterNode)
            } finally {
                backdropNode.endRecording()
            }
            canvas.drawRenderNode(backdropNode)
        }

        override fun drawHighlightNode(
            canvas: Canvas,
            width: Int,
            height: Int,
            clipPath: Path,
            pillRect: RectF,
            cornerRadius: Float,
            strokeWidth: Float,
            blurRadius: Float,
            alpha: Float,
            shader: Shader
        ) {
            if (!canvas.isHardwareAccelerated) return
            highlightNode.setUseCompositingLayer(true, plusCompositePaint)
            highlightNode.alpha = alpha.coerceIn(0f, 1f)

            val newKey = ((width * 31 + height) * 31 + cornerRadius.roundToInt()) * 31 + strokeWidth.roundToInt()
            if (newKey != highlightKey || !highlightNode.hasDisplayList()) {
                highlightKey = newKey
                highlightPaint.strokeWidth = strokeWidth
                highlightPaint.maskFilter = if (blurRadius > 0.2f) {
                    BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                } else {
                    null
                }
            }
            highlightPaint.shader = shader
            highlightNode.setPosition(0, 0, width, height)
            val rec = highlightNode.beginRecording(width, height)
            try {
                rec.clipPath(clipPath)
                rec.drawRoundRect(pillRect, cornerRadius, cornerRadius, highlightPaint)
            } finally {
                highlightNode.endRecording()
                highlightPaint.shader = null
            }
            canvas.drawRenderNode(highlightNode)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object BackdropEffectApplierApi31 {
        fun apply(node: RenderNode, effect: RenderEffect?) {
            node.setRenderEffect(effect)
        }
    }
}

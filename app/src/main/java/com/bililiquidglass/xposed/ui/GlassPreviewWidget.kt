package io.github.tomorisuki2333.bililiquidglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tomorisuki2333.bililiquidglass.config.GlassConfig
import io.github.tomorisuki2333.bililiquidglass.glass.LiquidGlassPillLayout
import kotlin.math.roundToInt

/**
 * Interactive preview demonstrating the live RenderNode + AGSL Liquid Glass floating pill
 * refracting animated video feed cards underneath.
 */
class GlassPreviewWidget(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val feedView = PreviewFeedCanvasView(context)
    private val pillLayout = LiquidGlassPillLayout(context)
    private val tabViews = ArrayList<LinearLayout>()
    private val tabLabels = listOf("首页", "动态", "+", "会员购", "我的")
    private val tabIcons = listOf("⌂", "◎", "+", "◈", "☺")
    private var selectedIndex = 0
    private var previewDark = false

    private val ticker = object : Runnable {
        override fun run() {
            if (isAttachedToWindow && !isDraggingFeed) {
                feedView.advanceScroll(0.55f * density)
                pillLayout.invalidateGlassBackdrop()
                postOnAnimation(this)
            }
        }
    }
    private var isDraggingFeed = false
    private var lastTouchY = 0f

    init {
        clipChildren = false
        clipToPadding = false
        val cornerRadius = 20f * density
        val containerPath = Path()
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true

        addView(feedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        tabLabels.forEachIndexed { index, title ->
            val slot = buildPreviewTabSlot(index, title, tabIcons[index])
            tabViews.add(slot)
            tabsRow.addView(slot, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        pillLayout.backdropSourceView = feedView
        pillLayout.attachTargetBar(tabsRow)
        pillLayout.onTabDragSelectedListener = { slotIndex ->
            if (slotIndex != 2 && slotIndex in tabViews.indices) {
                selectedIndex = slotIndex
                refreshTabColors()
            }
            pillLayout.updateSelection(selectedIndex, tabLabels.size)
        }
        pillLayout.updateSelection(selectedIndex, tabLabels.size)
        addView(pillLayout, buildPillLayoutParams(GlassConfig()))

        applyConfig(GlassConfig(), previewDark)
        // Avoid unused local warning if needed cleanly
        containerPath.reset()
    }

    fun applyConfig(config: GlassConfig, darkPreview: Boolean) {
        previewDark = darkPreview
        feedView.isDark = darkPreview
        pillLayout.config = config
        pillLayout.isNightTheme = darkPreview
        pillLayout.detectedBaseBgColor = if (darkPreview) 0xFF17181A.toInt() else 0xFFF4F6FA.toInt()
        pillLayout.layoutParams = buildPillLayoutParams(config)
        pillLayout.visibility = if (config.enabled) VISIBLE else GONE
        refreshTabColors()
        pillLayout.invalidateGlassBackdrop()
    }

    private fun buildPillLayoutParams(cfg: GlassConfig): LayoutParams {
        val barH = (cfg.barHeightDp * density).roundToInt()
        val hMargin = (cfg.horizontalMarginDp * density).roundToInt()
        val bMargin = (cfg.bottomMarginDp * density).roundToInt()
        return LayoutParams(LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM).apply {
            leftMargin = hMargin
            rightMargin = hMargin
            bottomMargin = bMargin
        }
    }

    private fun buildPreviewTabSlot(index: Int, title: String, symbol: String): LinearLayout {
        val isPublish = index == 2
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }

        if (isPublish) {
            val plusBtn = TextView(context).apply {
                text = "+"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (previewDark) 0xFFFF80A6.toInt() else 0xFFFB7299.toInt())
                background = buildPreviewPublishGlassBg(previewDark)
            }
            container.addView(
                plusBtn,
                LinearLayout.LayoutParams((36f * density).roundToInt(), (28f * density).roundToInt())
            )
        } else {
            val icon = TextView(context).apply {
                text = symbol
                textSize = 15f
                gravity = Gravity.CENTER
                tag = "icon"
            }
            val label = TextView(context).apply {
                text = title
                textSize = 10f
                gravity = Gravity.CENTER
                tag = "label"
            }
            container.addView(icon)
            container.addView(label)
            container.setOnClickListener {
                selectedIndex = index
                pillLayout.updateSelection(selectedIndex, tabLabels.size)
                refreshTabColors()
            }
        }
        return container
    }

    private fun buildPreviewPublishGlassBg(isDark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(
                if (isDark) {
                    intArrayOf(0x6E424856, 0x3D1F242D)
                } else {
                    intArrayOf(0xAEFFFFFF.toInt(), 0x5EEEF2F8)
                }
            )
            setStroke(
                (1.1f * density).roundToInt().coerceAtLeast(1),
                if (isDark) 0x52FFFFFF else 0xDAFFFFFF.toInt()
            )
        }
    }

    private fun refreshTabColors() {
        val activeColor = 0xFFFB7299.toInt()
        val inactiveColor = if (previewDark) 0xFFE5E7EB.toInt() else 0xFF33363B.toInt()
        tabViews.forEachIndexed { idx, slot ->
            if (idx == 2) {
                val plusBtn = slot.getChildAt(0) as? TextView
                if (plusBtn != null) {
                    plusBtn.setTextColor(if (previewDark) 0xFFFF80A6.toInt() else 0xFFFB7299.toInt())
                    plusBtn.background = buildPreviewPublishGlassBg(previewDark)
                }
                return@forEachIndexed
            }
            val color = if (idx == selectedIndex) activeColor else inactiveColor
            for (c in 0 until slot.childCount) {
                val tv = slot.getChildAt(c) as? TextView ?: continue
                tv.setTextColor(color)
                tv.typeface = if (idx == selectedIndex) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDraggingFeed = true
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastTouchY - event.y
                lastTouchY = event.y
                feedView.advanceScroll(dy)
                pillLayout.invalidateGlassBackdrop()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingFeed = false
                removeCallbacks(ticker)
                postOnAnimation(ticker)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private class PreviewFeedCanvasView(context: Context) : View(context) {
        var isDark = false
            set(value) {
                field = value
                invalidate()
            }

        private val d = resources.displayMetrics.density
        private var scrollYOffset = 0f
        private val cardRect = RectF()
        private val thumbRect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * d
            typeface = Typeface.DEFAULT_BOLD
        }
        private val gradients = arrayOf(
            intArrayOf(0xFFFF6B9D.toInt(), 0xFFFF9E7D.toInt()),
            intArrayOf(0xFF38BDF8.toInt(), 0xFF6366F1.toInt()),
            intArrayOf(0xFF34D399.toInt(), 0xFF06B6D4.toInt()),
            intArrayOf(0xFFF59E0B.toInt(), 0xFFEF4444.toInt()),
            intArrayOf(0xFFA855F7.toInt(), 0xFFEC4899.toInt()),
            intArrayOf(0xFFFB7299.toInt(), 0xFF23ADE5.toInt())
        )
        private val sampleTitles = arrayOf(
            "4K HDR 夜景延时摄影：城市流光",
            "Liquid Glass AGSL 着色器原理解析",
            "哔哩哔哩首页双列卡片滑动测试",
            "物理弹簧动画与光学色散效果预览",
            "Material 质感与高刷流畅交互体验",
            "LSPosed API 102 现代化模块架构"
        )

        fun advanceScroll(dy: Float) {
            scrollYOffset += dy
            val cycle = (128f * d) * 6f
            if (cycle > 1f) {
                scrollYOffset %= cycle
                if (scrollYOffset < 0f) scrollYOffset += cycle
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            canvas.drawColor(if (isDark) 0xFF17181A.toInt() else 0xFFF1F3F7.toInt())

            val pad = 10f * d
            val gap = 8f * d
            val cardW = (w - pad * 2f - gap) * 0.5f
            val cardH = 120f * d
            val rowH = cardH + gap
            val totalRows = 6
            val cycleH = totalRows * rowH

            val startRow = (-2)
            val endRow = (h / rowH).toInt() + 2
            val cardBg = if (isDark) 0xFF24262B.toInt() else Color.WHITE
            val titleColor = if (isDark) 0xFFE5E7EB.toInt() else 0xFF18191C.toInt()

            for (r in startRow..endRow) {
                val top = r * rowH - (scrollYOffset % rowH)
                if (top > h || top + cardH < 0f) continue
                val rowIdx = (((scrollYOffset / rowH).toInt() + r) % totalRows + totalRows) % totalRows
                for (col in 0..1) {
                    val left = pad + col * (cardW + gap)
                    val cardIndex = (rowIdx * 2 + col) % gradients.size
                    val pair = gradients[cardIndex]

                    cardRect.set(left, top, left + cardW, top + cardH)
                    paint.shader = null
                    paint.color = cardBg
                    canvas.drawRoundRect(cardRect, 12f * d, 12f * d, paint)

                    thumbRect.set(left, top, left + cardW, top + cardH * 0.68f)
                    paint.shader = LinearGradient(
                        thumbRect.left, thumbRect.top,
                        thumbRect.right, thumbRect.bottom,
                        pair[0], pair[1],
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(thumbRect, 12f * d, 12f * d, paint)
                    paint.shader = null

                    // Cover badge pill to create sharp edge contrast for visible glass refraction
                    paint.color = 0x77000000
                    canvas.drawRoundRect(
                        left + 8f * d,
                        top + 8f * d,
                        left + 52f * d,
                        top + 24f * d,
                        6f * d,
                        6f * d,
                        paint
                    )

                    textPaint.color = titleColor
                    canvas.drawText(
                        sampleTitles[cardIndex].take(11),
                        left + 8f * d,
                        top + cardH * 0.86f,
                        textPaint
                    )
                }
            }
            if (cycleH < 0f) return
        }
    }
}

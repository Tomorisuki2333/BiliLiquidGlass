package io.github.tomorisuki2333.bililiquidglass.ui

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.tomorisuki2333.bililiquidglass.R
import io.github.tomorisuki2333.bililiquidglass.config.GlassConfig
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Module companion settings UI with live Liquid Glass preview and LSPosed API 102 RemotePreferences.
 */
class MainActivity : AppCompatActivity() {

    private val density by lazy { resources.displayMetrics.density }

    private var service: XposedService? = null
    private var remotePrefs: SharedPreferences? = null
    private lateinit var localPrefs: SharedPreferences
    private var config = GlassConfig()
    private var previewDark = false
    private var isUpdatingUi = false

    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var scopeBtn: MaterialButton
    private lateinit var previewWidget: GlassPreviewWidget

    private val sliderBinders = ArrayList<(GlassConfig) -> Unit>()
    private val switchBinders = ArrayList<(GlassConfig) -> Unit>()

    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(boundService: XposedService) {
            runOnUiThread {
                service = boundService
                remotePrefs = try {
                    boundService.getRemotePreferences(GlassConfig.PREF_GROUP)
                } catch (_: Throwable) {
                    null
                }
                remotePrefs?.let { rp ->
                    config = GlassConfig.fromPreferences(rp)
                    GlassConfig.writeTo(localPrefs, config)
                }
                bindUiToConfig()
                updateFrameworkStatusCard()
            }
        }

        override fun onServiceDied(diedService: XposedService) {
            runOnUiThread {
                if (service === diedService) {
                    service = null
                    remotePrefs = null
                    updateFrameworkStatusCard()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        localPrefs = getSharedPreferences(GlassConfig.PREF_GROUP, MODE_PRIVATE)
        config = GlassConfig.fromPreferences(localPrefs)
        previewDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        setContentView(buildContentView())
        bindUiToConfig()
        updateFrameworkStatusCard()

        XposedServiceHelper.registerListener(serviceListener)
    }

    override fun onDestroy() {
        service = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pageBg = if (isNight) 0xFF101216.toInt() else 0xFFF3F5FA.toInt()
        val cardBg = if (isNight) 0xFF1B1E24.toInt() else Color.WHITE
        val textPrimary = if (isNight) 0xFFF3F4F6.toInt() else 0xFF18191C.toInt()
        val textSecondary = if (isNight) 0xFF9CA3AF.toInt() else 0xFF61666D.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
        }

        val rootContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16f)
            setPadding(pad, pad, pad, pad)
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootContent) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16f), sys.top + dp(12f), dp(16f), sys.bottom + dp(20f))
            insets
        }

        // Header
        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "AndroidLiquidGlass · LSPosed API 102 (Android ${Build.VERSION.RELEASE})"
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(2f), 0, 0)
        })
        topHeaderRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topHeaderRow.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.title_open_source_licenses)
                textSize = 12f
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, OpenSourceLicensesActivity::class.java))
                }
            }
        )
        rootContent.addView(
            topHeaderRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        )

        // Status Card
        val statusCard = createCard(cardBg)
        statusTitle = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        statusSubtitle = TextView(this).apply {
            textSize = 12.5f
            setTextColor(textSecondary)
            setPadding(0, dp(4f), 0, dp(8f))
        }
        scopeBtn = MaterialButton(this).apply {
            text = getString(R.string.btn_request_scope)
            visibility = View.GONE
            setOnClickListener { requestBiliScope() }
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusSubtitle)
        statusCard.addView(scopeBtn)
        rootContent.addView(statusCard, cardLp())

        // Live Preview Card
        val previewCard = createCard(cardBg)
        val previewHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewHeader.addView(
            TextView(this).apply {
                text = "实时液态玻璃预览（可拖动卡片 / 点击底栏切换）"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val themeToggle = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (previewDark) "夜间底色" else "日间底色"
            textSize = 11.5f
            setOnClickListener {
                previewDark = !previewDark
                text = if (previewDark) "夜间底色" else "日间底色"
                previewWidget.applyConfig(config, previewDark)
            }
        }
        previewHeader.addView(themeToggle)
        previewCard.addView(previewHeader)

        previewWidget = GlassPreviewWidget(this)
        previewCard.addView(
            previewWidget,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220f)).apply {
                topMargin = dp(8f)
            }
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            previewCard.addView(TextView(this).apply {
                text = "当前设备为 API ${Build.VERSION.SDK_INT}：折射与高光 AGSL Shader 需要 Android 13 (API 33)+，当前已自动启用半透明降级样式"
                textSize = 11.5f
                setTextColor(0xFFD97706.toInt())
                setPadding(0, dp(8f), 0, 0)
            })
        }
        rootContent.addView(previewCard, cardLp())

        // Geometry & Switches Card
        val switchesCard = createCard(cardBg)
        addSwitch(switchesCard, "启用首页悬浮液态玻璃底栏", textPrimary, { it.enabled }) { v ->
            updateConfig { copy(enabled = v) }
        }
        addSwitch(switchesCard, "启用色散效应 (Chromatic Aberration)", textPrimary, { it.chromaticAberration }) { v ->
            updateConfig { copy(chromaticAberration = v) }
        }
        addSwitch(switchesCard, "启用景深边缘弧度 (Depth Effect)", textPrimary, { it.depthEffect }) { v ->
            updateConfig { copy(depthEffect = v) }
        }
        addSwitch(switchesCard, "选中项滑动玻璃胶囊 (Active Tab Pill)", textPrimary, { it.activePillEnabled }) { v ->
            updateConfig { copy(activePillEnabled = v) }
        }
        addSwitch(switchesCard, "触控交互径向高光", textPrimary, { it.interactiveHighlightEnabled }) { v ->
            updateConfig { copy(interactiveHighlightEnabled = v) }
        }
        addSwitch(switchesCard, "自动为信息流增加底部安全留白", textPrimary, { it.adjustFeedPadding }) { v ->
            updateConfig { copy(adjustFeedPadding = v) }
        }
        rootContent.addView(switchesCard, cardLp())

        // Optical Sliders Card
        val opticalCard = createCard(cardBg)
        opticalCard.addView(sectionHeader("光学与形变参数", textPrimary))
        addSlider(opticalCard, "高斯模糊半径 (dp)", 0f, 24f, 0.5f, textPrimary, textSecondary,
            { String.format(Locale.US, "%.1f dp", it) }, { it.blurRadiusDp }) { v ->
            updateConfig { copy(blurRadiusDp = v) }
        }
        addSlider(opticalCard, "边缘折射高度 (dp)", 0f, 32f, 1f, textPrimary, textSecondary,
            { String.format(Locale.US, "%.0f dp", it) }, { it.refractionHeightDp }) { v ->
            updateConfig { copy(refractionHeightDp = v) }
        }
        addSlider(opticalCard, "边缘折射强度 (dp)", 0f, 40f, 1f, textPrimary, textSecondary,
            { String.format(Locale.US, "%.0f dp", it) }, { it.refractionAmountDp }) { v ->
            updateConfig { copy(refractionAmountDp = v) }
        }
        addSlider(opticalCard, "背景色彩鲜艳度 (Vibrancy)", 0.8f, 2.0f, 0.05f, textPrimary, textSecondary,
            { String.format(Locale.US, "%.2fx", it) }, { it.vibrancy }) { v ->
            updateConfig { copy(vibrancy = v) }
        }
        addSlider(opticalCard, "玻璃表面色调不透明度", 0.05f, 0.85f, 0.02f, textPrimary, textSecondary,
            { "${(it * 100f).roundToInt()}%" }, { it.tintOpacity }) { v ->
            updateConfig { copy(tintOpacity = v) }
        }
        addSlider(opticalCard, "45° 玻璃边缘高光强度", 0f, 1f, 0.05f, textPrimary, textSecondary,
            { "${(it * 100f).roundToInt()}%" }, { it.highlightIntensity }) { v ->
            updateConfig { copy(highlightIntensity = v) }
        }
        addSlider(opticalCard, "底部悬浮投影浓度", 0f, 0.4f, 0.02f, textPrimary, textSecondary,
            { "${(it * 100f).roundToInt()}%" }, { it.shadowAlpha }) { v ->
            updateConfig { copy(shadowAlpha = v) }
        }
        rootContent.addView(opticalCard, cardLp())

        // Geometry Sliders Card
        val layoutCard = createCard(cardBg)
        layoutCard.addView(sectionHeader("悬浮几何尺寸", textPrimary))
        addSlider(layoutCard, "悬浮底栏高度 (dp)", 48f, 76f, 1f, textPrimary, textSecondary,
            { String.format(Locale.US, "%.0f dp", it) }, { it.barHeightDp }) { v ->
            updateConfig { copy(barHeightDp = v) }
        }
        addSlider(layoutCard, "左右悬浮边距 (dp)", 0f, 40f, 1f, textPrimary, textSecondary,
            { String.format(Locale.US, "%.0f dp", it) }, { it.horizontalMarginDp }) { v ->
            updateConfig { copy(horizontalMarginDp = v) }
        }
        addSlider(layoutCard, "底部悬浮距边 (dp)", 0f, 40f, 1f, textPrimary, textSecondary,
            { String.format(Locale.US, "%.0f dp", it) }, { it.bottomMarginDp }) { v ->
            updateConfig { copy(bottomMarginDp = v) }
        }
        addSlider(layoutCard, "圆角比率 (1.0 = 全胶囊)", 0.2f, 1.0f, 0.05f, textPrimary, textSecondary,
            { "${(it * 100f).roundToInt()}%" }, { it.cornerRadiusFraction }) { v ->
            updateConfig { copy(cornerRadiusFraction = v) }
        }
        rootContent.addView(layoutCard, cardLp())

        // Footer actions
        rootContent.addView(MaterialButton(this).apply {
            text = getString(R.string.btn_reset_defaults)
            setOnClickListener {
                config = GlassConfig()
                saveConfig()
                bindUiToConfig()
                Toast.makeText(this@MainActivity, "已恢复默认液态玻璃参数", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        rootContent.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.btn_open_source_licenses)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, OpenSourceLicensesActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            }
        )

        rootContent.addView(TextView(this).apply {
            text = getString(R.string.tip_restart)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
            setPadding(0, dp(8f), 0, dp(12f))
        })

        val scroll = ScrollView(this)
        scroll.addView(rootContent)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun createCard(bgColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14f)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(bgColor)
            }
        }
    }

    private fun cardLp(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12f)
        }
    }

    private fun sectionHeader(title: String, color: Int): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            setPadding(0, 0, 0, dp(8f))
        }
    }

    private fun addSwitch(
        parent: LinearLayout,
        label: String,
        textColor: Int,
        getter: (GlassConfig) -> Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        val sw = MaterialSwitch(this).apply {
            text = label
            textSize = 14f
            setTextColor(textColor)
            setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUi) onToggle(isChecked)
            }
        }
        switchBinders.add { cfg -> sw.isChecked = getter(cfg) }
        parent.addView(
            sw,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4f)
            }
        )
    }

    private fun addSlider(
        parent: LinearLayout,
        title: String,
        from: Float,
        to: Float,
        step: Float,
        titleColor: Int,
        valueColor: Int,
        formatter: (Float) -> String,
        getter: (GlassConfig) -> Float,
        onChange: (Float) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(titleColor)
        }
        val valueView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(valueColor)
            typeface = Typeface.MONOSPACE
        }
        row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueView)

        val slider = Slider(this).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            addOnChangeListener { _, value, fromUser ->
                valueView.text = formatter(value)
                if (fromUser && !isUpdatingUi) {
                    onChange(value)
                }
            }
        }
        sliderBinders.add { cfg ->
            val v = getter(cfg).coerceIn(from, to)
            val snapped = (from + ((v - from) / step).roundToInt() * step).coerceIn(from, to)
            slider.value = snapped
            valueView.text = formatter(snapped)
        }
        parent.addView(row)
        parent.addView(slider)
    }

    private fun updateConfig(transform: GlassConfig.() -> GlassConfig) {
        config = config.transform().clamped()
        saveConfig()
        previewWidget.applyConfig(config, previewDark)
    }

    private fun saveConfig() {
        GlassConfig.writeTo(localPrefs, config)
        remotePrefs?.let { rp ->
            try {
                GlassConfig.writeTo(rp, config)
            } catch (_: Throwable) {
            }
        }
    }

    private fun bindUiToConfig() {
        isUpdatingUi = true
        try {
            switchBinders.forEach { it(config) }
            sliderBinders.forEach { it(config) }
            previewWidget.applyConfig(config, previewDark)
        } finally {
            isUpdatingUi = false
        }
    }

    private fun updateFrameworkStatusCard() {
        val svc = service
        if (svc == null) {
            statusTitle.text = "LSPosed 服务未激活"
            statusTitle.setTextColor(0xFFDC2626.toInt())
            statusSubtitle.text = getString(R.string.status_disconnected)
            scopeBtn.visibility = View.GONE
            return
        }

        statusTitle.setTextColor(0xFF16A34A.toInt())
        statusTitle.text = getString(
            R.string.status_connected,
            svc.apiVersion,
            svc.frameworkName,
            svc.frameworkVersion
        )
        val granted = try {
            svc.scope.filter { it in KNOWN_BILI_PKGS }
        } catch (_: Throwable) {
            emptyList()
        }
        if (granted.isEmpty()) {
            statusSubtitle.text = getString(R.string.scope_missing)
            scopeBtn.visibility = View.VISIBLE
        } else {
            statusSubtitle.text = getString(R.string.scope_granted, granted.joinToString(", "))
            scopeBtn.visibility = View.GONE
        }
    }

    private fun requestBiliScope() {
        val svc = service ?: return
        try {
            svc.requestScope(KNOWN_BILI_PKGS, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: MutableList<String>) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "作用域授权成功", Toast.LENGTH_SHORT).show()
                        updateFrameworkStatusCard()
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "请在 LSPosed 管理器中勾选哔哩哔哩：$message", Toast.LENGTH_LONG).show()
                    }
                }
            })
        } catch (t: Throwable) {
            Toast.makeText(this, "请手动在 LSPosed 管理器中勾选哔哩哔哩", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    companion object {
        private val KNOWN_BILI_PKGS = listOf(
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "com.bilibili.app.blue",
            "com.bilibili.app.hd"
        )
    }
}

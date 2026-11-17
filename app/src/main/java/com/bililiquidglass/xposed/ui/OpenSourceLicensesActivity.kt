package com.bililiquidglass.xposed.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bililiquidglass.xposed.BuildConfig
import com.bililiquidglass.xposed.R
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/**
 * Displays open-source libraries, attributions, and license notices referenced by BiliLiquidGlass.
 */
class OpenSourceLicensesActivity : AppCompatActivity() {

    private val density by lazy { resources.displayMetrics.density }

    data class OpenSourceLibrary(
        val name: String,
        val artifactOrVersion: String,
        val author: String,
        val license: String,
        val url: String,
        val description: String,
        val licenseText: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildContentView())
    }

    private fun buildContentView(): View {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pageBg = if (isNight) 0xFF101216.toInt() else 0xFFF3F5FA.toInt()
        val cardBg = if (isNight) 0xFF1B1E24.toInt() else Color.WHITE
        val textPrimary = if (isNight) 0xFFF3F4F6.toInt() else 0xFF18191C.toInt()
        val textSecondary = if (isNight) 0xFF9CA3AF.toInt() else 0xFF61666D.toInt()
        val accentPink = if (isNight) 0xFFFF80A6.toInt() else 0xFFFB7299.toInt()
        val badgeBg = if (isNight) 0x33FB7299 else 0x1FFB7299

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
            v.setPadding(dp(16f), sys.top + dp(12f), dp(16f), sys.bottom + dp(24f))
            insets
        }

        // Top Bar
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        val backBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "← 返回"
            textSize = 12.5f
            setOnClickListener { finish() }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, 0, 0)
        }
        titleCol.addView(TextView(this).apply {
            text = getString(R.string.title_open_source_licenses)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            textSize = 12.5f
            setTextColor(textSecondary)
        })
        headerRow.addView(backBtn)
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rootContent.addView(headerRow)

        rootContent.addView(TextView(this).apply {
            text = "本模块基于以下优秀开源项目与组件构建，特此向原作者及开源社区致谢。点击卡片可查看许可证详情或访问项目主页。"
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(4f), 0, dp(12f))
        })

        for (lib in LIBRARIES) {
            rootContent.addView(
                buildLibraryCard(lib, cardBg, textPrimary, textSecondary, accentPink, badgeBg),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12f)
                }
            )
        }

        val scroll = ScrollView(this)
        scroll.addView(rootContent)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    private fun buildLibraryCard(
        lib: OpenSourceLibrary,
        cardBg: Int,
        textPrimary: Int,
        textSecondary: Int,
        accentPink: Int,
        badgeBg: Int
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14f)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(cardBg)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showLicenseDialog(lib) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            TextView(this).apply {
                text = lib.name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(TextView(this).apply {
            text = lib.license
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentPink)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(badgeBg)
            }
        })
        card.addView(topRow)

        card.addView(TextView(this).apply {
            text = "${lib.author} · ${lib.artifactOrVersion}"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(textSecondary)
            setPadding(0, dp(3f), 0, dp(6f))
        })

        card.addView(TextView(this).apply {
            text = lib.description
            textSize = 13f
            setTextColor(textPrimary)
            setPadding(0, 0, 0, dp(8f))
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(
            TextView(this).apply {
                text = lib.url
                textSize = 11.5f
                setTextColor(accentPink)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val openWebBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "访问仓库"
            textSize = 11.5f
            setOnClickListener { openUrl(lib.url) }
        }
        actionRow.addView(openWebBtn)
        card.addView(actionRow)

        return card
    }

    private fun showLicenseDialog(lib: OpenSourceLibrary) {
        AlertDialog.Builder(this)
            .setTitle("${lib.name} (${lib.license})")
            .setMessage("${lib.author}\n${lib.url}\n\n${lib.licenseText}")
            .setPositiveButton("访问仓库") { _, _ -> openUrl(lib.url) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    companion object {
        private const val APACHE_2_NOTICE =
            "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                "you may not use this file except in compliance with the License.\n" +
                "You may obtain a copy of the License at\n\n" +
                "    http://www.apache.org/licenses/LICENSE-2.0\n\n" +
                "Unless required by applicable law or agreed to in writing, software " +
                "distributed under the License is distributed on an \"AS IS\" BASIS, " +
                "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied."

        private const val GPL_3_NOTICE =
            "This program is free software: you can redistribute it and/or modify " +
                "it under the terms of the GNU General Public License as published by " +
                "the Free Software Foundation, either version 3 of the License, or " +
                "(at your option) any later version.\n\n" +
                "    https://www.gnu.org/licenses/gpl-3.0.html"

        val LIBRARIES = listOf(
            OpenSourceLibrary(
                name = "AndroidLiquidGlass",
                artifactOrVersion = "Kyant0/AndroidLiquidGlass",
                author = "Kyant0",
                license = "Apache-2.0",
                url = "https://github.com/Kyant0/AndroidLiquidGlass",
                description = "提供本模块核心液态玻璃光学着色器与几何算法设计参考（圆角矩形 SDF、弯月面边缘透镜折射与色散 AGSL RuntimeShader、45° 菲涅尔高光、触控交互径向光效及色彩鲜艳度矩阵）。",
                licenseText = "Copyright 2025 Kyant0\n\n$APACHE_2_NOTICE"
            ),
            OpenSourceLibrary(
                name = "libxposed API & Service",
                artifactOrVersion = "io.github.libxposed:api:102.0.0 / service:102.0.0",
                author = "libxposed (LSPosed Team)",
                license = "Apache-2.0",
                url = "https://github.com/libxposed/api",
                description = "提供现代 Xposed API 102 标准 Hook 接口（XposedModule / XposedInterface）与跨进程 RemotePreferences 实时配置同步服务。",
                licenseText = "Copyright 2023-2026 libxposed Contributors\n\n$APACHE_2_NOTICE"
            ),
            OpenSourceLibrary(
                name = "LSPosed Framework",
                artifactOrVersion = "LSPosed API 102 Runtime",
                author = "LSPosed Developers",
                license = "GPL-3.0",
                url = "https://github.com/LSPosed/LSPosed",
                description = "Android ART 运行时 Hook 框架与模块作用域管理支持。",
                licenseText = "Copyright LSPosed Developers\n\n$GPL_3_NOTICE"
            ),
            OpenSourceLibrary(
                name = "AndroidX Core KTX & AppCompat",
                artifactOrVersion = "androidx.core:core-ktx:1.15.0 · appcompat:1.7.0",
                author = "The Android Open Source Project",
                license = "Apache-2.0",
                url = "https://developer.android.com/jetpack/androidx",
                description = "提供 Android 边到边沉浸式窗口（WindowInsetsCompat）、基础 Activity 兼容及 Kotlin 扩展支持。",
                licenseText = "Copyright The Android Open Source Project\n\n$APACHE_2_NOTICE"
            ),
            OpenSourceLibrary(
                name = "Material Components for Android",
                artifactOrVersion = "com.google.android.material:material:1.12.0",
                author = "Google LLC",
                license = "Apache-2.0",
                url = "https://github.com/material-components/material-components-android",
                description = "提供模块设置界面中的 Material 3 开关（MaterialSwitch）、参数滑块（Slider）与按钮（MaterialButton）组件。",
                licenseText = "Copyright Google LLC\n\n$APACHE_2_NOTICE"
            ),
            OpenSourceLibrary(
                name = "Kotlin Standard Library",
                artifactOrVersion = "org.jetbrains.kotlin:kotlin-stdlib:2.1.10",
                author = "JetBrains s.r.o.",
                license = "Apache-2.0",
                url = "https://github.com/JetBrains/kotlin",
                description = "模块开发语言与标准库支持。",
                licenseText = "Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors\n\n$APACHE_2_NOTICE"
            )
        )
    }
}

package io.github.tomorisuki2333.bililiquidglass

import io.github.tomorisuki2333.bililiquidglass.config.GlassConfig
import io.github.tomorisuki2333.bililiquidglass.glass.LiquidGlassGeometry
import io.github.tomorisuki2333.bililiquidglass.glass.LiquidGlassShaders
import io.github.tomorisuki2333.bililiquidglass.hook.ComposeColorSuppressor
import io.github.tomorisuki2333.bililiquidglass.hook.LegacyChromeRole
import io.github.tomorisuki2333.bililiquidglass.hook.LegacyTabChromePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassConfigAndGeometryTest {

    @Test
    fun configClampingRespectsSafeBounds() {
        val extreme = GlassConfig(
            barHeightDp = 12f,
            horizontalMarginDp = -10f,
            bottomMarginDp = 99f,
            cornerRadiusFraction = 2.5f,
            blurRadiusDp = -5f,
            refractionHeightDp = 80f,
            refractionAmountDp = -2f,
            vibrancy = 5f,
            highlightIntensity = -1f,
            tintOpacity = 1f,
            shadowAlpha = 0.9f
        ).clamped()

        assertEquals(48f, extreme.barHeightDp, 0.001f)
        assertEquals(0f, extreme.horizontalMarginDp, 0.001f)
        assertEquals(40f, extreme.bottomMarginDp, 0.001f)
        assertEquals(1.0f, extreme.cornerRadiusFraction, 0.001f)
        assertEquals(0f, extreme.blurRadiusDp, 0.001f)
        assertEquals(32f, extreme.refractionHeightDp, 0.001f)
        assertEquals(0f, extreme.refractionAmountDp, 0.001f)
        assertEquals(2.0f, extreme.vibrancy, 0.001f)
        assertEquals(0f, extreme.highlightIntensity, 0.001f)
        assertEquals(0.85f, extreme.tintOpacity, 0.001f)
        assertEquals(0.4f, extreme.shadowAlpha, 0.001f)
    }

    @Test
    fun capsuleGeometryAndPaddingCalculationsMatchExpectations() {
        assertEquals(29f, LiquidGlassGeometry.clampCornerRadius(320f, 58f, 1.0f), 0.001f)
        assertEquals(14.5f, LiquidGlassGeometry.clampCornerRadius(320f, 58f, 0.5f), 0.001f)
        assertEquals(0f, LiquidGlassGeometry.clampCornerRadius(0f, 58f, 1.0f), 0.001f)

        assertEquals(0, LiquidGlassGeometry.computeBackdropPaddingPx(0.4f))
        assertEquals(24, LiquidGlassGeometry.computeBackdropPaddingPx(23.2f))
    }

    @Test
    fun vibrancyMatrixPreservesLuminanceBalance() {
        val m = LiquidGlassShaders.buildColorControlsMatrix(saturation = 1.5f)
        assertEquals(20, m.size)
        // Row sums of RGB weight terms for unity contrast & zero brightness should equal 1.0
        val row0 = m[0] + m[1] + m[2]
        val row1 = m[5] + m[6] + m[7]
        val row2 = m[10] + m[11] + m[12]
        assertEquals(1f, row0, 0.001f)
        assertEquals(1f, row1, 0.001f)
        assertEquals(1f, row2, 0.001f)
        assertEquals(1f, m[18], 0.001f)
    }

    @Test
    fun surfaceAndIndicatorTintAdaptToTheme() {
        assertTrue(GlassConfig.isColorDark(0xFF17181A.toInt()))
        assertFalse(GlassConfig.isColorDark(0xFFFFFFFF.toInt()))

        val lightTint = GlassConfig.computeSurfaceTintColor(isDarkTheme = false, tintOpacity = 0.5f)
        val darkTint = GlassConfig.computeSurfaceTintColor(isDarkTheme = true, tintOpacity = 0.5f)
        assertEquals(128, (lightTint ushr 24) and 0xFF)
        assertEquals(0xFAFAFA, lightTint and 0xFFFFFF)
        assertEquals(0x16171A, darkTint and 0xFFFFFF)

        // Default optical parameters balance readability with glass refraction & vibrancy
        val defaults = GlassConfig()
        assertEquals(6.0f, defaults.blurRadiusDp, 0.001f)
        assertEquals(0.27f, defaults.tintOpacity, 0.001f)
        assertEquals(0.50f, defaults.highlightIntensity, 0.001f)
        assertEquals(1.45f, defaults.vibrancy, 0.001f)
        assertEquals(22f, defaults.refractionHeightDp, 0.001f)
        assertEquals(28f, defaults.refractionAmountDp, 0.001f)
        val lightPill = GlassConfig.computeIndicatorTintColor(isDarkTheme = false, pressProgress = 0f)
        assertEquals(0xFFFFFF, lightPill and 0xFFFFFF)
        assertTrue((lightPill ushr 24) in 50..96)
        assertTrue((GlassConfig.computeIndicatorTintColor(isDarkTheme = true, pressProgress = 1f) ushr 24) in 32..96)
    }

    @Test
    fun composeColorSuppressorHandlesSrgbUnspecifiedTransparentAndShapes() {
        assertEquals(0, ComposeColorSuppressor.decodeComposeSrgbArgb(ComposeColorSuppressor.TRANSPARENT_COLOR))
        assertNull(ComposeColorSuppressor.decodeComposeSrgbArgb(ComposeColorSuppressor.UNSPECIFIED_COLOR))

        val darkBarBg = 0xFF202124L shl 32
        assertEquals(0xFF202124.toInt(), ComposeColorSuppressor.decodeComposeSrgbArgb(darkBarBg))

        val lightBarBg = 0xFFFFFFFFL shl 32
        assertEquals(0xFFFFFFFF.toInt(), ComposeColorSuppressor.decodeComposeSrgbArgb(lightBarBg))

        // Suppresses opaque/semi-transparent rectangular fills (Bg1, Ga2 hairline)
        assertTrue(ComposeColorSuppressor.shouldSuppressBarBackgroundColor(darkBarBg, isRectangleShape = true))
        assertTrue(ComposeColorSuppressor.shouldSuppressBarBackgroundColor(lightBarBg, isRectangleShape = true))

        // Preserves non-rectangle shapes (badges/pills), transparent, and unspecified colors
        assertFalse(ComposeColorSuppressor.shouldSuppressBarBackgroundColor(darkBarBg, isRectangleShape = false))
        assertFalse(ComposeColorSuppressor.shouldSuppressBarBackgroundColor(ComposeColorSuppressor.TRANSPARENT_COLOR, isRectangleShape = true))
        assertFalse(ComposeColorSuppressor.shouldSuppressBarBackgroundColor(ComposeColorSuppressor.UNSPECIFIED_COLOR, isRectangleShape = true))
    }

    @Test
    fun legacyTabChromePolicyClassifiesRealTabHostHierarchyCleanly() {
        // Direct child 0: @id/tab_background ImageView -> GARB_IMAGE
        assertEquals(
            LegacyChromeRole.GARB_IMAGE,
            LegacyTabChromePolicy.classifyNode(
                entryName = "tab_background",
                depthFromTabHost = 1,
                isViewGroup = false,
                isImageView = true,
                isLinearLayout = false,
                layoutHeightPx = 150,
                measuredHeightPx = 150,
                maxDividerHeightPx = 6,
                insideTabContainer = false
            )
        )

        // Direct child 1: anonymous vertical LinearLayout wrapping divider + @id/container -> STRUCTURAL_GROUP
        assertEquals(
            LegacyChromeRole.STRUCTURAL_GROUP,
            LegacyTabChromePolicy.classifyNode(
                entryName = null,
                depthFromTabHost = 1,
                isViewGroup = true,
                isImageView = false,
                isLinearLayout = true,
                layoutHeightPx = -2,
                measuredHeightPx = 0,
                maxDividerHeightPx = 6,
                insideTabContainer = false
            )
        )

        // Depth 2 child 0: @id/bottom_tab_divider before first layout (measuredHeight == 0!) -> DIVIDER
        assertEquals(
            LegacyChromeRole.DIVIDER,
            LegacyTabChromePolicy.classifyNode(
                entryName = "bottom_tab_divider",
                depthFromTabHost = 2,
                isViewGroup = false,
                isImageView = false,
                isLinearLayout = false,
                layoutHeightPx = 2,
                measuredHeightPx = 0,
                maxDividerHeightPx = 6,
                insideTabContainer = false
            )
        )

        // Depth 2 child 1: @id/container LinearLayout holding TabHost.a Wh0 background -> TAB_CONTAINER
        assertEquals(
            LegacyChromeRole.TAB_CONTAINER,
            LegacyTabChromePolicy.classifyNode(
                entryName = "container",
                depthFromTabHost = 2,
                isViewGroup = true,
                isImageView = false,
                isLinearLayout = true,
                layoutHeightPx = -2,
                measuredHeightPx = 144,
                maxDividerHeightPx = 6,
                insideTabContainer = false
            )
        )

        // Fallback when id names are obfuscated/null at depth 2 LinearLayout -> TAB_CONTAINER
        assertEquals(
            LegacyChromeRole.TAB_CONTAINER,
            LegacyTabChromePolicy.classifyNode(
                entryName = null,
                depthFromTabHost = 2,
                isViewGroup = true,
                isImageView = false,
                isLinearLayout = true,
                layoutHeightPx = -2,
                measuredHeightPx = 144,
                maxDividerHeightPx = 6,
                insideTabContainer = false
            )
        )

        // Inside tab container items must stay intact (icons/badges/publish button)
        assertEquals(
            LegacyChromeRole.TAB_CONTENT,
            LegacyTabChromePolicy.classifyNode(
                entryName = null,
                depthFromTabHost = 3,
                isViewGroup = true,
                isImageView = false,
                isLinearLayout = false,
                layoutHeightPx = 144,
                measuredHeightPx = 144,
                maxDividerHeightPx = 6,
                insideTabContainer = true
            )
        )

        assertTrue(LegacyTabChromePolicy.isMeaningfulOpaqueColor(0xFF202124.toInt()))
        assertFalse(LegacyTabChromePolicy.isMeaningfulOpaqueColor(0x00000000))
    }

    @Test
    fun `drag slot mapping reaches leftmost and rightmost Mine tab for both 2-tab and 5-tab layouts`() {
        // 2-tab layout: slot 0 = Home [0..500), slot 1 = Mine [500..1000]
        assertEquals(0f, LiquidGlassGeometry.computeHoveredSlot(250f, 1000f, 2), 0.001f)
        assertEquals(0.5f, LiquidGlassGeometry.computeHoveredSlot(500f, 1000f, 2), 0.001f)
        assertEquals(1f, LiquidGlassGeometry.computeHoveredSlot(750f, 1000f, 2), 0.001f)
        assertEquals(1f, LiquidGlassGeometry.computeHoveredSlot(990f, 1000f, 2), 0.001f)

        assertEquals(0, LiquidGlassGeometry.computeTargetSlotIndex(120f, 1000f, 2))
        assertEquals(1, LiquidGlassGeometry.computeTargetSlotIndex(650f, 1000f, 2))
        assertEquals(1, LiquidGlassGeometry.computeTargetSlotIndex(1200f, 1000f, 2))

        // 5-tab layout: slot 0 .. slot 4 ("我的")
        assertEquals(0f, LiquidGlassGeometry.computeHoveredSlot(80f, 1000f, 5), 0.001f)
        assertEquals(4f, LiquidGlassGeometry.computeHoveredSlot(900f, 1000f, 5), 0.001f)
        assertEquals(0, LiquidGlassGeometry.computeTargetSlotIndex(50f, 1000f, 5))
        assertEquals(2, LiquidGlassGeometry.computeTargetSlotIndex(500f, 1000f, 5))
        assertEquals(4, LiquidGlassGeometry.computeTargetSlotIndex(850f, 1000f, 5))
    }
}

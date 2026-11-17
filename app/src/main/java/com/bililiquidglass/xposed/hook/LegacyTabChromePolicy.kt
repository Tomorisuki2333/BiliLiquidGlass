package com.bililiquidglass.xposed.hook

/**
 * Pure classification policy for nodes inside legacy XML [com.bilibili.lib.homepage.widget.TabHost].
 */
internal enum class LegacyChromeRole {
    /** ImageView used by Garb themes to cover the bar with a bitmap (`@id/tab_background`). */
    GARB_IMAGE,

    /** Hairline divider at the top of the bar (`@id/bottom_tab_divider`). */
    DIVIDER,

    /** Horizontal tab item row whose own background must be stripped (`@id/container`), stopping recursion. */
    TAB_CONTAINER,

    /** Outer structural wrapper between TabHost and [TAB_CONTAINER]; strip background and descend. */
    STRUCTURAL_GROUP,

    /** Individual tab item or leaf view inside [TAB_CONTAINER]; leave visuals intact. */
    TAB_CONTENT
}

internal object LegacyTabChromePolicy {

    /**
     * Classifies a view inside [TabHost] without requiring Android framework subclasses at test time.
     */
    fun classifyNode(
        entryName: String?,
        depthFromTabHost: Int,
        isViewGroup: Boolean,
        isImageView: Boolean,
        isLinearLayout: Boolean,
        layoutHeightPx: Int,
        measuredHeightPx: Int,
        maxDividerHeightPx: Int,
        insideTabContainer: Boolean
    ): LegacyChromeRole {
        if (insideTabContainer) {
            return LegacyChromeRole.TAB_CONTENT
        }
        return when {
            entryName == "tab_background" || (depthFromTabHost == 1 && isImageView) ->
                LegacyChromeRole.GARB_IMAGE

            entryName == "bottom_tab_divider" ||
                (!isViewGroup && (layoutHeightPx in 1..maxDividerHeightPx || measuredHeightPx in 1..maxDividerHeightPx)) ->
                LegacyChromeRole.DIVIDER

            entryName == "container" ->
                LegacyChromeRole.TAB_CONTAINER

            isLinearLayout && depthFromTabHost >= 2 ->
                LegacyChromeRole.TAB_CONTAINER

            isViewGroup ->
                LegacyChromeRole.STRUCTURAL_GROUP

            else ->
                LegacyChromeRole.TAB_CONTENT
        }
    }

    /**
     * Returns true when [argb] carries meaningful opacity that reflects a real base theme color
     * (i.e. not cleared / `Color.TRANSPARENT`).
     */
    fun isMeaningfulOpaqueColor(argb: Int): Boolean {
        val alpha = (argb ushr 24) and 0xFF
        return alpha >= 0x10
    }
}

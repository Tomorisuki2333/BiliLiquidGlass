package com.bililiquidglass.xposed.hook

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import com.bililiquidglass.xposed.config.GlassConfig
import com.bililiquidglass.xposed.glass.LiquidGlassPillLayout
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Installs Liquid Glass floating bottom bar transformations on both Bilibili Home implementations:
 * 1) Modern Compose Home (HomeFrameFragment / BottomTabComponent / WindowAwareComposeView)
 * 2) Legacy XML Home (BaseMainFrameFragment / TabHost)
 */
internal class BiliHomeBarHook(
    private val xposed: XposedInterface,
    private val configProvider: () -> GlassConfig
) {
    private val tag = "BiliLiquidGlass"

    private val watcher = CoordinatorInvalidationWatcher(xposed)
    private val composeSuppressor = ComposeBarBackgroundSuppressor(xposed)
    private val baselineBottomPaddings = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val wrappedBars = Collections.synchronizedMap(WeakHashMap<View, LiquidGlassPillLayout>())
    private val watchedLegacyBars = Collections.newSetFromMap(
        Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    )
    private val styledPublishBgState = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val styledPublishDrawables = Collections.synchronizedMap(WeakHashMap<View, Drawable>())

    private val composeHookInstalled = AtomicBoolean(false)
    private val composePublishHookInstalled = AtomicBoolean(false)
    private val legacyHookInstalled = AtomicBoolean(false)
    private val legacyPublishHookInstalled = AtomicBoolean(false)
    private val loggedComposeWrapped = AtomicBoolean(false)
    private val loggedLegacyWrapped = AtomicBoolean(false)

    // Cached reflective accessors for Compose HomeFrameViewModel bottom tab state & actions.
    @Volatile
    private var bottomTabStateFlowField: Field? = null

    @Volatile
    private var stateContainerToTabsListChain: Pair<Field, Field>? = null

    @Volatile
    private var tabItemSelectedField: Field? = null

    @Volatile
    private var tabItemPublishField: Field? = null

    @Volatile
    private var dispatchActionMethod: Method? = null

    @Volatile
    private var bottomTabClickActionCtor: Constructor<*>? = null

    fun install(classLoader: ClassLoader) {
        val cfg = configProvider()
        if (!cfg.enabled) {
            xposed.log(Log.WARN, tag, "Module disabled via preferences; skipping Home hooks.")
            return
        }

        val composeInstalled = installComposeHomeHooks(classLoader)
        val legacyInstalled = installLegacyHomeHooks(classLoader)
        xposed.log(
            Log.WARN,
            tag,
            "Home hooks initialized (compose=$composeInstalled, legacy=$legacyInstalled)"
        )
    }

    private fun installComposeHomeHooks(classLoader: ClassLoader): Boolean {
        val baseHomeClass = HostReflection.findClassOrNull(
            classLoader,
            "tv.danmaku.bili.home.page.BaseHomeFrameFragment"
        )
        val homeClass = HostReflection.findClassOrNull(
            classLoader,
            "tv.danmaku.bili.home.page.HomeFrameFragment"
        )
        val targetClass = baseHomeClass ?: homeClass ?: return false

        val onViewCreated = HostReflection.findMethodExact(
            targetClass,
            "onViewCreated",
            View::class.java,
            Bundle::class.java
        ) ?: return false

        if (!composeHookInstalled.compareAndSet(false, true)) return true

        deoptimizeSafe(onViewCreated)
        if (homeClass != null && homeClass != targetClass) {
            deoptimizeSafe(
                HostReflection.findMethodExact(
                    homeClass,
                    "onViewCreated",
                    View::class.java,
                    Bundle::class.java
                )
            )
        }

        xposed.hook(onViewCreated).intercept { chain ->
            val result = chain.proceed()
            val fragment = chain.thisObject
            val rootView = chain.getArg(0) as? ViewGroup
            if (rootView != null) {
                tryWrapComposeHome(fragment, rootView, classLoader)
            }
            result
        }

        installComposeTabStateHooks(classLoader)
        installComposePublishGlassHook(classLoader)
        return true
    }

    private fun tryWrapComposeHome(fragment: Any?, rootView: ViewGroup, classLoader: ClassLoader) {
        val pkg = rootView.context.packageName
        val res = rootView.resources

        val contentId = res.getIdentifier("content", "id", pkg)
        val tabHostId = res.getIdentifier("tab_host", "id", pkg)

        val contentView = if (contentId != 0) rootView.findViewById<View>(contentId) else null
        val tabHost = (if (tabHostId != 0) rootView.findViewById<View>(tabHostId) else null)
            ?: findChildByClassName(rootView, "WindowAwareComposeView")

        if (contentView == null || tabHost == null) {
            xposed.log(Log.WARN, tag, "Compose Home views missing: content=$contentView tabHost=$tabHost")
            return
        }

        wrapComposeBottomBar(fragment, rootView, contentView, tabHost, classLoader)
    }

    private fun wrapComposeBottomBar(
        owner: Any?,
        coordinator: ViewGroup,
        contentView: View,
        tabHost: View,
        classLoader: ClassLoader
    ) {
        val pill = wrapBarInFloatingPill(coordinator, contentView, tabHost) ?: return
        pill.onTabDragSelectedListener = { slotIndex ->
            switchComposeTabByIndex(owner, tabHost, pill, slotIndex, classLoader)
        }
        if (tabHost is ViewGroup) {
            composeSuppressor.registerBottomBar(tabHost, pill, classLoader)
        }
        syncComposeSelectionFromFragment(owner, pill)
        if (loggedComposeWrapped.compareAndSet(false, true)) {
            xposed.log(
                Log.WARN,
                tag,
                "Wrapped Compose bottom bar: tabHost=${tabHost.javaClass.name}, childCount=${(tabHost as? ViewGroup)?.childCount ?: 0}"
            )
        }
    }

    private fun switchComposeTabByIndex(
        owner: Any?,
        barView: View,
        pill: LiquidGlassPillLayout,
        slotIndex: Int,
        classLoader: ClassLoader
    ) {
        xposed.log(Log.WARN, tag, "Compose drag tab switch: slot=$slotIndex owner=${owner?.javaClass?.simpleName}")
        try {
            val vm = owner?.let { findHomeFrameViewModel(it) }
            if (vm != null) {
                val state = extractComposeTabsState(vm)
                if (state != null) {
                    val (tabs, currentSelected) = state
                    if (slotIndex !in tabs.indices) {
                        pill.updateSelection(currentSelected, tabs.size)
                        return
                    }
                    if (isComposeTabPublishItem(tabs[slotIndex])) {
                        // Do not launch publish composer mid-swipe; snap back to current active tab.
                        pill.updateSelection(currentSelected, tabs.size)
                        return
                    }
                    if (slotIndex == currentSelected) {
                        pill.updateSelection(currentSelected, tabs.size)
                        return
                    }
                    if (dispatchComposeTabSelect(vm, slotIndex, classLoader) ||
                        pill.dispatchSyntheticTapAtSlot(slotIndex, tabs.size)
                    ) {
                        pill.updateSelection(slotIndex, tabs.size)
                        pill.postDelayed({ syncComposeSelectionFromFragment(owner, pill) }, 60L)
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            xposed.log(Log.WARN, tag, "Compose VM drag tab switch fallback: ${t.message}")
        }

        // Synthetic touch tap fallback directly into the Compose bar at target slot center
        val barWidth = barView.width
        val barHeight = barView.height
        val slotCount = (owner?.let {
            findHomeFrameViewModel(it)?.let { vm -> extractComposeTabsState(vm)?.first?.size }
        } ?: 5).coerceIn(1, 8)
        if (barWidth > 0 && barHeight > 0 && slotIndex in 0 until slotCount) {
            val tapX = (slotIndex + 0.5f) * (barWidth.toFloat() / slotCount)
            val tapY = barHeight * 0.5f
            val downTime = SystemClock.uptimeMillis()
            val downEv = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
            val upEv = MotionEvent.obtain(downTime, downTime + 25L, MotionEvent.ACTION_UP, tapX, tapY, 0)
            try {
                barView.dispatchTouchEvent(downEv)
                barView.dispatchTouchEvent(upEv)
            } finally {
                downEv.recycle()
                upEv.recycle()
            }
            pill.postDelayed({ syncComposeSelectionFromFragment(owner, pill) }, 60L)
        }
    }

    private fun isComposeTabPublishItem(tabItem: Any?): Boolean {
        if (tabItem == null) return false
        val str = runCatching { tabItem.toString() }.getOrNull()
        if (str != null) {
            if (str.contains("isPublish=true")) return true
            if (str.contains("isPublish=false")) return false
        }
        val f = tabItemPublishField ?: resolvePublishBooleanField(tabItem)?.also {
            tabItemPublishField = it
        } ?: return false
        return runCatching { f.getBoolean(tabItem) }.getOrDefault(false)
    }

    private fun resolvePublishBooleanField(tabItem: Any): Field? {
        return try {
            val fieldN = tabItem.javaClass.getDeclaredField("n")
            if (fieldN.type == Boolean::class.javaPrimitiveType) {
                fieldN.isAccessible = true
                fieldN
            } else {
                null
            }
        } catch (_: NoSuchFieldException) {
            null
        }
    }

    private fun dispatchComposeTabSelect(vm: Any, slotIndex: Int, classLoader: ClassLoader): Boolean {
        val method = dispatchActionMethod ?: vm.javaClass.methods.firstOrNull { m ->
            m.parameterCount == 1 &&
                m.returnType == Void.TYPE &&
                m.parameterTypes[0].isInterface &&
                !m.parameterTypes[0].name.startsWith("java.") &&
                !m.parameterTypes[0].name.startsWith("android.") &&
                (m.name == "k1" || m.name.length <= 3)
        }?.also {
            it.isAccessible = true
            dispatchActionMethod = it
        } ?: return false

        val actionInterface = method.parameterTypes[0]
        val ctor = bottomTabClickActionCtor ?: run {
            val z7kClass = HostReflection.findClassOrNull(classLoader, "z7k")
            if (z7kClass != null && actionInterface.isAssignableFrom(z7kClass)) {
                runCatching {
                    z7kClass.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
                }.getOrNull()
            } else {
                null
            }
        }?.also {
            bottomTabClickActionCtor = it
        } ?: return false

        val action = ctor.newInstance(slotIndex)
        if (!action.toString().contains("BottomTabClickAction(")) {
            return false
        }
        method.invoke(vm, action)
        return true
    }

    /**
     * Hooks `mzo0.b(GarbInfo, int)` (a pure helper returning `BottomTabGarbUi`) so Compose's
     * center `+` publish button renders with a frosted translucent Liquid Glass surface & pink `+`
     * tint in both light and dark modes, without touching any `@Composable` UI slot tables.
     */
    private fun installComposePublishGlassHook(classLoader: ClassLoader) {
        if (!composePublishHookInstalled.compareAndSet(false, true)) return

        val garbInfoClass = HostReflection.findClassOrNull(
            classLoader,
            "com.bilibili.compose.theme.GarbInfo"
        ) ?: return
        val mzo0Class = HostReflection.findClassOrNull(classLoader, "mzo0") ?: return
        val builderMethod = mzo0Class.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 2 &&
                p[0] == garbInfoClass &&
                p[1] == Int::class.javaPrimitiveType &&
                !m.returnType.isPrimitive
        } ?: return

        builderMethod.isAccessible = true
        xposed.hook(builderMethod).intercept { chain ->
            val result = chain.proceed() ?: return@intercept null
            if (!configProvider().enabled) return@intercept result
            val str = runCatching { result.toString() }.getOrNull() ?: return@intercept result
            if (!str.startsWith("BottomTabGarbUi(")) return@intercept result
            rewriteBottomTabGarbUiForGlass(result) ?: result
        }
    }

    private fun rewriteBottomTabGarbUiForGlass(garbUi: Any): Any? {
        return try {
            val cls = garbUi.javaClass
            val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount == 17 } ?: return null
            ctor.isAccessible = true

            fun fieldVal(name: String): Any? = cls.getDeclaredField(name).apply { isAccessible = true }.get(garbUi)

            // Detect bar darkness from `i` (packed Compose Color `textColor`: light text => dark bar).
            val textColorLong = fieldVal("i") as? Long ?: 0L
            val textArgb = (textColorLong ushr 32).toInt()
            val isDarkBar = !GlassConfig.isColorDark(textArgb)

            fun packComposeColor(argb: Int): Long = (argb.toLong() and 0xFFFFFFFFL) shl 32

            val glassStart = packComposeColor(if (isDarkBar) 0x6E3E4452 else 0xA8FFFFFF.toInt())
            val glassEnd = packComposeColor(if (isDarkBar) 0x3E1E222B else 0x5CEBF0F7)
            val plusTint = packComposeColor(if (isDarkBar) 0xFFFF80A6.toInt() else 0xFFFB7299.toInt())

            ctor.newInstance(
                fieldVal("a"),
                fieldVal("b"),
                fieldVal("c"),
                fieldVal("d"),
                fieldVal("e"),
                fieldVal("f"),
                fieldVal("g"),
                fieldVal("h"),
                fieldVal("i"),
                fieldVal("j"),
                fieldVal("k"),
                fieldVal("l"),
                glassStart,
                glassEnd,
                plusTint,
                "",
                ""
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun installComposeTabStateHooks(classLoader: ClassLoader) {
        val bottomTabCompClass = HostReflection.findClassOrNull(
            classLoader,
            "tv.danmaku.bili.home.components.bottomtab.BottomTabComponent"
        ) ?: return

        val composerClass = HostReflection.findClassOrNull(
            classLoader,
            "androidx.compose.runtime.Composer"
        ) ?: return

        val composableMethod = bottomTabCompClass.declaredMethods.firstOrNull { method ->
            val p = method.parameterTypes
            p.size == 2 && p[0] == composerClass && p[1] == Int::class.javaPrimitiveType
        }
        if (composableMethod != null) {
            composableMethod.isAccessible = true
            xposed.hook(composableMethod).intercept { chain ->
                val res = chain.proceed()
                val component = chain.thisObject
                if (component != null) {
                    syncComposeSelectionFromComponent(component)
                }
                res
            }
        }

        // Lifecycle fallback (aie.onViewCreated(Bundle) / BottomTabComponent.m(Bundle)) in case
        // BaseHomeFrameFragment.onViewCreated was skipped or inlined by ART.
        val bundleLifecycleMethod = bottomTabCompClass.declaredMethods.firstOrNull { method ->
            val p = method.parameterTypes
            p.size == 1 && p[0] == Bundle::class.java && method.returnType == Void.TYPE
        }
        if (bundleLifecycleMethod != null) {
            bundleLifecycleMethod.isAccessible = true
            deoptimizeSafe(bundleLifecycleMethod)
            xposed.hook(bundleLifecycleMethod).intercept { chain ->
                val res = chain.proceed()
                val component = chain.thisObject
                if (component != null) {
                    val (_, coordinator, contentView, barView) = resolveComponentBindingViews(component)
                    if (barView != null && !wrappedBars.containsKey(barView) &&
                        coordinator != null && contentView != null
                    ) {
                        wrapComposeBottomBar(component, coordinator, contentView, barView, classLoader)
                    }
                }
                res
            }
        }
    }

    private fun syncComposeSelectionFromFragment(fragment: Any?, pill: LiquidGlassPillLayout) {
        if (fragment == null) return
        try {
            val vm = findHomeFrameViewModel(fragment) ?: return
            extractAndApplyTabState(vm, pill)
        } catch (_: Throwable) {
        }
    }

    private data class BindingViews(
        val binding: Any?,
        val coordinator: ViewGroup?,
        val contentView: View?,
        val barView: View?
    )

    private fun resolveComponentBindingViews(component: Any): BindingViews {
        val dField = findFieldWithChildAssignable(component.javaClass, "bag")
        val binding = dField?.get(component) ?: return BindingViews(null, null, null, null)
        var barView: View? = null
        var coordinator: ViewGroup? = null
        var contentView: View? = null

        for (f in binding.javaClass.declaredFields) {
            val typeName = f.type.name
            if (typeName.endsWith("WindowAwareComposeView")) {
                f.isAccessible = true
                barView = f.get(binding) as? View
            } else if (typeName.endsWith("CoordinatorLayout")) {
                f.isAccessible = true
                coordinator = f.get(binding) as? ViewGroup
            }
        }
        if (coordinator == null && barView != null) {
            coordinator = barView.parent as? ViewGroup
        }
        if (coordinator != null) {
            val pkg = coordinator.context.packageName
            val contentId = coordinator.resources.getIdentifier("content", "id", pkg)
            if (contentId != 0) {
                contentView = coordinator.findViewById(contentId)
            }
        }
        return BindingViews(binding, coordinator, contentView, barView)
    }

    private fun syncComposeSelectionFromComponent(component: Any) {
        try {
            val barView = resolveComponentBindingViews(component).barView
            val pill = barView?.let { wrappedBars[it] } ?: return
            val vm = findHomeFrameViewModel(component) ?: return
            extractAndApplyTabState(vm, pill)
        } catch (_: Throwable) {
        }
    }

    private fun findFieldWithChildAssignable(clazz: Class<*>, suffix: String): Field? {
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type.name == suffix || f.type.simpleName == suffix) {
                    f.isAccessible = true
                    return f
                }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun findHomeFrameViewModel(owner: Any): Any? {
        val cl = owner.javaClass.classLoader ?: return null
        val vmClass = HostReflection.findClassOrNull(
            cl,
            "tv.danmaku.bili.khome.vm.HomeFrameViewModel"
        ) ?: return null

        HostReflection.findFieldByType(owner.javaClass, vmClass)?.let { f ->
            return f.get(owner)
        }

        // Fragment ng() ViewModelLazy getter fallback
        for (m in owner.javaClass.methods) {
            if (m.parameterCount == 0 && m.returnType == vmClass) {
                m.isAccessible = true
                return m.invoke(owner)
            }
        }
        return null
    }

    private fun extractAndApplyTabState(vm: Any, pill: LiquidGlassPillLayout) {
        val state = extractComposeTabsState(vm) ?: return
        val (tabs, selectedIndex) = state
        pill.updateSelection(selectedIndex.coerceAtLeast(0), tabs.size)
    }

    private fun extractComposeTabsState(vm: Any): Pair<List<*>, Int>? {
        val cl = vm.javaClass.classLoader ?: return null
        val stateFlowClass = HostReflection.findClassOrNull(cl, "kotlinx.coroutines.flow.StateFlow")
            ?: return null
        val getValueMethod = HostReflection.findMethodExact(stateFlowClass, "getValue") ?: return null

        val flowField = bottomTabStateFlowField ?: resolveMainStateFlowField(vm, stateFlowClass, getValueMethod)?.also {
            bottomTabStateFlowField = it
        } ?: return null

        val stateFlow = flowField.get(vm) ?: return null
        val rootState = getValueMethod.invoke(stateFlow) ?: return null

        val chain = stateContainerToTabsListChain ?: resolveTabsListChain(rootState)?.also {
            stateContainerToTabsListChain = it
        } ?: return null

        val container = chain.first.get(rootState) ?: return null
        val tabs = chain.second.get(container) as? List<*> ?: return null
        if (tabs.isEmpty()) return null

        val selectedField = tabItemSelectedField ?: resolveSelectedBooleanField(tabs)?.also {
            tabItemSelectedField = it
        }

        var selectedIndex = -1
        if (selectedField != null) {
            for (i in tabs.indices) {
                val item = tabs[i] ?: continue
                if (selectedField.getBoolean(item)) {
                    selectedIndex = i
                    break
                }
            }
        }
        return tabs to selectedIndex.coerceAtLeast(0)
    }

    private fun resolveMainStateFlowField(
        vm: Any,
        stateFlowClass: Class<*>,
        getValueMethod: Method
    ): Field? {
        var cur: Class<*>? = vm.javaClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (!stateFlowClass.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val sf = f.get(vm) ?: continue
                val value = try {
                    getValueMethod.invoke(sf)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (resolveTabsListChain(value) != null) {
                    return f
                }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun resolveTabsListChain(rootState: Any): Pair<Field, Field>? {
        for (f1 in rootState.javaClass.declaredFields) {
            if (f1.type.isPrimitive || f1.type == String::class.java) continue
            f1.isAccessible = true
            val sub = f1.get(rootState) ?: continue
            for (f2 in sub.javaClass.declaredFields) {
                if (!List::class.java.isAssignableFrom(f2.type)) continue
                f2.isAccessible = true
                val list = f2.get(sub) as? List<*> ?: continue
                if (list.size in 2..8) {
                    val sample = list.firstOrNull() ?: continue
                    val boolFields = sample.javaClass.declaredFields.count {
                        it.type == Boolean::class.javaPrimitiveType
                    }
                    // Bottom tab items (i1o0) carry >= 3 boolean flags; top tabs (wno0) only carry 1.
                    if (boolFields >= 3) {
                        return f1 to f2
                    }
                }
            }
        }
        return null
    }

    private fun resolveSelectedBooleanField(tabs: List<*>): Field? {
        val sample = tabs.firstOrNull() ?: return null
        // Check well-known field 'e' first if its boolean distribution matches selection (exactly 1 true).
        try {
            val fieldE = sample.javaClass.getDeclaredField("e")
            if (fieldE.type == Boolean::class.javaPrimitiveType) {
                fieldE.isAccessible = true
                val trueCount = tabs.count { it != null && fieldE.getBoolean(it) }
                if (trueCount == 1) return fieldE
            }
        } catch (_: NoSuchFieldException) {
        }

        // General scan: look for a boolean field true on exactly 1 item and NOT the middle publish slot if >= 5 items.
        val candidates = sample.javaClass.declaredFields.filter {
            it.type == Boolean::class.javaPrimitiveType
        }
        for (f in candidates) {
            f.isAccessible = true
            val trueCount = tabs.count { it != null && f.getBoolean(it) }
            if (trueCount == 1) {
                return f
            }
        }
        return null
    }

    private fun installLegacyHomeHooks(classLoader: ClassLoader): Boolean {
        val mainFragClass = HostReflection.findClassOrNull(
            classLoader,
            "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment"
        ) ?: return false

        val onViewCreated = HostReflection.findMethodExact(
            mainFragClass,
            "onViewCreated",
            View::class.java,
            Bundle::class.java
        ) ?: return false

        if (!legacyHookInstalled.compareAndSet(false, true)) return true

        deoptimizeSafe(onViewCreated)
        HostReflection.findClassOrNull(classLoader, "tv.danmaku.bili.ui.main2.MainFragment")
            ?.let { mainSubclass ->
                deoptimizeSafe(
                    HostReflection.findMethodExact(
                        mainSubclass,
                        "onViewCreated",
                        View::class.java,
                        Bundle::class.java
                    )
                )
            }

        xposed.hook(onViewCreated).intercept { chain ->
            val result = chain.proceed()
            val rootView = chain.getArg(0) as? ViewGroup
            if (rootView != null) {
                tryWrapLegacyHome(rootView, classLoader)
            }
            result
        }

        installLegacyTabHostHooks(classLoader)
        installLegacyPublishViewHooks(classLoader)
        return true
    }

    private fun installLegacyPublishViewHooks(classLoader: ClassLoader) {
        if (!legacyPublishHookInstalled.compareAndSet(false, true)) return
        val publishViewClass = HostReflection.findClassOrNull(
            classLoader,
            "com.bilibili.lib.homepage.widget.HomeTabPublishView"
        ) ?: return

        for (m in publishViewClass.declaredMethods) {
            if ((m.name == "d" && m.parameterCount == 0) ||
                (m.name == "f" && m.parameterCount == 3)
            ) {
                m.isAccessible = true
                deoptimizeSafe(m)
                xposed.hook(m).intercept { chain ->
                    val res = chain.proceed()
                    val publishView = chain.thisObject as? View
                    if (publishView != null && configProvider().enabled) {
                        val hostPill = findAncestorPill(publishView)
                        val isNight = hostPill?.isNightTheme ?: (
                            (publishView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                                Configuration.UI_MODE_NIGHT_YES
                            )
                        styleSinglePublishContainer(publishView, isNight)
                        hostPill?.invalidateGlassBackdrop()
                    }
                    res
                }
            }
        }
    }

    private fun findAncestorPill(view: View): LiquidGlassPillLayout? {
        var cur = view.parent
        while (cur is View) {
            if (cur is LiquidGlassPillLayout) return cur
            cur = cur.parent
        }
        return null
    }

    private fun tryWrapLegacyHome(rootView: ViewGroup, classLoader: ClassLoader) {
        val pkg = rootView.context.packageName
        val res = rootView.resources
        val contentId = res.getIdentifier("content", "id", pkg)
        val bottomNavId = res.getIdentifier("bottom_navigation", "id", pkg)

        val contentView = if (contentId != 0) rootView.findViewById<View>(contentId) else null
        val tabHost = (if (bottomNavId != 0) rootView.findViewById<View>(bottomNavId) else null)
            ?: findChildByClassName(rootView, "com.bilibili.lib.homepage.widget.TabHost")

        if (contentView == null || tabHost == null) {
            xposed.log(Log.WARN, tag, "Legacy Home views missing: content=$contentView tabHost=$tabHost")
            return
        }

        val pill = wrapBarInFloatingPill(rootView, contentView, tabHost) ?: return
        tabHost.alpha = 1f
        pill.onTabDragSelectedListener = { slotIndex ->
            switchLegacyTabByIndex(tabHost, pill, slotIndex)
        }
        stripLegacyTabHostChrome(tabHost, pill, classLoader)
        ensureLegacyChromeWatcher(tabHost, pill, classLoader)

        val tabCount = findLegacyTabCount(tabHost)
        val selected = findLegacySelectedIndex(tabHost)
        pill.updateSelection(selected, tabCount)

        if (loggedLegacyWrapped.compareAndSet(false, true)) {
            xposed.log(
                Log.WARN,
                tag,
                "Wrapped Legacy TabHost: class=${tabHost.javaClass.name}, tabs=$tabCount, selected=$selected, " +
                    "bg=0x${pill.detectedBaseBgColor.toUInt().toString(16)}, tree=[${describeViewTree(tabHost)}]"
            )
        }
    }

    private fun installLegacyTabHostHooks(classLoader: ClassLoader) {
        val tabHostClass = HostReflection.findClassOrNull(
            classLoader,
            "com.bilibili.lib.homepage.widget.TabHost"
        ) ?: return

        // TabHost.w(int index, boolean smooth) - programmatic selection
        HostReflection.findMethodExact(
            tabHostClass,
            "w",
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!
        )?.let { selectMethod ->
            deoptimizeSafe(selectMethod)
            xposed.hook(selectMethod).intercept { chain ->
                val res = chain.proceed()
                val tabHost = chain.thisObject as? View
                val rawIdx = (chain.getArg(0) as? Int) ?: 0
                val pill = tabHost?.let { wrappedBars[it] }
                if (tabHost != null && pill != null) {
                    val visibleIdx = resolveVisibleTabIndex(tabHost, rawIdx)
                    pill.updateSelection(visibleIdx, findLegacyTabCount(tabHost))
                    stripLegacyTabHostChrome(tabHost, pill, classLoader)
                }
                res
            }
        }

        // TabHost.v(View tabView, int index, boolean selected) - manual tab taps & programmatic sync
        HostReflection.findMethodExact(
            tabHostClass,
            "v",
            View::class.java,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!
        )?.let { updateTabStateMethod ->
            deoptimizeSafe(updateTabStateMethod)
            xposed.hook(updateTabStateMethod).intercept { chain ->
                val res = chain.proceed()
                val selected = (chain.getArg(2) as? Boolean) == true
                if (selected) {
                    val tabHost = chain.thisObject as? View
                    val tabView = chain.getArg(0) as? View
                    val rawIdx = (chain.getArg(1) as? Int) ?: 0
                    val pill = tabHost?.let { wrappedBars[it] }
                    if (tabHost != null && pill != null) {
                        val visibleIdx = resolveVisibleTabIndex(tabHost, rawIdx, tabView)
                        pill.updateSelection(visibleIdx, findLegacyTabCount(tabHost))
                        pill.invalidateGlassBackdrop()
                    }
                }
                res
            }
        }

        // TabHost.p(int, View) - binds tab item views and applies TintFrameLayout foreground ripple
        HostReflection.findMethodExact(
            tabHostClass,
            "p",
            Int::class.javaPrimitiveType!!,
            View::class.java
        )?.let { bindItemMethod ->
            xposed.hook(bindItemMethod).intercept { chain ->
                val res = chain.proceed()
                val tabHost = chain.thisObject as? View
                val pill = tabHost?.let { wrappedBars[it] }
                if (tabHost != null && pill != null) {
                    clearTabItemRippleChrome(chain.getArg(1) as? View)
                    stylePublishButtonsAsLiquidGlass(tabHost, pill.isNightTheme)
                }
                res
            }
        }

        // TabHost.setTabs(List) and TabHost.q(List)
        for (methodName in arrayOf("setTabs", "q")) {
            HostReflection.findMethodExact(tabHostClass, methodName, List::class.java)?.let { m ->
                xposed.hook(m).intercept { chain ->
                    val res = chain.proceed()
                    val tabHost = chain.thisObject as? View
                    val list = chain.getArg(0) as? List<*>
                    val pill = tabHost?.let { wrappedBars[it] }
                    if (tabHost != null && pill != null) {
                        val visibleCount = findLegacyTabCount(tabHost)
                        if (visibleCount > 0 || (list != null && list.isNotEmpty())) {
                            val count = if (visibleCount > 0) visibleCount else list!!.size
                            pill.updateSelection(findLegacySelectedIndex(tabHost), count)
                        }
                        stripLegacyTabHostChrome(tabHost, pill, classLoader)
                    }
                    res
                }
            }
        }

        // Theme updates on TabHost (r() and s(Garb, boolean))
        for (m in tabHostClass.declaredMethods) {
            if ((m.name == "r" && m.parameterCount == 0) ||
                (m.name == "s" && m.parameterCount == 2 && m.parameterTypes[1] == Boolean::class.javaPrimitiveType)
            ) {
                m.isAccessible = true
                deoptimizeSafe(m)
                xposed.hook(m).intercept { chain ->
                    val res = chain.proceed()
                    val tabHost = chain.thisObject as? View
                    val pill = tabHost?.let { wrappedBars[it] }
                    if (tabHost != null && pill != null) {
                        stripLegacyTabHostChrome(tabHost, pill, classLoader)
                    }
                    res
                }
            }
        }
    }

    private fun stripLegacyTabHostChrome(
        tabHost: View,
        pill: LiquidGlassPillLayout,
        classLoader: ClassLoader
    ) {
        if (tabHost !is ViewGroup) return

        var detectedColor: Int? = extractOpaqueBgColor(tabHost)
        if (tabHost.background != null) {
            tabHost.background = null
        }

        val maxDividerPx = (2f * tabHost.resources.displayMetrics.density).roundToInt().coerceAtLeast(4)

        fun visit(node: ViewGroup, depth: Int) {
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) ?: continue
                val childName = child.javaClass.name
                if (childName.endsWith("ComposeView") || childName.endsWith("AndroidComposeView")) {
                    if (child is ViewGroup) {
                        composeSuppressor.registerBottomBar(child, pill, classLoader)
                    }
                    continue
                }

                val entryName = safeResourceEntryName(child)
                val lpHeight = child.layoutParams?.height ?: 0
                val role = LegacyTabChromePolicy.classifyNode(
                    entryName = entryName,
                    depthFromTabHost = depth,
                    isViewGroup = child is ViewGroup,
                    isImageView = child is ImageView,
                    isLinearLayout = child is LinearLayout,
                    layoutHeightPx = lpHeight,
                    measuredHeightPx = child.height,
                    maxDividerHeightPx = maxDividerPx,
                    insideTabContainer = false
                )

                when (role) {
                    LegacyChromeRole.GARB_IMAGE -> {
                        if (child.visibility != View.GONE) child.visibility = View.GONE
                        if (child.alpha != 0f) child.alpha = 0f
                        if (child is ImageView && child.drawable != null) child.setImageDrawable(null)
                        if (child.background != null) child.background = null
                    }

                    LegacyChromeRole.DIVIDER -> {
                        if (child.visibility != View.GONE) child.visibility = View.GONE
                        if (child.alpha != 0f) child.alpha = 0f
                        if (child.background != null) child.background = null
                    }

                    LegacyChromeRole.TAB_CONTAINER -> {
                        extractOpaqueBgColor(child)?.let { detectedColor = it }
                        if (child.background != null) child.background = null
                        if (child is ViewGroup) {
                            for (j in 0 until child.childCount) {
                                clearTabItemRippleChrome(child.getChildAt(j))
                            }
                        }
                    }

                    LegacyChromeRole.STRUCTURAL_GROUP -> {
                        extractOpaqueBgColor(child)?.let { if (detectedColor == null) detectedColor = it }
                        if (child.background != null) child.background = null
                        if (child is ViewGroup) {
                            visit(child, depth + 1)
                        }
                    }

                    LegacyChromeRole.TAB_CONTENT -> Unit
                }
            }
        }

        visit(tabHost, 1)

        val nightMode = (tabHost.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val effectiveColor = detectedColor
        if (effectiveColor != null) {
            val isDark = GlassConfig.isColorDark(effectiveColor)
            if (pill.detectedBaseBgColor != effectiveColor) {
                pill.detectedBaseBgColor = effectiveColor
            }
            if (pill.isNightTheme != isDark) {
                pill.isNightTheme = isDark
            }
        } else if (pill.isNightTheme != nightMode) {
            pill.isNightTheme = nightMode
            pill.detectedBaseBgColor = if (nightMode) 0xFF17181A.toInt() else Color.WHITE
        }

        stylePublishButtonsAsLiquidGlass(tabHost, pill.isNightTheme)
        centerAndBoostLegacyTabItems(tabHost, pill.isNightTheme)
    }

    private fun ensureLegacyChromeWatcher(
        tabHost: View,
        pill: LiquidGlassPillLayout,
        classLoader: ClassLoader
    ) {
        if (!watchedLegacyBars.add(tabHost)) return

        val recheck = Runnable {
            stripLegacyTabHostChrome(tabHost, pill, classLoader)
            pill.invalidateGlassBackdrop()
        }

        tabHost.viewTreeObserver.addOnPreDrawListener(
            ViewTreeObserver.OnPreDrawListener {
                stripLegacyTabHostChrome(tabHost, pill, classLoader)
                true
            }
        )

        val hierarchyWatcher = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                tabHost.removeCallbacks(recheck)
                tabHost.post(recheck)
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        }
        if (tabHost is ViewGroup) {
            tabHost.setOnHierarchyChangeListener(hierarchyWatcher)
            for (i in 0 until tabHost.childCount) {
                val child = tabHost.getChildAt(i)
                if (child is LinearLayout && safeResourceEntryName(child) != "container") {
                    child.setOnHierarchyChangeListener(hierarchyWatcher)
                }
            }
        }
    }

    private fun clearTabItemRippleChrome(tabItem: View?) {
        if (tabItem == null) return
        if (tabItem.foreground != null) {
            tabItem.foreground = null
        }
        if (tabItem.background != null) {
            tabItem.background = null
        }
        if (tabItem.stateListAnimator != null) {
            tabItem.stateListAnimator = null
        }
    }

    private fun extractOpaqueBgColor(view: View): Int? {
        val cd = view.background as? ColorDrawable ?: return null
        val c = cd.color
        return if (LegacyTabChromePolicy.isMeaningfulOpaqueColor(c)) c else null
    }

    private fun safeResourceEntryName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID || (id ushr 24) != 0x7f) return null
        return try {
            view.resources.getResourceEntryName(id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findLegacyTabContainer(tabHost: View): LinearLayout? {
        if (tabHost !is ViewGroup) return null
        fun search(group: ViewGroup, depth: Int): LinearLayout? {
            if (depth > 3) return null
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                if (child is LinearLayout) {
                    if (safeResourceEntryName(child) == "container") return child
                    val nested = search(child, depth + 1)
                    if (nested != null) return nested
                    if (child.orientation == LinearLayout.HORIZONTAL) return child
                } else if (child is ViewGroup) {
                    val nested = search(child, depth + 1)
                    if (nested != null) return nested
                }
            }
            return null
        }
        return search(tabHost, 1)
    }

    private fun findVisibleLegacyTabs(tabHost: View): List<View> {
        val container = findLegacyTabContainer(tabHost) ?: return emptyList()
        if (container.childCount <= 0) return emptyList()
        val visible = ArrayList<View>(container.childCount)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child.visibility != View.GONE) {
                visible.add(child)
            }
        }
        return visible
    }

    private fun resolveVisibleTabIndex(
        tabHost: View,
        rawIndex: Int,
        tabView: View? = null
    ): Int {
        val visibleTabs = findVisibleLegacyTabs(tabHost)
        if (visibleTabs.isEmpty()) return rawIndex.coerceAtLeast(0)
        if (tabView != null) {
            val byView = visibleTabs.indexOf(tabView)
            if (byView >= 0) return byView
        }
        val container = findLegacyTabContainer(tabHost)
        if (container != null && rawIndex in 0 until container.childCount) {
            val rawView = container.getChildAt(rawIndex)
            if (rawView != null) {
                val mapped = visibleTabs.indexOf(rawView)
                if (mapped >= 0) return mapped
            }
        }
        return findLegacySelectedIndex(tabHost)
    }

    private fun findLegacyTabCount(tabHost: View): Int {
        val visible = findVisibleLegacyTabs(tabHost)
        if (visible.isNotEmpty()) return visible.size
        val container = findLegacyTabContainer(tabHost)
        if (container != null && container.childCount > 0) {
            return container.childCount
        }
        return 5
    }

    private fun findLegacySelectedIndex(tabHost: View): Int {
        val visible = findVisibleLegacyTabs(tabHost)
        for (i in visible.indices) {
            if (visible[i].isSelected) return i
        }
        return 0
    }

    private fun switchLegacyTabByIndex(
        tabHost: View,
        pill: LiquidGlassPillLayout,
        slotIndex: Int
    ) {
        val visibleTabs = findVisibleLegacyTabs(tabHost)
        if (slotIndex !in visibleTabs.indices) return
        val target = visibleTabs[slotIndex]
        if (isPublishTabView(target)) {
            // Sliding across the center publish button should not pop open the composer modal.
            pill.updateSelection(findLegacySelectedIndex(tabHost), visibleTabs.size)
            return
        }
        val container = findLegacyTabContainer(tabHost)
        val rawIdx = container?.indexOfChild(target)?.takeIf { it >= 0 } ?: slotIndex
        val msg = "Legacy drag tab switch: slot=$slotIndex raw=$rawIdx visible=${visibleTabs.size} " +
            "wasSelected=${target.isSelected} hasClick=${target.hasOnClickListeners()}"
        Log.w(tag, msg)
        xposed.log(Log.WARN, tag, msg)

        val clicked = target.performClick() || triggerClickableChild(target)
        val wInvoked = if (!target.isSelected) invokeLegacyTabHostSelect(tabHost, rawIdx) else false
        if (!target.isSelected) {
            pill.dispatchSyntheticTapAtSlot(slotIndex, visibleTabs.size)
        }
        val resMsg = "Legacy drag result: clicked=$clicked wInvoked=$wInvoked nowSelected=${target.isSelected}"
        Log.w(tag, resMsg)
        xposed.log(Log.WARN, tag, resMsg)
        pill.post {
            pill.updateSelection(findLegacySelectedIndex(tabHost), findLegacyTabCount(tabHost))
            pill.invalidateGlassBackdrop()
        }
    }

    private fun invokeLegacyTabHostSelect(tabHost: View, rawIndex: Int): Boolean {
        return try {
            val m = HostReflection.findMethodExact(
                tabHost.javaClass,
                "w",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            ) ?: return false
            m.invoke(tabHost, rawIndex, true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun triggerClickableChild(root: View): Boolean {
        if (root !is ViewGroup) return false
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child.hasOnClickListeners() && child.performClick()) return true
            if (triggerClickableChild(child)) return true
        }
        return false
    }

    private fun isPublishTabView(view: View, depth: Int = 0): Boolean {
        // Every TabHost item inflates layout 2131494882 which includes a GONE HomeTabPublishView!
        // Only treat a tab slot as the publish button when its HomeTabPublishView is actually VISIBLE.
        if (view.visibility != View.VISIBLE || depth > 3) return false
        val entry = safeResourceEntryName(view)
        if (entry == "publish_bg" || entry == "publish_plus") return true
        if (view.javaClass.simpleName.contains("Publish", ignoreCase = true)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isPublishTabView(view.getChildAt(i) ?: continue, depth + 1)) return true
            }
        }
        return false
    }

    /**
     * Converts stock Bilibili's opaque pink `HomeTabPublishView` square/circle (`@id/publish_bg`,
     * `@id/publish_plus`, `@id/night_shadow`) and hides SVGA/garb bitmap overlays into a frosted
     * Liquid Glass squircle that blends with the floating pill in both day and night modes.
     */
    private fun stylePublishButtonsAsLiquidGlass(tabHost: View, isNight: Boolean) {
        val visibleTabs = findVisibleLegacyTabs(tabHost)
        for (tabItem in visibleTabs) {
            styleSinglePublishContainer(tabItem, isNight)
        }
    }

    private fun styleSinglePublishContainer(root: View, isNight: Boolean) {
        val publishRoot = findPublishRoot(root) ?: (root as? ViewGroup)
        if (publishRoot == null || publishRoot.visibility != View.VISIBLE) return

        val pkg = root.context.packageName
        val res = root.resources
        val bgId = res.getIdentifier("publish_bg", "id", pkg)
        val plusId = res.getIdentifier("publish_plus", "id", pkg)
        val shadowId = res.getIdentifier("night_shadow", "id", pkg)
        if (bgId == 0 && plusId == 0) return

        val publishBg = if (bgId != 0) publishRoot.findViewById<View>(bgId) else null
        val publishPlus = if (plusId != 0) publishRoot.findViewById<View>(plusId) else null
        val nightShadow = if (shadowId != 0) publishRoot.findViewById<View>(shadowId) else null
        if (publishBg == null && publishPlus == null) return

        if (nightShadow != null && nightShadow.visibility != View.GONE) {
            nightShadow.visibility = View.GONE
        }
        hidePublishOverlaySiblings(publishRoot, publishBg, publishPlus)

        val density = res.displayMetrics.density
        if (publishBg != null) {
            if (publishBg.visibility != View.VISIBLE) {
                publishBg.visibility = View.VISIBLE
            }
            val expectedDrawable = styledPublishDrawables[publishBg]
            if (expectedDrawable == null ||
                styledPublishBgState[publishBg] != isNight ||
                publishBg.background !== expectedDrawable
            ) {
                val glassDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setColors(
                        if (isNight) {
                            intArrayOf(0x6E424856, 0x3D1F242D)
                        } else {
                            intArrayOf(0xCCFFFFFF.toInt(), 0x84F4F7FB.toInt())
                        }
                    )
                    setStroke(
                        (1.15f * density).roundToInt().coerceAtLeast(1),
                        if (isNight) 0x52FFFFFF else 0xDAFFFFFF.toInt()
                    )
                }
                styledPublishBgState[publishBg] = isNight
                styledPublishDrawables[publishBg] = glassDrawable
                publishBg.elevation = 0f
                publishBg.background = glassDrawable
            }
        }

        if (publishPlus != null) {
            if (publishPlus.visibility != View.VISIBLE) {
                publishPlus.visibility = View.VISIBLE
            }
            val accent = if (isNight) 0xFFFF80A6.toInt() else 0xFFFB7299.toInt()
            if (publishPlus is ImageView) {
                publishPlus.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            } else if (publishPlus is ViewGroup) {
                for (i in 0 until publishPlus.childCount) {
                    val icon = publishPlus.getChildAt(i) as? ImageView
                    icon?.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
                }
            }
        }
    }

    private fun findPublishRoot(view: View): ViewGroup? {
        if (view.javaClass.name.endsWith("HomeTabPublishView") && view is ViewGroup) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findPublishRoot(view.getChildAt(i) ?: continue)
                if (found != null) return found
            }
        }
        return null
    }

    private fun hidePublishOverlaySiblings(
        publishRoot: ViewGroup,
        publishBg: View?,
        publishPlus: View?
    ) {
        fun isOrContainsTarget(candidate: View): Boolean {
            if (candidate === publishBg || candidate === publishPlus) return true
            if (candidate !is ViewGroup) return false
            if (publishBg != null && isDescendant(candidate, publishBg)) return true
            if (publishPlus != null && isDescendant(candidate, publishPlus)) return true
            return false
        }

        fun walk(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                if (child === publishBg || child === publishPlus) continue
                if (isOrContainsTarget(child)) {
                    if (child is ViewGroup) walk(child)
                } else {
                    if (child.visibility != View.GONE) {
                        child.visibility = View.GONE
                    }
                    if (child.alpha != 0f) {
                        child.alpha = 0f
                    }
                }
            }
        }
        walk(publishRoot)
    }

    private fun isDescendant(ancestor: ViewGroup, target: View): Boolean {
        var cur = target.parent
        while (cur is View) {
            if (cur === ancestor) return true
            cur = cur.parent
        }
        return false
    }

    /**
     * Removes excess top offset/padding so icon+label tab slots align vertically along the
     * floating pill midline alongside the centered `+` button, and deepens light-mode inactive
     * label contrast so dark feed cards underneath remain easy to read.
     */
    private fun centerAndBoostLegacyTabItems(tabHost: View, isNight: Boolean) {
        if (tabHost.paddingTop != 0 || tabHost.paddingBottom != 0) {
            tabHost.setPadding(tabHost.paddingLeft, 0, tabHost.paddingRight, 0)
        }
        val container = findLegacyTabContainer(tabHost) ?: return
        if (container.paddingTop != 0 || container.paddingBottom != 0) {
            container.setPadding(container.paddingLeft, 0, container.paddingRight, 0)
        }
        val containerLp = container.layoutParams
        if (containerLp is ViewGroup.MarginLayoutParams &&
            (containerLp.topMargin != 0 || containerLp.bottomMargin != 0)
        ) {
            containerLp.topMargin = 0
            containerLp.bottomMargin = 0
            container.layoutParams = containerLp
        }

        val density = tabHost.resources.displayMetrics.density
        val minIconPx = (14f * density).roundToInt()
        val visibleTabs = findVisibleLegacyTabs(tabHost)
        for (tabItem in visibleTabs) {
            if (isPublishTabView(tabItem)) {
                if (kotlin.math.abs(tabItem.translationY) > 0.5f) {
                    tabItem.translationY = 0f
                }
                continue
            }

            if (tabItem.height > 0) {
                var minTop = Int.MAX_VALUE
                var maxBottom = Int.MIN_VALUE

                fun scanContent(node: View, offsetY: Int) {
                    if (node.visibility != View.VISIBLE || node.height <= 0) return
                    val absTop = offsetY + node.top
                    val absBottom = offsetY + node.bottom
                    if (node is android.widget.TextView) {
                        if (!node.text.isNullOrBlank()) {
                            if (absTop < minTop) minTop = absTop
                            if (absBottom > maxBottom) maxBottom = absBottom
                            if (!isNight && !tabItem.isSelected && !node.isSelected) {
                                boostLightInactiveLabelColor(node)
                            }
                        }
                        return
                    }
                    if (node is ImageView) {
                        if (node.width >= minIconPx && node.height >= minIconPx) {
                            if (absTop < minTop) minTop = absTop
                            if (absBottom > maxBottom) maxBottom = absBottom
                        }
                        return
                    }
                    if (node is ViewGroup) {
                        for (i in 0 until node.childCount) {
                            val child = node.getChildAt(i) ?: continue
                            scanContent(child, absTop)
                        }
                    }
                }

                if (tabItem is ViewGroup) {
                    for (i in 0 until tabItem.childCount) {
                        val child = tabItem.getChildAt(i) ?: continue
                        scanContent(child, 0)
                    }
                }

                if (maxBottom > minTop) {
                    val contentMidY = (minTop + maxBottom) * 0.5f
                    val targetMidY = tabItem.height * 0.5f
                    val desiredShift = (targetMidY - contentMidY).coerceIn(-7f * density, 3f * density)
                    if (kotlin.math.abs(tabItem.translationY - desiredShift) > 0.5f) {
                        tabItem.translationY = desiredShift
                    }
                }
            }
        }
    }

    private fun boostLightInactiveLabelColor(tv: android.widget.TextView) {
        val c = tv.currentTextColor
        val a = (c ushr 24) and 0xFF
        if (a < 180) return
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        // Deepen washed-out neutral greys (#555..#999) without altering colored/brand accents.
        val isNeutralGrey = kotlin.math.abs(r - g) < 18 && kotlin.math.abs(g - b) < 18 && r in 60..175
        if (isNeutralGrey) {
            tv.setTextColor(0xFF282B30.toInt())
        }
    }

    private fun describeViewTree(view: View, depth: Int = 0): String {
        if (depth > 2) return ""
        val entry = safeResourceEntryName(view)?.let { "#$it" } ?: ""
        val self = "${view.javaClass.simpleName}$entry"
        if (view !is ViewGroup || view.childCount == 0 || depth == 2) return self
        val kids = (0 until view.childCount.coerceAtMost(4)).joinToString(",") {
            describeViewTree(view.getChildAt(it), depth + 1)
        }
        return "$self($kids)"
    }

    private fun wrapBarInFloatingPill(
        coordinator: ViewGroup,
        contentView: View,
        targetBar: View
    ): LiquidGlassPillLayout? {
        wrappedBars[targetBar]?.let { existing ->
            val cfg = configProvider()
            existing.config = cfg
            applyFloatingLayoutParams(existing, cfg)
            return existing
        }

        val parentOfBar = targetBar.parent as? ViewGroup ?: return null
        val index = parentOfBar.indexOfChild(targetBar)
        if (index < 0) return null

        val cfg = configProvider()
        val nightMode = (coordinator.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val pill = LiquidGlassPillLayout(coordinator.context).apply {
            config = cfg
            backdropSourceView = contentView
            isNightTheme = nightMode
            detectedBaseBgColor = if (nightMode) 0xFF17181A.toInt() else Color.WHITE
            elevation = 0f
        }

        coordinator.clipChildren = false
        coordinator.clipToPadding = false

        val lp = buildFloatingLayoutParams(parentOfBar, targetBar.layoutParams, cfg)
        parentOfBar.removeView(targetBar)
        pill.attachTargetBar(targetBar)
        parentOfBar.addView(pill, index, lp)

        wrappedBars[targetBar] = pill
        watcher.attach(coordinator, contentView, pill)
        attachFeedBottomPaddingHelper(contentView, cfg)
        return pill
    }

    private fun applyFloatingLayoutParams(pill: LiquidGlassPillLayout, cfg: GlassConfig) {
        val density = pill.resources.displayMetrics.density
        val lp = pill.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.height = (cfg.barHeightDp * density).roundToInt()
        val hMargin = (cfg.horizontalMarginDp * density).roundToInt()
        lp.leftMargin = hMargin
        lp.rightMargin = hMargin
        lp.bottomMargin = (cfg.bottomMarginDp * density).roundToInt()
        pill.layoutParams = lp
    }

    private fun buildFloatingLayoutParams(
        parent: ViewGroup,
        originalLp: ViewGroup.LayoutParams?,
        cfg: GlassConfig
    ): ViewGroup.MarginLayoutParams {
        val density = parent.resources.displayMetrics.density
        val barH = (cfg.barHeightDp * density).roundToInt()
        val hMargin = (cfg.horizontalMarginDp * density).roundToInt()
        val bMargin = (cfg.bottomMarginDp * density).roundToInt()

        // Reuse the host LayoutParams instance (e.g. CoordinatorLayout.LayoutParams from host ClassLoader)
        // to avoid any ClassLoader mismatch while preserving host CoordinatorLayout behavior/gravity.
        val targetLp = (originalLp as? ViewGroup.MarginLayoutParams)
            ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH)
        targetLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        targetLp.height = barH
        targetLp.leftMargin = hMargin
        targetLp.rightMargin = hMargin
        targetLp.bottomMargin = bMargin
        try {
            val gravityField = targetLp.javaClass.getField("gravity")
            gravityField.setInt(targetLp, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        } catch (_: Throwable) {
        }
        return targetLp
    }

    private fun attachFeedBottomPaddingHelper(contentView: View, cfg: GlassConfig) {
        if (!cfg.adjustFeedPadding) return

        val applyPass = Runnable {
            if (contentView !is ViewGroup) return@Runnable
            val latestCfg = configProvider()
            if (!latestCfg.adjustFeedPadding || !latestCfg.enabled) return@Runnable
            val density = contentView.resources.displayMetrics.density
            val neededClearance = ((latestCfg.barHeightDp + latestCfg.bottomMarginDp + 8f) * density).roundToInt()
            val contentBottom = contentView.height
            if (contentBottom <= 0) return@Runnable
            applyPaddingToBottomScrollables(contentView, contentView, contentBottom, neededClearance)
        }

        contentView.post(applyPass)
        contentView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            contentView.removeCallbacks(applyPass)
            contentView.post(applyPass)
        }
        if (contentView is ViewGroup) {
            contentView.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    contentView.removeCallbacks(applyPass)
                    contentView.post(applyPass)
                }

                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
        }
    }

    private fun applyPaddingToBottomScrollables(
        rootContent: View,
        node: ViewGroup,
        contentHeight: Int,
        neededClearancePx: Int,
        depth: Int = 0
    ) {
        if (depth > 8) return
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? ViewGroup ?: continue
            if (child.visibility != View.VISIBLE) continue
            val isVerticallyScrollable =
                (child.javaClass.name.contains("RecyclerView") ||
                    child.javaClass.name.contains("NestedScrollView") ||
                    child.javaClass.name.endsWith("ScrollView") ||
                    child.javaClass.name.endsWith("ListView")) &&
                (child.canScrollVertically(1) || child.canScrollVertically(-1) ||
                    child.height >= (contentHeight * 0.55f).roundToInt())

            if (isVerticallyScrollable) {
                val relBottom = computeBottomRelativeTo(child, rootContent)
                if (relBottom >= contentHeight - neededClearancePx && !child.canScrollHorizontally(1)) {
                    val base = baselineBottomPaddings.getOrPut(child) { child.paddingBottom }
                    val targetBottom = max(base, neededClearancePx)
                    if (child.paddingBottom != targetBottom || child.clipToPadding) {
                        child.clipToPadding = false
                        child.setPadding(
                            child.paddingLeft,
                            child.paddingTop,
                            child.paddingRight,
                            targetBottom
                        )
                    }
                }
            } else {
                applyPaddingToBottomScrollables(
                    rootContent,
                    child,
                    contentHeight,
                    neededClearancePx,
                    depth + 1
                )
            }
        }
    }

    private fun computeBottomRelativeTo(view: View, ancestor: View): Int {
        var bottom = view.bottom
        var parent = view.parent
        while (parent is View && parent !== ancestor) {
            bottom += parent.top
            parent = parent.parent
        }
        return bottom
    }

    private fun findChildByClassName(group: ViewGroup, suffixOrFull: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            val name = child.javaClass.name
            if (name == suffixOrFull || name.endsWith(".$suffixOrFull")) {
                return child
            }
        }
        return null
    }

    private fun deoptimizeSafe(method: Method?) {
        if (method != null) {
            runCatching { xposed.deoptimize(method) }
        }
    }
}

package io.github.tomorisuki2333.bililiquidglass

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import android.util.Log
import io.github.tomorisuki2333.bililiquidglass.config.GlassConfig
import io.github.tomorisuki2333.bililiquidglass.hook.BiliHomeBarHook
import io.github.tomorisuki2333.bililiquidglass.hook.HostReflection
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern libxposed API 102 entry point for BiliLiquidGlass.
 */
class XposedEntry : XposedModule() {

    private val tag = "BiliLiquidGlass"
    private val appHooked = AtomicBoolean(false)

    @Volatile
    private var processName: String = ""

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        log(
            Log.WARN,
            tag,
            "Loaded in ${param.processName} (libxposed API ${getApiVersion()}, ${getFrameworkName()} ${getFrameworkVersion()})"
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        val pkg = param.packageName
        if (!TARGET_PACKAGES.contains(pkg)) return
        // Restrict UI hooks to the main UI process.
        if (processName.isNotEmpty() && processName != pkg) return

        remotePrefs = try {
            getRemotePreferences(GlassConfig.PREF_GROUP)
        } catch (t: Throwable) {
            log(Log.WARN, tag, "RemotePreferences unavailable, using defaults: ${t.message}")
            null
        }

        if (!appHooked.compareAndSet(false, true)) return

        val callOnCreate = HostReflection.findMethodExact(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            Application::class.java
        )
        if (callOnCreate == null) {
            log(Log.WARN, tag, "Instrumentation.callApplicationOnCreate not found; installing directly.")
            BiliHomeBarHook(this) { GlassConfig.fromPreferences(remotePrefs) }
                .install(param.classLoader)
            return
        }

        hook(callOnCreate).intercept { chain ->
            val result = chain.proceed()
            val app = chain.getArg(0) as? Application
            if (app != null && app.packageName == pkg) {
                val appCl = app.classLoader ?: param.classLoader
                BiliHomeBarHook(this) { GlassConfig.fromPreferences(remotePrefs) }.install(appCl)
            }
            result
        }
    }

    companion object {
        private val TARGET_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "com.bilibili.app.blue",
            "com.bilibili.app.hd"
        )
    }
}

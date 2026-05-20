package com.yhpgi.brightnessfix

import android.content.res.XResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class BrightnessFix : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private val logTag = "BrightnessFix"
    private val minBrightnessInt = 0
    private val minBrightnessFloat = minBrightnessInt / 255f
    private val loggedKeys = ConcurrentHashMap<String, Boolean>()
    private val lastFloatValues = ConcurrentHashMap<String, Float>()

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        logOnce("zygote", "initZygote minBrightnessFloat=$minBrightnessFloat")
        XResources.setSystemWideReplacement(
            "android",
            "integer",
            "config_screenBrightnessSettingMinimum",
            minBrightnessInt
        )

        // Android 16 QPR2 uses the float config for clamping the slider minimum.
        XResources.setSystemWideReplacement(
            "android",
            "dimen",
            "config_screenBrightnessSettingMinimumFloat",
            minBrightnessFloat
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                logOnce("hook_android", "hooking android (system_server)")
                hookSystemServer(lpparam.classLoader)
            }
            "com.android.systemui" -> {
                logOnce("hook_systemui", "hooking com.android.systemui")
                hookSystemUi(lpparam.classLoader)
            }
        }
    }

    private fun hookSystemServer(classLoader: ClassLoader) {
        hookBrightnessInfo(classLoader, "system")
        hookDisplayDeviceConfig(classLoader)
        hookBrightnessClamperController(classLoader)
        hookDisplayBrightnessController(classLoader)
        hookDisplayBrightnessStateBuilder(classLoader)
        hookDisplayBrightnessState(classLoader)
        hookDisplayPowerController(classLoader)
    }

    private fun hookSystemUi(classLoader: ClassLoader) {
        hookBrightnessInfo(classLoader, "systemui")
        hookDisplayBrightnessInfo(classLoader)
        hookDisplayManagerBrightnessSetters(classLoader)
        hookSettingsSystem(classLoader)
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.settings.brightness.BrightnessController",
            classLoader
        ) ?: run {
            logOnce("systemui_controller_missing", "SystemUI BrightnessController not found")
            return
        }

        val hooks = XposedBridge.hookAllMethods(
            controllerClass,
            "updateBrightnessInfo",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    setSystemUiMin(param.thisObject)
                    logOnce("systemui_update", "SystemUI updateBrightnessInfo observed")
                }
            }
        )
        logHookResult("systemui_update_hooks", "SystemUI updateBrightnessInfo", hooks.size)

        if (hooks.isEmpty()) {
            hookSystemUiBrightnessInfoMethods(controllerClass, classLoader)
        }
    }

    private fun hookBrightnessInfo(classLoader: ClassLoader, tag: String) {
        val infoClass = XposedHelpers.findClassIfExists(
            "android.hardware.display.BrightnessInfo",
            classLoader
        ) ?: run {
            logOnce("brightnessinfo_missing_$tag", "BrightnessInfo not found ($tag)")
            return
        }

        val hooks = XposedBridge.hookAllConstructors(
            infoClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    logBrightnessInfoValues("brightnessinfo_$tag", param.thisObject)
                    if (clampBrightnessInfoMin(param.thisObject)) {
                        logOnce("brightnessinfo_adjusted_$tag", "BrightnessInfo min adjusted ($tag)")
                    }
                }
            }
        )
        logHookResult("brightnessinfo_hooks_$tag", "BrightnessInfo constructors ($tag)", hooks.size)
    }

    private fun hookDisplayBrightnessInfo(classLoader: ClassLoader) {
        val displayClass = XposedHelpers.findClassIfExists(
            "android.view.Display",
            classLoader
        )
        if (displayClass != null) {
            val hooks = XposedBridge.hookAllMethods(
                displayClass,
                "getBrightnessInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logBrightnessInfoValues("display_get", param.result)
                        val replaced = replaceBrightnessInfoMin(param.result, "display_get")
                        if (replaced != null) {
                            param.result = replaced
                            logOnce("display_brightnessinfo", "Display.getBrightnessInfo adjusted")
                        }
                    }
                }
            )
            logHookResult("display_brightnessinfo_hooks", "Display#getBrightnessInfo", hooks.size)
        } else {
            logOnce("display_missing", "Display class not found in SystemUI")
        }

        val managerClass = XposedHelpers.findClassIfExists(
            "android.hardware.display.DisplayManager",
            classLoader
        )
        if (managerClass != null) {
            val hooks = XposedBridge.hookAllMethods(
                managerClass,
                "getBrightnessInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logBrightnessInfoValues("displaymanager_get", param.result)
                        val replaced = replaceBrightnessInfoMin(param.result, "displaymanager_get")
                        if (replaced != null) {
                            param.result = replaced
                            logOnce("dm_brightnessinfo", "DisplayManager.getBrightnessInfo adjusted")
                        }
                    }
                }
            )
            logHookResult("dm_brightnessinfo_hooks", "DisplayManager#getBrightnessInfo", hooks.size)
        } else {
            logOnce("displaymanager_missing", "DisplayManager not found in SystemUI")
        }
    }

    private fun hookDisplayManagerBrightnessSetters(classLoader: ClassLoader) {
        val managerClass = XposedHelpers.findClassIfExists(
            "android.hardware.display.DisplayManager",
            classLoader
        ) ?: run {
            logOnce("displaymanager_missing_setters", "DisplayManager not found for setters")
            return
        }

        var count = 0
        for (method in managerClass.declaredMethods) {
            if (!method.name.contains("Brightness", ignoreCase = true)) continue
            if (!method.parameterTypes.any { it == Float::class.javaPrimitiveType || it == Float::class.java }) {
                continue
            }
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val floatArgs = param.args.mapIndexedNotNull { index, arg ->
                            if (arg is Float) "$index=$arg" else null
                        }
                        if (floatArgs.isNotEmpty()) {
                            val firstValue = param.args.firstOrNull { it is Float } as? Float
                            val message = "DisplayManager.${method.name} floatArgs=${floatArgs.joinToString()}"
                            if (firstValue != null) {
                                logWhenValueChanges("dm_set_${method.name}", firstValue, message)
                            } else {
                                XposedBridge.log("$logTag: $message")
                            }
                        }
                    }
                }
            )
            count += 1
        }

        logOnce("displaymanager_setters", "DisplayManager brightness setters hooked ($count)")
    }

    private fun hookSystemUiBrightnessInfoMethods(
        controllerClass: Class<*>,
        classLoader: ClassLoader
    ) {
        val brightnessInfoClass = XposedHelpers.findClassIfExists(
            "android.hardware.display.BrightnessInfo",
            classLoader
        ) ?: run {
            logOnce("systemui_brightnessinfo_missing", "SystemUI BrightnessInfo not found")
            return
        }

        val methods = controllerClass.declaredMethods.filter { method ->
            method.parameterTypes.any { it == brightnessInfoClass }
        }

        if (methods.isEmpty()) {
            logOnce(
                "systemui_brightnessinfo_no_methods",
                "SystemUI BrightnessInfo methods not found"
            )
            return
        }

        for (method in methods) {
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        setSystemUiMin(param.thisObject)
                        logOnce(
                            "systemui_brightnessinfo_called_${method.name}",
                            "SystemUI ${method.name} observed"
                        )
                    }
                }
            )
        }

        logOnce(
            "systemui_brightnessinfo_hooks",
            "SystemUI BrightnessInfo hooks (${methods.size})"
        )
    }

    private fun hookDisplayDeviceConfig(classLoader: ClassLoader) {
        val configClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.DisplayDeviceConfig",
            classLoader
        ) ?: run {
            logOnce("ddc_missing", "DisplayDeviceConfig not found")
            return
        }

        val minHooks = XposedBridge.hookAllMethods(
            configClass,
            "getBrightnessMinimum",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val currentMin = param.result as? Float ?: return
                    if (currentMin > minBrightnessFloat) {
                        param.result = minBrightnessFloat
                    }
                    logOnce("ddc_min", "DisplayDeviceConfig#getBrightnessMinimum current=$currentMin")
                }
            }
        )
        logHookResult("ddc_min_hooks", "DisplayDeviceConfig#getBrightnessMinimum", minHooks.size)

        val backlightHooks = XposedBridge.hookAllMethods(
            configClass,
            "getBacklightFromBrightness",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val brightness = param.args.getOrNull(0) as? Float ?: return
                    val backlight = param.result as? Float ?: return
                    if (brightness <= minBrightnessFloat && backlight > brightness) {
                        param.result = brightness
                        logOnce(
                            "ddc_backlight",
                            "getBacklightFromBrightness brightness=$brightness backlight=$backlight"
                        )
                    }
                }
            }
        )
        logHookResult(
            "ddc_backlight_hooks",
            "DisplayDeviceConfig#getBacklightFromBrightness",
            backlightHooks.size
        )
    }

    private fun hookBrightnessClamperController(classLoader: ClassLoader) {
        val clamperClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.brightness.clamper.BrightnessClamperController",
            classLoader
        ) ?: run {
            logOnce("clamper_missing", "BrightnessClamperController not found")
            return
        }

        val hooks = XposedBridge.hookAllMethods(
            clamperClass,
            "getMinBrightness",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val currentMin = param.result as? Float ?: return
                    if (currentMin > minBrightnessFloat) {
                        param.result = minBrightnessFloat
                    }
                    logWhenValueChanges("clamper_min", currentMin, "getMinBrightness currentMin=$currentMin")
                }
            }
        )
        logHookResult("clamper_hooks", "BrightnessClamperController#getMinBrightness", hooks.size)
    }

    private fun hookDisplayBrightnessController(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.brightness.DisplayBrightnessController",
            classLoader
        ) ?: run {
            logOnce("dbc_missing", "DisplayBrightnessController not found")
            return
        }

        val hooks = XposedBridge.hookAllMethods(
            controllerClass,
            "updateScreenBrightnessSetting",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    logUpdateScreenBrightnessArgsOnce(param)
                    val minArg = param.args.getOrNull(1) as? Float ?: return
                    if (minArg > minBrightnessFloat) {
                        param.args[1] = minBrightnessFloat
                    }
                    logWhenValueChanges("dbc_min_arg", minArg, "updateScreenBrightnessSetting minArg=$minArg")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    setFloatFieldIfHigher(
                        param.thisObject,
                        "mCurrentMinBrightness",
                        minBrightnessFloat
                    )
                }
            }
        )
        logHookResult("dbc_hooks", "DisplayBrightnessController#updateScreenBrightnessSetting", hooks.size)
    }

    private fun hookDisplayBrightnessStateBuilder(classLoader: ClassLoader) {
        val builderClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.DisplayBrightnessState\$Builder",
            classLoader
        ) ?: run {
            logOnce("dbs_builder_missing", "DisplayBrightnessState.Builder not found")
            return
        }

        val methodNames = listOf(
            "setMinBrightness",
            "setMinimumBrightness",
            "setBrightnessMin",
            "setBrightnessMinimum"
        )

        var hookedAny = false
        for (methodName in methodNames) {
            val hooks = XposedBridge.hookAllMethods(
                builderClass,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val minArg = param.args.getOrNull(0) as? Float ?: return
                        if (minArg > minBrightnessFloat) {
                            param.args[0] = minBrightnessFloat
                        }
                        logWhenValueChanges(
                            "dbs_builder_min_arg",
                            minArg,
                            "DisplayBrightnessState.Builder $methodName minArg=$minArg"
                        )
                    }
                }
            )
            if (hooks.isNotEmpty()) {
                hookedAny = true
                logHookResult("dbs_builder_$methodName", "DisplayBrightnessState.Builder#$methodName", hooks.size)
            }
        }

        if (!hookedAny) {
            logOnce("dbs_builder_no_methods", "No min setter found on DisplayBrightnessState.Builder")
        }

        val buildHooks = XposedBridge.hookAllMethods(
            builderClass,
            "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    setFloatFieldIfHigher(param.thisObject, "mMinBrightness", minBrightnessFloat)
                    setFloatFieldIfHigher(param.thisObject, "mBrightnessMin", minBrightnessFloat)
                    try {
                        val currentMin = XposedHelpers.getFloatField(param.thisObject, "mMinBrightness")
                        logWhenValueChanges(
                            "dbs_builder_build",
                            currentMin,
                            "DisplayBrightnessState.Builder#build min=$currentMin"
                        )
                    } catch (_: Throwable) {
                        logOnce("dbs_builder_build", "DisplayBrightnessState.Builder#build observed")
                    }
                }
            }
        )
        logHookResult("dbs_builder_build_hooks", "DisplayBrightnessState.Builder#build", buildHooks.size)
    }

    private fun hookDisplayBrightnessState(classLoader: ClassLoader) {
        val stateClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.DisplayBrightnessState",
            classLoader
        ) ?: run {
            logOnce("dbs_missing", "DisplayBrightnessState not found")
            return
        }

        val hooks = XposedBridge.hookAllMethods(
            stateClass,
            "getMinBrightness",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val currentMin = param.result as? Float ?: return
                    if (currentMin > minBrightnessFloat) {
                        param.result = minBrightnessFloat
                    }
                    logWhenValueChanges(
                        "dbs_min",
                        currentMin,
                        "DisplayBrightnessState#getMinBrightness current=$currentMin"
                    )
                }
            }
        )
        logHookResult("dbs_min_hooks", "DisplayBrightnessState#getMinBrightness", hooks.size)
    }

    private fun hookDisplayPowerController(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.DisplayPowerController",
            classLoader
        ) ?: run {
            logOnce("dpc_missing", "DisplayPowerController not found")
            return
        }

        val hooks = XposedBridge.hookAllMethods(
            controllerClass,
            "clampScreenBrightness",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requested = param.args.getOrNull(0) as? Float ?: return
                    val clamped = param.result as? Float ?: return
                    if (requested < minBrightnessFloat) {
                        param.result = minBrightnessFloat
                        return
                    }
                    if (requested < clamped) {
                        param.result = requested
                    }
                    logWhenValueChanges(
                        "dpc_requested",
                        requested,
                        "clampScreenBrightness requested=$requested clamped=$clamped"
                    )
                }
            }
        )
        logHookResult("dpc_hooks", "DisplayPowerController#clampScreenBrightness", hooks.size)
    }

    private fun hookSettingsSystem(classLoader: ClassLoader) {
        val settingsClass = XposedHelpers.findClassIfExists(
            "android.provider.Settings\$System",
            classLoader
        ) ?: run {
            logOnce("settings_missing", "Settings.System not found")
            return
        }

        val floatHooks = XposedBridge.hookAllMethods(
            settingsClass,
            "putFloat",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = findStringArg(param.args) ?: return
                    if (key != "screen_brightness_float") return
                    val value = findFloatArg(param.args) ?: return
                    logWhenValueChanges(
                        "settings_brightness_float",
                        value,
                        "Settings.System.putFloat screen_brightness_float=$value"
                    )
                }
            }
        )
        logHookResult("settings_putfloat", "Settings.System#putFloat", floatHooks.size)

        val floatUserHooks = XposedBridge.hookAllMethods(
            settingsClass,
            "putFloatForUser",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = findStringArg(param.args) ?: return
                    if (key != "screen_brightness_float") return
                    val value = findFloatArg(param.args) ?: return
                    logWhenValueChanges(
                        "settings_brightness_float_user",
                        value,
                        "Settings.System.putFloatForUser screen_brightness_float=$value"
                    )
                }
            }
        )
        logHookResult("settings_putfloat_user", "Settings.System#putFloatForUser", floatUserHooks.size)

        val intHooks = XposedBridge.hookAllMethods(
            settingsClass,
            "putInt",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = findStringArg(param.args) ?: return
                    if (key != "screen_brightness") return
                    val value = findIntArg(param.args) ?: return
                    logWhenValueChanges(
                        "settings_brightness_int",
                        value.toFloat(),
                        "Settings.System.putInt screen_brightness=$value"
                    )
                }
            }
        )
        logHookResult("settings_putint", "Settings.System#putInt", intHooks.size)

        val intUserHooks = XposedBridge.hookAllMethods(
            settingsClass,
            "putIntForUser",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = findStringArg(param.args) ?: return
                    if (key != "screen_brightness") return
                    val value = findIntArg(param.args) ?: return
                    logWhenValueChanges(
                        "settings_brightness_int_user",
                        value.toFloat(),
                        "Settings.System.putIntForUser screen_brightness=$value"
                    )
                }
            }
        )
        logHookResult("settings_putint_user", "Settings.System#putIntForUser", intUserHooks.size)
    }

    private fun setFloatFieldIfHigher(target: Any?, fieldName: String, minValue: Float) {
        trySetFloatFieldIfHigher(target, fieldName, minValue)
    }

    private fun trySetFloatFieldIfHigher(target: Any?, fieldName: String, minValue: Float): Boolean {
        if (target == null) return false
        return try {
            val currentValue = XposedHelpers.getFloatField(target, fieldName)
            if (currentValue > minValue) {
                XposedHelpers.setFloatField(target, fieldName, minValue)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun clampBrightnessInfoMin(info: Any?): Boolean {
        var changed = false
        changed = trySetFloatFieldIfHigher(info, "brightnessMinimum", minBrightnessFloat) || changed
        changed = trySetFloatFieldIfHigher(info, "mBrightnessMinimum", minBrightnessFloat) || changed
        changed = trySetFloatFieldIfHigher(info, "mMinimumBrightness", minBrightnessFloat) || changed
        return changed
    }

    private fun replaceBrightnessInfoMin(info: Any?, source: String): Any? {
        if (info == null) return null
        val infoClass = info.javaClass
        if (infoClass.name != "android.hardware.display.BrightnessInfo") return null

        val brightness = getBrightnessInfoValue(info, listOf("brightness", "mBrightness"))
        val adjusted = getBrightnessInfoValue(info, listOf("adjustedBrightness", "mAdjustedBrightness"))
        val currentMin = getBrightnessInfoValue(
            info,
            listOf("brightnessMinimum", "mBrightnessMinimum", "mMinimumBrightness")
        )
        val max = getBrightnessInfoValue(info, listOf("brightnessMaximum", "mBrightnessMaximum"))
        val hbm = getIntField(info, listOf("highBrightnessMode", "mHighBrightnessMode"))
        val transition = getBrightnessInfoValue(
            info,
            listOf("highBrightnessTransitionPoint", "mHighBrightnessTransitionPoint")
        )
        val maxReason = getIntField(info, listOf("brightnessMaxReason", "mBrightnessMaxReason"))
        val overrideByWindow = getBooleanField(
            info,
            listOf("isBrightnessOverrideByWindow", "mIsBrightnessOverrideByWindow")
        )

        if (brightness.isNaN() || max.isNaN() || transition.isNaN()) {
            logOnce("brightnessinfo_missing_$source", "BrightnessInfo fields missing ($source)")
            return null
        }

        val newMin = if (!currentMin.isNaN() && currentMin <= minBrightnessFloat) {
            currentMin
        } else {
            minBrightnessFloat
        }

        return try {
            XposedHelpers.newInstance(
                infoClass,
                brightness,
                if (adjusted.isNaN()) brightness else adjusted,
                newMin,
                max,
                hbm,
                transition,
                maxReason,
                overrideByWindow
            )
        } catch (_: Throwable) {
            try {
                XposedHelpers.newInstance(
                    infoClass,
                    brightness,
                    newMin,
                    max,
                    hbm,
                    transition,
                    maxReason
                )
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun logBrightnessInfoValues(keyPrefix: String, info: Any?) {
        val min = getBrightnessInfoValue(info, listOf("brightnessMinimum", "mBrightnessMinimum"))
        if (!min.isNaN()) {
            logWhenValueChanges("${keyPrefix}_min", min, "$keyPrefix brightnessMinimum=$min")
        }
        val max = getBrightnessInfoValue(info, listOf("brightnessMaximum", "mBrightnessMaximum"))
        if (!max.isNaN()) {
            logWhenValueChanges("${keyPrefix}_max", max, "$keyPrefix brightnessMaximum=$max")
        }
        val brightness = getBrightnessInfoValue(info, listOf("brightness", "mBrightness"))
        if (!brightness.isNaN()) {
            logWhenValueChanges("${keyPrefix}_brightness", brightness, "$keyPrefix brightness=$brightness")
        }
    }

    private fun getBrightnessInfoValue(info: Any?, fieldNames: List<String>): Float {
        if (info == null) return Float.NaN
        for (name in fieldNames) {
            try {
                return XposedHelpers.getFloatField(info, name)
            } catch (_: Throwable) {
            }
        }
        return Float.NaN
    }

    private fun getIntField(info: Any?, fieldNames: List<String>): Int {
        if (info == null) return 0
        for (name in fieldNames) {
            try {
                return XposedHelpers.getIntField(info, name)
            } catch (_: Throwable) {
            }
        }
        return 0
    }

    private fun getBooleanField(info: Any?, fieldNames: List<String>): Boolean {
        if (info == null) return false
        for (name in fieldNames) {
            try {
                return XposedHelpers.getBooleanField(info, name)
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun findStringArg(args: Array<Any?>): String? {
        for (arg in args) {
            if (arg is String) return arg
        }
        return null
    }

    private fun findFloatArg(args: Array<Any?>): Float? {
        for (arg in args) {
            when (arg) {
                is Float -> return arg
                is Double -> return arg.toFloat()
            }
        }
        return null
    }

    private fun findIntArg(args: Array<Any?>): Int? {
        for (arg in args) {
            if (arg is Int) return arg
        }
        return null
    }

    private fun logUpdateScreenBrightnessArgsOnce(param: XC_MethodHook.MethodHookParam) {
        val floatArgs = param.args.mapIndexedNotNull { index, arg ->
            if (arg is Float) "$index=$arg" else null
        }
        if (floatArgs.isNotEmpty()) {
            logOnce("dbc_args", "updateScreenBrightnessSetting floatArgs=${floatArgs.joinToString()}")
        }
    }

    private fun setSystemUiMin(target: Any?) {
        setFloatFieldIfHigher(target, "mBrightnessMin", minBrightnessFloat)
        setFloatFieldIfHigher(target, "mMinimumBrightness", minBrightnessFloat)
        setFloatFieldIfHigher(target, "mMinBrightness", minBrightnessFloat)
        setFloatFieldIfHigher(target, "mBrightnessMinimum", minBrightnessFloat)
    }

    private fun logHookResult(key: String, label: String, hookCount: Int) {
        val status = if (hookCount > 0) "hooked" else "no methods"
        logOnce(key, "$label $status ($hookCount)")
    }

    private fun logOnce(key: String, message: String) {
        if (loggedKeys.putIfAbsent(key, true) == null) {
            XposedBridge.log("$logTag: $message")
        }
    }

    private fun logWhenValueChanges(key: String, value: Float, message: String) {
        if (value.isNaN()) return
        val prev = lastFloatValues.put(key, value)
        if (prev == null || abs(prev - value) > 0.0001f) {
            XposedBridge.log("$logTag: $message")
        }
    }

}
package com.yhpgi.brightnessfix

import android.content.res.XResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

class BrightnessFix : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private val logTag = "BrightnessFix"
    private val minBrightnessInt = 1
    private val minBrightnessFloat = minBrightnessInt / 255f
    private val loggedKeys = ConcurrentHashMap<String, Boolean>()

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
        hookDisplayDeviceConfig(classLoader)
        hookBrightnessClamperController(classLoader)
        hookDisplayBrightnessController(classLoader)
        hookDisplayBrightnessStateBuilder(classLoader)
        hookDisplayBrightnessState(classLoader)
        hookDisplayPowerController(classLoader)
    }

    private fun hookSystemUi(classLoader: ClassLoader) {
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
                    logOnce("clamper_called", "getMinBrightness currentMin=$currentMin")
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
                    val minArg = param.args.getOrNull(1) as? Float ?: return
                    if (minArg > minBrightnessFloat) {
                        param.args[1] = minBrightnessFloat
                    }
                    logOnce("dbc_called", "updateScreenBrightnessSetting minArg=$minArg")
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
                        logOnce("dbs_builder_called", "DisplayBrightnessState.Builder $methodName minArg=$minArg")
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
                        logOnce("dbs_builder_build", "DisplayBrightnessState.Builder#build min=$currentMin")
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
                    logOnce("dbs_min", "DisplayBrightnessState#getMinBrightness current=$currentMin")
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
                    logOnce("dpc_called", "clampScreenBrightness requested=$requested clamped=$clamped")
                }
            }
        )
        logHookResult("dpc_hooks", "DisplayPowerController#clampScreenBrightness", hooks.size)
    }

    private fun setFloatFieldIfHigher(target: Any?, fieldName: String, minValue: Float) {
        if (target == null) return
        try {
            val currentValue = XposedHelpers.getFloatField(target, fieldName)
            if (currentValue > minValue) {
                XposedHelpers.setFloatField(target, fieldName, minValue)
            }
        } catch (_: Throwable) {
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

}
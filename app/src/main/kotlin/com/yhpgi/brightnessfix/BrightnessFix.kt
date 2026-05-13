package com.yhpgi.brightnessfix

import android.content.res.XResources
import de.robv.android.xposed.IXposedHookZygoteInit

class BrightnessFix : IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        val minBrightnessInt = 1
        val minBrightnessFloat = minBrightnessInt / 255f

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

}
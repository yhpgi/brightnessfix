package com.yhpgi.brightnessfix

import android.content.res.XResources
import de.robv.android.xposed.IXposedHookZygoteInit

class BrightnessFix : IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        XResources.setSystemWideReplacement(
            "android",
            "integer",
            "config_screenBrightnessSettingMinimum",
            1
        )
    }

}
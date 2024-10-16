# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Proguard for Xposed.
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources
-keep class * extends de.robv.android.xposed.XC_MethodHook
-keep class * extends de.robv.android.xposed.XC_MethodReplacement

-keepclassmembers class * implements de.robv.android.xposed.IXposedHookZygoteInit {
   public void initZygote(de.robv.android.xposed.IXposedHookZygoteInit.StartupParam);
}

-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage {
   public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam);
}

-keepclassmembers class * implements de.robv.android.xposed.IXposedHookInitPackageResources {
   public void handleInitPackageResources(de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam);
}

-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
   protected void beforeHookedMethod(de.robv.android.xposed.XC_MethodHook.MethodHookParam);
   protected void afterHookedMethod(de.robv.android.xposed.XC_MethodHook.MethodHookParam);
}

-keepclassmembers class * extends de.robv.android.xposed.XC_MethodReplacement {
   protected void replaceHookedMethod(de.robv.android.xposed.XC_MethodHook.MethodHookParam);
}

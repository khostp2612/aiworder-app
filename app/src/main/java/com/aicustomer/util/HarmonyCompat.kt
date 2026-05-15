package com.aicustomer.util

import android.os.Build

object HarmonyCompat {
    private val isHarmony: Boolean by lazy {
        try {
            val clz = Class.forName("com.huawei.system.BuildEx")
            val brand = clz.getMethod("getOsBrand").invoke(null) as? String
            brand == "harmony"
        } catch (_: Exception) {
            false
        }
    }

    fun isHarmonyOS(): Boolean = isHarmony

    fun shouldUseLegacyPackaging(): Boolean {
        return isHarmony && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
    }

    fun shouldDisableOverscroll(): Boolean = isHarmony
}

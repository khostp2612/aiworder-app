package com.aicustomer.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

data class DeviceTier(
    val name: String,
    val ramGB: Int,
    val hasVulkan: Boolean,
    val recommendedQuant: String,
    val modelFile: String,
    val maxContextSize: Int
) {
    val supportsSpectralGate: Boolean get() = ramGB >= 6
    val supportsBargeSpectral: Boolean get() = ramGB >= 4

    companion object {
        const val TAG = "DeviceProfile"

        fun detect(context: Context): DeviceTier {
            val ramGB = getTotalRamGB(context)
            val hasVulkan = detectVulkan()
            val cores = Runtime.getRuntime().availableProcessors()

            Log.i(TAG, "Device: ram=$ramGB GB, vulkan=$hasVulkan, cores=$cores")

            return when {
                ramGB >= 8 -> DeviceTier(
                    name = "旗舰",
                    ramGB = ramGB,
                    hasVulkan = hasVulkan,
                    recommendedQuant = "Q4_K_M",
                    modelFile = "Qwen3-1.7B-Q4_K_M.gguf",
                    maxContextSize = 8192
                )
                ramGB >= 4 -> DeviceTier(
                    name = "标准",
                    ramGB = ramGB,
                    hasVulkan = hasVulkan,
                    recommendedQuant = "Q4_K_M",
                    modelFile = "Qwen3-0.6B-Q8_0.gguf",
                    maxContextSize = 2048
                )
                else -> DeviceTier(
                    name = "低端",
                    ramGB = ramGB,
                    hasVulkan = false,
                    recommendedQuant = "not_supported",
                    modelFile = "",
                    maxContextSize = 1024
                )
            }
        }

        private fun getTotalRamGB(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                return ((memInfo.totalMem / (1024 * 1024 * 1024)).toInt())
            }
            return 4
        }

        private fun detectVulkan(): Boolean {
            try {
                val pm = Class.forName("android.os.SystemProperties")
                val get = pm.getMethod("get", String::class.java, String::class.java)
                val roHardware = get.invoke(null, "ro.hardware.vulkan", "") as? String
                if (roHardware?.isNotBlank() == true) return true
                val gpuRenderer = get.invoke(null, "ro.hardware.egl", "") as? String
                return gpuRenderer == "adreno"
            } catch (_: Exception) {
                return false
            }
        }
    }
}

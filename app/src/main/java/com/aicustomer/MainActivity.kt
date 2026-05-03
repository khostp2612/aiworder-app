package com.aicustomer

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aicustomer.ui.navigation.AppNavigation
import com.aicustomer.ui.theme.AICustomerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关闭过度滚动拉伸效果，兼容HarmonyOS Skia渲染管线
        // 华为/鸿蒙的HWUI渲染器在处理StretchOverscrollNonClippingLayer时会崩溃
        window.decorView.overScrollMode = View.OVER_SCROLL_NEVER
        setContent {
            AICustomerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

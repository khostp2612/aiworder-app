package com.aicustomer.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aicustomer.ui.chat.ChatScreen
import com.aicustomer.ui.identity.IdentitySetupScreen
import com.aicustomer.ui.memory.MemoryBrowserScreen
import com.aicustomer.ui.models.ModelDownloadScreen
import com.aicustomer.ui.voice.VoiceCallScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("chat") {
                ChatScreen(
                    onNavigateToIdentity = { navController.navigate("identity") },
                    onNavigateToMemory = { navController.navigate("memory") },
                    onNavigateToModels = { navController.navigate("models") },
                    onNavigateToVoice = { navController.navigate("voice_call") }
                )
            }
            composable("identity") {
                IdentitySetupScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("memory") {
                MemoryBrowserScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("models") {
                ModelDownloadScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("voice_call") {
                VoiceCallScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

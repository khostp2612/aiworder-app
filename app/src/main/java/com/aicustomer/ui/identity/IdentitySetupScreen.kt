package com.aicustomer.ui.identity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicustomer.data.model.Identity
import com.aicustomer.ui.viewmodel.IdentityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySetupScreen(
    onBack: () -> Unit,
    viewModel: IdentityViewModel = viewModel(factory = IdentityViewModel.Factory(LocalContext.current.applicationContext as android.app.Application))
) {
    val identities by viewModel.identities.collectAsState()
    val activeIdentity by viewModel.activeIdentity.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val presets = viewModel.presets

    var selectedIdentity by remember { mutableStateOf<Identity?>(activeIdentity) }
    var previousSelection by remember { mutableStateOf<Identity?>(null) }
    var showCustomForm by remember { mutableStateOf(false) }

    var customName by remember { mutableStateOf("") }
    var customPersonality by remember { mutableStateOf("") }
    var customStyle by remember { mutableStateOf("") }
    var customExpertise by remember { mutableStateOf("") }
    var customTaboos by remember { mutableStateOf("") }
    var customGreeting by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("身份设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("选择AI的身份角色", style = MaterialTheme.typography.headlineSmall)
            Text("身份决定了AI的性格、说话风格和专业领域", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 第一排：内置预设身份
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(presets, key = { "preset_${it.name}" }) { identity ->
                    IdentityCard(
                        identity = identity,
                        isSelected = selectedIdentity?.name == identity.name && selectedIdentity?.id == 0L,
                        onSelect = {
                            selectedIdentity = identity
                            showCustomForm = false
                        }
                    )
                }
            }

            // 第二排：用户创建的自定义身份（排除预设同名）
            val presetNames = presets.map { it.name }.toSet()
            val customIdentities = identities.filter { it.id > 0L && it.name !in presetNames }
            if (customIdentities.isNotEmpty()) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("我的身份", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(customIdentities, key = { it.id }) { identity ->
                        IdentityCard(
                            identity = identity,
                            isSelected = selectedIdentity?.id == identity.id,
                            onSelect = {
                                selectedIdentity = identity
                                showCustomForm = false
                            },
                            onDelete = { viewModel.deleteIdentity(identity) }
                        )
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedButton(
                onClick = {
                    showCustomForm = !showCustomForm
                    if (showCustomForm) {
                        previousSelection = selectedIdentity
                        selectedIdentity = null
                    } else {
                        selectedIdentity = previousSelection ?: activeIdentity
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (showCustomForm) "收起自定义" else "+ 创建自定义身份")
            }

            if (showCustomForm) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = customName, onValueChange = { if (it.length <= 20) customName = it }, label = { Text("角色名 *") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
                    OutlinedTextField(value = customPersonality, onValueChange = { if (it.length <= 50) customPersonality = it }, label = { Text("性格特征") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = customStyle, onValueChange = { if (it.length <= 50) customStyle = it }, label = { Text("说话风格") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = customExpertise, onValueChange = { if (it.length <= 50) customExpertise = it }, label = { Text("专业领域") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = customGreeting, onValueChange = { if (it.length <= 100) customGreeting = it }, label = { Text("开场白") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = customTaboos, onValueChange = { if (it.length <= 50) customTaboos = it }, label = { Text("禁忌话题（可选）") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (showCustomForm && customName.isNotBlank()) {
                        viewModel.createCustomIdentity(customName, customPersonality, customStyle, customExpertise, customGreeting, customTaboos)
                        onBack()
                    } else {
                        selectedIdentity?.let { viewModel.activateIdentity(it) }
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isLoading && (selectedIdentity != null || (showCustomForm && customName.isNotBlank()))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("确认身份")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityCard(
    identity: Identity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(identity.name, style = MaterialTheme.typography.titleMedium, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    }
                }
            }
            Text(identity.personality, style = MaterialTheme.typography.bodySmall, color = (if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f))
            Text(identity.expertise, style = MaterialTheme.typography.labelSmall, color = (if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.5f))
        }
    }
}

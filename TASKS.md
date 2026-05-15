# aiworder 任务清单

## 已完成

### 全离线化改造
- [x] 删除 XfyunAsrProvider / CfAsrProvider / AsrProvider 接口
- [x] 删除 OpenAiClient / MiniCpmSpeechClient
- [x] 删除 DeepSeekConfigCard / OpenAiConfigCard / MiniCpmConfigCard / AsrProviderSection / SharedComponents
- [x] VoicePipeline 本地化：VAD(VadDetector) + STT(SttEngine) 替换云端 ASR
- [x] VoiceCallViewModel 本地化：LlmEngine.generateStream() 替换 OpenAiClient
- [x] ChatViewModel 去云端化：移除 generateCloudResponse 和所有云端路由
- [x] SecureStorage 精简：移除所有 API Key 存储
- [x] ModelsViewModel 精简：移除所有云端 StateFlow
- [x] ModelDownloadScreen 精简：移除所有云端配置卡片
- [x] EmbeddingEngine 去云端化：移除 OkHttp + 云端嵌入回退
- [x] build.gradle.kts: 移除 OkHttp + security-crypto 依赖
- [x] AndroidManifest: 移除 ACCESS_NETWORK_STATE
- [x] App.kt: 移除 SecureStorage 初始化

### MiniCPM-o 端侧推理
- [x] llm_bridge.cpp 适配 MiniCPM-o GGUF (system prompt + chat template)
- [x] LlmEngine.kt GPU layer 自适应 (Vulkan 99 / CPU 0)
- [x] ModelManager.kt 替换 Qwen 为 MiniCPM-o
- [x] 移除内置 Qwen3-0.6B GGUF，改为引导下载
- [x] 多设备分级 (DeviceProfile.kt)

### Vulkan GPU 加速
- [x] build.gradle.kts 启用 GGML_VULKAN=ON (实际修复: 原为 OFF，现已改为 ON)
- [x] Vulkan 设备检测 + 回退

### 语音交互链路修复 (2026-05-13)
- [x] Sampler: greedy → temperature+top_p+top_k+min_p chain (修复回复多样性)
- [x] SttEngine: 统一 filesDir/assets 模型加载路径
- [x] VadDetector: 加固模型路径检查 + API 调用
- [x] VoicePipeline: isAsrReady() 改为真实状态，修复初始化顺序
- [x] ModelManager: 添加 mtmd 音频编码器下载配置
- [ ] VITS 离线 TTS 集成到 VoicePipeline

### 已知限制
- [ ] GGML_VULKAN=ON 需要 NDK 环境支持 glslc 编译器；若 CMake 配置失败，改回 OFF 或安装 Vulkan SDK
- [ ] mtmd 音频编码器 GGUF 需手动从 MiniCPM-o-2_6 项目中提取并转换为 GGUF

### 去鸿蒙化
- [x] HarmonyCompat.kt 条件化鸿蒙适配
- [x] MainActivity overscroll 条件化
- [x] InferenceService foregroundServiceType 去鸿蒙注释
- [x] AndroidManifest 移除华为专属权限
- [x] build.gradle.kts 关闭 useLegacyPackaging
- [x] App.kt 崩溃重启限制弹性化

## 待完成

- [ ] 编译验证通过 (./gradlew assembleDebug)
- [ ] Vulkan 后端稳定性测试（Adreno 740）
- [x] MiniCPM-o GGUF chat template 实际验证 (sampler chain 已修复)
- [ ] 本地 STT+VAD 语音管线端到端测试
- [ ] LlmEngine 流式生成 + TTS 队列对齐测试
- [ ] 打断逻辑（abort LLM + TTS）响应速度验证
# Native 层架构说明

## llm_bridge.cpp (活跃)

JNI 桥接 llama.cpp，被 `CMakeLists.txt` 编译为 `libllm_bridge.so`。

### 关键设计
- 全局单例 `GenerationContext`，互斥锁保护，同一时刻仅允许一个推理任务
- 使用 `common_chat_templates` (Jinja) 格式化对话模板
- MiniCPM3 chat template 自动识别（通过 GGUF metadata）
- `llama_sampler_init_greedy()` 贪心采样（确定性输出）
- **Vulkan GPU 加速**：根据设备支持自动启用 (`n_gpu_layers = 99` 或 `0`)
- **自适应线程数**：根据设备核心数自动调整，留 1-2 核给 UI/音频
- 流式生成通过 JNI 回调 `onNativeToken` / `onNativeComplete` 桥接到 Kotlin

### JNI 方法列表
| 方法 | 功能 |
|------|------|
| `nativeInit` | 加载 GGUF 模型，创建 context（支持 GPU layers） |
| `nativeGenerateStreamCallback` | 流式 token 生成 |
| `nativeGenerateSingle` | 非流式生成（记忆评分用） |
| `nativeAbort` | 设置 stop_flag 中断生成 |
| `nativeSetSystemPrompt` | 动态设置系统提示词 |
| `nativeDestroy` | 释放所有资源 |
| `nativeGetContextSize` | 返回 context 大小 (2048/4096) |
| `nativeSetDebugLogPath` | 设置调试日志文件路径 |

## Sherpa-ONNX 全本地管线

### 语音识别 (SttEngine → VoicePipeline)
- Zipformer 中文流式 STT，通过 `OnlineRecognizer` + `OnlineStream` API
- 接入 `VoicePipeline.localSttRecordLoop()`，在进程内运行
- VAD 端点检测通过 `VadDetector` (Silero VAD)，驱动 STT 开始/结束

### 语音活动检测 (VadDetector)
- Silero VAD ONNX 模型，在进程内运行
- 输出 `SILENCE / SPEECH / SPEECH_END` 三态
- 配合 SttEngine 实现"有说话时送入 STT，静音/说完了取结果"

### 语音合成 (SherpaTtsEngine)
- VITS 中文 TTS，通过 `OfflineTts` API
- 支持逐句合成，由 `VoicePipeline.ttsConsumerLoop()` 流式消费

### 全双工语音处理 (VoicePipeline)
```
麦克风 → AudioRecord(16kHz, 30ms chunk)
  → VadDetector.process(chunk) → SPEECH/SILENCE/SPEECH_END
  → [SPEECH] SttEngine.acceptWaveform → getPartialResult → onInterimText
  → [SPEECH_END/Silence] getFinalResultAndReset → onFinalText
  → ViewModel.processUserSpeech → LlmEngine.generateStream → 逐句TTS
```

### 打断检测
- TTS 播放期间，VAD 持续监控麦克风能量
- 用户说话能量 > TTS 回声基线 × 3.0，连续 8 帧 → 中断
- `llmEngine.abort()` + `systemTts.stop()` + 清空队列

## 云端集成（已移除）
所有云端组件已删除：XfyunAsrProvider, CfAsrProvider, OpenAiClient, MiniCpmSpeechClient

## 历史决策
- Qwen3-0.6B 替换为 MiniCPM3-4B (2026-05)，同步启用 Vulkan GPU 加速
- 鸿蒙专有 workaround 条件化为 `HarmonyCompat.kt`，标准 Android 跳过
- 云端 LLM/ASR 全部移除，改为全本地语音管线 (2026-05)

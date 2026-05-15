# AI Worder — 架构文档

## 概述

全离线端侧 AI 语音助手。所有模型（LLM、STT、VAD、TTS、嵌入）均通过 llama.cpp、ONNX Runtime 和 sherpa-onnx 在设备本地运行。

**目标设备**：OnePlus Ace 2 Pro (骁龙 8 Gen 2, 24GB RAM, Adreno 740)

---

## 鸟瞰架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    UI 层 (Jetpack Compose)                       │
│  语音通话界面  文字聊天界面  人设设置  记忆浏览                     │
│       │               │          │          │                   │
├───────┼───────────────┼──────────┼──────────┼───────────────────┤
│       ▼               ▼          ▼          ▼                   │
│  语音通话VM       聊天VM     人设VM     记忆VM                    │
│       │               │          │          │                   │
├───────┼───────────────┼──────────┼──────────┼───────────────────┤
│       ▼               ▼          ▼          ▼                   │
│  语音管线         大模型引擎   人设管理器   记忆管理器              │
│  音频预处理器      嵌入引擎                                     │
│  本地ASR提供者     模型管理器                                    │
│  VAD检测器                                                       │
│  SenseVoice STT引擎 (OfflineRecognizer)                          │
├─────────────────────────────────────────────────────────────────┤
│  原生库                                                          │
│  libsherpa-onnx-jni.so   libllm_bridge.so   libonnxruntime.so   │
├─────────────────────────────────────────────────────────────────┤
│  模型（设备端）                                                   │
│  Qwen3-0.6B-Q8_0.gguf  SenseVoice-int8.onnx  silero_vad.onnx   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 包结构

```
com.aicustomer/
├── App.kt                          应用入口，依赖注入
├── MainActivity.kt                 Compose 入口
├── engine/                         机器学习推理引擎
│   ├── LlmEngine.kt                llama.cpp JNI 桥接 (Qwen 0.6B)
│   ├── EmbeddingEngine.kt          文本嵌入 (GTE-small-zh, ONNX)
│   ├── SenseVoiceSttEngine.kt      离线语音识别 (SenseVoice, OfflineRecognizer)
│   ├── SherpaTtsEngine.kt          本地语音合成 (VITS Piper, 实验)
│   ├── ModelManager.kt             模型下载/解压/状态管理
│   ├── InferenceService.kt         前台服务，保障 CPU 优先级
│   └── DeviceProfile.kt            设备能力检测
├── voice/                          语音管线
│   ├── VoicePipeline.kt            全双工编排器
│   ├── AsrProvider.kt              ASR 抽象接口
│   ├── LocalAsrProvider.kt         能量+VAD 门控 → 音频缓冲 → STT
│   ├── VadDetector.kt              Silero VAD 封装
│   ├── AudioPreprocessor.kt        DC阻断 + 噪音门 + 自动增益
│   └── HomophoneCorrector.kt       同音字后处理纠错
├── identity/                       AI 人设
│   ├── IdentityManager.kt          增删改查 + 预设
│   ├── PromptBuilder.kt            文字聊天系统提示词
│   └── VoicePromptBuilder.kt       语音聊天系统提示词
├── memory/                         三层记忆
│   ├── MemoryManager.kt            编排器
│   ├── WorkingMemory.kt            滑动窗口（内存）
│   ├── ShortTermMemory.kt          会话摘要（30天）
│   ├── LongTermMemory.kt           向量索引持久记忆
│   ├── MemoryCompressor.kt         大模型压缩
│   └── ImportanceScorer.kt         大模型重要性评分
├── data/
│   ├── model/                      Room 实体
│   │   ├── Message.kt              聊天/语音消息
│   │   ├── Memory.kt               记忆条目 + 嵌入向量
│   │   └── Identity.kt             AI 人设配置
│   └── local/                      Room 持久化
│       ├── AppDatabase.kt          SQLite 数据库
│       ├── MessageDao.kt
│       ├── MemoryDao.kt
│       ├── IdentityDao.kt
│       └── VectorIndex.kt          余弦相似度检索
├── ui/
│   ├── viewmodel/                  状态管理
│   │   ├── ChatViewModel.kt
│   │   ├── VoiceCallViewModel.kt
│   │   ├── IdentityViewModel.kt
│   │   ├── MemoryViewModel.kt
│   │   └── ModelsViewModel.kt
│   ├── voice/                      语音通话组件
│   │   ├── VoiceCallScreen.kt
│   │   ├── VoiceOrb.kt             振幅动画光球
│   │   ├── WaveformRing.kt         旋转波形环
│   │   ├── AmbientBackground.kt    流体渐变 + 粒子背景
│   │   ├── SubtitleArea.kt         实时字幕
│   │   └── BottomControlBar.kt     通话控制栏
│   ├── chat/                       文字聊天组件
│   │   ├── ChatScreen.kt
│   │   ├── MessageBubble.kt
│   │   └── VoiceButton.kt
│   ├── navigation/AppNavigation.kt 导航宿主（5 路由）
│   └── theme/Theme.kt              Material3 深色/浅色
└── util/
    ├── VoiceTextCleaner.kt         TTS 文本预处理
    ├── ThinkingContentFilter.kt    <think> 标签过滤
    └── HarmonyCompat.kt            鸿蒙系统检测
```

---

## 语音交互数据流

```
┌─ 麦克风 ───────────────────────────────────────────────────┐
│  AudioRecord (16kHz, 单声道, PCM_16BIT, VOICE_COMMUNICATION) │
│  每块: 512 采样 = 32ms                                      │
└────────────────────┬────────────────────────────────────────┘
                     │ ShortArray
                     ▼
┌─ 音频预处理器 ──────────────────────────────────────────────┐
│  DC阻断 → 噪音门 (3dB膝点) → 自动增益 (RMS 0.3)             │
│  输出: FloatArray (预处理后，用于振幅动画)                    │
│  原始音频: FloatArray (用于 VAD + STT)                       │
└──────────┬──────────────────────┬───────────────────────────┘
           │ 原始                 │ 预处理
           ▼                      ▼
┌─ 本地ASR提供者 ───────────┐  ┌─ UI振幅 ──────────────────┐
│  自适应能量阈值            │  │  能量 → 波形动画           │
│  Silero VAD (原始音频)      │  │  barge-in 打断检测        │
│  语音起始 (3帧确认,96ms)    │  └───────────────────────────┘
│  端点 (25帧静音=800ms,     │
│  或 VAD SPEECH_END)       │
│  音频缓冲 (FloatArray)     │
│  → 端点检出                │
│     ↓                      │
│  SenseVoice OfflineRecognizer │
│  (SenseVoice int8, ITN=True,  │
│   numThreads=4, hotwords)    │
│     ↓                      │
│  HomophoneCorrector (后处理) │
└───────────────────────────┘
           │
           ▼  STT 文本 (带标点)
┌─ 语音管线 ──────────────────────────────────────────────────┐
│  状态: 空闲 → 聆听 → 思考 → 说话 → 聆听                       │
│  打断: 能量 > 基线×2.5 连续3帧, 50ms保护期                   │
│  超时: 聆听=30s, 思考=25s, 说话=30s                          │
│  空白端点: 4次 → 重置                                       │
└────────────────────┬────────────────────────────────────────┘
                     │ onFinalText(text)
                     ▼
┌─ 语音通话VM ───────────────────────────────────────────────┐
│  提示词 → LlmEngine.generateStream()                         │
│  句子拆分 → 语音文本清洗器 → TTS 队列                        │
└────────────────────┬────────────────────────────────────────┘
                     │ queueTtsSentence / queueTurnEnd
                     ▼
┌─ TTS 消费者 ───────────────────────────────────────────────┐
│  TextToSpeech (Android 系统 TTS)                             │
│  中文: 需安装 Google TTS                                     │
│  无中文语音时静默回退 (纯文字模式)                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 状态机

```
                    ┌──────────┐
       开始通话      │   空闲    │  结束通话
       ──────────► │          │ ◄────────
                    └────┬─────┘
                         │
                    ┌────▼─────┐
          ┌─────────│   聆听    │◄──────────┐
          │ 30秒    │          │           │ TTS完成 /
          │ 超时    └────┬─────┘           │ 打断
          │              │ 检测到语音       │
          │         ┌────▼─────┐           │
          │         │ 音频缓冲  │           │
          │         │ +VAD端点  │──┐        │
          │         └────┬─────┘  │        │
          │              │ 端点检出│        │
          │         ┌────▼─────┐  │        │
          │         │ SenseVoice│  │        │
          │         │ 离线识别  │  │        │
          │         └────┬─────┘  │        │
          │              │ STT文本│        │
          │         ┌────▼─────┐  │        │
          │         │   思考    │  │        │
          │ 25秒    │ (大模型)  │  │        │
          │ 超时    └──────────┘  │        │
          │                       │        │
          ▼                  ┌────▼───┴─┐
    "未检测到语音"             │   说话    │
     (重置)                   │  (TTS)   │
                             └──────────┘
```

---

## 记忆系统

```
用户消息 ──► 工作记忆 (滑动窗口, 最大 800 字)
                         │
                    重要性评分器 (大模型: 0-10 分)
                         │
                    ┌────▼─────┐
                    │ 分数<6?  │──是──► 丢弃
                    └────┬─────┘
                         │ 否
                    ┌────▼──────────┐
                    │   长期记忆     │
                    │   向量索引     │
                    │ (384维余弦)    │
                    │   淘汰策略:     │
                    │ 重要性×0.5    │
                    │ +时间衰减×0.5 │
                    └────────────────┘

会话结束 ──► 记忆压缩器 (大模型)
                         │
                    ┌────▼──────────┐
                    │   短期记忆     │
                    │  50字摘要      │
                    │  30天保留      │
                    └────────────────┘
```

---

## 关键参数

| 组件 | 参数 | 值 |
|------|------|-----|
| 音频录制 | 采样率 | 16000 Hz |
| 音频录制 | 音源 | VOICE_COMMUNICATION |
| 音频录制 | 每块大小 | 512 采样 (32ms) |
| 语音检测 | 模型 | Silero VAD ONNX |
| 语音检测 | 语音确认 | 3 帧 (96ms) |
| 语音检测 | 静音端点 | 25 帧 (800ms) |
| 语音检测 | 最小语音 | 16 帧 (512ms) |
| 语音识别 | 模型 | SenseVoice int8 (OfflineRecognizer) |
| 语音识别 | 语言 | 中文 (zh) |
| 语音识别 | ITN | 启用 (标点+数字标准化) |
| 语音识别 | 线程数 | 4 |
| 语音识别 | 热词 | 启用 (客服场景高频词) |
| 语音识别 | 后处理 | HomophoneCorrector 同音字纠错 |
| 大模型 | 模型 | Qwen3-0.6B Q8_0 GGUF |
| 大模型 | GPU | Vulkan (99 层) |
| 大模型 | 线程数 | CPU核心数 - 1 |
| 嵌入 | 模型 | GTE-small-zh (ONNX) |
| 嵌入 | 维度 | 384 |
| 语音合成 | 引擎 | Android TextToSpeech |
| 语音合成 | 语速 | 1.5x |
| 语音合成 | 音高 | 1.05 |

---

## 依赖库

| 库 | 用途 |
|----|------|
| llama.cpp (JNI) | 大模型推理 |
| sherpa-onnx 1.13.2 | VAD + STT + TTS |
| ONNX Runtime 1.20.0 | 嵌入推理 |
| Jetpack Compose | UI 框架 |
| Room | SQLite 持久化 |
| Kotlin 协程 | 异步 |

---

## 模型清单

| 模型 | 文件 | 大小 | 用途 |
|------|------|------|------|
| Qwen3-0.6B | Qwen3-0.6B-Q8_0.gguf | 610MB | 本地对话推理 |
| SenseVoice int8 | model.int8.onnx | 228MB | 离线语音识别 |
| Silero VAD | silero_vad.onnx | 2MB | 语音活动检测 |
| GTE-small-zh | (EmbeddingEngine) | ~50MB | 文本嵌入/向量检索 |

---

## 当前限制

1. **语音合成**：SherpaTtsEngine 已禁用 — 当前 sherpa-onnx 版本下 null assetManager 会崩溃。系统 TTS 需安装 Google TTS 中文语音包（OnePlus ColorOS 未预装）。
2. **推理速度**：Qwen3-0.6B 在移动端约 5-15 秒响应时间，取决于上下文长度。
3. **内存占用**：全部模型加载后约 1.3GB 内存占用（LLM 900MB + STT 350MB + VAD 10MB + 嵌入 80MB）。24GB RAM 设备完全无压力。
4. **SenseVoice 离线识别**：非流式，需要在 VAD 端点检出后缓冲音频并一次性识别。端点后延迟约 100-200ms（SD8G2）。
5. **SenseVoice 模型首次下载**：228MB 需要通过网络预先下载（GitHub Release），见 `download_models.sh`。

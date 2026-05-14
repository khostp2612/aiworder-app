<p align="center">
  <h1 align="center">🤖 AIWorker</h1>
  <p align="center"><strong>全离线端侧 AI 语音助手</strong></p>
  <p align="center">所有模型本地运行，零网络依赖，隐私至上</p>
</p>

---

## ✨ 功能特性

- **全离线运行** — LLM、STT、VAD、TTS、向量嵌入全部端侧推理，无需任何云端服务
- **实时语音对话** — 全双工语音交互，支持打断（barge-in），状态机驱动的流畅对话体验
- **本地大模型** — 基于 llama.cpp Vulkan GPU 加速的 Qwen3-0.6B 本地推理
- **离线语音识别** — SenseVoice int8 离线 STT，支持热词增强与同音字纠错
- **三层记忆系统** — 工作记忆 → 短期摘要 → 长期向量索引，越用越懂你
- **AI 人设** — 可自定义 AI 的性格、说话风格和专业领域
- **设备自适应** — 根据 RAM / GPU 自动选择最优量化模型和推理参数

---

## 🏗️ 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                     UI 层 (Jetpack Compose)                      │
│   语音通话界面    文字聊天界面    人设设置    记忆浏览             │
├─────────────────────────────────────────────────────────────────┤
│                 ViewModel 层 (状态管理)                          │
│   VoiceCallVM    ChatVM    IdentityVM    MemoryVM               │
├─────────────────────────────────────────────────────────────────┤
│                   业务逻辑层                                     │
│   语音管线      大模型引擎      人设管理器    记忆管理器          │
│   音频预处理器   嵌入引擎       提示词构建器                     │
│   本地ASR       模型管理器                                      │
│   VAD检测器                                                     │
├─────────────────────────────────────────────────────────────────┤
│                   原生库层                                      │
│   libsherpa-onnx-jni.so   libllm_bridge.so   libonnxruntime.so │
├─────────────────────────────────────────────────────────────────┤
│                   端侧模型                                      │
│   Qwen3-0.6B GGUF    SenseVoice int8    Silero VAD             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎙️ 语音交互链路

```
麦克风 → 音频预处理 → VAD(Silero) → 离线STT(SenseVoice) → LLM(Qwen3) → TTS → 扬声器
         DC阻断           │                                              │
         噪音门           │         全双工状态机                            │
         自动增益         │    空闲 → 聆听 → 思考 → 说话 ─┐               │
                          │         ↑                    │               │
                          │         └──── TTS 完成/打断 ←┘               │
                          │                                          打断检测
                          └── 能量比 + VAD 双重门控 ───────────────────────┘
```

---

## 🧠 三层记忆架构

| 层级 | 类型 | 容量 | 保留期 | 用途 |
|------|------|------|--------|------|
| **工作记忆** | 滑动窗口 | ≤800 字 | 当前会话 | 即时对话上下文 |
| **短期记忆** | LLM 摘要 | ≤50 字/条 | 30 天 | 会话级要点归纳 |
| **长期记忆** | 向量索引 (384维) | 不限 | 永久 | 跨会话语义检索 |

记忆写入流程：消息 → 重要性评分 (LLM 0-10) → 分数≥6 写入长期记忆 → 会话结束压缩为短期摘要

---

## 🛠️ 技术栈

| 组件 | 技术方案 | 说明 |
|------|----------|------|
| **LLM 推理** | llama.cpp Vulkan (JNI) | Qwen3-0.6B Q8_0 GGUF，GPU offload 99 层 |
| **语音识别** | Sherpa-ONNX SenseVoice | int8 量化，离线非流式，支持热词 |
| **语音检测** | Sherpa-ONNX Silero VAD | 96ms 语音确认，800ms 静音端点 |
| **语音合成** | Android TTS | 语速 1.5x，音高 1.05 |
| **向量嵌入** | ONNX Runtime | GTE-small-zh，384 维余弦相似度 |
| **数据存储** | Room (SQLite) + 自建向量索引 | 消息/记忆/人设持久化 |
| **UI 框架** | Jetpack Compose + Material3 | 深色/浅色主题 |
| **原生层** | C++17 + CMake | llama.cpp 完整源码树编译 |
| **CI/CD** | Drone CI | Docker gradle:8.5-jdk17 |

---

## 📁 项目结构

```
com.aicustomer/
├── App.kt                          # Application 入口，全局依赖注入
├── MainActivity.kt                 # Compose 导航宿主
├── engine/                         # 推理引擎层
│   ├── LlmEngine.kt                # llama.cpp JNI 桥接
│   ├── EmbeddingEngine.kt          # GTE-small-zh 文本嵌入
│   ├── SenseVoiceSttEngine.kt      # SenseVoice 离线 STT
│   ├── SherpaTtsEngine.kt          # VITS 本地 TTS (实验)
│   ├── ModelManager.kt             # 模型下载/解压/状态管理
│   ├── InferenceService.kt         # 前台服务，保障 CPU 优先级
│   └── DeviceProfile.kt            # 设备能力检测与分级
├── voice/                          # 语音管线层
│   ├── VoicePipeline.kt            # 全双工状态机编排器
│   ├── AsrProvider.kt              # ASR 抽象接口
│   ├── LocalAsrProvider.kt         # 能量+VAD 门控 → STT
│   ├── VadDetector.kt              # Silero VAD 封装
│   ├── AudioPreprocessor.kt        # DC阻断 + 噪音门 + 自动增益
│   └── HomophoneCorrector.kt       # 同音字后处理纠错
├── memory/                         # 三层记忆系统
│   ├── MemoryManager.kt            # 记忆编排器
│   ├── WorkingMemory.kt            # 滑动窗口 (内存)
│   ├── ShortTermMemory.kt          # 会话摘要 (30天)
│   ├── LongTermMemory.kt           # 向量索引持久记忆
│   ├── MemoryCompressor.kt         # LLM 压缩摘要
│   └── ImportanceScorer.kt         # LLM 重要性评分
├── identity/                       # AI 人设管理
│   ├── IdentityManager.kt          # 增删改查 + 预设
│   ├── PromptBuilder.kt            # 文字聊天系统提示词
│   └── VoicePromptBuilder.kt       # 语音聊天系统提示词
├── data/                           # 数据持久层
│   ├── model/                      # Room 实体 (Message/Memory/Identity)
│   └── local/                      # Room DAO + 自建向量索引
├── ui/                             # 界面层
│   ├── viewmodel/                  # ViewModel (Chat/Voice/Identity/Memory/Models)
│   ├── voice/                      # 语音通话 (光球动画/波形环/流体背景/字幕)
│   ├── chat/                       # 文字聊天 (消息气泡/语音按钮)
│   ├── identity/                   # 人设设置
│   ├── memory/                     # 记忆浏览
│   ├── models/                     # 模型下载管理
│   ├── navigation/                 # 5 路由导航
│   └── theme/                      # Material3 深色/浅色
└── util/                           # 工具类
    ├── VoiceTextCleaner.kt         # TTS 文本预处理
    ├── ThinkingContentFilter.kt     # <think> 标签过滤
    └── HarmonyCompat.kt            # 鸿蒙系统兼容
```

---

## 🤖 端侧模型清单

| 模型 | 文件 | 大小 | 用途 | 来源 |
|------|------|------|------|------|
| Qwen3-0.6B | Qwen3-0.6B-Q8_0.gguf | ~610MB | 本地对话推理 | 需下载 |
| SenseVoice | model.int8.onnx | ~228MB | 离线语音识别 | 需下载 |
| Silero VAD | silero_vad.onnx | ~2MB | 语音活动检测 | 需下载 |
| GTE-small-zh | (内嵌) | ~50MB | 文本向量嵌入 | 内置 |

---

## 📱 设备分级

| 级别 | RAM | GPU | 推荐模型 | Context |
|------|-----|-----|----------|---------|
| 🏆 旗舰 | 12GB+ | Vulkan | Qwen3-0.6B Q8_0 | 4096 |
| 🔥 高端 | 8GB+ | 有/无 | Qwen3-0.6B Q8_0 | 4096 |
| ✅ 标准 | 6GB+ | 无 | Qwen3-0.6B Q4_K_M | 2048 |
| ❌ 低端 | <6GB | 无 | 不支持 | — |

> 目标设备：**OnePlus Ace 2 Pro** (骁龙 8 Gen 2, 24GB RAM, Adreno 740)

---

## 🚀 编译与运行

### 环境要求

- **Android Studio** Hedgehog | 2023.1.1+
- **JDK** 17
- **Android SDK** 34
- **CMake** 3.22.1
- **NDK** (自动下载)
- **设备** arm64-v8a, 6GB+ RAM, Android 8.0+

### 步骤

```bash
# 1. 克隆仓库
git clone https://github.com/khostp2612/aiworker.git
cd aiworker

# 2. 下载 AI 模型 (SenseVoice + Silero VAD, ~230MB)
bash download_models.sh

# 3. 编译 Debug 版本
./gradlew assembleDebug

# 4. 安装到设备
./gradlew installDebug
```

> **注意**：Qwen3-0.6B GGUF 模型需手动下载至设备存储，App 内置模型管理页面可引导下载。

### 首次运行

1. 启动 App → 进入「模型」页面
2. 下载 LLM 模型 (Qwen3-0.6B)
3. 确认 STT / VAD 模型已就绪
4. 开始文字聊天或语音通话

---

## ⚠️ 已知限制

| 限制 | 说明 |
|------|------|
| **TTS** | SherpaTtsEngine 已禁用（sherpa-onnx null assetManager 崩溃），需系统 TTS + Google 中文语音包 |
| **推理速度** | Qwen3-0.6B 移动端约 5-15s 响应，取决于上下文长度 |
| **内存占用** | 全模型加载约 1.3GB (LLM 900MB + STT 350MB + VAD 10MB + 嵌入 80MB) |
| **STT 延迟** | SenseVoice 离线非流式，端点后需 100-200ms 一次性识别 (SD8G2) |
| **模型下载** | SenseVoice 228MB 需网络预先下载 (GitHub Release) |

---

## 📄 License

MIT

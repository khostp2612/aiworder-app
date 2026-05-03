# AI客服 - 离线安卓应用

基于Qwen3-0.6B (内置) / Qwen2.5-1.5B (可选)的离线AI客服，具备长记忆、固定身份、实时语音对话三大核心功能。

## 功能特性

- **离线运行**：所有AI模型本地运行，无需网络
- **长记忆系统**：三层记忆架构（工作/短期/长期），越用越好用
- **固定身份**：可设定AI的性格、说话风格、专业领域
- **实时语音**：STT语音识别 → LLM推理 → TTS语音合成流水线
- **向量检索**：语义相似度搜索，智能回忆相关记忆
- **双模型架构**：内置Qwen3-0.6B开箱即用，可选下载Qwen2.5-1.5B增强

## 技术栈

| 组件 | 技术方案 |
|------|----------|
| LLM推理 | llama.cpp (Qwen3-0.6B Q8_0内置 / Qwen2.5-1.5B Q4_K_M可选) |
| 语音识别 | Sherpa-ONNX (Zipformer中文流式) |
| 语音合成 | Sherpa-ONNX (VITS中文) |
| 语音检测 | Sherpa-ONNX (Silero VAD) |
| 向量嵌入 | ONNX Runtime (GTE-small-zh) |
| 数据存储 | Room (SQLite) + 自建向量索引 |
| UI框架 | Jetpack Compose + Material3 |

> Sherpa-ONNX使用Java API（AAR直接集成），`native/sherpa_bridge.cpp`为预留的JNI桥接层（当前未使用）。

## 环境准备

### 1. 安装开发工具

- **Android Studio** Hedgehog | 2023.1.1 或更新版本
- **Android SDK** compileSdk 34, NDK 26.x
- **CMake** 3.22.1+
- **Java** 17

### 2. 生成Gradle Wrapper

```bash
# 安装Gradle（macOS）
brew install gradle

# 在项目根目录生成wrapper
cd /Users/mac/Downloads/aiwork
gradle wrapper --gradle-version 8.5

# 给gradlew加执行权限
chmod +x gradlew
```

### 3. 编译llama.cpp

> llama.cpp源码已内置在`native/llama.cpp/`中，通过CMakeLists.txt自动编译，无需手动操作。

### 4. 下载AI模型

在App内通过"模型管理"页面下载，或手动放入设备：

```
/data/data/com.aicustomer/files/models/
├── qwen3-0.6b/Qwen3-0.6B-Q8_0.gguf  (内置 ~610MB)
├── qwen1.5b-q4km/qwen2.5-1.5b-instruct-q4_k_m.gguf  (~1GB，可选下载)
├── stt-zipformer-zh/ (encoder/decoder/joiner/tokens)
├── tts-vits-zh/ (model/tokens/lexicon)
├── vad-silero/silero_vad.onnx  (~2MB)
└── embed-gte-small-zh/ (model/vocab)  (~30MB)
```

**模型下载地址：**

| 模型 | 下载源 | 说明 |
|------|--------|------|
| Qwen3-0.6B GGUF | 内置 (assets) | 开箱即用，首次启动自动提取 |
| Qwen2.5-1.5B GGUF | https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF | 可选增强模型 |
| Zipformer STT | https://github.com/k2-fsa/sherpa-onnx/releases | 语音识别 |
| VITS TTS | https://github.com/k2-fsa/sherpa-onnx/releases | 语音合成 |
| Silero VAD | https://github.com/k2-fsa/sherpa-onnx/releases | 语音活动检测 |
| GTE-small-zh | https://huggingface.co/thenlper/gte-small-zh | 向量嵌入 |

## 编译和运行

### 用Android Studio

1. 打开Android Studio
2. File → Open → 选择 `/Users/mac/Downloads/aiwork`
3. 等待Gradle Sync完成
4. 连接Android手机（arm64-v8a, Android 8.0+）
5. 点击Run

### 用命令行

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## 硬件要求

- **最低**: 6GB RAM + 64GB存储
- **推荐**: 8GB RAM + 128GB存储
- **CPU**: arm64-v8a
- **系统**: Android 8.0 (API 26)+

## 内存占用

| 组件 | 内存 |
|------|------|
| Qwen3-0.6B Q8_0 | ~0.6 GB |
| Qwen2.5-1.5B Q4_K_M | ~1.0 GB |
| Zipformer STT (按需) | ~80 MB |
| VITS TTS (按需) | ~150 MB |
| GTE-small-zh | ~30 MB |
| App + UI | ~100 MB |
| **总计 (0.6B)** | **~1.0 GB** |
| **总计 (1.5B)** | **~1.4 GB** |

8GB设备可用内存4-5GB，完全足够。

## 常见问题

**Q: 编译报错找不到llama.cpp?**
A: 先编译llama.cpp Android静态库，参见上方步骤。

**Q: 模型加载失败?**
A: 确认gguf文件完整（约1GB），路径正确。

**Q: 8GB手机能跑吗?**
A: 可以，LLM约1GB，全部组件约1.4GB。

## License

MIT

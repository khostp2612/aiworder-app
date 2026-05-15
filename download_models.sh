#!/bin/bash
# SenseVoice 模型下载脚本
# 用于 AI Worder 项目 — 下载 SenseVoice 语音识别模型
#
# 模型信息：
#   - 名称：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17
#   - 大小：~228MB (model.int8.onnx) + 308KB (tokens.txt)
#   - 语言：中文/英语/日语/韩语/粤语
#   - 来源：阿里达摩院 FunAudioLLM SenseVoice
#
# 前置条件：需要安装 GitHub CLI (gh) 或 curl

set -e

MODEL_DIR="app/src/main/assets/models/sense-voice"
mkdir -p "$MODEL_DIR"

echo "=== 下载 Sherpa-ONNX AAR (v1.13.2) ==="
curl -L -o app/libs/sherpa-onnx.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar"

echo "=== 下载 SenseVoice 模型 ==="
if command -v gh &> /dev/null; then
    echo "使用 GitHub CLI 下载..."
    gh release download -R k2-fsa/sherpa-onnx asr-models \
      -p "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2" \
      --dir /tmp
else
    echo "GitHub CLI 不可用，尝试 curl..."
    curl -L -o /tmp/sense-voice.tar.bz2 \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
fi

echo "=== 解压模型 ==="
TARBALL=$(ls /tmp/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2 2>/dev/null || echo "/tmp/sense-voice.tar.bz2")
tar xf "$TARBALL" -C /tmp/

echo "=== 复制到项目 ==="
EXTRACT_DIR=$(ls -d /tmp/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/ 2>/dev/null | head -1)
if [ -z "$EXTRACT_DIR" ]; then
    echo "错误：找不到解压目录"
    exit 1
fi
cp "$EXTRACT_DIR/model.int8.onnx" "$MODEL_DIR/"
cp "$EXTRACT_DIR/tokens.txt" "$MODEL_DIR/"

echo "=== 清理临时文件 ==="
rm -f /tmp/sense-voice.tar.bz2 /tmp/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
rm -rf /tmp/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/

echo "=== 完成 ==="
ls -lh "$MODEL_DIR/"

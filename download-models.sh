#!/usr/bin/env bash
# 下载构建所需的大型模型 / 词库文件
# 这些文件体积超过 GitHub 100MB 限制，未纳入 git，构建前需执行一次本脚本。
# 用法（在 fcitx5-android 目录内）：bash download-models.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== [1/2] 下载 Paraformer 中文语音模型（int8 约 217MB，Apache-2.0）==="
ASR_ASSET_DIR="$SCRIPT_DIR/plugin/asr/src/main/assets"
ASR_MODEL_DIR="$ASR_ASSET_DIR/sherpa-onnx-paraformer-zh-2024-03-09"
ASR_TARBALL="sherpa-onnx-paraformer-zh-2024-03-09.tar.bz2"
ASR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$ASR_TARBALL"

if [ -f "$ASR_MODEL_DIR/model.int8.onnx" ]; then
  echo "已存在，跳过：$ASR_MODEL_DIR/model.int8.onnx"
else
  mkdir -p "$ASR_ASSET_DIR"
  echo "下载：$ASR_URL（官方包含 fp32+int8 两个模型）"
  curl -L --fail --retry 3 -o "$ASR_TARBALL" "$ASR_URL"
  tar -xjf "$ASR_TARBALL" -C "$ASR_ASSET_DIR"
  rm -f "$ASR_TARBALL"
  # 仅保留 int8 模型与词表，删除 fp32 大模型与测试音频，避免打进 APK
  rm -f "$ASR_MODEL_DIR/model.onnx"
  rm -rf "$ASR_MODEL_DIR/test_wavs"
  echo "完成：$ASR_MODEL_DIR"
fi

echo "=== [2/2] 下载万象拼音语言模型（约 400MB，CC BY 4.0，需署名）==="
WANXIANG_DIR="$SCRIPT_DIR/plugin/rime/src/main/cpp/wanxiang"
WANXIANG_GRAM="$WANXIANG_DIR/wanxiang-lts-zh-hans.gram"
# 版本号可按需调整（见 https://github.com/amzxyz/rime-wanxiang/releases）
WANXIANG_VERSION="v8.8.0"
WANXIANG_URL="https://github.com/amzxyz/rime-wanxiang/releases/download/$WANXIANG_VERSION/wanxiang-lts-zh-hans.gram"

if [ -f "$WANXIANG_GRAM" ]; then
  echo "已存在，跳过：$WANXIANG_GRAM"
else
  mkdir -p "$WANXIANG_DIR"
  echo "下载：$WANXIANG_URL"
  curl -L --fail --retry 3 -o "$WANXIANG_GRAM" "$WANXIANG_URL"
  echo "完成：$WANXIANG_GRAM"
fi

echo "=== [3/3] 下载 Qwen2.5-1.5B-Instruct GGUF（Q4_K_M 约 1.1GB，Apache-2.0）==="
LLM_ASSET_DIR="$SCRIPT_DIR/plugin/llm/src/main/assets/llm"
LLM_MODEL_FILE="$LLM_ASSET_DIR/qwen2.5-1.5b-instruct-q4_k_m.gguf"
# Qwen 官方 GGUF 发布。ModelScope（魔搭）国内直连快；备选 huggingface / hf-mirror：
#   https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
LLM_URL="https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf"

if [ -f "$LLM_MODEL_FILE" ]; then
  echo "已存在，跳过：$LLM_MODEL_FILE"
else
  mkdir -p "$LLM_ASSET_DIR"
  echo "下载：$LLM_URL"
  curl -L --fail --retry 3 -o "$LLM_MODEL_FILE" "$LLM_URL"
  echo "完成：$LLM_MODEL_FILE"
fi

echo "=== 全部就绪，可开始构建 ==="

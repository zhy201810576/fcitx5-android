#!/usr/bin/env bash
# 下载构建所需的大型模型 / 词库文件
# 这些文件体积超过 GitHub 100MB 限制，未纳入 git，构建前需执行一次本脚本。
# 用法（在 fcitx5-android 目录内）：bash download-models.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== [1/2] 下载 SenseVoice 语音模型（约 228MB，Apache-2.0）==="
ASR_ASSET_DIR="$SCRIPT_DIR/plugin/asr/src/main/assets"
ASR_MODEL_DIR="$ASR_ASSET_DIR/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
ASR_TARBALL="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
ASR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$ASR_TARBALL"

if [ -f "$ASR_MODEL_DIR/model.int8.onnx" ]; then
  echo "已存在，跳过：$ASR_MODEL_DIR/model.int8.onnx"
else
  mkdir -p "$ASR_ASSET_DIR"
  echo "下载：$ASR_URL"
  curl -L --fail --retry 3 -o "$ASR_TARBALL" "$ASR_URL"
  tar -xjf "$ASR_TARBALL" -C "$ASR_ASSET_DIR"
  rm -f "$ASR_TARBALL"
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

echo "=== 全部就绪，可开始构建 ==="

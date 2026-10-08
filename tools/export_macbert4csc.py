#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MacBERT4CSC 端侧模型准备：下载官方预导出 ONNX → INT8 动态量化 → 自检 → 复制到 csc 插件 assets。

模型仓库 shibing624/macbert4csc-base-chinese 自带 `onnx/model.onnx`（标准 BertForMaskedLM
导出，fp32，约 410MB），故无需 torch/transformers 重新导出，仅需：
  1. 下载 onnx/model.onnx 与 onnx/vocab.txt（国内走 hf-mirror，失败自动重试）；
  2. onnxruntime 动态量化（INT8，无需校准集）→ 约 110MB；
  3. 用 ONNX Runtime + 与 Kotlin 侧完全一致的最小分词逻辑自检已知样例。

产物（供 :plugin:csc 打包，app 端 MacBert4CscEngine 经 ONNX Runtime 加载）：
  plugin/csc/src/main/assets/csc/macbert4csc-base-int8.onnx
  plugin/csc/src/main/assets/csc/vocab.txt

用法（仅需 numpy + onnxruntime + onnx + requests，无 torch）：
  cd fcitx5-android/tools
  python -m venv venv
  venv/Scripts/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple \
      onnx "onnxruntime==1.19.2" numpy requests
  venv/Scripts/python export_macbert4csc.py

> Windows 下 onnxruntime 1.30.0 的 wheel 导入即段错误（Segmentation fault），
> 工具侧固定用 1.19.2（仅影响导出/自检；Android 端 AAR 版本在 libs.versions.toml 独立指定）。

说明：模型与词表体积较大（INT8 约 110MB），未纳入 git（见 .gitignore）。
"""
import os
import shutil
import time
from pathlib import Path

import requests

# 国内直连 HuggingFace 走 hf-mirror；已显式指定时尊重外部值
BASE_URL = os.environ.get("HF_ENDPOINT", "https://hf-mirror.com").rstrip("/")

MODEL_REPO = "shibing624/macbert4csc-base-chinese"
ONNX_REMOTE = "onnx/model.onnx"
VOCAB_REMOTE = "onnx/vocab.txt"
THRESHOLD = 0.7


def log(msg: str):
    print(f"[export_macbert4csc] {msg}", flush=True)


def download(remote: str, dest: Path, retries: int = 8):
    """流式下载 + 断点续传。hf-mirror 的 LFS 会 302 到 CloudFront（us.aws.cdn.hf.co），
    curl 偶发空响应（exit 52），requests 流式更稳；失败自动从断点续传。"""
    url = f"{BASE_URL}/{MODEL_REPO}/resolve/main/{remote}"
    if dest.exists() and dest.stat().st_size > 0:
        log(f"已存在，跳过：{dest}")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    headers = {"User-Agent": "MemeBoard/1.0"}
    for attempt in range(1, retries + 1):
        existing = part.stat().st_size if part.exists() else 0
        h = dict(headers)
        if existing > 0:
            h["Range"] = f"bytes={existing}-"
        try:
            log(f"下载（第 {attempt}/{retries} 次，续传 {existing / 1024 / 1024:.1f} MB）：{url}")
            with requests.get(url, stream=True, timeout=(15, 180), headers=h) as r:
                if r.status_code not in (200, 206):
                    raise RuntimeError(f"HTTP {r.status_code}")
                content_length = r.headers.get("Content-Length")
                expected = None
                if content_length:
                    remaining = int(content_length)
                    expected = (existing + remaining) if r.status_code == 206 else remaining
                mode = "ab" if r.status_code == 206 else "wb"
                with open(part, mode) as f:
                    for chunk in r.iter_content(chunk_size=1024 * 1024):
                        if chunk:
                            f.write(chunk)
                if expected is not None and part.stat().st_size != expected:
                    raise RuntimeError(f"大小不符：{part.stat().st_size} != {expected}（续传）")
            part.replace(dest)
            log(f"完成：{dest}（{dest.stat().st_size / 1024 / 1024:.1f} MB）")
            return
        except Exception as e:
            log(f"下载失败（{type(e).__name__}: {e}）")
            if attempt == retries:
                raise
            time.sleep(3 * attempt)


def cjk(ch: str) -> bool:
    cp = ord(ch)
    return 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0xF900 <= cp <= 0xFAFF


def load_vocab(path: Path):
    # 注意：vocab.txt 含个别被 str.splitlines() 当作行分隔符的字符（如 U+0085/U+2028），
    # 必须按「行迭代（仅 \n/\r\n） + 去行尾空白」读取，与模型 tokenizer / Kotlin 端 readLine 一致，
    # 否则词表行数会从 21128 变成 21130，token id 错位导致预测全乱。
    id_to_token = [line.rstrip("\r\n") for line in open(path, encoding="utf-8")]
    token_to_id = {tok: i for i, tok in enumerate(id_to_token)}
    return token_to_id, id_to_token


def quantize(src: Path, dst: Path):
    from onnxruntime.quantization import QuantType, quantize_dynamic

    dst.parent.mkdir(parents=True, exist_ok=True)
    log(f"INT8 动态量化：{src} → {dst}")
    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
        per_channel=False,
    )


def verify(int8_onnx: Path, vocab_path: Path):
    """与 Kotlin MacBertTokenizer / MacBert4CscEngine 相同逻辑逐字纠错，核对已知样例。"""
    import numpy as np
    import onnxruntime as ort

    token_to_id, id_to_token = load_vocab(vocab_path)
    sess = ort.InferenceSession(str(int8_onnx), providers=["CPUExecutionProvider"])
    in_names = [i.name for i in sess.get_inputs()]
    out_name = sess.get_outputs()[0].name
    log(f"输入：{in_names}；输出：{out_name}")

    def correct(text: str, threshold: float = THRESHOLD):
        # 与 Kotlin MacBertTokenizer 一致：CJK 单 token、其余丢弃，[CLS]/[SEP] 包裹
        ids = [token_to_id["[CLS]"]]
        char_to_pos = [-1] * len(text)
        for i, ch in enumerate(text):
            if cjk(ch):
                char_to_pos[i] = len(ids)
                ids.append(token_to_id.get(ch, token_to_id["[UNK]"]))
        ids.append(token_to_id["[SEP]"])
        n = len(ids)
        ids_np = np.array([ids], dtype=np.int64)
        att = np.ones([1, n], dtype=np.int64)
        tt = np.zeros([1, n], dtype=np.int64)
        feed = {}
        for name in in_names:
            if "input_ids" in name:
                feed[name] = ids_np
            elif "attention_mask" in name:
                feed[name] = att
            elif "token_type_ids" in name:
                feed[name] = tt
        logits = sess.run(None, feed)[0][0]  # [seq, vocab]

        out = list(text)
        edits = []
        for i, ch in enumerate(text):
            tok_pos = char_to_pos[i]
            if tok_pos < 0:
                continue
            row = logits[tok_pos]
            x = row - row.max()
            probs = np.exp(x) / np.exp(x).sum()
            arg = int(np.argmax(probs))
            tok_str = id_to_token[arg]
            if len(tok_str) == 1 and cjk(tok_str) and float(probs[arg]) >= threshold and tok_str != ch:
                out[i] = tok_str
                edits.append((i, ch, tok_str, round(float(probs[arg]), 4)))
        return "".join(out), edits

    cases = [
        "今天新情很好",
        "网安",
        "水饺",
        "你好嘛",
        "你说是吧",
        "他要去哪里",
        "她今天很开心",
    ]
    results = {}
    for c in cases:
        fixed, edits = correct(c)
        results[c] = (fixed, edits)
        log(f"  「{c}」→「{fixed}」{('  edits=' + str(edits)) if edits else ''}")

    assert results["今天新情很好"][0] == "今天心情很好", f"已知样例校验失败：{results['今天新情很好']}"
    log("已知样例校验通过（今天新情很好 → 今天心情很好）")
    return results


def main():
    root = Path(__file__).resolve().parents[1]  # fcitx5-android/
    work = Path(__file__).resolve().parent / "macbert4csc_work"
    fp32 = work / "onnx" / "model.onnx"
    vocab = work / "onnx" / "vocab.txt"
    int8 = work / "int8" / "macbert4csc-base-int8.onnx"
    assets = root / "plugin" / "csc" / "src" / "main" / "assets" / "csc"

    t0 = time.time()
    download(ONNX_REMOTE, fp32)
    download(VOCAB_REMOTE, vocab)
    if not int8.exists():
        quantize(fp32, int8)

    verify(int8, vocab)

    assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(int8, assets / int8.name)
    shutil.copy2(vocab, assets / "vocab.txt")
    log(f"已复制到 {assets}")
    log(f"模型大小：{int8.stat().st_size / 1024 / 1024:.1f} MB")
    log(f"词表大小：{(assets / 'vocab.txt').stat().st_size / 1024:.1f} KB")
    log(f"总耗时 {time.time() - t0:.1f}s，完成。")


if __name__ == "__main__":
    main()

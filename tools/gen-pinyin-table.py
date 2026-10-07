#!/usr/bin/env python3
# 生成汉字→无调拼音表（用于语音识别结果的 LM 重打分纠错）。
#
# 数据源：mozillazg/pinyin-data 的 pinyin.txt（按频率排序的读音，取每个字最常见读音）。
# 输出：每行「汉字<TAB>无调拼音」，ü 转成 v（libime 拼音编码用 v 表示 ü）。
#
# 用法：
#   python gen-pinyin-table.py --out app/src/main/assets/memeboard/pinyin-table.txt
import argparse
import re
import unicodedata
import urllib.request

PINYIN_DATA_URL = "https://raw.githubusercontent.com/mozillazg/pinyin-data/master/pinyin.txt"

# 声调符号 → 无调元音（ü 直接映射为 v）
ACC = {
    'ā': 'a', 'á': 'a', 'ǎ': 'a', 'à': 'a',
    'ē': 'e', 'é': 'e', 'ě': 'e', 'è': 'e',
    'ī': 'i', 'í': 'i', 'ǐ': 'i', 'ì': 'i',
    'ō': 'o', 'ó': 'o', 'ǒ': 'o', 'ò': 'o',
    'ū': 'u', 'ú': 'u', 'ǔ': 'u', 'ù': 'u',
    'ǖ': 'v', 'ǘ': 'v', 'ǚ': 'v', 'ǜ': 'v', 'ü': 'v',
    'ń': 'n', 'ň': 'n', 'ǹ': 'n',
    'ê': 'e', 'ế': 'e', 'ề': 'e', 'ḿ': 'm',
}


def to_ascii(py: str) -> str:
    mapped = ''.join(ACC.get(ch, ch) for ch in py)
    return ''.join(c for c in mapped if unicodedata.category(c) != 'Mn')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="output pinyin-table.txt path")
    args = parser.parse_args()

    data = {}
    with urllib.request.urlopen(PINYIN_DATA_URL) as resp:
        for line in resp.read().decode("utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"U\+([0-9A-Fa-f]+):\s*([^#]+?)\s*(?:#\s*(.))?\s*$", line)
            if not m:
                continue
            cp = int(m.group(1), 16)
            first = m.group(2).split(",")[0].strip()
            data[chr(cp)] = to_ascii(first)

    with open(args.out, "w", encoding="utf-8") as f:
        for ch in sorted(data.keys()):
            f.write(f"{ch}\t{data[ch]}\n")
    print(f"wrote {len(data)} entries to {args.out}")


if __name__ == "__main__":
    main()

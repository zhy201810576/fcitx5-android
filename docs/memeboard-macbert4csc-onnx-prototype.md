# MemeBoard 语音纠错 · MacBERT4CSC 端侧 ONNX 原型

## 背景与目标

语音识别（sherpa-onnx Paraformer）的「拼音对、字错」同音字错误，此前用端侧 LLM
（Qwen2.5-1.5B + llama.cpp）选择性纠错。该路线的问题：

- 自回归逐 token 解码，短句也要数百 ms；模型约 1.3GB，内存压力大；
- 依赖 Vulkan/NDK/llama.cpp master 分支，已在 Adreno 上踩过 shader 链接与 SIGABRT；
- 结构性不匹配：同音字纠错是**等长逐字替换**，不是文本生成。

本原型用专用 CSC 模型 **MacBERT4CSC**（`shibing624/macbert4csc-base-chinese`，
Apache-2.0，102M 参数，SIGHAN15 字级 precision 0.9372）经 ONNX Runtime 端侧推理，
目标是以「纯 CPU、<50ms、~110MB」取代 LLM 成为默认纠错。

## 方案

```
ASR 原文 ──► BERT 分词（CJK 单 token）──► MacBERT4CSC INT8 ONNX ──► 逐字 argmax + softmax 阈值 ──► 仅高置信替换
```

关键点：

- 模型即标准 `BertForMaskedLM` 的导出（pycorrector 的 `MacBertCorrector` 直接加载
  `BertForMaskedLM`，推理用 MLM correction 权重），无需魔改网络；
- 逐字取 argmax 的 softmax 置信度，仅当 `prob >= threshold` 且预测为单个 CJK 字符时替换
  ——天然「等长替换、低置信不改」，无 LLM 的增删字/过度纠正问题；
- INT8 **动态量化**（无需校准集），约 110MB；纯 CPU 推理，绕开 Vulkan/NDK。

## 文件清单

新增：

| 文件 | 职责 |
| --- | --- |
| `tools/export_macbert4csc.py` | 下载模型 → 导出 ONNX → INT8 动态量化 → 复制到 csc 插件 assets → 自检 |
| `plugin/csc/` | 新插件 APK（仿 `:plugin:llm`），承载 `macbert4csc-base-int8.onnx` + `vocab.txt` |
| `app/.../link/MacBertTokenizer.kt` | 中文 BERT 最小分词器（CJK 单 token、标点丢弃、源字符↔token 对齐） |
| `app/.../link/MacBert4CscEngine.kt` | ONNX Runtime 会话 + 逐字 argmax/softmax 纠错 |
| `app/.../link/MacBert4CscController.kt` | 从 csc 插件 assets 拷贝模型到 filesDir + 预加载/释放 |
| `app/src/test/.../MacBertTokenizerTest.kt` | 分词器对齐逻辑单测 |

修改：

- `gradle/libs.versions.toml`：新增 `onnxruntime = "1.30.0"`；
- `app/build.gradle.kts`：`implementation(libs.onnxruntime.android)`；
- `settings.gradle.kts`：`include(":plugin:csc")`；
- `AsrRescore.kt`：新增 `correctWithCsc` + `postprocess` 分支（与 LLM 互斥，独立开关）；
- `MemeBoardPrefs.kt`：新增 `KEY_CSC_CORRECT` / `getCscCorrectEnabled`（默认关）；
- `AsrEvalCollector.kt`：样本增加 `cscEdits` 字段、汇总增加 csc 计数；
- `.gitignore`：忽略 csc 模型产物与导出 venv/中间目录。

## 导出模型（一次性）

模型仓库自带官方预导出 ONNX（`onnx/model.onnx`，标准 BertForMaskedLM，fp32 约 410MB），
因此**无需 torch/transformers 重新导出**，只需下载 + 动态量化。在 `fcitx5-android/` 下：

```bash
cd fcitx5-android/tools
python -m venv venv
venv/Scripts/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple \
    onnx "onnxruntime==1.19.2" numpy requests
venv/Scripts/python export_macbert4csc.py   # 国内自动走 hf-mirror，失败自动重试
```

产物写入 `plugin/csc/src/main/assets/csc/`（`macbert4csc-base-int8.onnx` ~110MB +
`vocab.txt` ~110KB）。脚本会用 ONNX Runtime 按与 Kotlin 侧一致的最小分词逻辑自检已知样例
`今天新情很好 → 今天心情很好`，并打印一组 IME 同音样例（`网安/水饺/你好嘛/你说是吧/他…`）
供人工核对。

> 坑：hf-mirror 偶发连接失败，脚本内已带重试；若长期不稳可改 `HF_ENDPOINT` 指向
> ModelScope（模型同名 `shibing624/macbert4csc-base-chinese`，但 ModelScope 只放 PyTorch
> 权重、无 `onnx/` 子目录，需回退到 torch+optimum 重新导出）。
> 另：Windows 下 `onnxruntime 1.30.0` 的 wheel 导入即段错误，工具侧固定 `==1.19.2`。
> 再：`vocab.txt` 含个别被 `str.splitlines()` 当行分隔符的字符（U+0085/U+2028 一类），
> 词表读取必须按「行迭代 + 去行尾空白」（21128 行），否则 splitlines 会多拆出 2 行导致
> token id 错位、预测全乱——脚本内 `load_vocab` 已按此处理，Kotlin 端 `readLine` 天然一致。

## 原型自检结果（阈值 0.7）

| 输入 | 输出 | 备注 |
| --- | --- | --- |
| 今天新情很好 | 今天心情很好 | 已知样例，新→心（conf 0.80）✓ |
| 网安 | 网安 | 单字对太短，模型不改（需句内语境） |
| 水饺 | 水饺 | 同上 |
| 你好嘛 | 你好嘛 | 同上 |
| 你说是吧 | 你说是吧 | 声调歧义正确保持原样 ✓ |
| 他要去哪里 | **她**要去哪里 | ⚠️ 误纠：无性别语境却把他改她（conf 0.89） |
| 她今天很开心 | 她今天很开心 | 正确不改 ✓ |

结论：模型能修「新→心」、不碰声调歧义，但存在**他/她过度纠正**（MacBERT4CSC 训练数据的
性别代词偏差）。这正是 LLM 路径里 prompt 专门约束第三人称代词的原因。阈值 0.9 能压掉这个
误纠（0.89 < 0.9），但也会误伤 新→心（0.80 < 0.9）——阈值是 precision/recall 的直接旋钮，
需在真实 ASR 样本上 tune；或后续对「他/她/它」做白名单/黑名单后处理。

## 构建与安装

构建在 WSL2（C++ 需 Linux），CSC 插件与 app 分开打包：

```bash
# WSL 内，同步源码后
./gradlew :plugin:csc:assembleDebug -PbuildABI=x86_64      # 模拟器；真机换 arm64-v8a
./gradlew :app:assembleDebug -PbuildABI=x86_64
```

装到设备：先装 app，再装 csc 插件 APK（`plugin/csc/build/outputs/apk/debug/` 下）。
与 LLM 插件同理，插件需单独安装，`MacBert4CscController` 经 `createPackageContext` 读其 assets。

## 评测（与 LLM side-by-side）

1. 系统设置 → 小企鹅输入法 → MemeBoard（或语音设置）：关掉「LLM 选择性纠错」，打开
   「CSC 纠错」开关（原型期默认关，开关在 `MemeBoardPrefs` 已就位）；
2. 语音输入若干句子，样本由 `AsrEvalCollector` 落盘到
   `filesDir/asr_eval/samples.jsonl`，`mode` 字段区分 `llm` / `csc` / `libime` / `none`，
   csc 样本含 `cscEdits`（形如 `2:新->心`）；
3. 对比两种模式的 `cscAccepted` / `llmAccepted` / `unchanged` 与人工核对 precision。

## 已知局限与下一步

- **领域错配**：SIGHAN 上的 0.9372 不代表对 Paraformer 错误分布同样精度；需用本机真实
  ASR 错误样本（`AsrEvalCollector` 已采集）做 side-by-side，甚至后续全参微调（102M 很小）。
- **无跨句语境**：当前 CSC 单句纠错，不注入光标前文字（靠句内语义即可区分同音字）；
  跨句代词（他/她/它）消歧目前仍是 LLM 的强项，CSC 版需评估是否够用。
- **阈值调优**：`threshold` 默认 0.7（pycorrector 默认），IME 偏好 precision 可调高；
  后续可做「候选式纠错」：低置信位置下划线标记、用户点选替换（`CscEdit` 已带位置与置信度）。
- **tokenizer 简化**：只处理 CJK + 中文标点，拉丁/数字已在 `AsrRescore` 上游过滤；
  若将来放开英文，需补 WordPiece 子词切分。
- **包体**：`onnxruntime-android`（完整版）每 ABI 约 45MB，原型可用；发布需评估
  `onnxruntime-mobile` 定制（裁掉不用的 op）或静态链接 sherpa-onnx 的 ORT 内核。
- **aboutlibraries**：新增依赖会触发 SPDX 联网，构建脚本已加超时快速失败跳过。

## 打包部署坑（2026-10-08 真机验证）

- **`libonnxruntime.so` 冲突（必踩）**：sherpa-onnx（语音 ASR）的 AAR 自带一份
  `libonnxruntime.so`（arm64 约 21MB），与 onnxruntime-android 1.30.0 的同名库（约 33MB）
  在 `mergeDebugNativeLibs` 阶段报 `2 files found with path 'lib/arm64-v8a/libonnxruntime.so'`。
  修复：`app/build.gradle.kts` 里 `packaging { jniLibs { pickFirsts += "**/libonnxruntime.so" } }`，
  并把 `onnxruntime-android` 声明在 `sherpa-onnx` **之前**（pickFirsts 取先声明者）。二者同为
  ORT C API（ABI 稳定），sherpa-onnx 的 jni 可兼容 1.30.0 的 .so；反之（保留 sherpa 旧 .so）
  则 `ai.onnxruntime` 的 JNI 桥会因 API 版本过旧而失败，故必须保留 1.30.0 版本。
  验证：`unzip -l <apk> | grep libonnxruntime` 看到 33MB 的即为 1.30.0。
- **签名**：真机装的是 release key（alias shiqu）签名包，debug 包须先 `apksigner sign` 重签
  再 `install -r`，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- **HyperOS 新包安装**：全新包（csc 插件首次安装）会被「USB 安装」拦截，报
  `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，需在手机开发者选项开启「USB 安装」
  或在弹窗点允许。

## 许可

- MacBERT4CSC 模型：Apache-2.0（`shibing624/macbert4csc-base-chinese`）。
- ONNX Runtime：MIT。
- 本 fork 主体：LGPL-2.1，改动保留许可声明。

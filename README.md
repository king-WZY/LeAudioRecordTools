# LeAudioRecordTools — 音频设备通路测试工具

Android 录音/回放测试应用，用于验证音频输入/输出设备通路是否可用，
重点支持 LE Audio 蓝牙设备的麦克风（上行）与扬声器（下行）双向验证。
内置离线语音识别（Vosk / Paraformer 双引擎），可直接验证拾音效果。

## 功能特性

- **输入设备可选**：主动探测所有音频输入设备（`isSource`），如内置 MIC、LE Audio 耳机、USB、蓝牙 SCO 等
- **输出设备可选**：主动探测所有音频输出设备（`isSink`），如扬声器、LE Audio、A2DP、USB、HDMI 等
- **采样率动态适配**：切换输入设备时读取其支持采样率（`getSampleRates()`）动态刷新；未上报时使用通用候选
- **实时电平监视**：录音时显示 RMS / 峰值 / dB 与电平条
- **实时波形显示**：示波器样式波形，实时反映输入振幅
- **录音数字增益**：可选 1x/2x/4x/8x/16x（+0/+6/+12/+18/+24 dB），补偿弱麦克风信号，带防削波
- **录音自动清理**：最多保留最近 10 个录音，超出自动删除最旧文件（启动及录音完成时执行）
- **WAV 录音**：PCM 16bit，保存到应用外部存储目录
- **显式设备路由**：通过 `setPreferredDevice()` 指定录音/回放设备，绕过 AudioPolicy 自动路由
- **离线语音识别（双引擎）**：
  - Vosk（`vosk-model-small-cn-0.22`）：流式识别，自带端点检测
  - Paraformer（阿里达摩院 ONNX int8）：非自回归模型，转录录音文件 + 实时滑动窗口识别
  - 内置静音检测：无语音输入时不推理，避免静音识别出乱码
  - ASR 独立增益（1x~64x）与实时输入电平显示，可一键分析建议增益

> 全部基于公开 Android API，不依赖特定设备或平台。

## 构建与安装

### 环境要求

| 依赖 | 版本 |
| :--- | :--- |
| JDK | 17 |
| Android SDK | compileSdk 36 |
| Gradle | 8.13（wrapper 内置） |

### 构建

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 安装与启动

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.qcc.leaudiorecord android.permission.RECORD_AUDIO
adb shell am start -n com.qcc.leaudiorecord/.MainActivity
```

## 使用说明

1. **选择录音输入**：点击"录音输入设备"，从弹出列表选择（默认内置 MIC）
2. **选择回放输出**：点击"回放输出设备"，从弹出列表选择（默认扬声器）
3. **选择采样率**：下拉框（随所选输入设备自动适配）
4. **选择录音增益**：下拉框（默认 1x；麦克风信号弱时调大，如 LE Audio 开发板 MIC 用 8x/16x）
5. **录音**：点击"开始录音"，对目标麦克风说话，观察实时电平/波形
6. **停止录音**：再次点击按钮，录音保存为 WAV，自动清理旧文件（最多保留 10 个）
7. **回放**：点击"回放"，从文件列表选择 WAV，声音从所选输出设备播放
8. **语音识别（验证拾音）**：
   - 点击"切换"在 **Vosk / Paraformer** 引擎间切换，首次使用自动从 assets 解压模型
   - **转录录音**：对最近一次录音文件做离线识别（Paraformer 要求 16kHz/单声道/16bit）
   - **实时识别**：对着麦克风说话，实时显示识别结果；静音时不输出，停顿后给出最终结果
   - **ASR 增益**：拾音弱时调大（建议 8x~64x）；"自动增益分析"可从最近录音一键计算建议值
   - **ASR 电平**：实时显示识别输入电平，便于判断麦克风信号是否足够

## 语音识别引擎

### Vosk（流式）

- 模型：`vosk-model-small-cn-0.22`，打包为 `app/src/main/assets/vosk/model.zip`
- 优点：轻量（~40MB）、流式增量识别、自带端点检测与中间结果

### Paraformer（离线 ONNX）

- 模型：`paraformer-zh-2023-09-14`（int8 量化），打包为 `app/src/main/assets/paraformer/model.zip`
  - 内含 `model.int8.onnx`（~243MB）、`tokens.txt`（8404 词表）、`am.mvn`（CMVN 参数）
- 特征管线与 sherpa-onnx/kaldi 参考实现严格对齐：int16 尺度 FBank（预加重 0.97 + 去直流 + snip_edges=true）、LFR(7,6)、CMVN
- 实时识别：2s 滑动窗口 / 0.5s 步进 / 独立推理线程，语音结束后自动输出最终结果
- 静音检测（自适应）：按 100ms 分块计算 RMS，峰值块与底噪差值 ≥10dB 且绝对值 >-90dBFS 才判定为有语音并推理，纯静音直接跳过（不输出乱码）；适配拾音较弱的设备（如开发板内置 MIC）

## 录音文件位置

```
/sdcard/Android/data/com.qcc.leaudiorecord/files/rec_<时间戳>_<采样率>hz.wav
```

拉取文件：

```bash
adb pull /sdcard/Android/data/com.qcc.leaudiorecord/files/rec_*.wav /tmp/
```

## 日志

| 类别 | 命令 |
| :--- | :--- |
| 应用日志（设备枚举 / 设备设置 / 采样率探测） | `adb logcat -s LeAudioRecord` |
| LE Audio 会话（上行/下行数据通路） | `adb logcat -s bluetooth` |

## 常见问题

| 现象 | 排查方向 |
| :--- | :--- |
| 设备列表为空 | 检查 `RECORD_AUDIO` 权限是否授予 |
| 输入设备设置后无声 | 确认设备在线；`logcat -s LeAudioRecord` 查看 `applied=true` |
| LE Audio 上行无声 | 确认蓝牙端 Source ASE 已激活；观察 `bluetooth` 日志中 `OnLocalAudioSinkResume` |
| 波形/电平静止 | 环境静音或所选 MIC 无信号，切换其他输入设备对照 |
| 实时识别静音也出字 | 已内置静音检测（-45dBFS 阈值）；若仍出现，多为环境噪声过强，调高 ASR 增益后重试 |
| Paraformer 转录提示"仅支持 16kHz/单声道/16bit" | 录音采样率需选 16000（Paraformer 模型固定 16k 输入） |
| Paraformer 模型加载慢 | 首次需从 assets 解压 ~243MB 模型到外部存储，之后直接复用缓存 |

## 工程结构

```
app/src/main/java/com/qcc/leaudiorecord/
├── MainActivity.kt        # UI + 设备探测 + 会话控制 + 引擎切换
├── AudioRecordPlayer.kt   # 录音/回放核心（setPreferredDevice）
├── WavFile.kt             # WAV 头写入/解析
├── WaveformView.kt        # 实时波形视图
├── AudioGainUtil.kt       # 电平分析 / 增益应用 / 自动增益建议
├── AsrEngine.kt           # 语音识别引擎统一接口
├── VoskHelper.kt          # Vosk 流式识别引擎
├── ParaformerHelper.kt    # Paraformer ONNX 引擎（特征 + 推理 + 实时识别 + 静音检测）
└── Fbank.kt               # FBank 特征提取（与 sherpa-onnx/kaldi 对齐）

app/src/main/assets/
├── vosk/model.zip         # vosk-model-small-cn-0.22
├── paraformer/model.zip   # paraformer-zh ONNX int8（model.int8.onnx + tokens.txt + am.mvn）
└── sounds/Lullaby.wav     # 默认回放音频
```

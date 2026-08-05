# LeAudioRecordTools — 音频设备通路测试工具

Android 录音/回放测试应用，用于验证音频输入/输出设备通路是否可用，
重点支持 LE Audio 蓝牙设备的麦克风（上行）与扬声器（下行）双向验证。

## 功能特性

- **输入设备可选**：主动探测所有音频输入设备（`isSource`），如内置 MIC、LE Audio 耳机、USB、蓝牙 SCO 等
- **输出设备可选**：主动探测所有音频输出设备（`isSink`），如扬声器、LE Audio、A2DP、USB、HDMI 等
- **采样率动态适配**：切换输入设备时读取其支持采样率（`getSampleRates()`）动态刷新；未上报时使用通用候选
- **实时电平监视**：录音时显示 RMS / 峰值 / dB 与电平条
- **实时波形显示**：示波器样式波形，实时反映输入振幅
- **WAV 录音**：PCM 16bit，保存到应用外部存储目录
- **显式设备路由**：通过 `setPreferredDevice()` 指定录音/回放设备，绕过 AudioPolicy 自动路由

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
4. **录音**：点击"开始录音"，对目标麦克风说话，观察实时电平/波形
5. **停止录音**：再次点击按钮，录音保存为 WAV
6. **回放**：点击"回放"，声音从所选输出设备播放

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

## 工程结构

```
app/src/main/java/com/qcc/leaudiorecord/
├── MainActivity.kt        # UI + 设备探测 + 会话控制
├── AudioRecordPlayer.kt   # 录音/回放核心（setPreferredDevice）
├── WavFile.kt             # WAV 头写入/解析
└── WaveformView.kt        # 实时波形视图
```

# LE Audio 录音测试工具 (LeAudioRecordTools)

用于验证 QCC5181 开发板 LE Audio 麦克风（上行）与扬声器（下行）通路的 Android 测试程序。

## 功能

- **录音输入设备可选**：本机 MIC / LE Audio 开发板 MIC / 蓝牙 SCO / 有线耳机等
- **回放输出设备可选**：本机扬声器 / LE Audio 开发板 / 蓝牙 A2DP 等
- **实时输入电平监视**：录音时显示 RMS / 峰值 / dB，直观验证 MIC 是否拾音
- **实时波形显示**：示波器样式波形，实时显示录音振幅
- **采样率可选**：8k / 16k / 24k / 32k / 44.1k / 48k（默认 16k，LE Audio 通话常用）
- 录音保存为 WAV（PCM 16bit mono），位于 App 外部存储目录
- 通过 `AudioRecord.setPreferredDevice()` / `AudioTrack.setPreferredDevice()`
  显式指定设备，绕过 AudioPolicy 自动路由

## 核心技术原理

```
┌─────────────────────────────────────────────────────────────┐
│                    设备通路验证                               │
│                                                             │
│  录音（上行 MIC）：                                          │
│  AudioRecord.setPreferredDevice(BLE_HEADSET_IN)             │
│    → AudioPolicy 强制路由到 0xa0000000                      │
│    → BT Stack: OnLocalAudioSinkResume                      │
│    → LE_AUDIO_SOFTWARE_DECODING_DATAPATH 会话               │
│    → Source ASE / CIS 上行 → 开发板 MIC                     │
│                                                             │
│  回放（下行扬声器）：                                        │
│  AudioTrack.setPreferredDevice(BLE_HEADSET_OUT)            │
│    → AudioPolicy 强制路由到 0x20000000                      │
│    → LE_AUDIO_SOFTWARE_ENCODING_DATAPATH 会话               │
│    → Sink ASE / CIS 下行 → 开发板扬声器                     │
└─────────────────────────────────────────────────────────────┘
```

## 构建与部署

```bash
# 构建
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/king/Android/Sdk
./gradlew assembleDebug

# 安装（设备已 adb 连接）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动并授予录音权限
adb shell pm grant com.qcc.leaudiorecord android.permission.RECORD_AUDIO
adb shell am start -n com.qcc.leaudiorecord/.MainActivity
```

## 使用步骤

1. 打开 App，确认设备枚举（"输入 N 个, 输出 N 个"）
2. **录音输入**：默认选中 LE Audio 耳机(QCC5181)，可切本机 MIC
3. **回放输出**：默认选中 LE Audio 耳机(QCC5181)，可切本机扬声器
4. 点击"开始录音"，对着设备说话，观察实时电平（应明显波动）
5. 点击"停止录音"
6. 点击"回放"，确认声音从所选输出设备播放

## 录音文件位置

```
/sdcard/Android/data/com.qcc.leaudiorecord/files/rec_<时间戳>_<采样率>hz.wav
```

可用 `adb pull` 拉取分析：

```bash
adb pull /sdcard/Android/data/com.qcc.leaudiorecord/files/rec_*.wav /tmp/
```

## 日志调试

```bash
# App 日志（设备枚举、设备设置结果）
adb logcat -s LeAudioRecord

# 蓝牙 LE Audio 栈日志（上行/下行会话）
adb logcat -s bluetooth | grep -iE "OnLocalAudioSinkResume|OnLocalAudioSourceResume|StartReceivingAudio|LE_AUDIO_SOFTWARE"
```

## 验证要点

| 检查项 | 预期 |
| :--- | :--- |
| 输入设备枚举 | 出现 LE Audio 耳机(QCC5181) type=26 |
| 输出设备枚举 | 出现 LE Audio 耳机(QCC5181) type=26 |
| 录音设备设置 | logcat 显示 `set input device ... applied=true` |
| 上行会话 | `LE_AUDIO_SOFTWARE_DECODING_DATAPATH started` |
| 实时电平 | 对着开发板说话电平明显波动（> -30dB） |
| 回放设备设置 | logcat 显示 `set output device ... applied=true` |
| 下行会话 | `LE_AUDIO_SOFTWARE_ENCODING_DATAPATH started` |

## 已知结论（2026-08-05 实测）

- Android 侧 LE Audio **双向通路可建立**（上行/下行会话均成功启动）
- LE Audio 输入录音电平约为 **-60 ~ -120dB**（微弱底噪），
  上行数据流稳定但**开发板 MIC 未有效拾音** → 需排查开发板固件 MIC 通路
- 本机 MIC 在静音环境为全零（峰值 0），属正常
- 完整链路分析见 QCC5181 仓库文档：
  `docs/headset/QCC5181-LE-Audio-VoIP-call-bidirectional-fix.md`

## 本机 MIC 全零的根因（Rockchip HAL 虚拟卡 100 缺失）

**现象**：硬件 MIC（ES8323 codec）正常（tinycap 直录有声），扬声器/LE Audio 输出正常，
但 App 用本机 MIC 录音全零。

**根因**：`audio.primary.rk30board.so`（Rockchip tinyalsa HAL）的内置 MIC 录音路径
硬编码使用**虚拟声卡 100**（`SND_OUT_VIRTUAL_CARD_SPEAKER=100`，audio_hw.h:116）：
- 内置 MIC 地址固定为 `"bottom"`（`in_get_microphones` 硬编码）→ `in->address != NULL`
  → 走虚拟卡分支（audio_hw.c:1815-1820）
- 虚拟卡 100 需 `snd_aloop.index=100` 内核参数创建；本设备 bootargs 未配置
  → snd_aloop 自动分配为 card 2 → `pcm_open(100, 0)` 失败 → 录音全零

**日志证据**：
```
E modules.tinyalsa.audio_hal: pcm_open() failed: cannot open device 0 for card 100: No such file or directory
D modules.tinyalsa.audio_hal: start_input_stream open card = 100, device = 0 fail
```

**修复方案**：
- **方案 A（符合 Rockchip 平台设计）**：bootargs/bootconfig 加 `snd_aloop.index=100`，
  让 snd_aloop 注册为虚拟卡 100（Rockchip 参考平台 rk3308/rk1808 均用此法）
- **方案 B（HAL 层）**：修改 `start_input_stream`，当虚拟卡 100 打开失败时
  fallback 到真实 MIC 卡（card 1）
- **次要问题**：`mixer_paths.xml` 引用 `Capture MIC Path` 控件，但 ES8388 驱动实际
  控件名为 `Main Mic Switch`/`Differential Mux`/`Line Mux`，需确认 HAL 匹配逻辑

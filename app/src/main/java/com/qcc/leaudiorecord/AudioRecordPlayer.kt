package com.qcc.leaudiorecord

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 录音/回放核心逻辑（纯 Android framework 实现）
 *
 * - 录音：AudioRecord + 指定输入设备（setPreferredDevice）+ 写入 WAV
 * - 回放：AudioTrack + 指定输出设备（setPreferredDevice）+ 读取 WAV
 *
 * 通过 setPreferredDevice 显式选择设备，绕过 AudioPolicy 的自动路由，
 * 从而可以直接验证"本机 MIC"与"LE Audio 开发板 MIC"两条通路。
 */
class AudioRecordPlayer {

    companion object {
        private const val TAG = "LeAudioRecord"
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        /** 录音采样率（LE Audio 通话常用 16kHz/32kHz，可选） */
        var sampleRate: Int = 16000

        /**
         * 录音数字增益倍数（默认 1.0 = 不处理）。
         * 用于补偿麦克风输入信号过弱（如 LE Audio 开发板 MIC 增益不足）。
         * 应用时带防削波饱和（clip 到 Int16 范围）。
         */
        var recordGain: Float = 1.0f

        /**
         * 实时语音识别增益倍数（默认 1.0 = 不处理）。
         * 与录音增益独立，以便 ASR 时使用更高增益补偿。
         */
        var asrGain: Float = 1.0f

        /** 通用采样率候选（当设备未上报支持采样率时使用） */
        val FALLBACK_SAMPLE_RATES = intArrayOf(8000, 16000, 24000, 32000, 44100, 48000)

        /**
         * 生成设备显示名（通用探测，不针对特定设备/平台）。
         * 格式：类型名 (厂商 productName) [address]
         */
        fun deviceLabel(d: AudioDeviceInfo): String {
            val typeName = typeName(d.type)
            val name = d.productName?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: ""
            val addr = try {
                d.address
            } catch (_: Exception) {
                ""
            }
            val namePart = if (name.isNotBlank() && !name.equals(typeName, ignoreCase = true)) {
                " ($name)"
            } else ""
            val addrPart = if (addr.isNotBlank() && addr != "0") " [$addr]" else ""
            return "$typeName$namePart$addrPart"
        }

        /** 设备类型 → 通用中文名（覆盖 API 36 全部 TYPE_* 常量） */
        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置 MIC"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "安全扬声器"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙 A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio 耳机"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "LE Audio 音箱"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "LE Audio 广播"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机(MIC)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
            AudioDeviceInfo.TYPE_HEARING_AID -> "助听器"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX 线路"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路"
            AudioDeviceInfo.TYPE_DOCK -> "底座"
            AudioDeviceInfo.TYPE_DOCK_ANALOG -> "模拟底座"
            AudioDeviceInfo.TYPE_FM -> "FM 收音机"
            AudioDeviceInfo.TYPE_FM_TUNER -> "FM 调谐器"
            AudioDeviceInfo.TYPE_TV_TUNER -> "TV 调谐器"
            AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
            AudioDeviceInfo.TYPE_IP -> "IP 通话"
            AudioDeviceInfo.TYPE_BUS -> "总线设备"
            AudioDeviceInfo.TYPE_MULTICHANNEL_GROUP -> "多声道组"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远程混合"
            AudioDeviceInfo.TYPE_UNKNOWN -> "未知设备"
            else -> "设备(type=$type)"
        }
    }

    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private val recording = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private var lastFile: File? = null

    /** 当前正在录音（线程安全） */
    val isRecording: Boolean get() = recording.get()
    val isPlaying: Boolean get() = playing.get()

    fun getLastFile(): File? = lastFile

    /**
     * 开始录音
     *
     * @param outFile 输出的 WAV 文件
     * @param inputDevice 录音输入设备（可为 null 使用系统默认）
     * @param onStatus 状态回调（主线程）
     * @param onLevel 实时电平回调（主线程，约 4 次/秒，参数为峰值 RMS 分贝）
     * @param onWaveform 实时波形回调（主线程，每帧原始 PCM 采样）
     */
    fun startRecording(
        outFile: File,
        inputDevice: AudioDeviceInfo?,
        onStatus: (String) -> Unit,
        onLevel: ((peak: Int, rms: Int, db: Float) -> Unit)? = null,
        onWaveform: ((ShortArray) -> Unit)? = null
    ): Boolean {
        if (recording.get()) {
            onStatus("已在录音中")
            return false
        }

        val rate = sampleRate
        val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_IN, ENCODING)
        if (minBuf <= 0) {
            onStatus("录音参数不支持: rate=$rate")
            return false
        }

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate, CHANNEL_IN, ENCODING,
                minBuf * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onStatus("AudioRecord 初始化失败（检查录音权限）")
                record.release()
                return false
            }
            val deviceText = if (inputDevice != null) {
                record.preferredDevice = inputDevice
                val applied = record.preferredDevice == inputDevice
                Log.i(TAG, "set input device: ${deviceLabel(inputDevice)} applied=$applied")
                deviceLabel(inputDevice) + (if (applied) " ✓" else " (可能未生效)")
            } else {
                "系统默认"
            }
            onStatus("录音设备: $deviceText")

            val writer = WavFileWriter(outFile, rate, CHANNEL_IN, ENCODING)
            record.startRecording()
            recording.set(true)

            recordThread = thread(name = "recorder") {
                val buf = ByteArray(minBuf)
                var lastReport = 0L
                try {
                    while (recording.get()) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) {
                            // 数字增益（补偿弱麦克风信号），带防削波饱和
                            val g = recordGain
                            if (g != 1.0f) {
                                val samples = n / 2
                                for (i in 0 until samples) {
                                    val idx = i * 2
                                    val raw = ((buf[idx].toInt() and 0xFF) or
                                            (buf[idx + 1].toInt() shl 8)).toShort().toInt()
                                    val v = (raw * g).toInt().coerceIn(-32768, 32767)
                                    buf[idx] = (v and 0xFF).toByte()
                                    buf[idx + 1] = ((v shr 8) and 0xFF).toByte()
                                }
                            }
                            writer.write(buf, 0, n)
                            // 电平 + 波形（每 ~200ms 报告一次）
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 200) {
                                lastReport = now
                                val samples = n / 2
                                if (onLevel != null) {
                                    var peak = 0
                                    var sumSq = 0L
                                    for (i in 0 until samples) {
                                        val s = (buf[i * 2].toInt() and 0xFF) or
                                                (buf[i * 2 + 1].toInt() shl 8)
                                        val v = s.toShort().toInt()
                                        if (v < 0) -v else v.let { peak = maxOf(peak, it) }
                                        sumSq += v.toLong() * v
                                    }
                                    val rms = if (samples > 0) {
                                        kotlin.math.sqrt((sumSq / samples).toDouble()).toInt()
                                    } else 0
                                    val db = if (rms > 0) {
                                        20f * kotlin.math.log10(rms / 32768f)
                                    } else -120f
                                    val pk = peak
                                    val rm = rms
                                    val d = db
                                    Handler(Looper.getMainLooper()).post {
                                        onLevel(pk, rm, d)
                                    }
                                }
                                if (onWaveform != null) {
                                    val wf = ShortArray(samples)
                                    for (i in 0 until samples) {
                                        wf[i] = ((buf[i * 2].toInt() and 0xFF) or
                                                (buf[i * 2 + 1].toInt() shl 8)).toShort()
                                    }
                                    val s = wf
                                    Handler(Looper.getMainLooper()).post {
                                        onWaveform(s)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "record error", e)
                } finally {
                    try {
                        writer.close()
                        writer.patchHeader(outFile)
                    } catch (e: Exception) {
                        Log.e(TAG, "wav finalize error", e)
                    }
                    try {
                        record.stop()
                    } catch (_: Exception) {
                    }
                    record.release()
                    lastFile = outFile
                    val dataBytes = writer.dataBytes
                    val seconds = if (rate > 0) dataBytes / (rate.toLong() * BYTES_PER_SAMPLE) else 0L
                    Handler(Looper.getMainLooper()).post {
                        onStatus(
                            "录音完成: ${outFile.absolutePath}\n" +
                                    "大小: ${outFile.length()} bytes, 时长: ${seconds}s"
                        )
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording error", e)
            record?.release()
            onStatus("录音启动失败: ${e.message}")
            return false
        }
    }

    /** 停止录音 */
    fun stopRecording() {
        recording.set(false)
        recordThread?.join(2000)
        recordThread = null
    }

    /**
     * 回放 WAV 文件
     *
     * @param file 要回放的 WAV 文件
     * @param outputDevice 输出设备（可为 null 使用系统默认）
     * @param onStatus 状态回调（主线程）
     */
    fun play(file: File, outputDevice: AudioDeviceInfo?, onStatus: (String) -> Unit): Boolean {
        if (playing.get()) {
            onStatus("正在回放中")
            return false
        }
        if (!file.exists()) {
            onStatus("文件不存在: ${file.absolutePath}")
            return false
        }

        // 解析 WAV 头
        val header = WavFileReader.parse(file)
            ?: run {
                onStatus("无法解析 WAV 文件头")
                return false
            }
        val rate = header.sampleRate
        val channels = if (header.channels == 2) AudioFormat.CHANNEL_OUT_STEREO
        else AudioFormat.CHANNEL_OUT_MONO
        val enc = when (header.bitsPerSample) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            8 -> AudioFormat.ENCODING_PCM_8BIT
            else -> {
                onStatus("不支持的位深: ${header.bitsPerSample}")
                return false
            }
        }

        val minBuf = AudioTrack.getMinBufferSize(rate, channels, enc)
        if (minBuf <= 0) {
            onStatus("回放参数不支持")
            return false
        }

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(channels)
                        .setEncoding(enc)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                onStatus("AudioTrack 初始化失败")
                track.release()
                return false
            }
            val deviceText = if (outputDevice != null) {
                track.preferredDevice = outputDevice
                val applied = track.preferredDevice == outputDevice
                Log.i(TAG, "set output device: ${deviceLabel(outputDevice)} applied=$applied")
                deviceLabel(outputDevice) + (if (applied) " ✓" else " (可能未生效)")
            } else {
                "系统默认"
            }
            onStatus("回放设备: $deviceText")

            playing.set(true)
            playThread = thread(name = "player") {
                try {
                    FileInputStream(file).use { fis ->
                        fis.skip(header.dataOffset)
                        track.play()
                        val buf = ByteArray(minBuf)
                        while (playing.get()) {
                            val n = fis.read(buf)
                            if (n < 0) break
                            var off = 0
                            while (off < n) {
                                val w = track.write(buf, off, n - off)
                                if (w < 0) break
                                off += w
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "play error", e)
                } finally {
                    try {
                        track.stop()
                    } catch (_: Exception) {
                    }
                    track.release()
                    playing.set(false)
                    Handler(Looper.getMainLooper()).post {
                        onStatus("回放完成: ${file.name}")
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "play error", e)
            track?.release()
            onStatus("回放启动失败: ${e.message}")
            return false
        }
    }

    /** 停止回放 */
    fun stopPlayback() {
        playing.set(false)
        playThread?.join(2000)
        playThread = null
    }

    /** 释放资源 */
    fun release() {
        stopRecording()
        stopPlayback()
    }
}

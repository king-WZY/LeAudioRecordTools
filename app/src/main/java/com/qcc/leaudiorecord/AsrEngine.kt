package com.qcc.leaudiorecord

import android.media.AudioDeviceInfo
import java.io.File

/**
 * 语音识别引擎的统一接口
 *
 * Vosk 和 Paraformer 都实现此接口，上层通过 AsrManager 透明切换。
 */
interface AsrEngine {

    /** 引擎名称（用于 UI 显示） */
    val name: String

    /** 模型是否已就绪 */
    val isReady: Boolean

    /**
     * 初始化引擎：加载模型，完成后回调
     * @param onReady  就绪回调
     * @param onError  错误回调
     */
    fun initialize(onReady: () -> Unit, onError: (String) -> Unit)

    /**
     * 转录 WAV 文件（16kHz, mono, 16bit）
     * @param wavFile  WAV 文件
     * @param onResult 识别结果（主线程）
     * @param onError  错误（主线程）
     */
    fun transcribeFile(wavFile: File, onResult: (String) -> Unit, onError: (String) -> Unit)

    /**
     * 启动实时流式识别
     *
     * 引擎自建 AudioRecord 采集（与录音路径独立），通过 [inputDevice] 绑定到
     * 用户在 UI 上选择的输入设备（经 `setPreferredDevice`），确保路由与"开始录音"
     * 一致；不指定时走系统默认路由。
     *
     * @param inputDevice 指定的录音输入设备（null = 系统默认）
     * @param onPartial 中间结果（主线程）
     * @param onFinal   最终结果（主线程）
     * @param onError   错误（主线程）
     * @param onLevel   实时电平回调（主线程，可选），参数 peak/rms/dbfs
     */
    fun startListening(
        inputDevice: AudioDeviceInfo?,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: ((peak: Int, rms: Int, dbfs: Float) -> Unit)? = null
    )

    /** 停止实时流式识别 */
    fun stopListening()

    /** 释放所有资源 */
    fun release()
}
package com.qcc.leaudiorecord

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Vosk 离线语音识别辅助类
 *
 * 职责：
 * 1. 管理模型文件生命周期（从 assets 解压到内部存储）
 * 2. 初始化 Vosk 引擎
 * 3. 提供 WAV 文件转录与实时流式识别两种接口
 *
 * 模型要求：将 vosk-model-small-cn-0.22 或 vosk-model-small-en-us-0.15 的 zip
 * 包放入 app/src/main/assets/vosk/ 目录下，应用首次启动时自动解压。
 */
class VoskHelper(private val appContext: Context) {

    companion object {
        private const val TAG = "LeAudioVosk"
        /** assets 中模型 zip 包的路径 */
        private const val ASSET_MODEL_ZIP = "vosk/model.zip"
        /** 内部存储解压后的模型目录名 */
        private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null

    /** 模型是否已就绪 */
    val isReady: Boolean get() = model != null

    // ======================== 模型部署 ========================

    /**
     * 检查模型是否已解压，若否则从 assets 解压。
     * 解压完成后调用 [onReady] 回调。
     */
    fun ensureModel(onReady: () -> Unit, onError: (String) -> Unit) {
        val modelDir = getModelDir()
        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            Log.i(TAG, "model dir already exists: ${modelDir.absolutePath}")
            loadModel(modelDir, onReady, onError)
            return
        }

        // 从 assets 解压模型 zip（zip 内含顶层目录，自动剥离）
        try {
            appContext.assets.open(ASSET_MODEL_ZIP).use { stream ->
                val zis = ZipInputStream(stream)
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    // 剥离顶层目录（如 vosk-model-small-cn-0.22/am → am）
                    val relative = entry.name.substringAfter('/')
                    if (relative.isBlank()) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val target = File(modelDir, relative)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                out.write(buffer, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "model unzipped to ${modelDir.absolutePath}")
            loadModel(modelDir, onReady, onError)
        } catch (e: Exception) {
            Log.e(TAG, "failed to extract model", e)
            onError("模型解压失败: ${e.message}")
        }
    }

    private fun getModelDir(): File {
        // 放在 app 外部文件目录下，避免被清理
        return File(appContext.getExternalFilesDir(null), MODEL_DIR_NAME)
    }

    private fun loadModel(modelDir: File, onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            model = Model(modelDir.absolutePath)
            Log.i(TAG, "Vosk model loaded")
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "failed to load model", e)
            onError("模型加载失败: ${e.message}")
        }
    }

    // ======================== WAV 文件转录 ========================

    /**
     * 转录 WAV 文件（16kHz, mono, 16bit）。
     *
     * @param wavFile  WAV 文件
     * @param onResult 识别结果回调（主线程）
     * @param onError  错误回调
     */
    fun transcribeFile(wavFile: File, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val m = model ?: run {
            onError("模型未初始化")
            return
        }
        thread {
            try {
                // 使用 16kHz 采样率识别（Vosk 默认）
                val rec = Recognizer(m, 16000.0f)
                rec.setWords(false) // 不需要单词语义时间戳，只取文本
                val fis = FileInputStream(wavFile)
                // 跳过 WAV 头（44 bytes）
                fis.skip(44)
                val buf = ByteArray(4096)
                var n: Int
                var finalText = ""
                while (fis.read(buf).also { n = it } > 0) {
                    if (rec.acceptWaveForm(buf, n)) {
                        val result = rec.result
                        // 从 JSON 中提取 "text" 字段
                        val text = extractText(result)
                        if (text.isNotBlank()) {
                            finalText += text + " "
                        }
                    }
                }
                // 获取最后一段
                val finalResult = rec.finalResult
                val text = extractText(finalResult)
                if (text.isNotBlank()) {
                    finalText += text
                }
                fis.close()
                rec.close()
                val trimmed = finalText.trim()
                Handler(Looper.getMainLooper()).post {
                    if (trimmed.isNotBlank()) {
                        onResult(trimmed)
                    } else {
                        onResult("(未识别到语音)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe error", e)
                Handler(Looper.getMainLooper()).post {
                    onError("转录失败: ${e.message}")
                }
            }
        }
    }

    // ======================== 实时流式识别 ========================

    /**
     * 启动实时流式语音识别。
     * 使用 Vosk 的 SpeechService 封装，从默认 MIC 实时采集识别。
     *
     * @param onPartial  中间结果（主线程）
     * @param onFinal    最终结果（主线程）
     * @param onError    错误回调
     */
    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val m = model ?: run {
            onError("模型未初始化")
            return
        }
        if (speechService != null) {
            onError("已在识别中")
            return
        }
        try {
            val rec = Recognizer(m, 16000.0f)
            speechService = SpeechService(rec, 16000.0f).apply {
                startListening(object : RecognitionListener {
                    override fun onResult(result: String) {
                        val text = extractText(result)
                        if (text.isNotBlank()) {
                            Handler(Looper.getMainLooper()).post { onFinal(text) }
                        }
                    }

                    override fun onFinalResult(result: String) {
                        val text = extractText(result)
                        if (text.isNotBlank()) {
                            Handler(Looper.getMainLooper()).post { onFinal(text) }
                        }
                    }

                    override fun onPartialResult(result: String) {
                        val text = extractText(result)
                        if (text.isNotBlank()) {
                            Handler(Looper.getMainLooper()).post { onPartial(text) }
                        }
                    }

                    override fun onError(e: Exception) {
                        Log.e(TAG, "speech service error", e)
                        Handler(Looper.getMainLooper()).post { onError(e.message ?: "识别错误") }
                    }

                    override fun onTimeout() {
                        Log.w(TAG, "speech service timeout")
                    }
                })
            }
            Log.i(TAG, "real-time listening started")
        } catch (e: Exception) {
            Log.e(TAG, "start listening failed", e)
            onError("启动识别失败: ${e.message}")
        }
    }

    /** 停止实时流式识别 */
    fun stopListening() {
        speechService?.let {
            try {
                it.stop()
                it.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "stop listening error", e)
            }
        }
        speechService = null
    }

    // ======================== 工具方法 ========================

    /**
     * 从 Vosk JSON 结果中提取 text 字段。
     * 例如 {"text": "你好世界"} → "你好世界"
     */
    private fun extractText(json: String): String {
        val marker = "\"text\" : \""
        val start = json.indexOf(marker)
        if (start < 0) return ""
        val begin = start + marker.length
        val end = json.indexOf('"', begin)
        return if (end > begin) json.substring(begin, end) else ""
    }

    /** 释放所有资源 */
    fun release() {
        stopListening()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}

/**
 * 启动一个后台线程执行代码块
 */
private fun thread(block: () -> Unit): Thread {
    return Thread(block).apply { start() }
}
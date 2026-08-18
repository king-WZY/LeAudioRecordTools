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
 * Vosk 离线语音识别引擎 —— 实现 [AsrEngine] 接口
 *
 * 模型要求：将 vosk-model-small-cn-0.22 的 zip 包放入
 * app/src/main/assets/vosk/ 目录下，应用首次启动时自动解压。
 */
class VoskHelper(private val appContext: Context) : AsrEngine {

    override val name: String get() = "Vosk"

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

    override val isReady: Boolean get() = model != null

    // ======================== 模型部署 ========================

    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        ensureModel(onReady, onError)
    }

    private fun ensureModel(onReady: () -> Unit, onError: (String) -> Unit) {
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

    override fun transcribeFile(wavFile: File, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val m = model ?: run {
            onError("模型未初始化")
            return
        }
        thread {
            try {
                val rec = Recognizer(m, 16000.0f)
                rec.setWords(false)
                val fis = FileInputStream(wavFile)
                fis.skip(44)
                val buf = ByteArray(4096)
                var n: Int
                var finalText = ""
                while (fis.read(buf).also { n = it } > 0) {
                    if (rec.acceptWaveForm(buf, n)) {
                        val result = rec.result
                        val text = extractText(result)
                        if (text.isNotBlank()) {
                            finalText += text + " "
                        }
                    }
                }
                val finalResult = rec.finalResult
                val text = extractText(finalResult)
                if (text.isNotBlank()) {
                    finalText += text
                }
                fis.close()
                rec.close()
                val trimmed = finalText.trim()
                Handler(Looper.getMainLooper()).post {
                    onResult(trimmed.ifBlank { "(未识别到语音)" })
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe error", e)
                Handler(Looper.getMainLooper()).post { onError("转录失败: ${e.message}") }
            }
        }
    }

    // ======================== 实时流式识别 ========================

    override fun startListening(
        inputDevice: android.media.AudioDeviceInfo?,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: ((peak: Int, rms: Int, dbfs: Float) -> Unit)?
    ) {
        val m = model ?: run {
            onError("模型未初始化")
            return
        }
        if (speechService != null) {
            onError("已在识别中")
            return
        }
        if (inputDevice != null) {
            // Vosk SpeechService 内部自建 AudioRecord，无法 setPreferredDevice。
            // 此处记录但仍走 Vosk 自采路径（如需指定设备路由请用 Paraformer）。
            Log.w(TAG, "inputDevice=${inputDevice.id} ignored by Vosk (uses internal AudioSource.MIC)")
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

    override fun stopListening() {
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

    private fun extractText(json: String): String {
        val marker = "\"text\" : \""
        val start = json.indexOf(marker)
        if (start < 0) return ""
        val begin = start + marker.length
        val end = json.indexOf('"', begin)
        return if (end > begin) json.substring(begin, end) else ""
    }

    override fun release() {
        stopListening()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}

private fun thread(block: () -> Unit): Thread {
    return Thread(block).apply { start() }
}
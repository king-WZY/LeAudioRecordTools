package com.qcc.leaudiorecord

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * Paraformer 离线语音识别引擎（阿里达摩院非自回归模型）
 *
 * 模型输入/输出（来自 sherpa-onnx 打包的 paraformer-zh-2023-09-14）：
 *   speech:         [batch, T, 560]  float32  — 80维FBank经LFR(7帧,stride=6)后+CMVN
 *   speech_lengths: [batch]           int32
 *   logits:         [batch, T', 8404] float32  — 8404 = vocab_size
 *   token_num:      [batch]           int32    — 有效token数
 *   us_alphas:      [batch, T, 1]     float32  — 未使用
 *   us_cif_peak:    [batch, T, 1]     float32  — 未使用
 *
 * 模型文件放在 assets/paraformer/model.zip 中，包含：
 *   - model.int8.onnx    (ONNX 模型，int8 量化)
 *   - tokens.txt          (词表，8404个token)
 *   - am.mvn              (CMVN 归一化参数)
 */
class ParaformerHelper(private val appContext: Context) : AsrEngine {

    override val name: String get() = "Paraformer"

    companion object {
        private const val TAG = "LeAudioParaformer"
        private const val ASSET_MODEL_ZIP = "paraformer/model.zip"
        private const val MODEL_DIR_NAME = "paraformer-model"

        /** LFR 参数 */
        private const val LFR_M = 7   // 帧拼接窗长
        private const val LFR_N = 6   // 帧移步长

        /** 采样率 */
        const val SAMPLE_RATE = 16000

        /** 实时识别音频块时长 (ms) */
        private const val CHUNK_MS = 800
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokens: List<String> = emptyList()
    private var cmvnNegMean: FloatArray? = null
    private var cmvnInvStd: FloatArray? = null
    private var fbank: FbankExtractor? = null

    // 实时识别相关
    private var listenThread: Thread? = null
    @Volatile private var isListening = false
    private var audioRecord: android.media.AudioRecord? = null
    private var onPartialCb: ((String) -> Unit)? = null
    private var onFinalCb: ((String) -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null
    private var onLevelCb: ((Int, Int, Float) -> Unit)? = null

    override val isReady: Boolean get() = ortSession != null

    // ======================== 模型部署 ========================

    override fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        ensureModel(onReady, onError)
    }

    private fun ensureModel(onReady: () -> Unit, onError: (String) -> Unit) {
        val modelDir = getModelDir()
        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            Log.i(TAG, "model dir exists: ${modelDir.absolutePath}")
            loadModel(modelDir, onReady, onError)
            return
        }

        try {
            appContext.assets.open(ASSET_MODEL_ZIP).use { stream ->
                val zis = ZipInputStream(stream)
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
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
            Log.e(TAG, "extract model failed", e)
            onError("模型解压失败: ${e.message}")
        }
    }

    private fun getModelDir(): File {
        return File(appContext.getExternalFilesDir(null), MODEL_DIR_NAME)
    }

    private fun loadModel(modelDir: File, onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            // 加载 ONNX 模型
            val modelFile = File(modelDir, "model.int8.onnx")
            if (!modelFile.exists()) {
                onError("模型文件不存在: ${modelFile.absolutePath}")
                return
            }

            // 加载词表
            val tokensFile = File(modelDir, "tokens.txt")
            if (tokensFile.exists()) {
                tokens = tokensFile.readLines().map { it.trim().split(" ").first() }
                Log.i(TAG, "loaded ${tokens.size} tokens")
            } else {
                Log.w(TAG, "tokens.txt not found")
                tokens = emptyList()
            }

            // 加载 CMVN 参数
            val cmvnFile = File(modelDir, "am.mvn")
            if (cmvnFile.exists()) {
                loadCmvn(cmvnFile)
            } else {
                Log.w(TAG, "am.mvn not found, CMVN disabled")
            }

            // 创建 ONNX 会话
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOpts)
            fbank = FbankExtractor()

            Log.i(TAG, "Paraformer model loaded")
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "load model failed", e)
            onError("模型加载失败: ${e.message}")
        }
    }

    /**
     * 解析 am.mvn 文件（kaldi 格式）
     * 格式：<LearnRateCoef> 1 <LearnRateCoef> 值1 值2 ... 值N
     * 前 560 个为 neg_mean，后 560 个为 inv_std
     */
    private fun loadCmvn(file: File) {
        val text = file.readText()
        val parts = text.split(Regex("\\s+"))
        // 找到 <LearnRateCoef> 后面的数值
        var startIdx = -1
        for (i in parts.indices) {
            if (parts[i] == "<LearnRateCoef>" && i + 2 < parts.size) {
                startIdx = i + 2
                break
            }
        }
        if (startIdx < 0) {
            Log.w(TAG, "am.mvn: <LearnRateCoef> not found")
            return
        }

        // 从 startIdx 到末尾一共 1120 个值（560 neg_mean + 560 inv_std）
        val values = parts.drop(startIdx).mapNotNull { it.toFloatOrNull() }
        if (values.size < 1120) {
            Log.w(TAG, "am.mvn: expected 1120 values, got ${values.size}")
            return
        }

        val featDim = 560
        cmvnNegMean = FloatArray(featDim) { values[it] }
        cmvnInvStd = FloatArray(featDim) { values[featDim + it] }
        Log.i(TAG, "CMVN loaded: neg_mean[0]=${cmvnNegMean!![0]}")
    }

    // ======================== 特征处理 ========================

    /**
     * 提取 FBank 特征 → LFR 拼接 → CMVN 归一化
     *
     * @param pcm 16kHz mono PCM 数据
     * @return [T, 560] float32 特征矩阵
     */
    private fun computeFeatures(pcm: ShortArray): Array<FloatArray> {
        val fb = fbank ?: return emptyArray()
        // 1. 提取 80 维 FBank
        val fbankFeats = fb.extract(pcm)
        if (fbankFeats.isEmpty()) return emptyArray()

        val t = fbankFeats.size
        val d = 80

        // 2. LFR：7 帧拼接，stride=6
        val lfrT = maxOf(0, (t - LFR_M) / LFR_N + 1)
        if (lfrT <= 0) return emptyArray()

        val lfrFeats = Array(lfrT) { i ->
            val startFrame = i * LFR_N
            val feat = FloatArray(d * LFR_M)
            for (j in 0 until LFR_M) {
                val srcIdx = startFrame + j
                if (srcIdx < t) {
                    System.arraycopy(fbankFeats[srcIdx], 0, feat, j * d, d)
                } else {
                    // 超出部分用最后一帧填充
                    System.arraycopy(fbankFeats[t - 1], 0, feat, j * d, d)
                }
            }
            feat
        }

        // 3. CMVN 归一化
        val negMean = cmvnNegMean
        val invStd = cmvnInvStd
        if (negMean != null && invStd != null) {
            for (i in 0 until lfrT) {
                for (j in 0 until d * LFR_M) {
                    lfrFeats[i][j] = (lfrFeats[i][j] + negMean[j]) * invStd[j]
                }
            }
        }

        return lfrFeats
    }

    // ======================== WAV 文件转录 ========================

    override fun transcribeFile(wavFile: File, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val session = ortSession ?: run { onError("模型未初始化"); return }

        thread {
            try {
                // 读取 WAV 文件（跳过 44 字节头）
                val fis = FileInputStream(wavFile)
                fis.skip(44)
                val pcmBytes = fis.readBytes()
                fis.close()

                // 转为 ShortArray
                val shortBuf = ShortArray(pcmBytes.size / 2)
                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuf)

                // 推理
                val text = runInference(session, shortBuf)
                Handler(Looper.getMainLooper()).post {
                    onResult(text.ifBlank { "(未识别到语音)" })
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe error", e)
                Handler(Looper.getMainLooper()).post { onError("转录失败: ${e.message}") }
            }
        }
    }

    /**
     * ONNX 推理核心
     */
    private fun runInference(session: OrtSession, pcm: ShortArray): String {
        val env = ortEnv ?: return "(引擎未初始化)"

        // 1. 提取特征
        val features = computeFeatures(pcm)
        if (features.isEmpty()) return "(音频过短)"

        val t = features.size
        val featDim = 560

        // 2. 展平为 [T, 560] 连续内存
        val flatInput = FloatArray(t * featDim)
        for (i in 0 until t) {
            System.arraycopy(features[i], 0, flatInput, i * featDim, featDim)
        }

        // 3. 创建输入张量
        // speech: [1, T, 560]
        val speechShape = longArrayOf(1, t.toLong(), featDim.toLong())
        val speechBuf = FloatBuffer.wrap(flatInput)
        val speechTensor = OnnxTensor.createTensor(env, speechBuf, speechShape)

        // speech_lengths: [1] = T (int32)
        val lenBuf = IntBuffer.wrap(intArrayOf(t))
        val lenTensor = OnnxTensor.createTensor(env, lenBuf, longArrayOf(1))

        // 4. 运行推理
        val inputs = mapOf(
            "speech" to speechTensor,
            "speech_lengths" to lenTensor
        )
        val results = session.run(inputs)
        speechTensor.close()
        lenTensor.close()

        // 5. 解析输出 logits 和 token_num
        val logitsTensor = results.get("logits") as? OnnxTensor
            ?: return "(输出 'logits' 未找到)"
        val tokenNumTensor = results.get("token_num") as? OnnxTensor
            ?: return "(输出 'token_num' 未找到)"

        val logitsShape = logitsTensor.info.shape
        val vocabSize = logitsShape[2].toInt()
        val logitsData = logitsTensor.floatBuffer

        // 有效 token 数
        val tokenNum = tokenNumTensor.intBuffer
        val validLen = if (tokenNum.remaining() > 0) tokenNum.get(0) else 0

        // 在每个位置取 argmax
        val tokenIds = IntArray(t) { pos ->
            var maxVal = -1e10f
            var maxIdx = 0
            for (v in 0 until vocabSize) {
                val val_ = logitsData.get(pos * vocabSize + v)
                if (val_ > maxVal) {
                    maxVal = val_
                    maxIdx = v
                }
            }
            maxIdx
        }

        // 解码
        val text = decodeTokens(tokenIds, validLen)
        results.close()
        return text
    }

    // ======================== Token 解码 ========================

    /**
     * 将 token ID 序列解码为文本
     * Paraformer 特殊 token: <blank>=0, <sos>=1, </sos>=2
     */
    private fun decodeTokens(tokenIds: IntArray, validLen: Int): String {
        if (tokens.isEmpty()) {
            return tokenIds.joinToString(" ") { it.toString() }
        }

        val sb = StringBuilder()
        var prevId = -1
        // 只取前 validLen 个 token
        val limit = maxOf(0, minOf(validLen, tokenIds.size))
        for (i in 0 until limit) {
            val id = tokenIds[i]
            // 跳过 <blank>(0), <sos>(1), </sos>(2) 和连续重复
            if (id <= 2 || id == prevId) continue
            if (id >= tokens.size) continue
            val token = tokens[id]
            // 跳过特殊标记（如 <unk>）
            if (token.startsWith("<") && token.endsWith(">")) continue
            sb.append(token)
            prevId = id
        }
        return sb.toString().trim()
    }

    // ======================== 实时流式识别 ========================

    override fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: ((peak: Int, rms: Int, dbfs: Float) -> Unit)?
    ) {
        if (isListening) {
            onError("已在识别中")
            return
        }
        if (!isReady) {
            onError("模型未初始化")
            return
        }

        onPartialCb = onPartial
        onFinalCb = onFinal
        onErrorCb = onError
        onLevelCb = onLevel
        isListening = true

        listenThread = thread(name = "paraformer-listen") {
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val record = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_SAMPLES * 2)
            )
            audioRecord = record

            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Handler(Looper.getMainLooper()).post {
                    onErrorCb?.invoke("AudioRecord 初始化失败")
                }
                return@thread
            }

            record.startRecording()
            Log.i(TAG, "real-time listening started")

            val accumulated = mutableListOf<Short>()
            val buf = ShortArray(CHUNK_SAMPLES)
            var lastLevelReport = 0L

            while (isListening) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    // 应用 ASR 增益
                    val gain = AudioRecordPlayer.asrGain
                    if (gain != 1.0f) {
                        AudioGainUtil.applyGain(buf, gain)
                    }

                    // 报告电平（每 ~200ms）
                    val now = System.currentTimeMillis()
                    if (onLevelCb != null && now - lastLevelReport >= 200) {
                        lastLevelReport = now
                        val level = AudioGainUtil.analyzeLevel(buf, gain)
                        Handler(Looper.getMainLooper()).post {
                            onLevelCb?.invoke(level.peak, level.rms, level.dbfs)
                        }
                    }

                    accumulated.addAll(buf.take(n))
                    // 累积至少 0.5s 音频后再识别
                    if (accumulated.size >= SAMPLE_RATE / 2) {
                        val pcm = accumulated.toShortArray()
                        accumulated.clear()
                        processChunk(pcm)
                    }
                }
            }

            // 处理剩余音频
            if (accumulated.isNotEmpty()) {
                processChunk(accumulated.toShortArray())
            }

            try { record.stop() } catch (_: Exception) {}
            record.release()
            audioRecord = null
            Log.i(TAG, "real-time listening stopped")
        }
    }

    private fun processChunk(pcm: ShortArray) {
        try {
            val session = ortSession ?: return
            val text = runInference(session, pcm)
            if (text.isNotBlank()) {
                Handler(Looper.getMainLooper()).post {
                    onPartialCb?.invoke(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "chunk inference error", e)
        }
    }

    override fun stopListening() {
        isListening = false
        listenThread?.join(2000)
        listenThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
    }

    // ======================== 资源释放 ========================

    override fun release() {
        stopListening()
        try { ortSession?.close() } catch (_: Exception) {}
        ortSession = null
        fbank = null
        tokens = emptyList()
        cmvnNegMean = null
        cmvnInvStd = null
    }
}
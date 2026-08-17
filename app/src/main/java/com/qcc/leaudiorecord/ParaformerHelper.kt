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

        /** 实时识别滑动窗口时长 (ms) */
        private const val WINDOW_MS = 2000
        private const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000   // 32000

        /** 实时识别推理步进：每积累这么多新样本推理一次 */
        private const val INFER_STEP_MS = 500
        private const val INFER_STEP_SAMPLES = SAMPLE_RATE * INFER_STEP_MS / 1000  // 8000

        /** 最小推理音频长度（低于此长度不做推理） */
        private const val MIN_INFER_SAMPLES = SAMPLE_RATE * 500 / 1000

        /** 采集 read 缓冲（100ms） */
        private const val READ_CHUNK_SAMPLES = SAMPLE_RATE * 100 / 1000  // 1600
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokens: List<String> = emptyList()
    private var cmvnNegMean: FloatArray? = null
    private var cmvnInvStd: FloatArray? = null
    private var fbank: FbankExtractor? = null

    // 实时识别相关
    private var listenThread: Thread? = null
    private var inferThread: Thread? = null
    @Volatile private var isListening = false
    private var audioRecord: android.media.AudioRecord? = null
    private var onPartialCb: ((String) -> Unit)? = null
    private var onFinalCb: ((String) -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null
    private var onLevelCb: ((Int, Int, Float) -> Unit)? = null

    // 滑动窗口共享状态（采集线程写，推理线程读）
    private val windowLock = Object()
    private var window = mutableListOf<Short>()
    private var newSamples = 0
    private var captureDone = false

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
     * 解析 am.mvn 文件（kaldi nnet1 格式，与 sherpa-onnx kaldi-cmvn 对齐）
     *
     * 结构：
     *   <Nnet>
     *     <Splice> 560 560 [ 0 ]                       ← 上下文数组（忽略）
     *     <AddShift> 560 560 <LearnRateCoef> 0 [ ...560 个 shift... ]   ← 负均值
     *     <Rescale> 560 560 <LearnRateCoef> 0 [ ...560 个 scale... ]    ← 逆标准差
     *   </Nnet>
     *
     * 必须分别解析两个 `[...]` 数组（共 3 个数组：Splice 上下文、AddShift、Rescale）。
     * 旧实现把 `<LearnRateCoef>` 之后所有数字混在一起，导致 inv_std 前 3 个值
     * 变成了 560.0 / 560.0 / 0.0（垃圾），CMVN 完全错误。
     */
    private fun loadCmvn(file: File) {
        val text = file.readText()
        val arrays = Regex("\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { m ->
                m.groupValues[1].trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .mapNotNull { it.toFloatOrNull() }
            }
            .toList()

        // arrays[0] = <Splice> 上下文 [0]，arrays[1] = <AddShift>（负均值），arrays[2] = <Rescale>（逆标准差）
        val shift = arrays.getOrNull(1) ?: emptyList()
        val scale = arrays.getOrNull(2) ?: emptyList()
        if (shift.size < 560 || scale.size < 560) {
            Log.w(TAG, "am.mvn: expected 2x560 values, got shift=${shift.size} scale=${scale.size}")
            return
        }

        cmvnNegMean = FloatArray(560) { shift[it] }
        cmvnInvStd = FloatArray(560) { scale[it] }
        Log.i(
            TAG,
            "CMVN loaded: neg_mean[0]=${cmvnNegMean!![0]} inv_std[0]=${cmvnInvStd!![0]}"
        )
    }

    // ======================== 特征处理 ========================

    /**
     * 提取 FBank 特征 → LFR 拼接 → CMVN 归一化
     *
     * 与 sherpa-onnx OfflineRecognizerParaformerImpl::DecodeStreams 一致：
     *   1. fbank(80) → LFR(7,6) → 560 维 → CMVN((x + neg_mean) * inv_std)
     *   2. LFR 仅保留完整窗口（start + M <= num_frames），不补帧
     *
     * @param pcm 16kHz mono PCM 数据（int16 尺度）
     * @return [T, 560] float32 特征矩阵
     */
    private fun computeFeatures(pcm: ShortArray): Array<FloatArray> {
        val fb = fbank ?: return emptyArray()
        // 1. 提取 80 维 FBank（与 kaldi/sherpa-onnx 前端一致）
        val fbankFeats = fb.extract(pcm)
        if (fbankFeats.isEmpty()) return emptyArray()

        val t = fbankFeats.size
        val d = 80

        // 2. LFR：7 帧拼接，stride=6；与 sherpa-onnx lfr.cc 一致，丢弃不完整窗口
        var numOut = 0
        var start = 0
        while (start + LFR_M <= t) {
            numOut++
            start += LFR_N
        }
        if (numOut <= 0) return emptyArray()

        val lfrFeats = Array(numOut) { i ->
            val startFrame = i * LFR_N
            val feat = FloatArray(d * LFR_M)
            for (j in 0 until LFR_M) {
                System.arraycopy(fbankFeats[startFrame + j], 0, feat, j * d, d)
            }
            feat
        }

        // 3. CMVN 归一化（与 sherpa-onnx ApplyCMVN 一致）
        val negMean = cmvnNegMean
        val invStd = cmvnInvStd
        if (negMean != null && invStd != null) {
            for (i in 0 until numOut) {
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
                // 解析 WAV 头（支持非标准/带扩展块的头，不能硬编码 44 字节）
                val header = WavFileReader.parse(wavFile)
                if (header == null) {
                    Handler(Looper.getMainLooper()).post { onError("无法解析 WAV 文件头") }
                    return@thread
                }
                if (header.sampleRate != SAMPLE_RATE ||
                    header.channels != 1 ||
                    header.bitsPerSample != 16
                ) {
                    Handler(Looper.getMainLooper()).post {
                        onError(
                            "仅支持 16kHz/单声道/16bit WAV，" +
                                    "当前 ${header.sampleRate}Hz/${header.channels}ch/${header.bitsPerSample}bit"
                        )
                    }
                    return@thread
                }

                FileInputStream(wavFile).use { fis ->
                    fis.skip(header.dataOffset)
                    val pcmBytes = fis.readBytes()

                    // 转为 ShortArray
                    val shortBuf = ShortArray(pcmBytes.size / 2)
                    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().get(shortBuf)

                    // 推理
                    val text = runInference(session, shortBuf)
                    Handler(Looper.getMainLooper()).post {
                        onResult(text.ifBlank { "(未识别到语音)" })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe error", e)
                Handler(Looper.getMainLooper()).post { onError("转录失败: ${e.message}") }
            }
        }
    }

    /**
     * ONNX 推理核心
     *
     * 注意：logits 输出长度（shape[1]）与输入 LFR 帧数 T 不相等（动态值，
     * 由模型内部 CIF 机制决定，实测 T=100 → 20，T=200 → 9）。
     * 必须按 logits 的实际长度做 argmax，否则越界读 BufferUnderflowException。
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

        // 5. 解析输出 logits
        // 注意：onnxruntime-android 1.21.0 的 run() 返回 OrtSession.Result（不是 Map），
        // Result.get(String) 返回 Optional<OnnxValue>，必须 .isPresent/.get() 再转 OnnxTensor。
        // 旧写法 `results.get("logits") as? OnnxTensor` 拿到的是 Optional，转换永远失败 → "输出 'logits' 未找到"
        val logitsOptional = results.get("logits")
        if (!logitsOptional.isPresent) {
            results.close()
            return "(输出 'logits' 未找到)"
        }
        val logitsTensor = logitsOptional.get() as OnnxTensor

        val logitsShape = logitsTensor.info.shape
        val logitsLen = logitsShape[1].toInt()   // 实际 logits 长度（动态）
        val vocabSize = logitsShape[2].toInt()   // 8404
        val logitsData = logitsTensor.floatBuffer

        // 6. 在每个位置取 argmax（遍历 logits 实际长度，而非输入帧数）
        val tokenIds = IntArray(logitsLen) { pos ->
            var maxVal = -1e10f
            var maxIdx = 0
            val base = pos * vocabSize
            for (v in 0 until vocabSize) {
                val val_ = logitsData.get(base + v)
                if (val_ > maxVal) {
                    maxVal = val_
                    maxIdx = v
                }
            }
            maxIdx
        }

        // 7. 解码
        val text = decodeTokens(tokenIds)
        results.close()
        return text
    }

    // ======================== Token 解码 ========================

    /**
     * 将 token ID 序列解码为文本
     *
     * 与 sherpa-onnx OfflineParaformerGreedySearchDecoder 一致：
     *   - 遍历 logits 全部位置，argmax == </s>(2) 时终止
     *   - <blank>(0) / <s>(1) / <unk> 等特殊 token 跳过
     *   - 不做 CTC 式连续去重！Paraformer 输出重复 token 是合法的（如"谢谢"）
     *   - BPE 前缀（以 @@ 结尾）与后续 token 拼接
     */
    private fun decodeTokens(tokenIds: IntArray): String {
        if (tokens.isEmpty()) {
            return tokenIds.joinToString(" ") { it.toString() }
        }

        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id < 0 || id >= tokens.size) continue
            if (id == 2) break                        // </s> 终止
            if (id <= 1) continue                     // <blank> / <s>
            val token = tokens[id]
            // 跳过特殊标记（如 <unk>）
            if (token.startsWith("<") && token.endsWith(">")) continue

            if (token.endsWith("@@")) {
                // BPE 前缀：去掉 @@ 后与后续 token 直接拼接
                sb.append(token.dropLast(2))
                continue
            }

            // 中英混排：ASCII token 前补一个空格
            if (token.all { it.code < 128 } && sb.isNotEmpty()) {
                val last = sb.last()
                if (last.code >= 128) sb.append(' ')
            }
            sb.append(token)
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
        synchronized(windowLock) {
            window = mutableListOf()
            newSamples = 0
            captureDone = false
        }

        // ===== 采集线程：AudioRecord → 2s 滑动窗口 =====
        listenThread = thread(name = "paraformer-capture") {
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
                maxOf(minBuf, READ_CHUNK_SAMPLES * 2 * 2)
            )
            audioRecord = record

            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Handler(Looper.getMainLooper()).post {
                    onErrorCb?.invoke("AudioRecord 初始化失败")
                }
                synchronized(windowLock) { captureDone = true; windowLock.notifyAll() }
                return@thread
            }

            record.startRecording()
            Log.i(TAG, "real-time listening started")

            val buf = ShortArray(READ_CHUNK_SAMPLES)
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

                    // 追加到滑动窗口，只保留最近 2s
                    synchronized(windowLock) {
                        window.addAll(buf.take(n))
                        newSamples += n
                        val excess = window.size - WINDOW_SAMPLES
                        if (excess > 0) {
                            window = window.drop(excess).toMutableList()
                        }
                        windowLock.notifyAll()
                    }
                }
            }

            // 采集结束：通知推理线程做最终识别
            synchronized(windowLock) {
                captureDone = true
                windowLock.notifyAll()
            }

            try { record.stop() } catch (_: Exception) {}
            record.release()
            audioRecord = null
            Log.i(TAG, "real-time listening stopped")
        }

        // ===== 推理线程：每 0.5s 新音频 → 推理最近 2s 窗口 =====
        inferThread = thread(name = "paraformer-infer") {
            var lastEmitted = ""
            var isFinal = false
            while (!isFinal) {
                val pcm: ShortArray?
                synchronized(windowLock) {
                    // 等待：有新音频可推理，或采集已结束
                    while (!captureDone && newSamples < INFER_STEP_SAMPLES) {
                        windowLock.wait(200)
                    }
                    pcm = if (window.size >= MIN_INFER_SAMPLES) {
                        window.toShortArray()
                    } else null
                    newSamples = 0
                    isFinal = captureDone
                }

                if (pcm == null) {
                    if (isFinal) break
                    continue
                }

                val text = try {
                    val session = ortSession
                    if (session != null) runInference(session, pcm) else ""
                } catch (e: Exception) {
                    Log.e(TAG, "inference error", e)
                    ""
                }

                if (text.isNotBlank() && text != lastEmitted) {
                    lastEmitted = text
                    val finalText = text
                    Handler(Looper.getMainLooper()).post {
                        if (isFinal) onFinalCb?.invoke(finalText)
                        else onPartialCb?.invoke(finalText)
                    }
                }
            }
        }
    }

    override fun stopListening() {
        isListening = false
        // 等采集线程退出
        listenThread?.join(2000)
        listenThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        // 唤醒推理线程完成最终识别
        synchronized(windowLock) {
            windowLock.notifyAll()
        }
        inferThread?.join(3000)
        inferThread = null
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
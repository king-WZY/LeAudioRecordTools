package com.qcc.leaudiorecord

import android.util.Log
import kotlin.math.*

/**
 * FBank (Filter Bank) 特征提取器 —— 与 sherpa-onnx / kaldi paraformer 前端严格对齐
 *
 * 参数来自 sherpa-onnx OfflineRecognizerParaformerImpl::InitFeatConfig：
 *   - normalize_samples = false  → 输入保持 int16 范围 [-32768, 32767]（绝不能除以 32768，
 *     am.mvn 的均值 ≈ 8.3 就是按 int16 尺度统计的）
 *   - window_type = hamming
 *   - high_freq = 0              → Nyquist（16kHz 下即 8000Hz）
 *   - snip_edges = true          → 帧数 = (N - frame_length) / frame_shift + 1
 *   - low_freq = 20（默认），80 bins，25ms / 10ms
 *   - kaldi 默认 preemph 0.97 + remove_dc_offset = true + dither = 0
 *   - 功率谱不做任何缩放（kaldi ComputePowerSpectrum 无除法）
 *
 * 注意：Paraformer 需要额外做 LFR(7帧拼接, stride=6) 和 CMVN，
 * 这些在 ParaformerHelper 中处理。
 */
class FbankExtractor {

    companion object {
        private const val TAG = "LeAudioFbank"

        /** 采样率 */
        const val SAMPLE_RATE = 16000
        /** 帧长 (ms) */
        const val FRAME_LENGTH_MS = 25
        /** 帧移 (ms) */
        const val FRAME_SHIFT_MS = 10
        /** FFT 点数 */
        const val FFT_SIZE = 512
        /** Mel 滤波器数量 */
        const val NUM_MEL_BINS = 80
        /** 最低频率 (Hz) */
        const val F_MIN = 20.0
        /** 最高频率 (Hz) —— high_freq=0 即 Nyquist */
        const val F_MAX = 8000.0
        /** kaldi 默认预加重系数 */
        const val PREEMPH_COEFF = 0.97f
    }

    /** 帧长 (采样点) */
    val frameLength: Int = SAMPLE_RATE * FRAME_LENGTH_MS / 1000  // 400 @ 16kHz

    /** 帧移 (采样点) */
    val frameShift: Int = SAMPLE_RATE * FRAME_SHIFT_MS / 1000   // 160 @ 16kHz

    // 预计算的 Hamming 窗
    private val hammingWindow: FloatArray
    // Mel 滤波器组权重
    private val melFilterBank: Array<FloatArray>

    init {
        // Hamming 窗（与 kaldi 一致）
        hammingWindow = FloatArray(frameLength) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (frameLength - 1))).toFloat()
        }

        // Mel 滤波器组（80 个滤波器，覆盖 F_MIN ~ F_MAX）
        val lowMel = hzToMel(F_MIN)
        val highMel = hzToMel(F_MAX)
        val numFftBins = FFT_SIZE / 2 + 1
        melFilterBank = Array(NUM_MEL_BINS) { FloatArray(numFftBins) }

        for (m in 0 until NUM_MEL_BINS) {
            val leftMel = lowMel + (highMel - lowMel) * m / (NUM_MEL_BINS + 1)
            val centerMel = lowMel + (highMel - lowMel) * (m + 1) / (NUM_MEL_BINS + 1)
            val rightMel = lowMel + (highMel - lowMel) * (m + 2) / (NUM_MEL_BINS + 1)

            val leftHz = melToHz(leftMel)
            val centerHz = melToHz(centerMel)
            val rightHz = melToHz(rightMel)

            val leftBin = leftHz / SAMPLE_RATE * FFT_SIZE
            val centerBin = centerHz / SAMPLE_RATE * FFT_SIZE
            val rightBin = rightHz / SAMPLE_RATE * FFT_SIZE

            for (k in 0 until numFftBins) {
                val v = if (k.toDouble() < leftBin || k.toDouble() > rightBin) {
                    0.0
                } else if (k.toDouble() <= centerBin) {
                    (k - leftBin) / (centerBin - leftBin)
                } else {
                    (rightBin - k) / (rightBin - centerBin)
                }
                melFilterBank[m][k] = v.toFloat()
            }
        }
    }

    /**
     * 从 PCM 数据提取 FBank 特征（与 sherpa-onnx/kaldi paraformer 前端对齐）。
     *
     * @param pcm 16kHz mono PCM 数据 (short array)，保持 int16 尺度
     * @return 特征矩阵 [num_frames, NUM_MEL_BINS]
     */
    fun extract(pcm: ShortArray): Array<FloatArray> {
        if (pcm.size < frameLength) return emptyArray()

        // snip_edges=true：帧数 = (N - frame_length) / frame_shift + 1
        val numFrames = (pcm.size - frameLength) / frameShift + 1

        val features = Array(numFrames) { t ->
            val start = t * frameShift

            // 1. 取帧（保持 int16 尺度，不做 /32768）
            val frame = FloatArray(frameLength) { i -> pcm[start + i].toFloat() }

            // 2. 去直流（kaldi remove_dc_offset=true）
            val mean = frame.sum() / frameLength
            for (i in frame.indices) {
                frame[i] = frame[i] - mean
            }

            // 3. 预加重（kaldi preemph_coeff=0.97）
            for (i in frameLength - 1 downTo 1) {
                frame[i] = frame[i] - PREEMPH_COEFF * frame[i - 1]
            }

            // 4. Hamming 窗 + 填 FFT 缓冲（snip_edges=true，不产生尾部越界帧）
            val real = FloatArray(FFT_SIZE)
            for (i in 0 until frameLength) {
                real[i] = frame[i] * hammingWindow[i]
            }

            // 5. FFT → 功率谱（kaldi 不做任何缩放）
            val imag = FloatArray(FFT_SIZE)
            fft(real, imag)
            val numFftBins = FFT_SIZE / 2 + 1
            val power = FloatArray(numFftBins) { k ->
                val r = real[k]
                val im = imag[k]
                r * r + im * im
            }

            // 6. Mel 滤波 → Log 压缩
            FloatArray(NUM_MEL_BINS) { m ->
                var sum = 0f
                for (k in 0 until numFftBins) {
                    sum += power[k] * melFilterBank[m][k]
                }
                ln(maxOf(sum, 1e-10f))
            }
        }

        return features
    }

    // ======================== FFT (Cooley-Tukey 基2) ========================

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT size must be power of 2" }

        // 位逆序重排
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // 蝶形运算
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until halfLen) {
                    val wR = cos(k * angle).toFloat()
                    val wI = sin(k * angle).toFloat()
                    val idx = i + k
                    val idx2 = idx + halfLen
                    val tR = wR * real[idx2] - wI * imag[idx2]
                    val tI = wR * imag[idx2] + wI * real[idx2]
                    real[idx2] = real[idx] - tR
                    imag[idx2] = imag[idx] - tI
                    real[idx] += tR
                    imag[idx] += tI
                }
            }
            len = len shl 1
        }
    }

    // ======================== Mel 频率转换 ========================

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
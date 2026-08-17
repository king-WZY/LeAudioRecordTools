package com.qcc.leaudiorecord

import android.util.Log
import kotlin.math.*

/**
 * FBank (Filter Bank) 特征提取器
 *
 * 将 16kHz mono PCM 音频转换为 80 维 FBank 特征（与 kaldi 对齐）。
 *
 * 处理流程（与 kaldi OnlineFbank 一致）：
 * 1. 分帧 + Hamming 窗 (snip_edges=false)
 * 2. FFT → 功率谱
 * 3. Mel 滤波器组 (80 bins, 20~8000Hz)
 * 4. Log 压缩
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
        /** 最高频率 (Hz) */
        const val F_MAX = 8000.0
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
     * 从 PCM 数据提取 FBank 特征（与 kaldi OnlineFbank 对齐）。
     * snip_edges=false 模式：最后一个帧可能部分超出信号末尾，但不会丢弃。
     *
     * @param pcm 16kHz mono PCM 数据 (short array)
     * @return 特征矩阵 [num_frames, NUM_MEL_BINS]
     */
    fun extract(pcm: ShortArray): Array<FloatArray> {
        // 转为 float [-1, 1]
        val floatPcm = FloatArray(pcm.size) { pcm[it].toFloat() / 32768f }

        // snip_edges=false 模式计算帧数
        val numFrames = maxOf(1,
            (floatPcm.size - frameLength + frameShift) / frameShift + 1
        )

        val features = Array(numFrames) { t ->
            val start = t * frameShift
            // 加窗（超出的部分补零，与 kaldi 一致）
            val windowed = FloatArray(FFT_SIZE) { i ->
                if (start + i < floatPcm.size) {
                    floatPcm[start + i] * hammingWindow.getOrElse(i) { 0f }
                } else 0f
            }

            // 实部 FFT
            val real = windowed
            val imag = FloatArray(FFT_SIZE)
            fft(real, imag)

            // 功率谱
            val numFftBins = FFT_SIZE / 2 + 1
            val power = FloatArray(numFftBins) { k ->
                val r = real[k]
                val im = imag[k]
                (r * r + im * im) / FFT_SIZE.toFloat()
            }

            // Mel 滤波 → Log 压缩
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
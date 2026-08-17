package com.qcc.leaudiorecord

import android.util.Log
import kotlin.math.*

/**
 * 音频增益工具类
 *
 * 功能：
 * 1. 给 PCM 数据应用数字增益（带防削波饱和）
 * 2. 分析音频电平（峰值、RMS、dBFS）
 * 3. 自动建议增益值（将 RMS 提升到目标电平）
 */
object AudioGainUtil {

    private const val TAG = "LeAudioGain"

    /** 目标 RMS 电平 (dBFS)：正常人说话约 -12 ~ -6 dBFS */
    private const val TARGET_RMS_DBFS = -12f

    /** 16-bit PCM 最大值 */
    private const val MAX_16BIT = 32767f
    private const val MIN_16BIT = -32768f

    /**
     * 电平分析结果
     * @param peak    最大采样值 (0~32767)
     * @param rms     RMS 值 (0~32767)
     * @param dbfs    当前 RMS 分贝数 (dBFS)，0dBFS = 最大电平
     * @param peakDb  峰值分贝数
     * @param clipping 是否削波
     */
    data class LevelInfo(
        val peak: Int,
        val rms: Int,
        val dbfs: Float,
        val peakDb: Float,
        val clipping: Boolean
    )

    /**
     * 分析 PCM 数据的电平
     *
     * @param pcm 16-bit PCM 数据
     * @param gain 当前应用的增益（用于计算原始电平）
     * @return 电平分析结果
     */
    fun analyzeLevel(pcm: ShortArray, gain: Float = 1.0f): LevelInfo {
        if (pcm.isEmpty()) return LevelInfo(0, 0, -120f, -120f, false)

        var peak = 0
        var sumSq = 0L
        var clipping = false

        for (s in pcm) {
            val v = abs(s.toInt())
            peak = maxOf(peak, v)
            sumSq += v.toLong() * v
            if (v >= 32767) clipping = true
        }

        val rms = if (pcm.isNotEmpty()) {
            sqrt(sumSq.toDouble() / pcm.size).toInt()
        } else 0

        // 还原到原始电平（除以增益）
        val rawPeak = if (gain > 0f) (peak / gain).toInt().coerceAtMost(32767) else 0
        val rawRms = if (gain > 0f) (rms / gain).toInt().coerceAtMost(32767) else 0

        val dbfs = if (rawRms > 0) {
            20f * log10(rawRms / MAX_16BIT)
        } else -120f

        val peakDb = if (rawPeak > 0) {
            20f * log10(rawPeak / MAX_16BIT)
        } else -120f

        return LevelInfo(rawPeak, rawRms, dbfs, peakDb, clipping)
    }

    /**
     * 对 PCM 数据应用数字增益
     *
     * @param pcm  原始 PCM 数据（就地修改）
     * @param gain 增益倍数（1.0 = 不处理）
     * @return 处理后的 pcm（同一个引用）
     */
    fun applyGain(pcm: ShortArray, gain: Float): ShortArray {
        if (gain == 1.0f) return pcm

        for (i in pcm.indices) {
            val v = (pcm[i].toInt() * gain).toInt().coerceIn(MIN_16BIT.toInt(), MAX_16BIT.toInt())
            pcm[i] = v.toShort()
        }
        return pcm
    }

    /**
     * 建议增益值：将当前 RMS 提升到目标电平 [-12dBFS]
     *
     * @param pcm PCM 数据
     * @return 建议增益倍数（带削波保护，限制最大 64x）
     */
    fun suggestGain(pcm: ShortArray): Float {
        val level = analyzeLevel(pcm)
        if (level.dbfs <= -120f) return 1.0f

        // 需要提升的 dB 数
        val neededDb = TARGET_RMS_DBFS - level.dbfs
        // 转为倍数
        val suggested = 10.0.pow(neededDb / 20.0).toFloat()

        // 检查是否会导致削波：peak * gain <= 32767
        val maxSafeGain = if (level.peak > 0) MAX_16BIT / level.peak else 64f

        val finalGain = minOf(suggested, maxSafeGain, 64f)

        // 如果原始信号已经足够大，不再增益
        return if (finalGain < 1.0f) 1.0f else finalGain
    }

    /**
     * 从 WAV 文件分析并建议增益
     *
     * @param path WAV 文件路径
     * @return 建议增益（含分析报告），失败返回 null
     */
    fun analyzeWavFile(path: String): SuggestResult? {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return null

            val fis = java.io.FileInputStream(file)
            fis.skip(44) // 跳过 WAV 头
            val pcmBytes = fis.readBytes()
            fis.close()

            val shortBuf = ShortArray(pcmBytes.size / 2)
            java.nio.ByteBuffer.wrap(pcmBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shortBuf)

            if (shortBuf.isEmpty()) return null

            val level = analyzeLevel(shortBuf)
            val suggested = suggestGain(shortBuf)

            SuggestResult(
                peak = level.peak,
                rms = level.rms,
                dbfs = level.dbfs,
                peakDb = level.peakDb,
                clipping = level.clipping,
                suggestedGain = suggested,
                durationMs = (shortBuf.size.toFloat() / 16000f * 1000f).toInt()
            )
        } catch (e: Exception) {
            Log.e(TAG, "analyzeWavFile error", e)
            null
        }
    }

    data class SuggestResult(
        val peak: Int,
        val rms: Int,
        val dbfs: Float,
        val peakDb: Float,
        val clipping: Boolean,
        val suggestedGain: Float,
        val durationMs: Int
    ) {
        val summary: String
            get() {
                val gainDb = 20f * log10(suggestedGain)
                return "RMS=${dbfs.toInt()}dBFS peak=${peakDb.toInt()}dBFS " +
                        "建议增益: ${suggestedGain}x (${gainDb.toInt()}dB)" +
                        if (clipping) " ⚠削波" else ""
            }
    }
}
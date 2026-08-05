package com.qcc.leaudiorecord

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 实时录音波形显示（示波器样式）
 *
 * 维护一个环形缓冲，保存最近若干采样点，绘制成波形曲线。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val buffer = IntArray(MAX_POINTS)
    private var bufferSize = 0
    private var writePos = 0

    private val wavePaint = Paint().apply {
        color = Color.rgb(0x21, 0x96, 0xF3)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val centerPaint = Paint().apply {
        color = Color.rgb(0x44, 0x44, 0x44)
        strokeWidth = 1f
    }

    /** 追加一帧采样（先降采样到 MAX_POINTS 内） */
    fun addSamples(samples: ShortArray) {
        if (samples.isEmpty()) return
        // 降采样：每个 buffer 位置代表 samples.size / MAX_POINTS 的峰值
        val step = max(1, samples.size / MAX_POINTS)
        var i = 0
        while (i < samples.size) {
            var peak = 0
            val end = minOf(i + step, samples.size)
            while (i < end) {
                val v = samples[i].toInt()
                peak = max(peak, if (v < 0) -v else v)
                i++
            }
            buffer[writePos] = peak
            writePos = (writePos + 1) % MAX_POINTS
            if (bufferSize < MAX_POINTS) bufferSize++
        }
        postInvalidateOnAnimation()
    }

    /** 清空波形 */
    fun clear() {
        bufferSize = 0
        writePos = 0
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f

        // 中心线
        canvas.drawLine(0f, midY, w, midY, centerPaint)

        if (bufferSize == 0) return

        // 从最旧到最新绘制
        val n = bufferSize
        val startIdx = (writePos - n + MAX_POINTS) % MAX_POINTS
        val stepX = w / MAX_POINTS

        var path = android.graphics.Path()
        var segmentStart = 0
        var idx = startIdx
        var x = 0f
        var first = true
        var penDown = false

        // 简单实现：逐点连线（振幅映射到 view 高度）
        for (i in 0 until n) {
            val amp = buffer[idx]
            // 振幅 0~32768 映射到 0~h（峰值在中间线上下）
            val y = midY - (amp / 32768f) * (h / 2f - 2f)
            x = i * stepX
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            idx = (idx + 1) % MAX_POINTS
        }
        canvas.drawPath(path, wavePaint)
    }

    companion object {
        private const val MAX_POINTS = 400
    }
}

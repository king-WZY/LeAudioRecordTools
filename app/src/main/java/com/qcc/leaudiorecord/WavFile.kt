package com.qcc.leaudiorecord

import android.media.AudioFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV 文件写入器（PCM 16bit）
 *
 * 先写占位头，录音结束时回填真实大小（finalizeHeader）。
 */
class WavFileWriter(
    file: File,
    sampleRate: Int,
    channelMask: Int,
    encoding: Int
) {
    private val out = FileOutputStream(file)
    private val channels: Int
    private val bitsPerSample: Int
    private val sampleRate: Int

    /** 已写入的数据字节数 */
    var dataBytes: Long = 0
        private set

    init {
        val ch = when (channelMask) {
            AudioFormat.CHANNEL_IN_MONO -> 1
            AudioFormat.CHANNEL_IN_STEREO -> 2
            AudioFormat.CHANNEL_OUT_MONO -> 1
            AudioFormat.CHANNEL_OUT_STEREO -> 2
            else -> 1
        }
        this.channels = ch
        this.bitsPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_8BIT -> 8
            else -> 16
        }
        this.sampleRate = sampleRate
        writeHeader(0)
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        out.write(data, offset, length)
        dataBytes += length
    }

    fun close() {
        out.close()
    }

    /** 录音结束后重新打开文件回填 WAV 头（在 close 之后调用） */
    fun patchHeader(file: File) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = dataBytes
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToLe((36 + dataSize).toInt()))
            raf.seek(22)
            raf.write(shortToLe(channels.toShort()))
            raf.seek(24)
            raf.write(intToLe(sampleRate))
            raf.seek(28)
            raf.write(intToLe(byteRate))
            raf.seek(32)
            raf.write(shortToLe(blockAlign.toShort()))
            raf.seek(34)
            raf.write(shortToLe(bitsPerSample.toShort()))
            raf.seek(40)
            raf.write(intToLe(dataSize.toInt()))
        }
    }

    private fun writeHeader(dummyDataSize: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dummyDataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                     // fmt chunk size
        header.putShort(1)                    // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dummyDataSize)
        out.write(header.array())
        dataBytes = 0
    }

    private fun intToLe(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortToLe(v: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}

/** WAV 文件头解析结果 */
data class WavHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long
)

/** WAV 文件头解析器 */
object WavFileReader {
    fun parse(file: File): WavHeader? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44) return null
                val riff = ByteArray(4)
                raf.readFully(riff)
                if (String(riff) != "RIFF") return null
                raf.skipBytes(4)
                raf.readFully(riff)
                if (String(riff) != "WAVE") return null

                var sampleRate = 0
                var channels = 1
                var bitsPerSample = 16
                var dataOffset = 44L
                var dataSize = 0L

                // 遍历 chunk
                while (raf.filePointer + 8 <= raf.length()) {
                    val chunkId = ByteArray(4)
                    raf.readFully(chunkId)
                    val size = leInt(raf)
                    val id = String(chunkId)
                    when (id) {
                        "fmt " -> {
                            raf.skipBytes(2) // audio format
                            channels = leShort(raf).toInt()
                            sampleRate = leInt(raf)
                            raf.skipBytes(6) // byte rate + block align
                            bitsPerSample = leShort(raf).toInt()
                            raf.skipBytes((size - 16).toInt())
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataSize = size.toLong()
                            raf.skipBytes(size)
                        }
                        else -> raf.skipBytes(size)
                    }
                }
                if (sampleRate == 0) null
                else WavHeader(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun leInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[3].toInt() and 0xFF shl 24) or
                (b[2].toInt() and 0xFF shl 16) or
                (b[1].toInt() and 0xFF shl 8) or
                (b[0].toInt() and 0xFF)
    }

    private fun leShort(raf: RandomAccessFile): Short {
        val b = ByteArray(2)
        raf.readFully(b)
        return ((b[1].toInt() and 0xFF shl 8) or (b[0].toInt() and 0xFF)).toShort()
    }
}

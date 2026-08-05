package com.qcc.leaudiorecord

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import java.io.File

/**
 * LE Audio 录音测试工具
 *
 * 功能：
 * 1. 选择录音输入设备（本机 MIC / LE Audio 开发板 MIC / 其他）——按钮 + 列表对话框
 * 2. 选择回放输出设备（本机扬声器 / LE Audio 开发板 / A2DP 等）——按钮 + 列表对话框
 * 3. 录音（AudioRecord → WAV）与回放（AudioTrack）
 * 4. 实时电平 + 实时波形显示
 *
 * 核心价值：通过 setPreferredDevice 显式指定设备，绕过 AudioPolicy 自动路由，
 * 直接验证 LE Audio 的 MIC 上行通路与扬声器下行通路。
 */
class MainActivity : Activity() {

    private lateinit var audioManager: AudioManager
    private lateinit var audio: AudioRecordPlayer

    private lateinit var btnInput: Button
    private lateinit var btnOutput: Button
    private lateinit var spinnerRate: Spinner
    private lateinit var spinnerGain: Spinner
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var txtFile: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLevel: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var waveform: WaveformView

    private val inputDevices = mutableListOf<AudioDeviceInfo?>()
    private val outputDevices = mutableListOf<AudioDeviceInfo?>()

    /** 当前选中的设备索引（0 = 系统默认） */
    private var selectedInputIdx = 0
    private var selectedOutputIdx = 0

    private companion object {
        const val REQ_RECORD_AUDIO = 100
        const val TAG = "LeAudioRecord"
        /** 录音文件最多保留数量（超出部分自动清理最旧文件） */
        const val MAX_RECORDINGS = 10
    }

    /** 读取增益 Spinner 选中值 → 倍数 */
    private fun selectedGain(): Float = when (spinnerGain.selectedItemPosition) {
        1 -> 2f
        2 -> 4f
        3 -> 8f
        4 -> 16f
        else -> 1f
    }

    /**
     * 清理旧录音文件，最多保留 keep 个（按修改时间倒序，最新优先）。
     * 仅清理本应用生成的 rec_*.wav（不影响手动放入的测试信号文件）。
     *
     * @return 删除的文件数量
     */
    private fun cleanupOldRecordings(keep: Int): Int {
        val dir = getExternalFilesDir(null) ?: return 0
        val recs = dir.listFiles { f ->
            f.isFile && f.name.startsWith("rec_") && f.name.endsWith(".wav")
        }?.sortedByDescending { it.lastModified() } ?: return 0
        var deleted = 0
        for (i in keep until recs.size) {
            val f = recs[i]
            if (f.delete()) {
                deleted++
                android.util.Log.i(TAG, "cleanup deleted ${f.name}")
            }
        }
        return deleted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audio = AudioRecordPlayer()

        btnInput = findViewById(R.id.btnInput)
        btnOutput = findViewById(R.id.btnOutput)
        spinnerRate = findViewById(R.id.spinnerSampleRate)
        spinnerGain = findViewById(R.id.spinnerGain)
        btnRecord = findViewById(R.id.btnRecord)
        btnPlay = findViewById(R.id.btnPlay)
        txtFile = findViewById(R.id.txtFile)
        txtStatus = findViewById(R.id.txtStatus)
        txtLevel = findViewById(R.id.txtLevel)
        levelBar = findViewById(R.id.levelBar)
        waveform = findViewById(R.id.waveform)

        btnInput.setOnClickListener { showDeviceDialog(isInput = true) }
        btnOutput.setOnClickListener { showDeviceDialog(isInput = false) }
        btnRecord.setOnClickListener { onRecordClick() }
        btnPlay.setOnClickListener { onPlayClick() }

        refreshDevices()
        // 启动时清理旧录音，最多保留 10 个
        val deleted = cleanupOldRecordings(keep = MAX_RECORDINGS)
        if (deleted > 0) {
            setStatus("已清理 $deleted 个旧录音（最多保留 ${MAX_RECORDINGS} 个）")
        }

        // 请求录音权限
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        audio.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            setStatus(if (granted) "录音权限已授予" else "未授予录音权限，无法录音")
        }
    }

    /**
     * 主动探测所有输入/输出设备（公开 API，通用）。
     * 每个列表首项为"系统默认"，随后列出所有探测到的设备。
     */
    private fun refreshDevices() {
        inputDevices.clear()
        outputDevices.clear()

        inputDevices.add(null) // 系统默认
        outputDevices.add(null)

        // 通用探测：不按类型白名单过滤，所有 isSource/isSink 设备均列出
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        android.util.Log.i(TAG, "inputs=${allInputs.size} outputs=${allOutputs.size}")

        for (d in allInputs.sortedBy { it.type }) {
            if (d.isSource) inputDevices.add(d)
        }
        for (d in allOutputs.sortedBy { it.type }) {
            if (d.isSink) outputDevices.add(d)
        }

        // 通用默认选择：输入优先内置 MIC，输出优先扬声器（无则第一项）
        selectedInputIdx = inputDevices.indexOfFirst {
            it?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }.let { if (it >= 0) it else 0 }
        selectedOutputIdx = outputDevices.indexOfFirst {
            it?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }.let { if (it >= 0) it else 0 }

        updateDeviceButtonText()
        refreshSampleRatesForSelectedInput()

        val total = "输入 ${inputDevices.size - 1} 个, 输出 ${outputDevices.size - 1} 个"
        setStatus("设备枚举完成：$total")
    }

    /**
     * 根据当前选中的输入设备动态刷新采样率下拉。
     * 优先使用设备上报的支持采样率（getSampleRates），
     * 未上报时使用通用候选列表。
     */
    private fun refreshSampleRatesForSelectedInput() {
        val dev = inputDevices.getOrNull(selectedInputIdx)
        val reported = dev?.sampleRates ?: IntArray(0)
        val rates = if (reported.isNotEmpty()) reported
        else AudioRecordPlayer.FALLBACK_SAMPLE_RATES
        android.util.Log.i(
            TAG,
            "input dev=${dev?.let { AudioRecordPlayer.deviceLabel(it) }} " +
                    "reportedRates=${reported.joinToString(",")} -> use=${rates.joinToString(",")}"
        )
        val items = rates.map { it.toString() }
        spinnerRate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        // 尽量选中 16kHz（LE Audio 通话常用），无则默认
        val idx = rates.indexOfFirst { it == 16000 }
        if (idx >= 0) spinnerRate.setSelection(idx)
    }

    /** 弹出设备选择对话框 */
    private fun showDeviceDialog(isInput: Boolean) {
        val devices = if (isInput) inputDevices else outputDevices
        val names = mutableListOf<String>()
        names.add("(系统默认)")
        for (i in 1 until devices.size) {
            val d = devices[i] ?: continue
            names.add(AudioRecordPlayer.deviceLabel(d))
        }
        val title = if (isInput) "选择录音输入设备" else "选择回放输出设备"
        val currentIdx = if (isInput) selectedInputIdx else selectedOutputIdx

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), currentIdx) { dialog, which ->
                if (isInput) {
                    selectedInputIdx = which
                    // 切换输入设备后重新探测其支持的采样率
                    refreshSampleRatesForSelectedInput()
                } else {
                    selectedOutputIdx = which
                }
                updateDeviceButtonText()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 更新输入/输出按钮文本为当前选中设备 */
    private fun updateDeviceButtonText() {
        val inDev = inputDevices.getOrNull(selectedInputIdx)
        val outDev = outputDevices.getOrNull(selectedOutputIdx)
        btnInput.text = if (inDev == null) "(系统默认)" else AudioRecordPlayer.deviceLabel(inDev)
        btnOutput.text = if (outDev == null) "(系统默认)" else AudioRecordPlayer.deviceLabel(outDev)
    }

    private fun onRecordClick() {
        if (audio.isRecording) {
            audio.stopRecording()
            btnRecord.text = getString(R.string.record_start)
            setStatus("录音已停止，可回放")
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            setStatus("需要录音权限")
            return
        }

        // 采样率
        val rate = spinnerRate.selectedItem?.toString()?.toIntOrNull() ?: 16000
        AudioRecordPlayer.sampleRate = rate

        // 录音增益（1x/2x/4x/8x/16x）
        AudioRecordPlayer.recordGain = selectedGain()

        val inputDevice = inputDevices.getOrNull(selectedInputIdx)
        val outFile = File(
            getExternalFilesDir(null),
            "rec_${System.currentTimeMillis()}_${rate}hz.wav"
        )
        outFile.parentFile?.mkdirs()

        val ok = audio.startRecording(
            outFile, inputDevice,
            onStatus = { msg ->
                runOnUiThread {
                    setStatus(msg)
                    if (msg.startsWith("录音完成")) {
                        btnRecord.text = getString(R.string.record_start)
                        txtFile.text = "文件: ${outFile.absolutePath}"
                        txtLevel.text = "输入电平: -- dB (RMS=-- peak=--)"
                        levelBar.progress = 0
                        waveform.clear()
                        // 自动清理旧录音，最多保留 10 个
                        val deleted = cleanupOldRecordings(keep = MAX_RECORDINGS)
                        if (deleted > 0) {
                            setStatus("已保留最近 ${MAX_RECORDINGS} 个录音，清理了 $deleted 个旧文件")
                        }
                    }
                }
            },
            onLevel = { peak, rms, db ->
                runOnUiThread {
                    txtLevel.text =
                        "输入电平: %.1f dB (RMS=%d peak=%d)".format(db, rms, peak)
                    // 映射到 0~100：-60dB ~ 0dB
                    val pct = ((db + 60f) / 60f * 100f).toInt().coerceIn(0, 100)
                    levelBar.progress = pct
                }
            },
            onWaveform = { samples ->
                runOnUiThread {
                    waveform.addSamples(samples)
                }
            }
        )
        if (ok) {
            btnRecord.text = getString(R.string.record_stop)
            txtFile.text = "文件: ${outFile.absolutePath}"
            setStatus("正在录音（${AudioRecordPlayer.sampleRate}Hz）…对着设备说话观察电平")
        }
    }

    private fun onPlayClick() {
        if (audio.isPlaying) {
            audio.stopPlayback()
            btnPlay.text = getString(R.string.play_start)
            setStatus("回放已停止")
            return
        }

        val dir = getExternalFilesDir(null)
        val wavFiles: Array<File> = dir?.listFiles { f ->
            f.isFile && f.name.endsWith(".wav")
        } ?: emptyArray()
        val sorted = wavFiles.sortedByDescending { it.lastModified() }
        if (sorted.isEmpty()) {
            setStatus("无 WAV 文件，请先录音")
            return
        }

        // 弹出文件选择：目录下全部 WAV（便于回放测试信号做对照）
        val names = sorted.map { f ->
            val sizeKb = f.length() / 1024
            "${f.name} (${sizeKb}KB)"
        }
        AlertDialog.Builder(this)
            .setTitle("选择回放文件")
            .setItems(names.toTypedArray()) { dialog, which ->
                startPlayback(sorted[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startPlayback(file: File) {
        val outputDevice = outputDevices.getOrNull(selectedOutputIdx)
        txtFile.text = "文件: ${file.absolutePath}"
        val ok = audio.play(file, outputDevice) { msg ->
            runOnUiThread {
                setStatus(msg)
                if (msg.startsWith("回放完成")) {
                    btnPlay.text = getString(R.string.play_start)
                }
            }
        }
        if (ok) {
            btnPlay.text = getString(R.string.play_stop)
            setStatus("正在回放: ${file.name}…")
        }
    }

    private fun setStatus(msg: String) {
        txtStatus.text = msg
    }
}

package com.qcc.leaudiorecord

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audio = AudioRecordPlayer()

        btnInput = findViewById(R.id.btnInput)
        btnOutput = findViewById(R.id.btnOutput)
        spinnerRate = findViewById(R.id.spinnerSampleRate)
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
     * 枚举所有输入/输出设备。
     * 每个列表首项为"系统默认"，随后按类型列出设备。
     */
    private fun refreshDevices() {
        inputDevices.clear()
        outputDevices.clear()

        inputDevices.add(null) // 系统默认
        outputDevices.add(null)

        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        android.util.Log.i(TAG, "inputs=${allInputs.size} outputs=${allOutputs.size}")

        for (d in allInputs.sortedBy { it.type }) {
            if (isRecordableInput(d)) {
                inputDevices.add(d)
            }
        }
        for (d in allOutputs.sortedBy { it.type }) {
            if (isPlayableOutput(d)) {
                outputDevices.add(d)
            }
        }

        // 默认选中 LE Audio（开发板），其次本机设备
        selectedInputIdx = inputDevices.indexOfFirst {
            it?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }.let { if (it >= 0) it else 0 }
        selectedOutputIdx = outputDevices.indexOfFirst {
            it?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }.let { if (it >= 0) it else 0 }

        updateDeviceButtonText()

        val total = "输入 ${inputDevices.size - 1} 个, 输出 ${outputDevices.size - 1} 个"
        setStatus("设备枚举完成：$total")
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

    /** 判断是否可作为录音输入设备 */
    private fun isRecordableInput(d: AudioDeviceInfo): Boolean {
        if (!d.isSource) return false
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_TELEPHONY -> true
            else -> false
        }
    }

    /** 判断是否可作为回放输出设备 */
    private fun isPlayableOutput(d: AudioDeviceInfo): Boolean {
        if (!d.isSink) return false
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
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

        val file = audio.getLastFile()
            ?: run {
                setStatus("请先录音")
                return
            }
        val outputDevice = outputDevices.getOrNull(selectedOutputIdx)

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
            setStatus("正在回放…")
        }
    }

    private fun setStatus(msg: String) {
        txtStatus.text = msg
    }
}

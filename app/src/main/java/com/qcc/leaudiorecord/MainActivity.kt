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
import java.io.FileOutputStream

/**
 * LE Audio 录音测试工具 + 离线语音识别（Vosk / Paraformer）
 *
 * 功能：
 * 1. 选择录音输入设备（本机 MIC / LE Audio 开发板 MIC / 其他）——按钮 + 列表对话框
 * 2. 选择回放输出设备（本机扬声器 / LE Audio 开发板 / A2DP 等）——按钮 + 列表对话框
 * 3. 录音（AudioRecord → WAV）与回放（AudioTrack）
 * 4. 实时电平 + 实时波形显示
 * 5. 离线语音识别 —— 支持 Vosk 和 Paraformer 双引擎切换
 *    - 转录录音文件 / 实时流式识别
 *
 * 核心价值：通过 setPreferredDevice 显式指定设备，绕过 AudioPolicy 自动路由，
 * 直接验证 LE Audio 的 MIC 上行通路与扬声器下行通路。
 */
class MainActivity : Activity() {

    private lateinit var audioManager: AudioManager
    private lateinit var audio: AudioRecordPlayer

    // 语音识别引擎
    private lateinit var vosk: VoskHelper
    private lateinit var paraformer: ParaformerHelper
    private val engines = mutableListOf<AsrEngine>()
    private var currentEngineIdx = 0
    private val currentEngine: AsrEngine get() = engines[currentEngineIdx]

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

    // ASR 控件
    private lateinit var btnAsrModel: Button
    private lateinit var btnAsrTranscribe: Button
    private lateinit var btnAsrListen: Button
    private lateinit var txtAsrStatus: TextView
    private lateinit var txtAsrResult: TextView

    // ASR 增益 & 电平控件
    private lateinit var spinnerAsrGain: Spinner
    private lateinit var btnAutoGain: Button
    private lateinit var txtAsrLevel: TextView
    private lateinit var asrLevelBar: ProgressBar

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

        /** assets 中的默认音频文件路径 */
        const val ASSET_DEFAULT_AUDIO = "sounds/Lullaby.wav"
        /** 默认音频复制到外部存储后的文件名 */
        const val DEFAULT_AUDIO_NAME = "default_lullaby.wav"
    }

    /**
     * 确保默认音频文件存在：首次启动时从 assets 复制到外部存储目录。
     *
     * @return 默认音频文件（不存在返回 null）
     */
    private fun ensureDefaultAudio(): File? {
        val dir = getExternalFilesDir(null) ?: return null
        val target = File(dir, DEFAULT_AUDIO_NAME)
        if (target.exists() && target.length() > 0) return target
        try {
            assets.open(ASSET_DEFAULT_AUDIO).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            android.util.Log.i(TAG, "default audio extracted: ${target.absolutePath}")
            return target
        } catch (e: Exception) {
            android.util.Log.e(TAG, "extract default audio failed", e)
            return null
        }
    }

    /** 读取增益 Spinner 选中值 → 倍数 */
    private fun selectedGain(): Float = when (spinnerGain.selectedItemPosition) {
        1 -> 2f
        2 -> 4f
        3 -> 8f
        4 -> 16f
        5 -> 32f
        6 -> 64f
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
        vosk = VoskHelper(this)
        paraformer = ParaformerHelper(this)
        engines.add(vosk)
        engines.add(paraformer)

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

        // ASR 控件
        btnAsrModel = findViewById(R.id.btnAsrModel)
        btnAsrTranscribe = findViewById(R.id.btnAsrTranscribe)
        btnAsrListen = findViewById(R.id.btnAsrListen)
        txtAsrStatus = findViewById(R.id.txtAsrStatus)
        txtAsrResult = findViewById(R.id.txtAsrResult)

        // ASR 增益 & 电平
        spinnerAsrGain = findViewById(R.id.spinnerAsrGain)
        btnAutoGain = findViewById(R.id.btnAutoGain)
        txtAsrLevel = findViewById(R.id.txtAsrLevel)
        asrLevelBar = findViewById(R.id.asrLevelBar)

        btnInput.setOnClickListener { showDeviceDialog(isInput = true) }
        btnOutput.setOnClickListener { showDeviceDialog(isInput = false) }
        btnRecord.setOnClickListener { onRecordClick() }
        btnPlay.setOnClickListener { onPlayClick() }

        // ASR 按钮事件
        btnAsrModel.setOnClickListener { onSwitchModel() }
        btnAsrTranscribe.setOnClickListener { onAsrTranscribe() }
        btnAsrListen.setOnClickListener { onAsrListenToggle() }
        btnAutoGain.setOnClickListener { onAutoGain() }

        refreshDevices()
        val deleted = cleanupOldRecordings(keep = MAX_RECORDINGS)
        if (deleted > 0) {
            setStatus("已清理 $deleted 个旧录音（最多保留 ${MAX_RECORDINGS} 个）")
        }
        val defaultAudio = ensureDefaultAudio()
        if (defaultAudio == null) {
            android.util.Log.w(TAG, "default audio unavailable")
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        }

        // 初始化当前引擎
        initCurrentEngine()
    }

    // ======================== 引擎切换 ========================

    /** 初始化当前选中的引擎 */
    private fun initCurrentEngine() {
        val engine = currentEngine
        txtAsrStatus.text = "初始化 ${engine.name} 模型…"
        btnAsrTranscribe.isEnabled = false
        btnAsrListen.isEnabled = false
        btnAsrModel.isEnabled = false
        btnAsrModel.text = "切换: ${engine.name}"

        engine.initialize(
            onReady = {
                runOnUiThread {
                    txtAsrStatus.text = "${engine.name} 就绪"
                    btnAsrTranscribe.isEnabled = true
                    btnAsrListen.isEnabled = true
                    btnAsrModel.isEnabled = true
                    android.util.Log.i(TAG, "${engine.name} model ready")
                }
            },
            onError = { err ->
                runOnUiThread {
                    txtAsrStatus.text = "${engine.name} 加载失败: $err"
                    btnAsrModel.isEnabled = true
                    android.util.Log.e(TAG, "${engine.name} init error: $err")
                }
            }
        )
    }

    /** 切换语音识别引擎 */
    private fun onSwitchModel() {
        // 如果正在实时识别，先停止
        if (btnAsrListen.text.toString() == getString(R.string.asr_listen_stop)) {
            currentEngine.stopListening()
            btnAsrListen.text = getString(R.string.asr_listen_start)
        }

        // 释放当前引擎
        val old = currentEngine
        old.release()

        // 切换到下一个
        currentEngineIdx = (currentEngineIdx + 1) % engines.size
        val engine = currentEngine
        btnAsrModel.text = "切换: ${engine.name}"
        txtAsrResult.text = "(识别结果将显示在这里)"
        txtAsrStatus.text = "正在切换至 ${engine.name}…"

        initCurrentEngine()
    }

    /** 转录最近一次录音文件 */
    private fun onAsrTranscribe() {
        val engine = currentEngine
        val lastFile = audio.getLastFile()
        if (lastFile == null || !lastFile.exists()) {
            setStatus("请先录音，再转录")
            txtAsrResult.text = "(无录音文件)"
            return
        }
        txtAsrResult.text = "识别中…"
        txtAsrStatus.text = "转录中: ${lastFile.name}"
        btnAsrTranscribe.isEnabled = false

        engine.transcribeFile(
            wavFile = lastFile,
            onResult = { text ->
                txtAsrResult.text = text
                txtAsrStatus.text = "转录完成"
                btnAsrTranscribe.isEnabled = true
                setStatus("识别结果: $text")
            },
            onError = { err ->
                txtAsrResult.text = "(识别失败)"
                txtAsrStatus.text = "转录错误: $err"
                btnAsrTranscribe.isEnabled = true
            }
        )
    }

    /** 实时语音识别开关 */
    private fun onAsrListenToggle() {
        if (!currentEngine.isReady) {
            txtAsrStatus.text = "模型未就绪"
            return
        }
        // 如果按钮文本是"停止识别"，则停止
        if (btnAsrListen.text.toString() == getString(R.string.asr_listen_stop)) {
            currentEngine.stopListening()
            btnAsrListen.text = getString(R.string.asr_listen_start)
            btnAsrTranscribe.isEnabled = true
            txtAsrStatus.text = "实时识别已停止"
            return
        }
        // 读取 ASR 增益
        val asrGain = when (spinnerAsrGain.selectedItemPosition) {
            1 -> 2f
            2 -> 4f
            3 -> 8f
            4 -> 16f
            5 -> 32f
            6 -> 64f
            else -> 1f
        }
        AudioRecordPlayer.asrGain = asrGain
        android.util.Log.i(TAG, "ASR gain set to ${asrGain}x")

        // 启动实时识别
        txtAsrResult.text = "正在聆听…"
        txtAsrStatus.text = "实时识别中（对着设备说话）"
        btnAsrListen.text = getString(R.string.asr_listen_stop)
        btnAsrTranscribe.isEnabled = false

        currentEngine.startListening(
            inputDevice = inputDevices.getOrNull(selectedInputIdx),
            onPartial = { partial ->
                txtAsrResult.text = partial
            },
            onFinal = { finalText ->
                txtAsrResult.text = finalText
                setStatus("识别结果: $finalText")
            },
            onError = { err ->
                txtAsrResult.text = "(识别中断)"
                txtAsrStatus.text = "识别错误: $err"
                btnAsrListen.text = getString(R.string.asr_listen_start)
                btnAsrTranscribe.isEnabled = true
            },
            onLevel = { peak, rms, dbfs ->
                txtAsrLevel.text = "ASR 输入电平: ${dbfs.toInt()} dBFS (RMS=$rms peak=$peak)"
                val pct = ((dbfs + 60f) / 60f * 100f).toInt().coerceIn(0, 100)
                asrLevelBar.progress = pct
            }
        )
    }

    /** 自动增益分析：从最近录音文件分析并建议增益 */
    private fun onAutoGain() {
        val lastFile = audio.getLastFile()
        if (lastFile == null || !lastFile.exists()) {
            setStatus("请先录音，再分析增益")
            return
        }
        val result = AudioGainUtil.analyzeWavFile(lastFile.absolutePath)
        if (result == null) {
            setStatus("无法分析录音文件")
            return
        }
        val msg = "录音分析: ${result.summary} (时长: ${result.durationMs}ms)"
        setStatus(msg)

        // 在 ASR 增益 Spinner 中选择最接近的建议增益
        val gainOptions = floatArrayOf(1f, 2f, 4f, 8f, 16f, 32f, 64f)
        var bestIdx = 0
        var bestDiff = Float.MAX_VALUE
        for (i in gainOptions.indices) {
            val diff = kotlin.math.abs(gainOptions[i] - result.suggestedGain)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        spinnerAsrGain.setSelection(bestIdx)
        AudioRecordPlayer.asrGain = gainOptions[bestIdx]
        txtAsrStatus.text = "建议增益: ${gainOptions[bestIdx]}x (${result.summary})"
    }

    override fun onDestroy() {
        // 释放所有引擎
        for (e in engines) {
            e.release()
        }
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

        // 详细日志：逐设备类型/地址/产品名，便于排查车机环境
        for (d in allInputs.sortedBy { it.type }) {
            android.util.Log.i(TAG, "  IN: ${AudioRecordPlayer.deviceLabel(d)}")
        }
        for (d in allOutputs.sortedBy { it.type }) {
            android.util.Log.i(TAG, "  OUT: ${AudioRecordPlayer.deviceLabel(d)} id=${d.id}")
        }

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

        // 默认音频（assets 复制而来，未录音时也可回放）置于列表首位
        val defaultAudio = File(dir, DEFAULT_AUDIO_NAME)
        val playList = mutableListOf<File>()
        val defaultAvailable = defaultAudio.exists() && defaultAudio.length() > 0
        if (defaultAvailable) {
            playList.add(defaultAudio)
        }
        // 过滤掉与默认文件重复的项
        playList.addAll(sorted.filter { it.absolutePath != defaultAudio.absolutePath })

        if (playList.isEmpty()) {
            setStatus("无 WAV 文件，请先录音")
            return
        }

        // 弹出文件选择：默认音频 + 目录下全部录音（便于回放测试信号做对照）
        val names = playList.map { f ->
            val sizeKb = f.length() / 1024
            if (f.absolutePath == defaultAudio.absolutePath) {
                "🎵 默认音频 Lullaby (${sizeKb}KB)"
            } else {
                "${f.name} (${sizeKb}KB)"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("选择回放文件")
            .setItems(names.toTypedArray()) { dialog, which ->
                startPlayback(playList[which])
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

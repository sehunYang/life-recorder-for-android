package com.liferecorder.debug

import android.app.Activity
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 녹음 경로 비교 실험 (디버그 빌드 전용). 압축하지 않은 WAV 로 경로마다 [SECONDS] 초씩 차례로 녹음한다.
 * 높은 삐 소리가 나면 읽기 시작, 낮은 소리가 나면 멈춘다. 결과는 files/audiotest/ 에 남는다.
 *
 *   adb shell am start -n com.liferecorder/.debug.AudioTestActivity
 */
class AudioTestActivity : Activity() {

    private class Variant(
        val name: String,
        val source: Int,
        val stereo: Boolean = false,
        val effects: Boolean = false,
    )

    private val variants = listOf(
        Variant("1_voice_recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION), // 지금 앱이 쓰는 경로
        Variant("2_mic", MediaRecorder.AudioSource.MIC),
        Variant("3_camcorder_stereo", MediaRecorder.AudioSource.CAMCORDER, stereo = true),
        Variant("4_unprocessed", MediaRecorder.AudioSource.UNPROCESSED),
        Variant("5_voice_communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
        Variant("6_voice_recognition_ns_agc", MediaRecorder.AudioSource.VOICE_RECOGNITION, effects = true),
    )

    private lateinit var status: TextView
    @Volatile private var stop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { textSize = 28f; gravity = Gravity.CENTER; setPadding(40, 40, 40, 40) }
        setContentView(status)
        Thread(::runAll, "audio-test").start()
    }

    override fun onDestroy() {
        stop = true
        super.onDestroy()
    }

    private fun show(s: String) = runOnUiThread { status.text = s }

    private fun runAll() {
        val dir = File(getExternalFilesDir(null), "audiotest").apply { mkdirs() }
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        show("곧 시작합니다\n\n높은 삐 → 읽기\n낮은 삐 → 멈춤")
        Thread.sleep(START_DELAY_MS)
        for ((i, v) in variants.withIndex()) {
            if (stop) break
            show("${i + 1} / ${variants.size}\n${v.name}\n\n읽어 주세요")
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
            Thread.sleep(700) // 삐 소리가 녹음에 들어가지 않게
            try {
                record(v, File(dir, "${v.name}.wav"))
            } catch (e: Exception) {
                Log.e(TAG, "${v.name} failed", e)
            }
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 400)
            show("${i + 1} / ${variants.size} 끝\n\n잠시 쉬세요")
            Thread.sleep(GAP_MS)
        }
        tone.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 800)
        show("모두 끝났습니다")
        Log.i(TAG, "done")
        tone.release()
    }

    private fun record(v: Variant, out: File) {
        val ch = if (v.stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val channels = if (v.stereo) 2 else 1
        val min = AudioRecord.getMinBufferSize(RATE, ch, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(v.source, RATE, ch, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, RATE))
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        if (v.effects) {
            ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
            agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } else null
        }
        val raf = RandomAccessFile(out, "rw").apply { setLength(0); write(ByteArray(44)) }
        val buf = ByteArray(RATE / 10 * 2 * channels)
        var bytes = 0L
        rec.startRecording()
        val end = System.currentTimeMillis() + SECONDS * 1000L
        var silenced: Boolean? = null
        while (System.currentTimeMillis() < end && !stop) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) { raf.write(buf, 0, n); bytes += n }
            if (silenced == null) silenced = rec.activeRecordingConfiguration?.isClientSilenced
        }
        val device = rec.routedDevice?.let { "${it.productName}/type${it.type}" }
        rec.stop()
        rec.release()
        ns?.release(); agc?.release()
        writeHeader(raf, bytes, channels)
        raf.close()
        Log.i(TAG, "${v.name}: ${bytes} bytes, silenced=$silenced, device=$device, " +
            "ns=${ns != null} agc=${agc != null} (available ns=${NoiseSuppressor.isAvailable()} agc=${AutomaticGainControl.isAvailable()})")
    }

    private fun writeHeader(raf: RandomAccessFile, dataBytes: Long, channels: Int) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        b.putInt(RATE).putInt(RATE * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(b.array())
    }

    companion object {
        private const val TAG = "AudioTest"
        private const val RATE = 48_000
        private const val SECONDS = 15
        private const val GAP_MS = 4_000L
        private const val START_DELAY_MS = 10_000L
    }
}

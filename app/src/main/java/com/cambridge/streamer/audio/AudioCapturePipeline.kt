package com.cambridge.streamer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Production AAC Audio Capture Pipeline
 *
 * Flow:
 *   HDMI USB Capture Card Audio (or Mic) → AudioRecord (PCM 16-bit, 44100 Hz, Mono)
 *     → MediaCodec AAC-LC encoder
 *       → onAudioSpecificConfig callback (csd-0, sent once as RTMP/RTSP AAC header)
 *       → onAudioFrame callback (raw AAC frame without ADTS header, sent per frame)
 *
 * Thread model: runs entirely on its own background thread — no UI interaction.
 */
class AudioCapturePipeline(
    private val context: Context? = null,
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val bitrate: Int = 128_000,
    /** Optional callback with verified active routed audio device. */
    val onAudioRoutingChecked: ((isUsb: Boolean, deviceName: String) -> Unit)? = null,
    /** Optional callback with live decibel volume level and source device for UI VU meter. */
    var onAudioLevel: ((dbLevel: Float, isUsb: Boolean, deviceName: String) -> Unit)? = null,
    /** Called with the AudioSpecificConfig (csd-0) byte array. */
    var onAudioSpecificConfig: ((ByteArray) -> Unit)? = null,
    /** Called for every AAC frame. [rawAac] has NO ADTS header. [dtsMs] is presentation time in ms. */
    var onAudioFrame: ((rawAac: ByteArray, dtsMs: Long) -> Unit)? = null
) {

    private val TAG = "AudioCapturePipeline"

    @Volatile private var running = false
    private var captureThread: Thread? = null

    var cachedAudioSpecificConfig: ByteArray? = null
        private set

    // ─── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        captureThread = Thread(::captureLoop, "AudioCapture").also { it.start() }
        Log.d(TAG, "Audio capture pipeline started")
    }

    fun stop() {
        running = false
        captureThread?.interrupt()
        captureThread = null
        Log.d(TAG, "Audio capture pipeline stopped")
    }

    // ─── Capture Loop ──────────────────────────────────────────────────────────

    private fun captureLoop() {
        // ── AudioRecord setup ──────────────────────────────────────────────────
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "AudioRecord: invalid buffer size ($minBufferSize)")
            return
        }

        // Use a generous read buffer: 2× the codec input requirement
        val readBufferSize = maxOf(minBufferSize * 2, 4096)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                readBufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", e)
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord.release()
            return
        }

        // Lock onto HDMI Capture Card USB Audio if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                // Capture cards may enumerate as USB_DEVICE, USB_HEADSET, or USB_ACCESSORY depending on firmware
                val usbAudio = devices?.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                }
                if (usbAudio != null) {
                    val ok = audioRecord.setPreferredDevice(usbAudio)
                    Log.d(TAG, "AudioRecord targeted USB Audio device: ${usbAudio.productName} (type=${usbAudio.type}), result=$ok")
                } else {
                    Log.d(TAG, "No USB Audio device found — using standard audio source (phone mic or aux-in)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error setting preferred audio device", e)
            }
        }

        // ── MediaCodec AAC encoder setup ───────────────────────────────────────
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AAC encoder", e)
            audioRecord.release()
            return
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1 // mono
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, readBufferSize)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        audioRecord.startRecording()
        Log.d(TAG, "Recording started: ${sampleRate}Hz, buffer=${readBufferSize}B")

        // Check and report active hardware routed device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val routed = audioRecord.routedDevice
            val isUsb = routed?.let {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            } ?: false
            val devName = routed?.productName?.toString() ?: if (isUsb) "HDMI USB Audio" else "Phone Mic"
            Log.d(TAG, "AudioRecord active routedDevice: $devName (isUsb=$isUsb, type=${routed?.type})")
            onAudioRoutingChecked?.invoke(isUsb, devName)
        }

        val pcmBuffer = ByteArray(readBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var ascSent = false

        var lastLevelReport = 0L
        var sumSquares = 0.0
        var sampleCount = 0

        try {
            while (running) {
                // 1. Read PCM from microphone
                val bytesRead = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead <= 0) continue

                // 1a. Compute live RMS decibel volume level for VU meter
                val numSamples = bytesRead / 2
                var p = 0
                for (s in 0 until numSamples) {
                    val sample = ((pcmBuffer[p + 1].toInt() shl 8) or (pcmBuffer[p].toInt() and 0xFF)).toShort()
                    sumSquares += (sample * sample)
                    sampleCount++
                    p += 2
                }

                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastLevelReport >= 120) {
                    val rms = if (sampleCount > 0) Math.sqrt(sumSquares / sampleCount) else 0.0
                    val db = if (rms > 1.0) (20 * Math.log10(rms / 32767.0)).toFloat().coerceIn(-60f, 0f) else -60f
                    sumSquares = 0.0
                    sampleCount = 0
                    lastLevelReport = now

                    val currentRouted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioRecord.routedDevice else null
                    val isUsbDevice = currentRouted?.let {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                    } ?: false
                    val devName = currentRouted?.productName?.toString() ?: if (isUsbDevice) "HDMI USB Audio" else "Phone Mic"
                    onAudioLevel?.invoke(db, isUsbDevice, devName)
                }

                // 2. Feed PCM into AAC encoder
                val inputIdx = codec.dequeueInputBuffer(10_000L)
                if (inputIdx >= 0) {
                    val inputBuf = codec.getInputBuffer(inputIdx) ?: continue
                    inputBuf.clear()
                    inputBuf.put(pcmBuffer, 0, bytesRead)
                    val ptsUs = System.nanoTime() / 1000L
                    codec.queueInputBuffer(inputIdx, 0, bytesRead, ptsUs, 0)
                }

                // 3. Drain encoder output
                drainLoop@ while (true) {
                    val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 0L)
                    when {
                        outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // csd-0 contains the AudioSpecificConfig (2 bytes for AAC-LC)
                            if (!ascSent) {
                                val newFormat = codec.outputFormat
                                val csd0 = newFormat.getByteBuffer("csd-0")
                                if (csd0 != null) {
                                    val asc = ByteArray(csd0.remaining())
                                    csd0.get(asc)
                                    cachedAudioSpecificConfig = asc
                                    Log.d(TAG, "AAC AudioSpecificConfig: ${asc.contentToString()}")
                                    onAudioSpecificConfig?.invoke(asc)
                                    ascSent = true
                                }
                            }
                        }
                        outputIdx >= 0 -> {
                            val outputBuf = codec.getOutputBuffer(outputIdx)
                            if (outputBuf != null && bufferInfo.size > 0) {
                                val dtsMs = bufferInfo.presentationTimeUs / 1000L

                                // Strip 7-byte ADTS header if present
                                val offset = bufferInfo.offset
                                val hasAdts = outputBuf.get(offset).toInt() and 0xFF == 0xFF &&
                                        outputBuf.get(offset + 1).toInt() and 0xF0 == 0xF0
                                val dataOffset = if (hasAdts) offset + 7 else offset
                                val dataSize   = bufferInfo.size - if (hasAdts) 7 else 0

                                if (dataSize > 0) {
                                    val rawAac = ByteArray(dataSize)
                                    outputBuf.position(dataOffset)
                                    outputBuf.get(rawAac)
                                    onAudioFrame?.invoke(rawAac, dtsMs)
                                }
                            }
                            codec.releaseOutputBuffer(outputIdx, false)
                        }
                        else -> break@drainLoop
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "Capture loop interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop error", e)
        } finally {
            audioRecord.stop()
            audioRecord.release()
            try {
                codec.stop()
                codec.release()
            } catch (_: Exception) {}
            Log.d(TAG, "Audio resources released")
        }
    }
}

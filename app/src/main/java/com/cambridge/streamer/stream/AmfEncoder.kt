package com.cambridge.streamer.stream

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Production AMF0 (Action Message Format 0) encoder.
 * Used to serialize RTMP command messages for YouTube Live / Facebook Live.
 */
object AmfEncoder {

    // AMF0 Type Markers
    private const val TYPE_NUMBER = 0x00
    private const val TYPE_BOOLEAN = 0x01
    private const val TYPE_STRING = 0x02
    private const val TYPE_OBJECT = 0x03
    private const val TYPE_NULL = 0x05
    private const val TYPE_ECMA_ARRAY = 0x08
    private const val TYPE_OBJECT_END = 0x09

    fun encodeString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(TYPE_STRING)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
        return out.toByteArray()
    }

    fun encodeNumber(value: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(value)
        val out = ByteArrayOutputStream()
        out.write(TYPE_NUMBER)
        for (i in 7 downTo 0) {
            out.write(((bits shr (i * 8)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    fun encodeBoolean(value: Boolean): ByteArray {
        return byteArrayOf(TYPE_BOOLEAN.toByte(), if (value) 1 else 0)
    }

    fun encodeNull(): ByteArray = byteArrayOf(TYPE_NULL.toByte())

    /** Encodes an AMF0 Object from a list of key-value pairs (preserving insertion order). */
    fun encodeObject(properties: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(TYPE_OBJECT)
        for ((key, value) in properties) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            out.write((keyBytes.size shr 8) and 0xFF)
            out.write(keyBytes.size and 0xFF)
            out.write(keyBytes)
            out.write(value)
        }
        // Object end marker
        out.write(0x00); out.write(0x00); out.write(TYPE_OBJECT_END)
        return out.toByteArray()
    }

    /** Encodes an ECMA Array (associative array — used for onMetaData). */
    fun encodeEcmaArray(properties: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(TYPE_ECMA_ARRAY)
        val count = properties.size
        out.write((count shr 24) and 0xFF)
        out.write((count shr 16) and 0xFF)
        out.write((count shr 8) and 0xFF)
        out.write(count and 0xFF)
        for ((key, value) in properties) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            out.write((keyBytes.size shr 8) and 0xFF)
            out.write(keyBytes.size and 0xFF)
            out.write(keyBytes)
            out.write(value)
        }
        out.write(0x00); out.write(0x00); out.write(TYPE_OBJECT_END)
        return out.toByteArray()
    }

    /**
     * Builds the full AMF0 payload for the RTMP "connect" command.
     * txId = 1.0 (always for connect)
     */
    fun buildConnectCommand(app: String, tcUrl: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeString("connect"))
        out.write(encodeNumber(1.0)) // transaction ID
        out.write(
            encodeObject(
                listOf(
                    "app" to encodeString(app),
                    "flashVer" to encodeString("FMLE/3.0 (compatible; FMSc/1.0)"),
                    "tcUrl" to encodeString(tcUrl),
                    "type" to encodeString("nonprivate")
                )
            )
        )
        return out.toByteArray()
    }

    /** AMF0 "createStream" command — txId = 2.0 */
    fun buildCreateStreamCommand(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeString("createStream"))
        out.write(encodeNumber(2.0))
        out.write(encodeNull())
        return out.toByteArray()
    }

    /** AMF0 "publish" command — txId = 5.0, live type */
    fun buildPublishCommand(streamKey: String, msgStreamId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeString("publish"))
        out.write(encodeNumber(5.0))
        out.write(encodeNull())
        out.write(encodeString(streamKey))
        out.write(encodeString("live"))
        return out.toByteArray()
    }

    /** @setDataFrame onMetaData for video stream parameters. */
    fun buildMetaDataCommand(width: Int, height: Int, fps: Int, bitrate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeString("@setDataFrame"))
        out.write(encodeString("onMetaData"))
        out.write(
            encodeEcmaArray(
                listOf(
                    "width" to encodeNumber(width.toDouble()),
                    "height" to encodeNumber(height.toDouble()),
                    "framerate" to encodeNumber(fps.toDouble()),
                    "videodatarate" to encodeNumber(bitrate.toDouble()),
                    "videocodecid" to encodeNumber(7.0), // H.264
                    "audiodatarate" to encodeNumber(128.0),
                    "audiocodecid" to encodeNumber(10.0), // AAC
                    "audiosamplerate" to encodeNumber(44100.0),
                    "audiosamplesize" to encodeNumber(16.0),
                    "stereo" to encodeBoolean(false),
                    "encoder" to encodeString("CamBridge Pro / Android")
                )
            )
        )
        return out.toByteArray()
    }
}

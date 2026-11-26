package com.riyaz.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes any audio or video file Android can read (mp3, m4a, opus, flac, wav, mp4…)
 * into raw 16-bit little-endian stereo PCM, which the C++ engine memory-maps.
 * Also computes a coarse peak overview for the waveform.
 */
class AudioDecoder(private val context: Context) {

    class Result(val sampleRate: Int, val frames: Long, val peaks: FloatArray)

    fun decode(uri: Uri, out: File, onProgress: (Float) -> Unit): Result {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("This file has no audio track")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            // Peak overview: about PEAK_BINS bins over the expected length.
            val expectedFrames = max(1L, durationUs * sampleRate / 1_000_000L)
            var framesPerBin = max(1L, expectedFrames / PEAK_BINS)
            val peaks = ArrayList<Float>(PEAK_BINS + 64)
            var binPeak = 0f
            var binCount = 0L

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            var frames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val scratch = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

            BufferedOutputStream(FileOutputStream(out), 256 * 1024).use { os ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            framesPerBin = max(1L, max(1L, durationUs * sampleRate / 1_000_000L) / PEAK_BINS)
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytesPerSample = if (floatPcm) 4 else 2
                            val n = info.size / (bytesPerSample * channels)
                            for (i in 0 until n) {
                                val l: Float
                                val r: Float
                                if (floatPcm) {
                                    l = buf.float
                                    r = if (channels > 1) buf.float else l
                                    repeat(max(0, channels - 2)) { buf.float }
                                } else {
                                    l = buf.short / 32768f
                                    r = if (channels > 1) buf.short / 32768f else l
                                    repeat(max(0, channels - 2)) { buf.short }
                                }
                                if (scratch.remaining() < 4) {
                                    os.write(scratch.array(), 0, scratch.position())
                                    scratch.clear()
                                }
                                scratch.putShort(toPcm16(l))
                                scratch.putShort(toPcm16(r))

                                val a = max(abs(l), abs(r))
                                if (a > binPeak) binPeak = a
                                if (++binCount >= framesPerBin) {
                                    peaks.add(binPeak)
                                    binPeak = 0f
                                    binCount = 0
                                }
                            }
                            frames += n
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                os.write(scratch.array(), 0, scratch.position())
            }
            if (binCount > 0) peaks.add(binPeak)
            codec.stop()
            codec.release()
            if (frames == 0L) error("Couldn't decode any audio from this file")
            return Result(sampleRate, frames, peaks.toFloatArray())
        } finally {
            extractor.release()
        }
    }

    private fun toPcm16(x: Float): Short = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    companion object {
        const val PEAK_BINS = 1200
    }
}

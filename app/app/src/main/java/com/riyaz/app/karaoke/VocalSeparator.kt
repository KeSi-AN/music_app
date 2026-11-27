package com.riyaz.app.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.riyaz.app.audio.NativeEngine
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Makes the karaoke (instrumental) track of a song on the phone with the MDX-Net "UVR_MDXNET_9482"
 * model, chosen in spike/SPIKE_RESULTS.md (Phase 3): 13.8 dB instrumental on MUSDB, better than HT-Demucs,
 * at a fraction of the cost. The model (30 MB) is downloaded once from the UVR project's model release.
 */
class VocalSeparator(private val context: Context) {

    private val modelFile = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_NAME)

    val modelReady: Boolean get() = modelFile.length() == MODEL_BYTES

    /** Downloads and checks the model if it isn't there yet. */
    fun ensureModel(onProgress: (Float) -> Unit, isCancelled: () -> Boolean) {
        if (modelReady) return
        val part = File(modelFile.path + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IOException("Model download failed (HTTP ${conn.responseCode})")
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        onProgress(done.toFloat() / MODEL_BYTES)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != MODEL_SHA256) throw IOException("The downloaded model is damaged (checksum mismatch)")
            part.renameTo(modelFile)
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    /**
     * Writes [backingOut] (16-bit stereo PCM, same rate and length as the song).
     * [onProgress] gets 0..1; [isCancelled] is checked between chunks.
     */
    /** Runtime settings, tunable from the debug test hook. */
    // Measured on the Edge 50 Neo (2026-11-27): the default CPU kernels without the memory arena were
    // both faster than XNNPACK and peaked at ~1.0 GB instead of 2.1 GB.
    data class Config(val xnnpack: Boolean = false, val threads: Int = 4, val arena: Boolean = false, val maxChunks: Int = Int.MAX_VALUE)

    fun separate(
        pcm: File, sampleRate: Int, backingOut: File, onProgress: (Float) -> Unit, isCancelled: () -> Boolean,
        config: Config = Config(),
    ) {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(config.arena)
            if (config.xnnpack) {
                // XNNPACK runs its own thread pool: keep ONNX Runtime's to 1 so the two don't fight
                setIntraOpNumThreads(1)
                runCatching { addXnnpack(mapOf("intra_op_num_threads" to config.threads.toString())) }
                    .onFailure { Log.w(TAG, "XNNPACK not available, using the default CPU kernels"); setIntraOpNumThreads(config.threads) }
            } else {
                setIntraOpNumThreads(config.threads)
            }
        }
        val handle = NativeEngine.nativeMdxOpen(pcm.absolutePath, sampleRate, N_FFT, DIM_F)
        if (handle == 0L) throw IOException("Couldn't read the song for separation")
        val tmp = File(backingOut.path + ".part")
        try {
            env.createSession(modelFile.absolutePath, opts).use { session ->
                val chunks = minOf(NativeEngine.nativeMdxChunks(handle), config.maxChunks)
                val input = FloatArray(4 * DIM_F * DIM_T)
                val shape = longArrayOf(1, 4, DIM_F.toLong(), DIM_T.toLong())
                val t0 = System.nanoTime()
                for (k in 0 until chunks) {
                    if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                    NativeEngine.nativeMdxInput(handle, k, input)
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { x ->
                        session.run(mapOf("input" to x)).use { out ->
                            val buf = (out[0] as OnnxTensor).floatBuffer
                            val pred = FloatArray(buf.remaining()).also { buf.get(it) }
                            NativeEngine.nativeMdxOutput(handle, k, pred)
                        }
                    }
                    onProgress((k + 1).toFloat() / chunks * 0.97f)
                }
                Log.i(TAG, "separation: $chunks chunks in ${(System.nanoTime() - t0) / 1_000_000} ms ($config)")
            }
            if (config.maxChunks != Int.MAX_VALUE) return   // timing run only
            if (!NativeEngine.nativeMdxWriteBacking(handle, tmp.absolutePath, COMPENSATE)) throw IOException("Couldn't save the karaoke track")
            tmp.renameTo(backingOut)
            onProgress(1f)
        } finally {
            NativeEngine.nativeMdxClose(handle)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "Riyaz"
        const val MODEL_NAME = "UVR_MDXNET_9482.onnx"
        private const val MODEL_URL = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR_MDXNET_9482.onnx"
        private const val MODEL_BYTES = 29_704_436L
        private const val MODEL_SHA256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184"
        private const val N_FFT = 6144
        private const val DIM_F = 2048
        private const val DIM_T = 256
        private const val COMPENSATE = 1.035f

        fun backingFile(songDir: File) = File(songDir, "backing.pcm")
    }
}

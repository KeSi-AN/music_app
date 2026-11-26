package com.riyaz.app.audio

/** Thin wrapper over the C++ engine (Oboe + Signalsmith Stretch). One instance per process. */
object NativeEngine {
    init {
        System.loadLibrary("riyaz")
    }

    external fun nativeLoad(pcmPath: String, sampleRate: Int): Boolean
    external fun nativeUnload()
    external fun nativePlay(): Boolean
    external fun nativePause()
    external fun nativeSeek(frame: Long)
    external fun nativeSetSemitones(semitones: Float)
    external fun nativeSetTempo(tempo: Float)
    external fun nativeSetNaturalVoice(on: Boolean)
    external fun nativePosition(): Long
    external fun nativeFrames(): Long
    external fun nativeIsPlaying(): Boolean
    external fun nativeReachedEnd(): Boolean

    /** Test harness only: offline render of part of a song with the live key/speed settings. */
    external fun nativeRenderTest(
        pcmPath: String, sampleRate: Int, startFrame: Long, outFrames: Int,
        semitones: Float, tempo: Float, natural: Boolean, outWav: String,
    ): Boolean
}

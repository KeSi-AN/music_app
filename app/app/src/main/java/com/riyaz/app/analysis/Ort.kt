package com.riyaz.app.analysis

import ai.onnxruntime.OrtEnvironment

/** The ONNX Runtime environment, with its telemetry switched off (Riyaz sends nothing anywhere). */
object Ort {
    val env: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment().also { runCatching { it.setTelemetry(false) } }
    }
}

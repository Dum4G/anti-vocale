package com.antivocale.app.transcription

import android.os.Build

/**
 * Resolves the ONNX Runtime execution provider for inference.
 *
 * sherpa-onnx supports "nnapi" (Android Neural Networks API) and "cpu".
 * NNAPI can route to NPU/GPU/DSP but often adds driver overhead that
 * outweighs the benefit for small models. Default is CPU.
 */
object InferenceProvider {

    /** User-visible provider options stored in preferences. */
    const val AUTO = "auto"
    const val NNAPI = "nnapi"
    const val CPU = "cpu"

    /**
     * Resolves the actual provider string to pass to sherpa-onnx.
     *
     * - "auto" -> "cpu" (NNAPI driver overhead often hurts small models)
     * - "nnapi" -> "nnapi" (user explicitly wants NNAPI), EXCEPT on MediaTek where
     *   the driver crashes are so severe we override to CPU for safety
     * - "cpu" -> "cpu" (user explicitly wants CPU)
     */
    fun resolve(preference: String): String {
        // No blanket MediaTek guard: some MediaTek devices (Dimensity 9300, issue #26)
        // have working NNAPI with 3x speedup. Crash recovery is handled at startup
        // (if NNAPI caused a native crash, the preference is reset to CPU there).
        return when (preference) {
            NNAPI -> NNAPI
            CPU -> CPU
            else -> CPU
        }
    }

    /**
     * Checks whether NNAPI is available on this device.
     *
     * NNAPI was introduced in API 27 (Android 8.1). Available on all devices
     * including MediaTek (issue #26: Dimensity 9300 works fine, 3x speedup).
     * Crash-prone devices are handled by the startup recovery: if NNAPI caused
     * a native crash, the preference is reset to CPU on the next launch.
     */
    fun isNnapiAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    }

    /**
     * Valid preference values for the settings dropdown. NNAPI is offered on every
     * device, matching [resolve]: the blanket MediaTek exclusion was dropped with the
     * issue-26 re-enable (working Dimensity NNAPI, crash recovery resets to CPU).
     * This MUST stay consistent with [resolve]; when it did not, the dropdown hid
     * NNAPI on MediaTek devices while an already-saved preference still used it.
     */
    val options: List<String>
        get() = listOf(AUTO, NNAPI, CPU)
}

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
     * MediaTek SoCs ship notoriously buggy NNAPI drivers (NeuroPilot) that crash with
     * ANEURALNETWORKS_BAD_DATA or SIGABRT on many ONNX models. Detect them and force CPU
     * to avoid native crashes. This is a blunt instrument but pragmatic: the research
     * (docs/scout-reports/2026-08-13-mediatek-crash-research.md) found no safe way to
     * use NNAPI on MediaTek for our model shapes.
     */
    private fun isMediaTek(): Boolean {
        val hw = Build.HARDWARE.orEmpty().lowercase()
        val board = Build.BOARD.orEmpty().lowercase()
        // MediaTek hardware strings: "mt67xx", "mt68xx", "mt6983", etc. Also check the
        // SOC manufacturer on API 31+ for authoritative detection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socMfr = Build.SOC_MANUFACTURER.orEmpty().lowercase()
            if (socMfr.contains("mediatek")) return true
        }
        return hw.startsWith("mt") || board.startsWith("mt") ||
            hw.contains("mediatek") || board.contains("mediatek")
    }

    /**
     * Resolves the actual provider string to pass to sherpa-onnx.
     *
     * - "auto" -> "cpu" (NNAPI driver overhead often hurts small models)
     * - "nnapi" -> "nnapi" (user explicitly wants NNAPI), EXCEPT on MediaTek where
     *   the driver crashes are so severe we override to CPU for safety
     * - "cpu" -> "cpu" (user explicitly wants CPU)
     */
    fun resolve(preference: String): String {
        // MediaTek guard: force CPU regardless of user preference. The NNAPI driver
        // crashes are uncatchable native SIGABRTs that kill the process.
        if (isMediaTek() && preference == NNAPI) {
            return CPU
        }
        return when (preference) {
            NNAPI -> NNAPI
            CPU -> CPU
            else -> CPU
        }
    }

    /**
     * Checks whether NNAPI is available on this device.
     *
     * NNAPI was introduced in API 27 (Android 8.1). Disabled on MediaTek due to
     * driver crashes (see isMediaTek). We don't probe specific driver capabilities
     * here; if NNAPI is present and not MediaTek, we let the user opt in.
     */
    fun isNnapiAvailable(): Boolean {
        if (isMediaTek()) return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    }

    /**
     * Valid preference values for the settings dropdown. On MediaTek devices, NNAPI is
     * excluded because the driver crashes are uncatchable native SIGABRTs.
     */
    val options: List<String>
        get() = if (isMediaTek()) listOf(AUTO, CPU) else listOf(AUTO, NNAPI, CPU)
}

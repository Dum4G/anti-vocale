package com.antivocale.app.transcription

import android.os.Build
import androidx.annotation.VisibleForTesting

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
        return isMediaTek(
            Build.HARDWARE.orEmpty(),
            Build.BOARD.orEmpty(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else ""
        )
    }

    /**
     * Pure, testable MediaTek detection. Checks hardware/board/SOC manufacturer strings.
     * Visible for testing.
     */
    @VisibleForTesting
    internal fun isMediaTek(hardware: String, board: String, socManufacturer: String): Boolean {
        val hw = hardware.lowercase()
        val bd = board.lowercase()
        val soc = socManufacturer.lowercase()
        return soc.contains("mediatek") ||
            hw.startsWith("mt") || bd.startsWith("mt") ||
            hw.contains("mediatek") || bd.contains("mediatek")
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
     * Valid preference values for the settings dropdown. On MediaTek devices, NNAPI is
     * excluded because the driver crashes are uncatchable native SIGABRTs.
     */
    val options: List<String>
        get() = if (isMediaTek()) listOf(AUTO, CPU) else listOf(AUTO, NNAPI, CPU)
}

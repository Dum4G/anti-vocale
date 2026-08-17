package com.antivocale.app.ui.tabs

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import com.antivocale.app.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.service.InferenceService
import com.antivocale.app.util.formatFileSize
import com.antivocale.app.transcription.WhisperModelManager
import com.antivocale.app.transcription.WhisperBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.WhisperDownloader
import com.antivocale.app.transcription.Qwen3AsrDownloader
import com.antivocale.app.transcription.Qwen3AsrModelManager
import com.antivocale.app.transcription.NemotronDownloader
import com.antivocale.app.transcription.NemotronModelVariant
import com.antivocale.app.transcription.NemotronStreamingBackend
import com.antivocale.app.transcription.GigaAmBackend
import com.antivocale.app.transcription.GigaAmDownloader
import com.antivocale.app.transcription.GigaAmModelVariant
import com.antivocale.app.transcription.ParakeetDownloader
import com.antivocale.app.transcription.ParakeetModelManager
import com.antivocale.app.transcription.Language
// GGUF: import com.antivocale.app.transcription.Gemma4GgufBackend
// GGUF: import com.antivocale.app.transcription.Gemma4GgufModelManager
import com.antivocale.app.transcription.ModelInfoProvider
import com.antivocale.app.transcription.ModelVariant
import com.antivocale.app.ui.components.DownloadButtonState
import com.antivocale.app.ui.components.DownloadProgressView
import com.antivocale.app.ui.components.InfoIconButton
import com.antivocale.app.ui.components.LanguageFilterBar
import com.antivocale.app.ui.components.ModelVariantCard
import com.antivocale.app.ui.components.ModelVariantCardState
import com.antivocale.app.ui.components.UnloadModelButton
import com.antivocale.app.ui.components.PartialDownloadSection
import com.antivocale.app.ui.components.BenchmarkDialog
import com.antivocale.app.ui.components.DeleteConfirmationDialog
import com.antivocale.app.ui.components.DownloadConfirmationDialog
import com.antivocale.app.ui.components.ModelInfoOverlay
import com.antivocale.app.benchmark.BenchmarkState
import com.antivocale.app.ui.viewmodel.ModelViewModel

/** Generic Parakeet info used for the section-level info overlay. */
private val ParakeetVariant = object : ModelVariant {
    override val titleResId = R.string.parakeet_title
    override val descriptionResId = R.string.parakeet_description
    override val dirName = "parakeet-tdt"
    override val estimatedSizeMB = 862L
    override val supportedLanguageCodes = Language.PARAKEET
}

private fun <T> filterVariants(
    entries: List<T>,
    languageCode: String?,
    getCodes: (T) -> Set<String>
): List<T> {
    if (languageCode == null) return entries
    return entries.filter { languageCode in getCodes(it) }
}

/** GGUF inference via llama-bro only ships native libs for arm64-v8a. */
private fun supportsArm64(): Boolean =
    Build.SUPPORTED_ABIS?.any { it.equals("arm64-v8a", ignoreCase = true) } == true



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelTab(
    viewModel: ModelViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val activeBackendId by viewModel.activeBackendId.collectAsState()
    val downloadUiState by viewModel.downloadUiState.collectAsState()
    val parakeetState by viewModel.parakeetState.collectAsState()
    val whisperState by viewModel.whisperState.collectAsState()
    val qwen3AsrState by viewModel.qwen3AsrState.collectAsState()
    val nemotronState by viewModel.nemotronState.collectAsState()
    val gigaAmState by viewModel.gigaAmState.collectAsState()
    val ggufState by viewModel.ggufState.collectAsState()

    // Transcription active state — used to warn about destructive operations
    val isTranscribing by InferenceService.isTranscribing.collectAsState()
    var pendingModelSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showUnloadDialog by remember { mutableStateOf(false) }

    var modelInfoVariant by remember { mutableStateOf<ModelVariant?>(null) }
    var externalToDelete by remember { mutableStateOf<com.antivocale.app.data.ExternalModelRecord?>(null) }
    // Shared family selection + import options for both import paths (folder and URL).
    var externalImport by remember { mutableStateOf(ExternalImportUiState()) }

    // Folder picker launcher for external-model imports (OpenDocumentTree; SAF copy in the VM).
    val externalFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.importExternalFromFolder(
                context, it, externalImport.family,
                ctcModelType = externalImport.ctcModelType,
                options = externalImport.options(),
                languages = externalImport.languageCodes(),
            )
        }
    }

    // Snackbar host state for displaying errors
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe token state changes and refresh ModelViewModel
    val tokenState by viewModel.tokenState.collectAsState()
    LaunchedEffect(tokenState) {
        viewModel.refreshTokenState()
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onModelSelected(context, it) }
    }

    // Handle file picker event
    LaunchedEffect(viewModel.filePickerEvent) {
        viewModel.filePickerEvent.collect {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Language filter state
    var filterLanguageCode by remember { mutableStateOf<String?>(null) }

    val visibleWhisperVariants = remember(filterLanguageCode) {
        filterVariants(WhisperModelManager.Variant.entries, filterLanguageCode) { it.supportedLanguageCodes }
    }
    val visibleQwen3AsrVariants = remember(filterLanguageCode) {
        filterVariants(Qwen3AsrModelManager.Variant.entries, filterLanguageCode) { it.supportedLanguageCodes }
    }
    val showGigaAm = remember(filterLanguageCode) {
        filterLanguageCode == null || filterLanguageCode in Language.GIGAAM
    }
    val showParakeet = remember(filterLanguageCode) {
        filterLanguageCode == null || filterLanguageCode in Language.PARAKEET
    }
    val visibleParakeetVariants = remember(filterLanguageCode) {
        filterVariants(ParakeetModelManager.Variant.entries, filterLanguageCode) { it.supportedLanguageCodes }
    }
    val visibleGemmaVariants = remember(filterLanguageCode) {
        filterVariants(ModelDownloader.ModelVariant.entries, filterLanguageCode) { it.supportedLanguageCodes }
    }

    // Auto-scroll when download error occurs (license requirement, auth error, etc.)
    // This scrolls to the error area so user can see what went wrong
    LaunchedEffect(downloadUiState.downloadError) {
        if (downloadUiState.downloadError != null) {
            delay(150) // Small delay to let the UI update first
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Collect one-time Snackbar events from ViewModel (Channel-based for guaranteed delivery)
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (event) {
                is ModelViewModel.SnackbarEvent.AuthRequired -> {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.model_requires_auth),
                        actionLabel = "Settings",
                        duration = SnackbarDuration.Long
                    ).let { result ->
                        if (result == SnackbarResult.ActionPerformed) {
                            onNavigateToSettings()
                        }
                    }
                }
                is ModelViewModel.SnackbarEvent.Message -> {
                    snackbarHostState.showSnackbar(
                        message = event.text,
                        duration = SnackbarDuration.Long
                    )
                }
            }
            viewModel.clearDownloadError()
        }
    }

    // Delete confirmation dialog
    if (downloadUiState.modelToDelete != null) {
        val deletingModelName = downloadUiState.modelToDelete?.displayName ?: ""
        val isActiveModel = uiState.modelName == deletingModelName
        DeleteConfirmationDialog(
            modelName = deletingModelName,
            isTranscribing = isTranscribing,
            isActiveModel = isActiveModel,
            onConfirm = { viewModel.confirmDeleteModel() },
            onDismiss = { viewModel.dismissDeleteDialog() }
        )
    }

    // Parakeet download confirmation dialog
    if (parakeetState.showDownloadDialog) {
        val variant = parakeetState.selectedVariant
        DownloadConfirmationDialog(
            title = stringResource(R.string.parakeet_download_confirm_title),
            message = stringResource(
                R.string.parakeet_download_confirm_message,
                variant?.estimatedSizeMB?.toInt() ?: 862
            ),
            onConfirm = { viewModel.confirmParakeetDownload() },
            onDismiss = { viewModel.dismissParakeetDownloadDialog() }
        )
    }

    // Parakeet delete confirmation dialog
    if (parakeetState.showDeleteDialog) {
        val variant = parakeetState.variantToDelete
        val variantDisplayName = variant?.let { stringResource(it.titleResId) } ?: stringResource(R.string.parakeet_name)
        val isParakeetActive = uiState.modelName == stringResource(R.string.parakeet_name)
        DeleteConfirmationDialog(
            modelName = variantDisplayName,
            isTranscribing = isTranscribing,
            isActiveModel = isParakeetActive,
            onConfirm = { viewModel.confirmParakeetDelete() },
            onDismiss = { viewModel.dismissParakeetDeleteDialog() }
        )
    }

    // Gemma download confirmation dialog
    if (downloadUiState.showDownloadDialog) {
        val variant = downloadUiState.selectedVariant
        DownloadConfirmationDialog(
            title = stringResource(R.string.gemma_download_confirm_title, variant?.displayName ?: "Gemma"),
            message = stringResource(R.string.gemma_download_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 0),
            onConfirm = { viewModel.confirmDownload() },
            onDismiss = { viewModel.dismissDownloadDialog() }
        )
    }

    // Gemma "update available" confirmation dialog (re-download a stale artifact, e.g. a
    // pre-2026-05-05 copy without the MTP drafter). Only reachable while the
    // MTP_SPECULATIVE_DECODING_ENABLED build flag is on (see ModelDownloadSection).
    if (downloadUiState.modelToUpdate != null) {
        val variant = downloadUiState.modelToUpdate
        DownloadConfirmationDialog(
            title = stringResource(R.string.gemma_update_confirm_title, variant?.displayName ?: "Gemma"),
            message = stringResource(R.string.gemma_update_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 0),
            confirmButtonText = stringResource(R.string.model_update_button),
            onConfirm = { viewModel.confirmUpdateModel() },
            onDismiss = { viewModel.dismissUpdateDialog() }
        )
    }

    // Whisper download confirmation dialog
    if (whisperState.showDownloadDialog) {
        val variant = whisperState.selectedVariant
        val isExtract = variant != null && whisperState.variantsNeedingExtraction.contains(variant)
        DownloadConfirmationDialog(
            title = stringResource(if (isExtract) R.string.whisper_extract_confirm_title else R.string.whisper_download_confirm_title, variant?.let { stringResource(it.titleResId) } ?: "Whisper"),
            message = stringResource(if (isExtract) R.string.whisper_extract_confirm_message else R.string.whisper_download_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 75),
            confirmButtonText = stringResource(if (isExtract) R.string.extract_model else R.string.download),
            onConfirm = { viewModel.confirmWhisperDownload() },
            onDismiss = { viewModel.dismissWhisperDownloadDialog() }
        )
    }

    // Whisper delete confirmation dialog
    if (whisperState.showDeleteDialog) {
        val variant = whisperState.variantToDelete
        val variantDisplayName = variant?.let { stringResource(it.titleResId) } ?: "Whisper"
        val isWhisperModelActive = uiState.modelName == variantDisplayName
        DeleteConfirmationDialog(
            modelName = variantDisplayName,
            isTranscribing = isTranscribing,
            isActiveModel = isWhisperModelActive,
            onConfirm = { viewModel.confirmWhisperDelete() },
            onDismiss = { viewModel.dismissWhisperDeleteDialog() }
        )
    }

    // Qwen3-ASR download confirmation dialog
    if (qwen3AsrState.showDownloadDialog) {
        val variant = qwen3AsrState.selectedVariant
        DownloadConfirmationDialog(
            title = stringResource(R.string.qwen3_asr_download_confirm_title, variant?.let { stringResource(it.titleResId) } ?: "Qwen3-ASR"),
            message = stringResource(R.string.qwen3_asr_download_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 827),
            onConfirm = { viewModel.confirmQwen3AsrDownload() },
            onDismiss = { viewModel.dismissQwen3AsrDownloadDialog() }
        )
    }

    // Qwen3-ASR delete confirmation dialog
    if (qwen3AsrState.showDeleteDialog) {
        val variant = qwen3AsrState.variantToDelete
        val variantDisplayName = variant?.let { stringResource(it.titleResId) } ?: "Qwen3-ASR"
        val isQwen3ModelActive = uiState.modelName == variantDisplayName
        DeleteConfirmationDialog(
            modelName = variantDisplayName,
            isTranscribing = isTranscribing,
            isActiveModel = isQwen3ModelActive,
            onConfirm = { viewModel.confirmQwen3AsrDelete() },
            onDismiss = { viewModel.dismissQwen3AsrDeleteDialog() }
        )
    }

    // Nemotron download confirmation dialog
    if (nemotronState.showDownloadDialog) {
        DownloadConfirmationDialog(
            title = stringResource(R.string.nemotron_download_confirm_title, stringResource(R.string.nemotron_name)),
            message = stringResource(R.string.nemotron_download_confirm_message, NemotronModelVariant.estimatedSizeMB.toInt()),
            onConfirm = { viewModel.confirmNemotronDownload() },
            onDismiss = { viewModel.dismissNemotronDownloadDialog() }
        )
    }

    // Nemotron delete confirmation dialog
    if (nemotronState.showDeleteDialog) {
        val nemotronDisplayName = stringResource(R.string.nemotron_name)
        DeleteConfirmationDialog(
            modelName = nemotronDisplayName,
            isTranscribing = isTranscribing,
            isActiveModel = uiState.modelName == nemotronDisplayName,
            onConfirm = { viewModel.confirmNemotronDelete() },
            onDismiss = { viewModel.dismissNemotronDeleteDialog() }
        )
    }

    // GigaAM download confirmation dialog
    if (gigaAmState.showDownloadDialog) {
        DownloadConfirmationDialog(
            title = stringResource(R.string.gigaam_download_confirm_title),
            message = stringResource(R.string.gigaam_download_confirm_message, GigaAmModelVariant.estimatedSizeMB.toInt()),
            onConfirm = { viewModel.confirmGigaAmDownload() },
            onDismiss = { viewModel.dismissGigaAmDownloadDialog() }
        )
    }

    // GigaAM delete confirmation dialog
    if (gigaAmState.showDeleteDialog) {
        val gigaamDisplayName = stringResource(R.string.gigaam_name)
        DeleteConfirmationDialog(
            modelName = gigaamDisplayName,
            isTranscribing = isTranscribing,
            isActiveModel = uiState.modelName == gigaamDisplayName,
            onConfirm = { viewModel.confirmGigaAmDelete() },
            onDismiss = { viewModel.dismissGigaAmDeleteDialog() }
        )
    }

    // External-model delete confirmation dialog
    externalToDelete?.let { record ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { externalToDelete = null },
            title = { Text(stringResource(R.string.external_delete_confirm, record.displayName)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    externalToDelete = null
                    viewModel.deleteExternalModel(record)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { externalToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // GGUF: disabled — download/delete dialogs commented out (GgufVariant type unavailable)
    // if (ggufState.showDownloadDialog) { ... viewModel.confirmGgufDownload() ... }
    // if (ggufState.showDeleteDialog) { ... viewModel.confirmGgufDelete() ... }

    // Unload model confirmation dialog
    if (showUnloadDialog) {
        AlertDialog(
            onDismissRequest = { showUnloadDialog = false },
            title = { Text(stringResource(R.string.dialog_unload_title)) },
            text = { Text(stringResource(R.string.dialog_unload_message, uiState.modelName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unloadModel()
                        showUnloadDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_unload))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnloadDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Model switch warning during active transcription
    if (pendingModelSwitch != null) {
        AlertDialog(
            onDismissRequest = {
                pendingModelSwitch = null
            },
            title = { Text(stringResource(R.string.dialog_transcription_active_title)) },
            text = { Text(stringResource(R.string.dialog_switch_model_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingModelSwitch?.invoke()
                        pendingModelSwitch = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_switch_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingModelSwitch = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Benchmark dialog
    val benchmarkState by viewModel.benchmarkState.collectAsState()
    val benchmarkTargetName by viewModel.benchmarkTargetName.collectAsState()
    if (benchmarkState !is BenchmarkState.Idle || benchmarkTargetName.isNotEmpty()) {
        BenchmarkDialog(
            modelName = benchmarkTargetName,
            state = benchmarkState,
            onDismiss = { viewModel.dismissBenchmark() },
            onCancel = { viewModel.cancelBenchmark() },
            onRerun = {
                viewModel.rerunBenchmark()
            }
        )
    }

    // Model info overlay
    val infoVariant = modelInfoVariant
    if (infoVariant != null) {
        ModelInfoOverlay(
            variant = infoVariant,
            info = ModelInfoProvider.getInfo(infoVariant),
            onDismiss = { modelInfoVariant = null }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Guarded model switch: warns if transcription is active
        val guardedSwitch: (() -> Unit) -> Unit = { action ->
            if (isTranscribing) {
                pendingModelSwitch = action
            } else {
                action()
            }
        }

        // Language filter
        LanguageFilterBar(
            selectedLanguageCode = filterLanguageCode,
            onLanguageSelected = { filterLanguageCode = it }
        )

        // Unload Model button — only shown when model is actually loaded in memory
        if (uiState.status == ModelViewModel.ModelStatus.READY) {
            UnloadModelButton(
                onClick = { showUnloadDialog = true },
                isTranscribing = isTranscribing
            )
        }

        // Whisper section - multilingual ASR backend (recommended)
        if (visibleWhisperVariants.isNotEmpty()) {
            WhisperDownloadSection(
                viewModel = viewModel,
                activeModelName = uiState.modelName,
                guardedModelSwitch = guardedSwitch,
                visibleVariants = visibleWhisperVariants,
                onInfoClick = { modelInfoVariant = it }
            )
        }

        // Qwen3-ASR section - state-of-the-art multilingual ASR backend
        if (visibleQwen3AsrVariants.isNotEmpty()) {
            Qwen3AsrDownloadSection(
                viewModel = viewModel,
                activeModelName = uiState.modelName,
                guardedModelSwitch = guardedSwitch,
                visibleVariants = visibleQwen3AsrVariants,
                onInfoClick = { modelInfoVariant = it }
            )
        }

        // Nemotron 3.5 section - streaming transducer ASR backend (single-variant)
        NemotronDownloadSection(
            viewModel = viewModel,
            activeModelName = uiState.modelName,
            guardedModelSwitch = guardedSwitch,
            onInfoClick = { modelInfoVariant = NemotronModelVariant }
        )

        // GigaAM v3 section - Russian ASR backend (single-variant)
        if (showGigaAm) {
            GigaAmDownloadSection(
                viewModel = viewModel,
                activeModelName = uiState.modelName,
                guardedModelSwitch = guardedSwitch,
                onInfoClick = { modelInfoVariant = GigaAmModelVariant }
            )
        }

        // GGUF section - on-device LLM text generation via llama.cpp
        // Hidden: llama-bro 1.2.3 does not yet support the Gemma 4 GGUF architecture.
        // Re-enable when llama-bro ships LLM_ARCH_GEMMA4 or we convert to .litertlm.
        // GgufDownloadSection(
        //     viewModel = viewModel,
        //     activeModelName = uiState.modelName,
        //     guardedModelSwitch = guardedSwitch,
        //     isSupported = supportsArm64(),
        //     onInfoClick = { modelInfoVariant = it }
        // )

        // Parakeet TDT section - fast multilingual ASR backend (auto-fallback: SmoothQuant → Stock int8)
        if (visibleParakeetVariants.isNotEmpty()) {
            ParakeetDownloadSection(
                viewModel = viewModel,
                activeModelName = uiState.modelName,
                guardedModelSwitch = guardedSwitch,
                visibleVariants = visibleParakeetVariants,
                onInfoClick = { modelInfoVariant = it }
            )
        }

        // Download models section - Gemma LLM models (advanced features)
        if (visibleGemmaVariants.isNotEmpty()) {
            ModelDownloadSection(
                viewModel = viewModel,
                context = context,
                onNavigateToSettings = onNavigateToSettings,
                activeModelName = uiState.modelName,
                visibleVariants = visibleGemmaVariants,
                guardedModelSwitch = guardedSwitch,
                onInfoClick = { modelInfoVariant = it }
            )
        }

        // Select Model Button - secondary option for local files.
        // SAF (OpenDocument) grants its own URI access, so no storage permission
        // Advanced section: manual model imports, collapsed by default to hide
        // complexity from users who just want the curated backends above.
        var advancedExpanded by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp)
            ) {
                Icon(
                    if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.model_advanced_section))
                    Text(
                        stringResource(R.string.model_advanced_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            if (advancedExpanded) {
                // Semantic grouping: LiteRT-LM (Gemma) and ONNX (sherpa) are
                // distinct ecosystems, each with its own import flow.
                Text(
                    stringResource(R.string.external_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )

                // LiteRT-LM (Gemma): same double-container structure as ONNX Sherpa.
                // SAF (OpenDocument) grants its own URI access, no storage permission
                // needed (TASK-301).
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("LiteRT-LM", style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.openFilePicker() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.select_model_from_device))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ONNX (sherpa): the section Card carries the header; no
                // separate label needed here.
                ExternalModelsSection(
                    viewModel = viewModel,
                    activeBackendId = activeBackendId,
                    folderPicker = { externalFolderPicker.launch(null) },
                    selection = externalImport,
                    onSelectionChange = { externalImport = it },
                    onDeleteRequest = { externalToDelete = it }
                )
            }
        }

        // Extra spacer to ensure downloading card can be fully scrolled into view
        Spacer(modifier = Modifier.height(200.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) { snackbarData ->
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f)
                ) + fadeIn(animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(
                        text = snackbarData.visuals.message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// ==================== Model Download Section ====================

/**
 * Gemma LiteRT-LM download section using the shared [ModelVariantCard].
 */
@Composable
private fun ModelDownloadSection(
    viewModel: ModelViewModel,
    context: android.content.Context,
    onNavigateToSettings: () -> Unit,
    activeModelName: String,
    visibleVariants: List<ModelDownloader.ModelVariant> = ModelDownloader.ModelVariant.entries,
    guardedModelSwitch: (() -> Unit) -> Unit = {},
    onInfoClick: (ModelVariant) -> Unit = {}
) {
    val downloadState by viewModel.downloadUiState.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.download_models),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.gemma_advanced_features_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant cards
            visibleVariants.forEachIndexed { index, variant ->
                val variantState = downloadState.variantDownloadStates[variant]
                ModelVariantCard(
                    state = ModelVariantCardState(
                        variant = variant,
                        isActive = activeModelName == stringResource(variant.titleResId),
                        downloadProgress = variantState?.downloadProgress ?: 0f,
                        downloadState = variantState?.downloadState ?: DownloadState.Idle,
                        errorMessage = variantState?.errorMessage,
                        partialDownload = variantState?.partialDownload,
                        buttonState = when {
                            variantState?.isDownloading == true -> DownloadButtonState.Downloading
                            downloadState.staleModels.contains(variant) -> DownloadButtonState.UpdateAvailable
                            downloadState.downloadedModels.contains(variant) -> DownloadButtonState.Downloaded
                            variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                            else -> DownloadButtonState.Idle
                        }
                    ),
                    downloadButtonTextResId = R.string.download,
                    onDownloadClick = { viewModel.showDownloadDialog(variant) },
                    onCancelClick = { viewModel.cancelDownload(variant) },
                    onResumeClick = { viewModel.resumeDownload(variant) },
                    onClearPartialClick = { viewModel.clearPartialDownload(variant) },
                    onUseClick = { guardedModelSwitch { viewModel.useDownloadedModel(variant) } },
                    onUpdateClick = { viewModel.showUpdateDialog(variant) },
                    onDeleteClick = { viewModel.showDeleteDialog(variant) },
                    onInfoClick = { onInfoClick(variant) }
                )

                // Auth warning for gated models when no token is configured
                if (variant.requiresAuth && !downloadState.hasToken) {
                    val tokenWarning = stringResource(R.string.requires_huggingface_token)
                    val addSettings = stringResource(R.string.add_in_settings)
                    val errorColor = MaterialTheme.colorScheme.error
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val authWarningText = remember(tokenWarning, addSettings, errorColor, primaryColor) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = errorColor)) {
                                append(tokenWarning + " ")
                            }
                            withStyle(SpanStyle(
                                color = primaryColor,
                                textDecoration = TextDecoration.Underline
                            )) {
                                append(addSettings)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    ClickableText(
                        text = authWarningText,
                        style = MaterialTheme.typography.labelSmall,
                        onClick = { onNavigateToSettings() }
                    )
                }

                if (index < visibleVariants.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

// ==================== Qwen3-ASR Download Section ====================

/**
 * Section for downloading Qwen3-ASR model (sherpa-onnx backend).
 * Supports multiple variants with download/delete/use functionality.
 */
@Composable
private fun Qwen3AsrDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    guardedModelSwitch: (() -> Unit) -> Unit = {},
    visibleVariants: List<Qwen3AsrModelManager.Variant> = Qwen3AsrModelManager.Variant.entries,
    onInfoClick: (ModelVariant) -> Unit = {}
) {
    val context = LocalContext.current
    val qwen3AsrState by viewModel.qwen3AsrState.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                qwen3AsrState.isAnyDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            qwen3AsrState.downloadedVariants.isNotEmpty() -> Icons.Default.CheckCircle
                            qwen3AsrState.isAnyDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.Translate
                        },
                        contentDescription = null,
                        tint = when {
                            qwen3AsrState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            qwen3AsrState.isAnyDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.qwen3_asr_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.qwen3_asr_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant cards
            visibleVariants.forEach { variant ->
                val variantState = qwen3AsrState.variantDownloadStates[variant]
                ModelVariantCard(
                    state = ModelVariantCardState(
                        variant = variant,
                        isActive = activeModelName == stringResource(variant.titleResId),
                        downloadProgress = variantState?.downloadProgress ?: 0f,
                        downloadState = variantState?.downloadState ?: DownloadState.Idle,
                        errorMessage = variantState?.errorMessage,
                        partialDownload = variantState?.partialDownload,
                        buttonState = when {
                            variantState?.isDownloading == true -> DownloadButtonState.Downloading
                            qwen3AsrState.downloadedVariants.contains(variant) -> DownloadButtonState.Downloaded
                            variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                            else -> DownloadButtonState.Idle
                        }
                    ),
                    downloadButtonTextResId = R.string.download,
                    onDownloadClick = { viewModel.showQwen3AsrDownloadDialog(variant) },
                    onCancelClick = { viewModel.cancelQwen3AsrDownload(variant) },
                    onResumeClick = { viewModel.resumeQwen3AsrDownload(variant) },
                    onClearPartialClick = { viewModel.clearQwen3AsrPartialDownload(variant) },
                    onUseClick = { guardedModelSwitch { viewModel.useQwen3AsrModel(variant) } },
                    onDeleteClick = { viewModel.showQwen3AsrDeleteDialog(variant) },
                    onBenchmarkClick = {
                        val path = Qwen3AsrDownloader.getModelPath(context, variant)
                        if (path != null) {
                            viewModel.startBenchmark(
                                Qwen3AsrBackend.BACKEND_ID,
                                path,
                                context.getString(variant.titleResId)
                            )
                        }
                    },
                    onInfoClick = { onInfoClick(variant) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}


// ==================== Nemotron Download Section ====================

/**
 * Section for downloading the Nemotron 3.5 streaming model (sherpa-onnx Online backend).
 * Single-variant — renders one [ModelVariantCard] bound to [NemotronModelVariant].
 */
@Composable
private fun NemotronDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    guardedModelSwitch: (() -> Unit) -> Unit = {},
    onInfoClick: (ModelVariant) -> Unit = {}
) {
    val context = LocalContext.current
    val nemotronState by viewModel.nemotronState.collectAsState()
    val variant = NemotronModelVariant
    val isDownloaded = nemotronState.modelPath != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                nemotronState.isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            isDownloaded -> Icons.Default.CheckCircle
                            nemotronState.isDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.GraphicEq
                        },
                        contentDescription = null,
                        tint = when {
                            isDownloaded -> MaterialTheme.colorScheme.primary
                            nemotronState.isDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.nemotron_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.nemotron_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            ModelVariantCard(
                state = ModelVariantCardState(
                    variant = variant,
                    isActive = activeModelName == stringResource(R.string.nemotron_name),
                    downloadProgress = nemotronState.downloadProgress,
                    downloadState = nemotronState.downloadState,
                    errorMessage = nemotronState.errorMessage,
                    partialDownload = nemotronState.partialDownload,
                    buttonState = when {
                        nemotronState.isDownloading -> DownloadButtonState.Downloading
                        isDownloaded -> DownloadButtonState.Downloaded
                        nemotronState.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                        else -> DownloadButtonState.Idle
                    }
                ),
                downloadButtonTextResId = R.string.nemotron_download,
                onDownloadClick = { viewModel.showNemotronDownloadDialog() },
                onCancelClick = { viewModel.cancelNemotronDownload() },
                onResumeClick = { viewModel.resumeNemotronDownload() },
                onClearPartialClick = { viewModel.clearNemotronPartialDownload() },
                onUseClick = { guardedModelSwitch { viewModel.useNemotronModel() } },
                onDeleteClick = { viewModel.showNemotronDeleteDialog() },
                onBenchmarkClick = {
                    val path = NemotronDownloader.getModelPath(context)
                    if (path != null) {
                        viewModel.startBenchmark(
                            NemotronStreamingBackend.BACKEND_ID,
                            path,
                            context.getString(R.string.nemotron_name)
                        )
                    }
                },
                onInfoClick = { onInfoClick(variant) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}


// ==================== GigaAM Download Section ====================

// ==================== External models section (v2a) ====================

/**
 * Family selection + conditional options for external-model imports. One immutable
 * holder so both import paths (folder and URL) share a single selection state.
 */
internal data class ExternalImportUiState(
    val family: com.antivocale.app.data.ModelFamily = com.antivocale.app.data.ModelFamily.TRANSDUCER,
    val ctcModelType: String = "nemo_ctc",
    val languages: String = "",
    val whisperLanguage: String = "",
    val sensevoiceLanguage: String = "",
    val sensevoiceItn: Boolean = false,
) {
    /** Family-specific importer options; blank optional fields are omitted. */
    fun options(): Map<String, String> = when (family) {
        com.antivocale.app.data.ModelFamily.WHISPER ->
            if (whisperLanguage.isBlank()) emptyMap()
            else mapOf("whisper.language" to whisperLanguage.trim())
        com.antivocale.app.data.ModelFamily.SENSE_VOICE -> buildMap {
            if (sensevoiceLanguage.isNotBlank()) put("sensevoice.language", sensevoiceLanguage.trim())
            put("sensevoice.itn", sensevoiceItn.toString())
        }
        else -> emptyMap()
    }

    /** Comma/space separated language codes for the importer's languages parameter. */
    fun languageCodes(): List<String> =
        languages.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Imported external models: one card per record plus the two import actions and the
 * shared family selector with its conditional options panel. The two standing notices
 * (single-pass risk, wrong-family crash) ride along every card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalModelsSection(
    viewModel: ModelViewModel,
    activeBackendId: String,
    folderPicker: () -> Unit,
    selection: ExternalImportUiState,
    onSelectionChange: (ExternalImportUiState) -> Unit,
    onDeleteRequest: (com.antivocale.app.data.ExternalModelRecord) -> Unit,
) {
    val records by viewModel.externalModels.collectAsState()
    val importState by viewModel.externalImportState.collectAsState()
    val context = LocalContext.current
    var urlDialogOpen by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var ctcExpanded by remember { mutableStateOf(false) }

    // Bundled catalog index for the URL-dialog autocomplete (search by name or
    // language). A read failure degrades to no suggestions, never a crash.
    val catalogEntries = remember {
        runCatching {
            context.assets.open("external-catalog/index.json").use {
                it.readBytes().decodeToString()
            }.let(com.antivocale.app.data.ExternalCatalog::parseIndex)
        }.getOrDefault(emptyList())
    }

    val familyOptions = remember {
        listOf(
            com.antivocale.app.data.ModelFamily.TRANSDUCER to R.string.external_family_transducer,
            com.antivocale.app.data.ModelFamily.WHISPER to R.string.external_family_whisper,
            com.antivocale.app.data.ModelFamily.CTC to R.string.external_family_ctc,
            com.antivocale.app.data.ModelFamily.SENSE_VOICE to R.string.external_family_sense_voice,
        )
    }

    // Outer section Card matching the curated sections (GigaAM, Nemotron):
    // surfaceVariant background, header with icon + title + description, 16dp padding.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header (same shape as GigaAmDownloadSection's header)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("ONNX Sherpa", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Family selector visible for BOTH import paths (folder and URL). Each
            // entry carries a help line listing the expected files, mirroring the
            // label + description pattern of the advanced-section button.
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it },
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                OutlinedTextField(
                    value = stringResource(familyOptions.first { it.first == selection.family }.second),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.external_family)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                    familyOptions.forEach { (value, labelRes) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(labelRes))
                                    Text(
                                        stringResource(
                                            when (value) {
                                                com.antivocale.app.data.ModelFamily.TRANSDUCER -> R.string.external_family_transducer_help
                                                com.antivocale.app.data.ModelFamily.WHISPER -> R.string.external_family_whisper_help
                                                com.antivocale.app.data.ModelFamily.CTC -> R.string.external_family_ctc_help
                                                com.antivocale.app.data.ModelFamily.SENSE_VOICE -> R.string.external_family_sense_voice_help
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onSelectionChange(selection.copy(family = value))
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Conditional options panel below the selector.
            when (selection.family) {
                com.antivocale.app.data.ModelFamily.WHISPER -> OutlinedTextField(
                    value = selection.whisperLanguage,
                    onValueChange = { onSelectionChange(selection.copy(whisperLanguage = it)) },
                    label = { Text(stringResource(R.string.external_option_language)) },
                    placeholder = { Text(stringResource(R.string.external_option_language_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                com.antivocale.app.data.ModelFamily.SENSE_VOICE -> {
                    OutlinedTextField(
                        value = selection.sensevoiceLanguage,
                        onValueChange = { onSelectionChange(selection.copy(sensevoiceLanguage = it)) },
                        label = { Text(stringResource(R.string.external_option_language)) },
                        placeholder = { Text(stringResource(R.string.external_option_language_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.external_option_itn),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = selection.sensevoiceItn,
                            onCheckedChange = { onSelectionChange(selection.copy(sensevoiceItn = it)) }
                        )
                    }
                }
                com.antivocale.app.data.ModelFamily.CTC -> ExposedDropdownMenuBox(
                    expanded = ctcExpanded,
                    onExpandedChange = { ctcExpanded = it },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    OutlinedTextField(
                        value = if (selection.ctcModelType == "zipformer_ctc")
                            stringResource(R.string.external_ctc_subtype_zipformer)
                        else stringResource(R.string.external_ctc_subtype_nemo),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.external_ctc_subtype)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ctcExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = ctcExpanded, onDismissRequest = { ctcExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.external_ctc_subtype_nemo)) },
                            onClick = {
                                onSelectionChange(selection.copy(ctcModelType = "nemo_ctc"))
                                ctcExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.external_ctc_subtype_zipformer)) },
                            onClick = {
                                onSelectionChange(selection.copy(ctcModelType = "zipformer_ctc"))
                                ctcExpanded = false
                            }
                        )
                    }
                }
                else -> {}
            }

            // Languages field for ALL families: stored on the record and used as the
            // Whisper default language when no explicit option is set.
            OutlinedTextField(
                value = selection.languages,
                onValueChange = { onSelectionChange(selection.copy(languages = it)) },
                label = { Text(stringResource(R.string.external_languages)) },
                placeholder = { Text(stringResource(R.string.external_languages_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = folderPicker,
                enabled = importState !is ModelViewModel.ExternalImportState.Importing,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.external_import_folder))
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { urlDialogOpen = true },
                enabled = importState !is ModelViewModel.ExternalImportState.Importing,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.external_import_url)) }
        }

        when (val st = importState) {
            is ModelViewModel.ExternalImportState.Importing -> Text(
                stringResource(R.string.external_importing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            is ModelViewModel.ExternalImportState.Error -> Text(
                stringResource(R.string.external_import_failed, st.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            else -> {}
        }

        // Gap between the import buttons/state and the first card
        if (records.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))

        records.forEachIndexed { index, record ->
            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
            ExternalModelCard(
                record = record,
                isActive = activeBackendId == record.backendId,
                onUse = { viewModel.useExternalModel(record) },
                onDelete = { onDeleteRequest(record) },
            )
        }

        // Standing notices rendered once at the section level, not per card.
        if (records.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Info, contentDescription = null,
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.external_notice_single_pass),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null,
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.external_notice_family),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }

    if (urlDialogOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { urlDialogOpen = false },
            title = { Text(stringResource(R.string.external_url_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text(stringResource(R.string.external_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Catalog autocomplete: suggestions filtered by the typed text
                    // over name + languages, filling URL and family on tap. Hidden
                    // once the text looks like a URL being typed or pasted.
                    if (!urlText.startsWith("http")) {
                        com.antivocale.app.data.ExternalCatalog
                            .filter(catalogEntries, urlText)
                            .forEach { entry ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            urlText = entry.entryUrl
                                            onSelectionChange(selection.copy(family = entry.family))
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        entry.languages.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                    }
                    // Architecture selector is in the section above (visible for both
                    // import paths), not duplicated here.
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        urlDialogOpen = false
                        if (urlText.isNotBlank()) {
                            viewModel.importExternalFromUrl(
                                urlText.trim(), selection.family,
                                ctcModelType = selection.ctcModelType,
                                options = selection.options(),
                                languages = selection.languageCodes(),
                            )
                        }
                    }
                ) { Text(stringResource(R.string.external_import)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { urlDialogOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ExternalModelCard(
    record: com.antivocale.app.data.ExternalModelRecord,
    isActive: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    // Same container, padding, and button-row pattern as ModelVariantCard:
    // surface color, 12dp inner padding, buttons aligned End with 8dp spacing.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(record.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            record.typeLabel +
                                " · " + com.antivocale.app.util.formatFileSize(record.sizeBytes) +
                                if (record.languages.isEmpty()) "" else " · " + record.languages.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isActive) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Action buttons: same arrangement as ModelVariantCard
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (!isActive) {
                    Button(onClick = onUse) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.use_model))
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Section for downloading the GigaAM v3 model (sherpa-onnx nemo_transducer backend).
 * Single-variant: renders one [ModelVariantCard] bound to [GigaAmModelVariant].
 */
@Composable
private fun GigaAmDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    guardedModelSwitch: (() -> Unit) -> Unit = {},
    onInfoClick: (ModelVariant) -> Unit = {}
) {
    val context = LocalContext.current
    val gigaAmState by viewModel.gigaAmState.collectAsState()
    val variant = GigaAmModelVariant
    val isDownloaded = gigaAmState.modelPath != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                gigaAmState.isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            isDownloaded -> Icons.Default.CheckCircle
                            gigaAmState.isDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.GraphicEq
                        },
                        contentDescription = null,
                        tint = when {
                            isDownloaded -> MaterialTheme.colorScheme.primary
                            gigaAmState.isDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.gigaam_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.gigaam_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            ModelVariantCard(
                state = ModelVariantCardState(
                    variant = variant,
                    isActive = activeModelName == stringResource(R.string.gigaam_name),
                    downloadProgress = gigaAmState.downloadProgress,
                    downloadState = gigaAmState.downloadState,
                    errorMessage = gigaAmState.errorMessage,
                    partialDownload = gigaAmState.partialDownload,
                    buttonState = when {
                        gigaAmState.isDownloading -> DownloadButtonState.Downloading
                        isDownloaded -> DownloadButtonState.Downloaded
                        gigaAmState.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                        else -> DownloadButtonState.Idle
                    }
                ),
                downloadButtonTextResId = R.string.gigaam_download,
                onDownloadClick = { viewModel.showGigaAmDownloadDialog() },
                onCancelClick = { viewModel.cancelGigaAmDownload() },
                onResumeClick = { viewModel.resumeGigaAmDownload() },
                onClearPartialClick = { viewModel.clearGigaAmPartialDownload() },
                onUseClick = { guardedModelSwitch { viewModel.useGigaAmModel() } },
                onDeleteClick = { viewModel.showGigaAmDeleteDialog() },
                onBenchmarkClick = {
                    val path = GigaAmDownloader.getModelPath(context)
                    if (path != null) {
                        viewModel.startBenchmark(
                            GigaAmBackend.BACKEND_ID,
                            path,
                            context.getString(R.string.gigaam_name)
                        )
                    }
                },
                onInfoClick = { onInfoClick(variant) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}


// ==================== GGUF Download Section ====================
/* GGUF: disabled — uncomment when re-enabling GGUF
// @Composable
// private fun GgufDownloadSection(
//     viewModel: ModelViewModel,
//     activeModelName: String,
//     guardedModelSwitch: (() -> Unit) -> Unit = {},
//     isSupported: Boolean = true,
//     onInfoClick: (ModelVariant) -> Unit = {}
// ) {
//     val context = LocalContext.current
//     val ggufState by viewModel.ggufState.collectAsState()
// 
//     Card(
//         modifier = Modifier.fillMaxWidth(),
//         colors = CardDefaults.cardColors(
//             containerColor = when {
//                 !isSupported -> MaterialTheme.colorScheme.errorContainer
//                 ggufState.isAnyDownloading -> MaterialTheme.colorScheme.secondaryContainer
//                 else -> MaterialTheme.colorScheme.surfaceVariant
//             }
//         )
//     ) {
//         Column(modifier = Modifier.padding(16.dp)) {
//             // Header
//             Row(
//                 modifier = Modifier.fillMaxWidth(),
//                 horizontalArrangement = Arrangement.SpaceBetween,
//                 verticalAlignment = Alignment.CenterVertically
//             ) {
//                 Row(verticalAlignment = Alignment.CenterVertically) {
//                     Icon(
//                         imageVector = when {
//                             !isSupported -> Icons.Default.Warning
//                             ggufState.downloadedVariants.isNotEmpty() -> Icons.Default.CheckCircle
//                             ggufState.isAnyDownloading -> Icons.Default.CloudDownload
//                             else -> Icons.Default.SmartToy
//                         },
//                         contentDescription = null,
//                         tint = when {
//                             !isSupported -> MaterialTheme.colorScheme.error
//                             ggufState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
//                             ggufState.isAnyDownloading -> MaterialTheme.colorScheme.secondary
//                             else -> MaterialTheme.colorScheme.onSurfaceVariant
//                         },
//                         modifier = Modifier.size(24.dp)
//                     )
//                     Spacer(modifier = Modifier.width(12.dp))
//                     Column {
//                         Text(
//                             text = stringResource(R.string.gguf_title),
//                             style = MaterialTheme.typography.titleMedium
//                         )
//                         Text(
//                             text = stringResource(R.string.gguf_description),
//                             style = MaterialTheme.typography.bodySmall,
//                             color = MaterialTheme.colorScheme.onSurfaceVariant
//                         )
//                     }
//                 }
//             }
// 
//             if (!isSupported) {
//                 Spacer(modifier = Modifier.height(8.dp))
//                 Text(
//                     text = stringResource(R.string.gguf_unsupported_arch),
//                     style = MaterialTheme.typography.bodySmall,
//                     color = MaterialTheme.colorScheme.error
//                 )
//                 return@Card
//             }
// 
//             Spacer(modifier = Modifier.height(8.dp))
// 
//             // Model variant cards
//             Gemma4GgufModelManager.GgufVariant.entries.forEach { variant ->
//                 val variantState = ggufState.variantDownloadStates[variant]
//                 ModelVariantCard(
//                     state = ModelVariantCardState(
//                         variant = variant,
//                         isActive = activeModelName == stringResource(variant.titleResId),
//                         downloadProgress = variantState?.downloadProgress ?: 0f,
//                         downloadState = variantState?.downloadState ?: DownloadState.Idle,
//                         errorMessage = variantState?.errorMessage,
//                         partialDownload = variantState?.partialDownload,
//                         buttonState = when {
//                             variantState?.isDownloading == true -> DownloadButtonState.Downloading
//                             ggufState.downloadedVariants.contains(variant) -> DownloadButtonState.Downloaded
//                             variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
//                             else -> DownloadButtonState.Idle
//                         }
//                     ),
//                     downloadButtonTextResId = R.string.download,
//                     onDownloadClick = { viewModel.showGgufDownloadDialog(variant) },
//                     onCancelClick = { viewModel.cancelGgufDownload(variant) },
//                     onResumeClick = { viewModel.resumeGgufDownload(variant) },
//                     onClearPartialClick = { viewModel.clearGgufPartialDownload(variant) },
//                     onUseClick = { guardedModelSwitch { viewModel.useGgufModel(variant) } },
//                     onDeleteClick = { viewModel.showGgufDeleteDialog(variant) },
//                     onBenchmarkClick = {
//                         val path = Gemma4GgufModelManager.getLocalPath(context, variant)
//                         if (path != null) {
//                             viewModel.startBenchmark(
//                                 Gemma4GgufBackend.BACKEND_ID,
//                                 path,
//                                 context.getString(variant.titleResId)
//                             )
//                         }
//                     },
//                     onInfoClick = { onInfoClick(variant) }
//                 )
//                 Spacer(modifier = Modifier.height(8.dp))
//             }
//         }
//     }
// }
*/


// ==================== Parakeet Download Section ====================

/**
 * Section for downloading Parakeet TDT models (sherpa-onnx backend).
 *
 * Two variants: SmoothQuant (recommended) and Stock int8 (lighter fallback).
 * Both are independently downloadable. The active model is auto-resolved at
 * transcription time (prefer SmoothQuant → Stock int8); there is no user-facing
 * variant selector. Mirrors [Qwen3AsrDownloadSection].
 */
@Composable
private fun ParakeetDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    guardedModelSwitch: (() -> Unit) -> Unit = {},
    visibleVariants: List<ParakeetModelManager.Variant> = ParakeetModelManager.Variant.entries,
    onInfoClick: (ModelVariant) -> Unit = {}
) {
    val context = LocalContext.current
    val parakeetState by viewModel.parakeetState.collectAsState()
    val parakeetName = stringResource(R.string.parakeet_name)
    val savedParakeetPath by viewModel.savedParakeetPath.collectAsState()
    val isParakeetBackendActive = activeModelName == parakeetName
    // Use the SAVED path (preference), not parakeetState.modelPath (which can lag
    // behind the actual active model on startup or after a preference change).
    val activeParakeetPath = savedParakeetPath ?: parakeetState.modelPath

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                parakeetState.isAnyDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            parakeetState.downloadedVariants.isNotEmpty() -> Icons.Default.CheckCircle
                            parakeetState.isAnyDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.GraphicEq
                        },
                        contentDescription = null,
                        tint = when {
                            parakeetState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            parakeetState.isAnyDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.parakeet_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.parakeet_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                InfoIconButton(onClick = { onInfoClick(ParakeetVariant) })
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant cards
            visibleVariants.forEach { variant ->
                val variantState = parakeetState.variantDownloadStates[variant]
                ModelVariantCard(
                    state = ModelVariantCardState(
                        variant = variant,
                        // The whole Parakeet backend resolves to one active model; mark the
                        // card active only if Parakeet is the active backend AND this variant's
                        // directory is the auto-resolved one.
                        isActive = isParakeetBackendActive && activeParakeetPath?.endsWith(variant.dirName) == true,
                        downloadProgress = variantState?.downloadProgress ?: 0f,
                        downloadState = variantState?.downloadState ?: DownloadState.Idle,
                        errorMessage = variantState?.errorMessage,
                        partialDownload = variantState?.partialDownload,
                        buttonState = when {
                            variantState?.isDownloading == true -> DownloadButtonState.Downloading
                            parakeetState.downloadedVariants.contains(variant) -> DownloadButtonState.Downloaded
                            variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                            else -> DownloadButtonState.Idle
                        }
                    ),
                    downloadButtonTextResId = R.string.download,
                    onDownloadClick = { viewModel.showParakeetDownloadDialog(variant) },
                    onCancelClick = { viewModel.cancelParakeetDownload(variant) },
                    onResumeClick = { viewModel.resumeParakeetDownload(variant) },
                    onClearPartialClick = { viewModel.clearParakeetPartialDownload(variant) },
                    onUseClick = { guardedModelSwitch { viewModel.useParakeetModel(variant) } },
                    onDeleteClick = { viewModel.showParakeetDeleteDialog(variant) },
                    onBenchmarkClick = {
                        val path = ParakeetDownloader.getModelPath(context, variant)
                        if (path != null) {
                            viewModel.startBenchmark(
                                SherpaOnnxBackend.BACKEND_ID,
                                path,
                                context.getString(variant.titleResId)
                            )
                        }
                    },
                    onInfoClick = { onInfoClick(variant) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ==================== Whisper Download Section ====================

/**
 * Section for downloading Whisper models (sherpa-onnx backend).
 * No authentication required - downloads from GitHub releases.
 * Excellent multilingual support with proper punctuation.
 */
@Composable
private fun WhisperDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    guardedModelSwitch: (() -> Unit) -> Unit = {},
    visibleVariants: List<WhisperModelManager.Variant> = WhisperModelManager.Variant.entries,
    onInfoClick: (ModelVariant) -> Unit = {}
) {
    val context = LocalContext.current
    val whisperState by viewModel.whisperState.collectAsState()
    var showSpeedComparison by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                whisperState.isAnyDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            whisperState.downloadedVariants.isNotEmpty() -> Icons.Default.CheckCircle
                            whisperState.isAnyDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.Translate
                        },
                        contentDescription = null,
                        tint = when {
                            whisperState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            whisperState.isAnyDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.whisper_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.whisper_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { showSpeedComparison = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.speed_comparison_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant selection
            visibleVariants.forEach { variant ->
                val needsExtraction = whisperState.variantsNeedingExtraction.contains(variant)
                val isOrphaned = whisperState.orphanedVariants.contains(variant)
                val variantState = whisperState.variantDownloadStates[variant]
                ModelVariantCard(
                    state = ModelVariantCardState(
                        variant = variant,
                        isActive = activeModelName == stringResource(variant.titleResId),
                        downloadProgress = variantState?.downloadProgress ?: 0f,
                        downloadState = variantState?.downloadState ?: DownloadState.Idle,
                        errorMessage = variantState?.errorMessage,
                        partialDownload = variantState?.partialDownload,
                        buttonState = when {
                            variantState?.isDownloading == true -> DownloadButtonState.Downloading
                            whisperState.downloadedVariants.contains(variant) -> DownloadButtonState.Downloaded
                            isOrphaned && needsExtraction -> DownloadButtonState.Orphaned
                            needsExtraction -> DownloadButtonState.NeedsExtraction
                            variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                            else -> DownloadButtonState.Idle
                        }
                    ),
                    downloadButtonTextResId = R.string.download,
                    extraBadges = {
                        if (variant == WhisperModelManager.Variant.DISTIL_LARGE_V3) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondary,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = stringResource(R.string.best_italian_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    },
                    cancelTextExtractor = { downloadState ->
                        if (downloadState is DownloadState.Extracting)
                            stringResource(R.string.cancel_extract)
                        else
                            stringResource(R.string.cancel_download)
                    },
                    onDownloadClick = { viewModel.showWhisperDownloadDialog(variant) },
                    onCancelClick = { viewModel.cancelWhisperDownload(variant) },
                    onResumeClick = { viewModel.resumeWhisperDownload(variant) },
                    onClearPartialClick = { viewModel.clearWhisperPartialDownload(variant) },
                    onExtraActionClick = { viewModel.clearOrphanedWhisperFiles(variant) },
                    onUseClick = { guardedModelSwitch { viewModel.useWhisperModel(variant) } },
                    onDeleteClick = { viewModel.showWhisperDeleteDialog(variant) },
                    onBenchmarkClick = {
                        val path = WhisperDownloader.getModelPath(context, variant)
                        if (path != null) {
                            viewModel.startBenchmark(
                                WhisperBackend.BACKEND_ID,
                                path,
                                context.getString(variant.titleResId)
                            )
                        }
                    },
                    onInfoClick = { onInfoClick(variant) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // Speed comparison dialog
    if (showSpeedComparison) {
        AlertDialog(
            onDismissRequest = { showSpeedComparison = false },
            title = {
                Text(
                    stringResource(R.string.speed_comparison_title),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.speed_comparison_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Comparison table
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // Header row
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.speed_comparison_header_model),
                                    modifier = Modifier.weight(2f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.speed_comparison_header_size),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.speed_comparison_header_speed),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.speed_comparison_header_quality),
                                    modifier = Modifier.weight(1.5f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Whisper Turbo (best Whisper speed/quality balance)
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_turbo_name),
                                size = stringResource(R.string.speed_comparison_turbo_size),
                                speed = stringResource(R.string.speed_comparison_turbo_speed),
                                quality = stringResource(R.string.speed_comparison_turbo_quality)
                            )
                            // Whisper Medium
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_medium_name),
                                size = stringResource(R.string.speed_comparison_medium_size),
                                speed = stringResource(R.string.speed_comparison_medium_speed),
                                quality = stringResource(R.string.speed_comparison_medium_quality)
                            )
                            // Whisper Small
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_small_name),
                                size = stringResource(R.string.speed_comparison_small_size),
                                speed = stringResource(R.string.speed_comparison_small_speed),
                                quality = stringResource(R.string.speed_comparison_small_quality)
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            // Distil Italian (best Italian quality)
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_distil_it_name),
                                size = stringResource(R.string.speed_comparison_distil_it_size),
                                speed = stringResource(R.string.speed_comparison_distil_it_speed),
                                quality = stringResource(R.string.speed_comparison_distil_it_quality),
                                badge = stringResource(R.string.speed_comparison_distil_it_note),
                                muted = false
                            )
                            // Parakeet TDT (recommended)
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_parakeet_name),
                                size = stringResource(R.string.speed_comparison_parakeet_size),
                                speed = stringResource(R.string.speed_comparison_parakeet_speed),
                                quality = stringResource(R.string.speed_comparison_parakeet_quality),
                                badge = stringResource(R.string.speed_comparison_parakeet_note),
                                muted = false
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            // Qwen3-ASR (broadest language coverage)
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_qwen3_name),
                                size = stringResource(R.string.speed_comparison_qwen3_size),
                                speed = stringResource(R.string.speed_comparison_qwen3_speed),
                                quality = stringResource(R.string.speed_comparison_qwen3_quality)
                            )
                            // GigaAM v3 (Russian)
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_gigaam_name),
                                size = stringResource(R.string.speed_comparison_gigaam_size),
                                speed = stringResource(R.string.speed_comparison_gigaam_speed),
                                quality = stringResource(R.string.speed_comparison_gigaam_quality)
                            )
                            // Nemotron (streaming, experimental)
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_nemotron_name),
                                size = stringResource(R.string.speed_comparison_nemotron_size),
                                speed = stringResource(R.string.speed_comparison_nemotron_speed),
                                quality = stringResource(R.string.speed_comparison_nemotron_quality)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedComparison = false }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }
}

@Composable
private fun ComparisonRow(
    name: String,
    size: String,
    speed: String,
    quality: String,
    badge: String? = null,
    muted: Boolean = false
) {
    val textColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(name, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = textColor)
        Text(size, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(speed, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (badge != null) {
            Column(modifier = Modifier.weight(1.5f)) {
                Text(quality, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Text(quality, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

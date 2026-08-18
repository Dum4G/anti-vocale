package com.antivocale.app.ui.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogStringKeys
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.CatalogVariantUi
import com.antivocale.app.transcription.ModelInfoProvider
import com.antivocale.app.transcription.ModelVariant
import com.antivocale.app.transcription.SherpaModelDownloader
import com.antivocale.app.ui.components.DownloadButtonState
import com.antivocale.app.ui.components.InfoIconButton
import com.antivocale.app.ui.components.LanguageFilterBar
import com.antivocale.app.ui.components.ModelVariantCard
import com.antivocale.app.ui.components.ModelVariantCardState
import com.antivocale.app.ui.components.UnloadModelButton
import com.antivocale.app.ui.components.BenchmarkDialog
import com.antivocale.app.ui.components.DeleteConfirmationDialog
import com.antivocale.app.ui.components.DownloadConfirmationDialog
import com.antivocale.app.ui.components.ModelInfoOverlay
import com.antivocale.app.benchmark.BenchmarkState
import com.antivocale.app.ui.viewmodel.ModelViewModel

private fun <T> filterVariants(
    entries: List<T>,
    languageCode: String?,
    getCodes: (T) -> Set<String>
): List<T> {
    if (languageCode == null) return entries
    return entries.filter { languageCode in getCodes(it) }
}

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
    val catalogStates by viewModel.catalogStates.collectAsState()

    // Transcription active state — used to warn about destructive operations
    val isTranscribing by InferenceService.isTranscribing.collectAsState()
    var pendingModelSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showUnloadDialog by remember { mutableStateOf(false) }

    var modelInfoVariant by remember { mutableStateOf<ModelVariant?>(null) }
    var externalToDelete by remember { mutableStateOf<com.antivocale.app.data.ExternalModelRecord?>(null) }

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

        // Catalog-driven model sections (Parakeet, Whisper, Qwen3-ASR, Nemotron, GigaAM).
        // One generic section per catalog entry — all model-specific behavior lives in the
        // catalog, never in hard-coded per-model UI.
        viewModel.catalogEntries.forEach { entry ->
            val visibleVariants = remember(entry.id, filterLanguageCode) {
                filterVariants(CatalogVariantUi.forEntry(entry.id), filterLanguageCode) { it.supportedLanguageCodes }
            }
            if (visibleVariants.isNotEmpty()) {
                CatalogModelSection(
                    viewModel = viewModel,
                    entry = entry,
                    state = catalogStates[entry.id] ?: ModelViewModel.ModelEntryUiState(),
                    activeBackendId = activeBackendId,
                    isTranscribing = isTranscribing,
                    visibleVariants = visibleVariants,
                    guardedModelSwitch = guardedSwitch,
                    onInfoClick = { modelInfoVariant = it }
                )
            }
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

// ==================== Catalog model section (generic) ====================

/**
 * One section for a bundled catalog model (Parakeet, Whisper, Qwen3-ASR, Nemotron,
 * GigaAM). Everything — header title/description, variant cards, download/delete
 * dialogs, info and comparison buttons — is driven by the [CatalogEntry] and the
 * per-entry UI state, so there is no per-model code left in the UI layer.
 */
@Composable
private fun CatalogModelSection(
    viewModel: ModelViewModel,
    entry: CatalogEntry,
    state: ModelViewModel.ModelEntryUiState,
    activeBackendId: String,
    isTranscribing: Boolean,
    visibleVariants: List<CatalogVariantUi>,
    guardedModelSwitch: (() -> Unit) -> Unit,
    onInfoClick: (ModelVariant) -> Unit,
) {
    val context = LocalContext.current
    val savedPath by viewModel.savedModelPath(entry.id).collectAsState()
    val isEntryActive = activeBackendId == entry.id
    var showSpeedComparison by remember(entry.id) { mutableStateOf(false) }

    val entryTitleResId = (entry.display as? CatalogDisplay.Resource)?.key
        ?.let { CatalogStringKeys.resolve(it) }
        ?: CatalogVariantUi.of(entry.id).titleResId
    val entryDescriptionResId = (entry.description as? CatalogDisplay.Resource)?.key?.let { CatalogStringKeys.resolve(it) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state.isAnyDownloading -> MaterialTheme.colorScheme.secondaryContainer
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
                            state.downloadedVariants.isNotEmpty() -> Icons.Default.CheckCircle
                            state.isAnyDownloading -> Icons.Default.CloudDownload
                            else -> if (entry.isStreaming) Icons.Default.GraphicEq else Icons.Default.Translate
                        },
                        contentDescription = null,
                        tint = when {
                            state.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            state.isAnyDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(entryTitleResId),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (entryDescriptionResId != null) {
                            Text(
                                text = stringResource(entryDescriptionResId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                when {
                    entry.speedComparison -> IconButton(onClick = { showSpeedComparison = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.speed_comparison_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    entry.noteKey != null -> InfoIconButton(onClick = { onInfoClick(CatalogVariantUi.of(entry.id)) })
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant cards
            visibleVariants.forEach { variant ->
                val variantState = state.variantDownloadStates[variant.variantName]
                val needsExtraction = state.variantsNeedingExtraction.contains(variant.variantName)
                val isOrphaned = state.orphanedVariants.contains(variant.variantName)
                ModelVariantCard(
                    state = ModelVariantCardState(
                        variant = variant,
                        // The whole entry resolves to one active backend; mark the card active
                        // only if this entry is the active backend AND this variant's directory
                        // is the saved (auto-resolved) one.
                        isActive = isEntryActive && savedPath?.endsWith(variant.dirName) == true,
                        downloadProgress = variantState?.downloadProgress ?: 0f,
                        downloadState = variantState?.downloadState ?: DownloadState.Idle,
                        errorMessage = variantState?.errorMessage,
                        partialDownload = variantState?.partialDownload,
                        buttonState = when {
                            variantState?.isDownloading == true -> DownloadButtonState.Downloading
                            state.downloadedVariants.contains(variant.variantName) -> DownloadButtonState.Downloaded
                            isOrphaned && needsExtraction -> DownloadButtonState.Orphaned
                            needsExtraction -> DownloadButtonState.NeedsExtraction
                            variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                            else -> DownloadButtonState.Idle
                        }
                    ),
                    downloadButtonTextResId = R.string.download,
                    extraBadges = variant.badgeKey?.let { badgeKey ->
                        {
                            Surface(
                                color = MaterialTheme.colorScheme.secondary,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = stringResource(CatalogStringKeys.resolve(badgeKey)),
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
                    onDownloadClick = { viewModel.showDownloadDialog(entry.id, variant.variantName) },
                    onCancelClick = { viewModel.cancelDownload(entry.id, variant.variantName) },
                    onResumeClick = { viewModel.resumeDownload(entry.id, variant.variantName) },
                    onClearPartialClick = { viewModel.clearPartialDownload(entry.id, variant.variantName) },
                    onExtraActionClick = { viewModel.clearOrphanedFiles(entry.id, variant.variantName) },
                    onUseClick = { guardedModelSwitch { viewModel.useModel(entry.id, variant.variantName) } },
                    onDeleteClick = { viewModel.showDeleteDialog(entry.id, variant.variantName) },
                    onBenchmarkClick = {
                        val path = SherpaModelDownloader.of(entry.id).getModelPath(context, variant.variantName)
                        if (path != null) {
                            viewModel.startBenchmark(
                                entry.id,
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

    // Download confirmation dialog (extraction-aware: a downloaded-but-unextracted
    // Whisper tar prompts to extract instead of re-downloading).
    if (state.showDownloadDialog) {
        val selectedName = state.selectedVariant?.let { stringResource(CatalogVariantUi.of(entry.id, it).titleResId) }
            ?: stringResource(entryTitleResId)
        val isExtract = state.selectedVariant != null && state.variantsNeedingExtraction.contains(state.selectedVariant)
        val sizeMb = state.selectedVariant?.let { CatalogVariantUi.of(entry.id, it).estimatedSizeMB.toInt() } ?: 0
        DownloadConfirmationDialog(
            title = stringResource(
                if (isExtract) R.string.catalog_extract_confirm_title else R.string.catalog_download_confirm_title,
                selectedName
            ),
            message = stringResource(
                if (isExtract) R.string.catalog_extract_confirm_message else R.string.catalog_download_confirm_message,
                sizeMb
            ),
            confirmButtonText = stringResource(if (isExtract) R.string.extract_model else R.string.download),
            onConfirm = { viewModel.confirmDownload(entry.id) },
            onDismiss = { viewModel.dismissDownloadDialog(entry.id) }
        )
    }

    // Delete confirmation dialog
    if (state.showDeleteDialog) {
        val variant = state.variantToDelete?.let { CatalogVariantUi.of(entry.id, it) }
        val variantDisplayName = variant?.let { stringResource(it.titleResId) } ?: stringResource(entryTitleResId)
        val isVariantActive = isEntryActive && variant != null && savedPath?.endsWith(variant.dirName) == true
        DeleteConfirmationDialog(
            modelName = variantDisplayName,
            isTranscribing = isTranscribing,
            isActiveModel = isVariantActive,
            onConfirm = { viewModel.confirmDelete(entry.id) },
            onDismiss = { viewModel.dismissDeleteDialog(entry.id) }
        )
    }

    // Speed comparison dialog (catalog-flagged entry: Whisper)
    if (showSpeedComparison) {
        SpeedComparisonDialog(onDismiss = { showSpeedComparison = false })
    }
}

// ==================== External models section (v2a) ====================

/**
 * Imported external models: one card per record plus the two JSON-only import
 * actions (paste text / URL). The standing notices (single-pass risk,
 * wrong-family crash) ride along every card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalModelsSection(
    viewModel: ModelViewModel,
    activeBackendId: String,
    onDeleteRequest: (com.antivocale.app.data.ExternalModelRecord) -> Unit,
) {
    val records by viewModel.externalModels.collectAsState()
    val importState by viewModel.externalImportState.collectAsState()
    var urlDialogOpen by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var pasteDialogOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }

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

            // JSON-only import (spec decision): the entry JSON declares everything
            // (name, description, modelType, languages, files) — no shared selector.
            Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { pasteDialogOpen = true },
                enabled = importState !is ModelViewModel.ExternalImportState.Importing,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.external_import_json))
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

    if (pasteDialogOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pasteDialogOpen = false },
            title = { Text(stringResource(R.string.external_json_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    placeholder = { Text(stringResource(R.string.external_json_hint)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        pasteDialogOpen = false
                        if (pasteText.isNotBlank()) {
                            viewModel.importExternalFromJsonText(pasteText.trim())
                        }
                    }
                ) { Text(stringResource(R.string.external_import)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pasteDialogOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (urlDialogOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { urlDialogOpen = false },
            title = { Text(stringResource(R.string.external_url_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = { Text(stringResource(R.string.external_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        urlDialogOpen = false
                        if (urlText.isNotBlank()) {
                            viewModel.importExternalFromUrl(urlText.trim())
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
                            record.modelType.ifBlank { "zipformer" } +
                                " · " + com.antivocale.app.util.formatFileSize(record.sizeBytes) +
                                if (record.languages.isEmpty()) "" else " · " + record.languages.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        record.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
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

// ==================== Speed comparison dialog ====================

/**
 * Static model speed/quality comparison shown from the Whisper section header.
 * Pure informational content — no model state involved.
 */
@Composable
private fun SpeedComparisonDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        }
    )
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

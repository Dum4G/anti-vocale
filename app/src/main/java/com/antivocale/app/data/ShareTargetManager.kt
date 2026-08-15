package com.antivocale.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Keeps the manifest share-target activity-aliases in sync with model availability.
 *
 * The alias <-> backend-id <-> model-path mapping lives in [BackendRegistry]: each
 * descriptor's [BackendDescriptor.shareAlias] is the activity-alias class name and its
 * model-path flow supplies the has-model check. Targets iterate in the registry's
 * canonical backend order; each component is set independently, so the order is not
 * observable.
 */
class ShareTargetManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val backendRegistry: BackendRegistry
) {
    companion object {
        private const val TAG = "ShareTargetManager"
    }

    private fun hasModel(backendId: String): Boolean = runBlocking {
        val descriptor = backendRegistry.byBackendId(backendId) ?: return@runBlocking false
        descriptor.modelPathFlow(preferencesManager).first() != null
    }

    private fun setComponentEnabled(target: BackendDescriptor, enabled: Boolean) {
        // Sideload-only and external backends have no manifest activity-alias; skip them.
        if (target.shareAlias.isBlank()) return
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, target.shareAlias),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ${target.shareAlias}", e)
        }
    }

    fun syncAll() {
        val advancedEnabled = runBlocking {
            preferencesManager.advancedSharingEnabled.first()
        }

        backendRegistry.backends.forEach { target ->
            // Skip alias-less targets before the has-model check: externals would
            // otherwise buy a pointless blocking DataStore read per sync.
            if (target.shareAlias.isBlank()) return@forEach
            setComponentEnabled(target, advancedEnabled && hasModel(target.backendId))
        }
    }

    fun onModelDeleted(backendId: String) {
        val target = backendRegistry.backends.find { it.backendId == backendId } ?: return
        setComponentEnabled(target, false)
    }

    fun onModelDownloaded() {
        syncAll()
    }

    fun setAdvancedSharingEnabled(enabled: Boolean) {
        if (enabled) {
            syncAll()
        } else {
            backendRegistry.backends.forEach { setComponentEnabled(it, false) }
        }
    }
}

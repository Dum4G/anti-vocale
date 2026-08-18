package com.antivocale.app

import android.app.Application
import androidx.work.Configuration
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.util.CrashReporter
import com.antivocale.app.util.LocaleManager
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class BridgeApplication : Application(), Configuration.Provider {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var shareTargetManager: ShareTargetManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var externalModelStore: com.antivocale.app.data.ExternalModelStore

    /**
     * Provides the Hilt-aware [androidx.work.WorkManager] configuration so that
     * `@HiltWorker`-annotated Workers (e.g. SubtitleChoiceTimeoutWorker) get their
     * dependencies injected. The manifest disables WorkManager's default
     * initializer so this factory wins.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        private const val PREFS_NAME = "localai_migration_prefs"
        private const val KEY_LANGUAGE_MIGRATED = "language_preference_migrated_v2"
    }

    override fun onCreate() {
        super.onCreate()
        com.antivocale.app.data.catalog.BundledCatalog.attach(this)
        com.antivocale.app.util.SharedAudioHandler.cleanupOldFiles(this)
        // BEFORE syncAll: a persisted "custom-transductor" id must already resolve to an
        // external record, or the share sync (and any early transcription) would see a
        // registry without it and silently fall through to the LLM loader.
        // Contained: any IO failure must not crash Application.onCreate (which runs
        // before the global exception handler is installed).
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.antivocale.app.data.CustomTransducerMigrator(preferencesManager, externalModelStore).migrate()
            }
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "External-model migration failed (will retry on next launch)", e)
            // Clear the done-marker so the migration retries on the next launch.
            kotlinx.coroutines.runBlocking {
                preferencesManager.saveExternalMigrationDone(false)
            }
        }
        shareTargetManager.syncAll()
        migrateLanguagePreference()
        installGlobalExceptionHandler()
    }

    /**
     * Wraps the default uncaught exception handler so that every crash
     * is reported to Crashlytics before the process terminates.
     */
    private fun installGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashReporter.report(throwable, "Uncaught exception on ${thread.name}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Migrates existing language preference from DataStore to the new Per-App Language API.
     * This only runs once for existing users; new users won't have anything to migrate.
     */
    private fun migrateLanguagePreference() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LANGUAGE_MIGRATED, false)) {
            return // Already migrated
        }

        runBlocking {
            val savedLanguage = preferencesManager.getLegacyLanguagePreference()
            if (savedLanguage != "system") {
                LocaleManager.setLocale(savedLanguage)
            }
        }

        prefs.edit().putBoolean(KEY_LANGUAGE_MIGRATED, true).apply()
    }
}

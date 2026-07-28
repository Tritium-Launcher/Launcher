/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.core

import io.github.tritium_launcher.api.core.project.ProjectBase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * App-wide event bus for decoupled communication between components.
 */
object TritiumEventBus {
    private val _events = MutableSharedFlow<TritiumEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun publish(event: TritiumEvent) {
        _events.tryEmit(event)
    }
}

inline fun <reified T : TritiumEvent> CoroutineScope.onEvent(
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    noinline handler: (T) -> Unit
) {
    launch(dispatcher) {
        TritiumEventBus.events.collect { event ->
            if (event is T) handler(event)
        }
    }
}

sealed interface TritiumEvent {
    /**
     * Request to focus the Registry Browser on a specific ID.
     */
    data class RegistryFocusRequest(val id: String) : TritiumEvent

    /**
     * Fired after the mod browser finishes downloading queued mods.
     */
    data object ModsInstalled : TritiumEvent

    /**
     * Request the Installed Mods panel to check for updates.
     */
    data object UpdateCheckRequested : TritiumEvent

    /**
     * Fired when the mod download queue changes (add/remove/clear).
     */
    data object QueuedDownloadsChanged : TritiumEvent

    // ── Project lifecycle ────────────────────────────────────────

    /**
     * A project has been opened in the UI.
     */
    data class ProjectOpened(val project: ProjectBase) : TritiumEvent

    /**
     * A project window is closing.
     */
    data class ProjectClosing(val project: ProjectBase) : TritiumEvent

    /**
     * A project was created (new project dialog).
     */
    data class ProjectCreated(val project: ProjectBase) : TritiumEvent

    /**
     * Project generation failed.
     */
    data class ProjectFailedToGenerate(val project: ProjectBase, val errorMsg: String) : TritiumEvent

    /**
     * All projects finished loading from the catalog.
     */
    data class ProjectFinishedLoading(val projects: List<ProjectBase>) : TritiumEvent

    // ── Editor lifecycle ─────────────────────────────────────────

    /**
     * An editor tab was opened.
     */
    data class EditorOpened(val providerId: String?, val filePath: String?) : TritiumEvent

    /**
     * An editor tab was closed.
     */
    data class EditorClosed(val providerId: String?, val filePath: String?) : TritiumEvent

    /**
     * A file was saved from an editor tab.
     */
    data class FileSaved(val providerId: String?, val filePath: String?) : TritiumEvent

    // ── Mod lifecycle (fine-grained) ─────────────────────────────

    /**
     * A single mod was installed (from queue, update, downgrade, or skipped-version install).
     */
    data class ModInstalled(
        val project: ProjectBase,
        val projectId: String,
        val modId: String,
        val displayName: String,
        val versionId: String,
        val versionLabel: String
    ) : TritiumEvent

    /**
     * A mod was uninstalled.
     */
    data class ModUninstalled(
        val project: ProjectBase,
        val projectId: String,
        val modId: String,
        val displayName: String
    ) : TritiumEvent

    /**
     * A mod was updated to a newer version.
     */
    data class ModUpdated(
        val project: ProjectBase,
        val projectId: String,
        val displayName: String,
        val oldVersionId: String,
        val newVersionId: String
    ) : TritiumEvent

    /**
     * A mod was downgraded to a previous version.
     */
    data class ModDowngraded(
        val project: ProjectBase,
        val projectId: String,
        val displayName: String,
        val oldVersionId: String,
        val newVersionId: String
    ) : TritiumEvent

    /**
     * A mod update was skipped (recorded in version history).
     */
    data class ModSkipped(
        val project: ProjectBase,
        val projectId: String,
        val displayName: String,
        val skippedVersionId: String,
        val skippedVersionLabel: String
    ) : TritiumEvent

    /**
     * A mod's enabled/disabled state was toggled.
     */
    data class ModEnabledToggled(
        val project: ProjectBase,
        val projectId: String,
        val enabled: Boolean
    ) : TritiumEvent

    /**
     * A mod's release-exclusion state was toggled.
     */
    data class ModReleaseToggled(
        val project: ProjectBase,
        val projectId: String,
        val excludedFromRelease: Boolean
    ) : TritiumEvent

    // ── Game process ─────────────────────────────────────────────

    /**
     * A game process was attached.
     */
    data class GameAttached(
        val projectScope: String,
        val projectName: String,
        val pid: Long
    ) : TritiumEvent

    /**
     * A game process was detached.
     */
    data class GameDetached(
        val projectScope: String,
        val projectName: String,
        val pid: Long
    ) : TritiumEvent

    /**
     * A game process exited.
     */
    data class GameExited(
        val projectScope: String,
        val projectName: String,
        val pid: Long,
        val exitCode: Int
    ) : TritiumEvent

    // ── Settings ─────────────────────────────────────────────────

    /**
     * A setting value changed.
     */
    data class SettingChanged(
        val nodeKey: String,
        val namespace: String,
        val oldValue: Any?,
        val newValue: Any?
    ) : TritiumEvent

    // ── Export ───────────────────────────────────────────────────

    /**
     * A release manifest was exported.
     */
    data class ReleaseManifestExported(
        val project: ProjectBase,
        val manifestPath: String
    ) : TritiumEvent

    // ── UI State ───────────────────────────────────────────────────

    /**
     * Application is quitting.
     */
    data object AppQuitting : TritiumEvent

    /**
     * UI state has been fully restored for a session.
     */
    data object UIStateRestored : TritiumEvent

    // ── Directory marks ───────────────────────────────────────────

    /**
     * Directory marks changed for a project.
     */
    data class DirectoryMarksChanged(val project: ProjectBase) : TritiumEvent
}

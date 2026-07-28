/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.registry.Registrable
import io.github.tritium_launcher.api.registry.RegistryKey
import io.github.tritium_launcher.api.registry.RegistryMngr
import io.github.tritium_launcher.api.runOnGuiThread
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.Qt
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RegistryDashboardPanel internal constructor() : QWidget() {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("RegistryDashboardPanel"))

    init {
        objectName = "registryDashboardPanel"

        val mainLayout = vBoxLayout {
            contentsMargins = 12.m
            widgetSpacing = 12
        }

        val title = label("Tritium Registries") {
            objectName = "registryDashboardPanelTitle"
        }
        val desc = label("Browse all registered extension points and their entries.") {
            objectName = "registryDashboardPanelDesc"
            wordWrap = true
        }

        val tree = QTreeWidget().apply {
            objectName = "registryDashboardTree"
            headerHidden = false
            columnCount = 2
            setHeaderLabels(listOf("Registry", "ID"))
            header()?.stretchLastSection = false
            header()?.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
            header()?.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
            alternatingRowColors = true
            rootIsDecorated = true
            animated = false
            selectionMode = QAbstractItemView.SelectionMode.SingleSelection
        }

        mainLayout.addWidget(title)
        mainLayout.addWidget(desc)
        mainLayout.addSpacing(8)
        mainLayout.addWidget(tree, 1)

        setLayout(mainLayout)

        setThemedStyle {
            selector("#registryDashboardPanel") { backgroundColor(TColors.Surface0) }
            selector("#registryDashboardPanelTitle") { fontSize(18); fontWeight(700) }
            selector("#registryDashboardPanelDesc") { fontSize(12); color(TColors.Subtext) }
            selector("#registryDashboardTree") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Surface1)
                borderRadius(6)
            }
        }

        loadRegistriesAsync(tree)
    }

    private fun loadRegistriesAsync(tree: QTreeWidget) {
        scope.launch {
            try {
                while (RegistryMngr.registries.values.any { !it.isFrozen }) {
                    delay(pollInterval)
                }
                val snapshot = RegistryMngr.registries.entries
                    .map { (key, registry) -> key to registry.all().toList() }
                    .sortedBy { it.first.name }

                runOnGuiThread { populateTree(tree, snapshot) }
            } catch (t: Throwable) {
                logger.warn("Failed to load registry snapshot", t)
            }
        }
    }

    private fun populateTree(
        tree: QTreeWidget,
        registries: List<Pair<RegistryKey, List<Registrable>>>
    ) {
        tree.clear()
        for ((key, entries) in registries) {
            val registry = RegistryMngr.registries[key] ?: continue
            val top = QTreeWidgetItem(tree)
            top.setText(0, key.name)
            top.setText(1, "${entries.size} entries")
            top.setData(0, Qt.ItemDataRole.UserRole, key.name)
            top.isExpanded = false
            for (entry in entries.sortedBy { it.id }) {
                val child = QTreeWidgetItem(top)
                child.setText(1, registry.namespacedIdOf(entry.id))
                child.setData(0, Qt.ItemDataRole.UserRole, entry.id)
            }
        }
        tree.resizeColumnToContents(0)
    }

    fun cancel() {
        scope.cancel()
    }

    companion object {
        private val pollInterval: Duration = 100.milliseconds
    }
}

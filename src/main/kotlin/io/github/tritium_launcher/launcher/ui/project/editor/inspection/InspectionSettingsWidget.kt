/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inspection

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.inspection.InspectionRegistry
import io.github.tritium_launcher.api.inspection.InspectionSpec
import io.github.tritium_launcher.api.settings.RefreshableSettingWidget
import io.github.tritium_launcher.api.settings.SettingWidgetContext
import io.qt.core.Qt
import io.qt.widgets.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class InspectionSettingsWidget(
    private val ctx: SettingWidgetContext<String>
) : QWidget(), RefreshableSettingWidget {

    private val tree = QTreeWidget()
    private val detailStack = QStackedWidget()
    private val titleLabel = QLabel()
    private val idLabel = QLabel()
    private val langLabel = QLabel()
    private val catLabel = QLabel()
    private val descLabel = QLabel()
    private val defaultSeverityLabel = QLabel()
    private val conditionLabel = QLabel()
    private val fixCountLabel = QLabel()
    private val severityCombo = QComboBox()

    private var inspectionByItem = LinkedHashMap<QTreeWidgetItem, InspectionSpec>()
    private var overrides = mutableMapOf<String, String>()
    private var isRefreshing = false
    private var currentSpec: InspectionSpec? = null

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val SEVERITY_OPTIONS = listOf(
            "IGNORE" to "Disabled",
            "HINT" to "Hint",
            "INFO" to "Info",
            "WARNING" to "Warning",
            "ERROR" to "Error"
        )
    }

    init {
        val splitter = QSplitter(Qt.Orientation.Horizontal)

        tree.apply {
            headerHidden = true
            minimumWidth = 220
            setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        }

        val emptyLabel = QLabel("Select an inspection to configure.").apply {
            setAlignment(Qt.AlignmentFlag.AlignCenter)
        }

        detailStack.apply {
            addWidget(emptyLabel)
            addWidget(buildDetailPanel())
            setCurrentIndex(0)
        }

        splitter.addWidget(tree)
        splitter.addWidget(detailStack)
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)

        val layout = QHBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(splitter)

        tree.itemSelectionChanged.connect { onSelectionChanged() }
        severityCombo.activated.connect { onSeverityChanged() }

        refreshFromSettingValue()
    }

    private fun buildDetailPanel(): QWidget {
        val panel = QWidget()
        panel.setContentsMargins(16, 8, 16, 8)
        val layout = QVBoxLayout(panel)
        layout.setSpacing(8)

        titleLabel.setStyleSheet("font-size: 16px; font-weight: 600;")
        idLabel.setStyleSheet("font-size: 11px; color: gray;")
        langLabel.setStyleSheet("font-size: 11px; color: gray;")
        catLabel.setStyleSheet("font-size: 11px; color: gray;")
        descLabel.setWordWrap(true)
        defaultSeverityLabel.setStyleSheet("font-size: 12px;")
        conditionLabel.setStyleSheet("font-size: 12px;")
        fixCountLabel.setStyleSheet("font-size: 12px;")

        severityCombo.apply {
            addItem("Default")
            SEVERITY_OPTIONS.forEach { (_, label) -> addItem(label) }
            setMinimumWidth(140)
        }

        val severityRow = QWidget()
        severityRow.setContentsMargins(0, 0, 0, 0)
        val severityRowLayout = QHBoxLayout(severityRow)
        severityRowLayout.setContentsMargins(0, 0, 0, 0)
        severityRowLayout.setSpacing(8)
        severityRowLayout.addWidget(QLabel("Severity Override:"))
        severityRowLayout.addWidget(severityCombo)
        severityRowLayout.addStretch()

        layout.addWidget(titleLabel)
        layout.addWidget(idLabel)
        layout.addWidget(langLabel)
        layout.addWidget(catLabel)
        layout.addWidget(descLabel)
        layout.addWidget(defaultSeverityLabel)
        layout.addWidget(conditionLabel)
        layout.addWidget(fixCountLabel)
        layout.addWidget(severityRow)
        layout.addStretch(1)

        return panel
    }

    private fun onSelectionChanged() {
        val item = tree.currentItem() ?: return
        if (item.childCount() > 0 || item.parent() == null) {
            detailStack.setCurrentIndex(0)
            return
        }
        val spec = inspectionByItem[item] ?: return

        isRefreshing = true
        currentSpec = spec
        titleLabel.text = spec.title
        idLabel.text = "ID: ${spec.id}"
        langLabel.text = "Language: ${spec.languageId}"
        catLabel.text = "Category: ${spec.category.joinToString(" \u203A ")}"
        descLabel.text = spec.description
        descLabel.isVisible = spec.description.isNotBlank()
        defaultSeverityLabel.text = "Default severity: ${spec.defaultSeverity.name}"

        val hasCondition = spec.condition != null
        conditionLabel.text = if (hasCondition) "Condition: active" else "Condition: none"
        conditionLabel.isVisible = true

        val fixCount = spec.fixes.size
        fixCountLabel.text = if (fixCount > 0) "Fixes available: $fixCount" else "No quick fixes"
        fixCountLabel.isVisible = true

        val overrideStr = overrides[spec.id]
        val sevIdx = if (overrideStr == null) 0 else {
            val optIdx = SEVERITY_OPTIONS.indexOfFirst { it.first == overrideStr }
            if (optIdx >= 0) optIdx + 1 else 0
        }
        severityCombo.setCurrentIndex(sevIdx)
        isRefreshing = false

        detailStack.setCurrentIndex(1)
    }

    private fun onSeverityChanged() {
        if (isRefreshing) return
        val spec = currentSpec ?: return

        val idx = severityCombo.currentIndex()
        if (idx == 0) {
            overrides.remove(spec.id)
        } else {
            overrides[spec.id] = SEVERITY_OPTIONS[idx - 1].first
        }
        commitOverrides()
    }

    private fun commitOverrides() {
        val raw = if (overrides.isEmpty()) "{}" else {
            json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                overrides
            )
        }
        ctx.updateValue(raw)
    }

    override fun refreshFromSettingValue() {
        isRefreshing = true
        try {
            val raw = ctx.currentValue().trim()
            overrides.clear()
            if (raw.isNotBlank() && raw != "{}") {
                overrides.putAll(
                    json.decodeFromString(
                        MapSerializer(String.serializer(), String.serializer()),
                        raw
                    )
                )
            }
            rebuildTree()
        } catch (t: Throwable) {
            overrides.clear()
        } finally {
            isRefreshing = false
        }
    }

    private fun rebuildTree() {
        tree.clear()
        inspectionByItem.clear()

        val grouped = InspectionRegistry.grouped()
        for ((langId, categories) in grouped) {
            val langItem = QTreeWidgetItem(tree)
            langItem.setText(0, langId.replaceFirstChar { it.uppercase() })
            for ((category, specs) in categories) {
                val catItem = QTreeWidgetItem(langItem)
                catItem.setText(0, category)
                for (spec in specs) {
                    val specItem = QTreeWidgetItem(catItem)
                    specItem.setText(0, spec.title)
                    specItem.setToolTip(0, spec.description)
                    inspectionByItem[specItem] = spec
                }
            }
        }
        tree.expandAll()

        if (tree.topLevelItemCount() > 0) {
            tree.topLevelItem(0)?.setExpanded(true)
        }
    }
}

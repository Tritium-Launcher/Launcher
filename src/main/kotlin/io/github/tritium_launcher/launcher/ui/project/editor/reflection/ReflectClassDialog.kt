/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.reflection

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.qs
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.pushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.Qt
import io.qt.widgets.*

class ReflectClassDialog(
    parent: QWidget?,
    private val className: String,
    private val reflected: JavaReflectionEngine.ReflectedClass,
) : QDialog(parent) {
    private val tree = QTreeWidget()
    private var result: JavaReflectionEngine.ReflectedClass? = null
    private var updating = false
    private lateinit var rootItem: QTreeWidgetItem

    init {
        windowTitle = "Select Members: $className"
        modal = true
        resize(600, 500)
        minimumSize = qs(400, 300)

        val header = QLabel("Members of <b>$className</b>")
        header.wordWrap = true

        tree.header()?.apply {
            hide()
            setStretchLastSection(true)
        }
        tree.setAnimated(true)
        tree.setRootIsDecorated(true)

        buildTree()

        tree.itemChanged.connect { item, _ ->
            if (item == null || updating) return@connect
            updating = true
            handleItemChanged(item)
            updating = false
        }

        val applyBtn = pushButton("Apply") {
            clicked.connect { saveSelection() }
        }
        val cancelBtn = pushButton("Cancel") {
            clicked.connect { reject() }
        }

        vBoxLayout(this) {
            addWidget(header)
            addWidget(tree, 1)
            addLayout(hBoxLayout {
                addStretch()
                addWidget(applyBtn)
                addWidget(cancelBtn)
            })
        }
    }

    private fun buildTree() {
        rootItem = QTreeWidgetItem(tree)
        rootItem.setText(0, className)
        rootItem.setCheckState(0, Qt.CheckState.Unchecked)
        rootItem.setData(0, Qt.ItemDataRole.UserRole, "root")

        val ctorCat = QTreeWidgetItem(rootItem)
        ctorCat.setText(0, "Constructors (${reflected.constructors.size})")
        ctorCat.setCheckState(0, Qt.CheckState.Unchecked)
        ctorCat.setData(0, Qt.ItemDataRole.UserRole, "category:constructors")

        for (ctor in reflected.constructors) {
            val text = "new(${ctor.parameterTypes.joinToString { it }}): $className"
            val item = QTreeWidgetItem(ctorCat)
            item.setText(0, text)
            item.setCheckState(0, Qt.CheckState.Unchecked)
            item.setData(0, Qt.ItemDataRole.UserRole, "ctor:${ctor.parameterTypes.joinToString(",")}")
        }

        val methodsCat = QTreeWidgetItem(rootItem)
        methodsCat.setText(0, "Methods (${reflected.methods.size})")
        methodsCat.setCheckState(0, Qt.CheckState.Unchecked)
        methodsCat.setData(0, Qt.ItemDataRole.UserRole, "category:methods")

        for (method in reflected.methods) {
            val text = "${method.name}(${method.parameterTypes.joinToString { it }}): ${method.returnType}"
            val item = QTreeWidgetItem(methodsCat)
            item.setText(0, text)
            item.setCheckState(0, Qt.CheckState.Unchecked)
            item.setData(0, Qt.ItemDataRole.UserRole, "method:${method.name}")
        }

        val fieldsCat = QTreeWidgetItem(rootItem)
        fieldsCat.setText(0, "Fields (${reflected.fields.size})")
        fieldsCat.setCheckState(0, Qt.CheckState.Unchecked)
        fieldsCat.setData(0, Qt.ItemDataRole.UserRole, "category:fields")

        for (field in reflected.fields) {
            val text = "${field.name}: ${field.type}"
            val item = QTreeWidgetItem(fieldsCat)
            item.setText(0, text)
            item.setCheckState(0, Qt.CheckState.Unchecked)
            item.setData(0, Qt.ItemDataRole.UserRole, "field:${field.name}")
        }

        tree.expandAll()
    }

    private fun handleItemChanged(item: QTreeWidgetItem) {
        val role = item.data(0, Qt.ItemDataRole.UserRole) as? String ?: return
        when {
            role == "root" -> {
                val state = item.checkState(0)
                for (i in 0 until item.childCount()) {
                    val cat = item.child(i)!!
                    cat.setCheckState(0, state)
                    for (j in 0 until cat.childCount()) {
                        cat.child(j)!!.setCheckState(0, state)
                    }
                }
            }
            role.startsWith("category:") -> {
                val state = item.checkState(0)
                for (i in 0 until item.childCount()) {
                    item.child(i)!!.setCheckState(0, state)
                }
                updateCategoryParentState()
            }
            else -> {
                val cat = item.parent() ?: return
                updateCategoryFromChildren(cat)
                updateCategoryParentState()
            }
        }
    }

    private fun updateCategoryFromChildren(cat: QTreeWidgetItem) {
        var checked = 0
        var unchecked = 0
        for (i in 0 until cat.childCount()) {
            when (cat.child(i)!!.checkState(0)) {
                Qt.CheckState.Checked -> checked++
                Qt.CheckState.Unchecked -> unchecked++
                else -> {
                    cat.setCheckState(0, Qt.CheckState.PartiallyChecked)
                    return
                }
            }
        }
        cat.setCheckState(0, when {
            checked == cat.childCount() -> Qt.CheckState.Checked
            unchecked == cat.childCount() -> Qt.CheckState.Unchecked
            else -> Qt.CheckState.PartiallyChecked
        })
    }

    private fun updateCategoryParentState() {
        var checked = 0
        var unchecked = 0
        for (i in 0 until rootItem.childCount()) {
            when (rootItem.child(i)!!.checkState(0)) {
                Qt.CheckState.Checked -> checked++
                Qt.CheckState.Unchecked -> unchecked++
                else -> {
                    rootItem.setCheckState(0, Qt.CheckState.PartiallyChecked)
                    return
                }
            }
        }
        rootItem.setCheckState(0, when {
            checked == rootItem.childCount() -> Qt.CheckState.Checked
            unchecked == rootItem.childCount() -> Qt.CheckState.Unchecked
            else -> Qt.CheckState.PartiallyChecked
        })
    }

    private fun saveSelection() {
        val selectedMethods = mutableListOf<JavaReflectionEngine.ReflectedMethod>()
        val selectedFields = mutableListOf<JavaReflectionEngine.ReflectedField>()
        val selectedCtors = mutableListOf<JavaReflectionEngine.ReflectedConstructor>()

        for (i in 0 until rootItem.childCount()) {
            val cat = rootItem.child(i)!!
            for (j in 0 until cat.childCount()) {
                val child = cat.child(j)!!
                if (child.checkState(0) != Qt.CheckState.Checked) continue
                val data = child.data(0, Qt.ItemDataRole.UserRole) as? String ?: continue
                when {
                    data.startsWith("method:") -> {
                        val name = data.removePrefix("method:")
                        reflected.methods.find { it.name == name }?.let { selectedMethods.add(it) }
                    }
                    data.startsWith("field:") -> {
                        val name = data.removePrefix("field:")
                        reflected.fields.find { it.name == name }?.let { selectedFields.add(it) }
                    }
                    data.startsWith("ctor:") -> {
                        val params = data.removePrefix("ctor:")
                        reflected.constructors.find { it.parameterTypes.joinToString(",") == params }?.let { selectedCtors.add(it) }
                    }
                }
            }
        }

        result = reflected.copy(
            methods = selectedMethods,
            fields = selectedFields,
            constructors = selectedCtors,
        )
        accept()
    }

    fun selectedClass(): JavaReflectionEngine.ReflectedClass? = result
}

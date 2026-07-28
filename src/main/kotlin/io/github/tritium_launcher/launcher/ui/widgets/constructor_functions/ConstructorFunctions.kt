/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Provides methods to make Qt Widgets without having to use .apply everywhere. It looks much cleaner this way!
 */

@file:Suppress("unused")

package io.github.tritium_launcher.launcher.ui.widgets.constructor_functions

import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QIcon
import io.qt.widgets.*


fun qWidget(parent: QWidget? = null, block: QWidget.() -> Unit = {}): QWidget =
    QWidget(parent).apply(block)

fun formLayout(parent: QWidget, block: QFormLayout.() -> Unit = {}): QFormLayout =
    QFormLayout(parent).apply(block)

fun formLayout(block: QFormLayout.() -> Unit = {}): QFormLayout =
    QFormLayout().apply(block)

fun gridLayout(parent: QWidget, block: QGridLayout.() -> Unit = {}): QGridLayout =
    QGridLayout(parent).apply(block)

fun gridLayout(block: QGridLayout.() -> Unit = {}): QGridLayout =
    QGridLayout().apply(block)

fun stackedLayout(parent: QWidget, block: QStackedLayout.() -> Unit = {}): QStackedLayout =
    QStackedLayout(parent).apply(block)

fun stackedLayout(parentLayout: QLayout, block: QStackedLayout.() -> Unit = {}): QStackedLayout =
    QStackedLayout(parentLayout).apply(block)

fun stackedLayout(block: QStackedLayout.() -> Unit = {}): QStackedLayout =
    QStackedLayout().apply(block)

fun vBoxLayout(parent: QWidget, block: QVBoxLayout.() -> Unit = {}): QVBoxLayout =
    QVBoxLayout(parent).apply(block)

fun vBoxLayout( block: QVBoxLayout.() -> Unit = {}): QVBoxLayout =
    QVBoxLayout().apply(block)

fun hBoxLayout(parent: QWidget, block: QHBoxLayout.() -> Unit = {}): QHBoxLayout =
    QHBoxLayout(parent).apply(block)

fun hBoxLayout(block: QHBoxLayout.() -> Unit = {}): QHBoxLayout =
    QHBoxLayout().apply(block)

fun widget(parent: QWidget? = null, block: QWidget.() -> Unit = {}): QWidget =
    QWidget(parent).apply(block)

fun widget(parent: QWidget? = null, windowFlags: Qt.WindowFlags, block: QWidget.() -> Unit = {}): QWidget =
    QWidget(parent, windowFlags).apply(block)

fun frame(parent: QWidget? = null, block: QFrame.() -> Unit = {}): QFrame =
    QFrame(parent).apply(block)

fun frame(parent: QWidget? = null, windowFlags: Qt.WindowFlags, block: QFrame.() -> Unit = {}): QFrame =
    QFrame(parent, windowFlags).apply(block)

fun label(parent: QWidget? = null, block: QLabel.() -> Unit = {}): QLabel =
    QLabel(parent).apply(block)

fun label(text: String, parent: QWidget? = null, block: QLabel.() -> Unit = {}): QLabel =
    QLabel(text, parent).apply(block)

fun pushButton(text: String, parent: QWidget? = null, block: QPushButton.() -> Unit = {}): QPushButton =
    QPushButton(text, parent).apply(block)

fun pushButton(parent: QWidget? = null, block: QPushButton.() -> Unit = {}): QPushButton =
    QPushButton(parent).apply(block)

fun toolButton(parent: QWidget? = null, block: QToolButton.() -> Unit = {}): QToolButton =
    QToolButton(parent).apply(block)


fun qAction(parent: QWidget? = null, block: QAction.() -> Unit = {}) =
    QAction(parent).apply(block)

fun qAction(text: String, block: QAction.() -> Unit = {}) =
    QAction(text).apply(block)

fun qAction(text: String, parent: QWidget? = null, block: QAction.() -> Unit = {}) =
    QAction(text, parent).apply(block)

fun qAction(text: String, icon: QIcon?, block: QAction.() -> Unit = {}) =
    QAction(text).apply(block).apply { icon?.let { setIcon(it) } }

fun qAction(text: String, icon: QIcon?, parent: QWidget? = null, block: QAction.() -> Unit = {}) =
    QAction(text, parent).apply(block).apply { icon?.let { setIcon(it) } }

/**
 * Provides methods to make Qt Widgets without having to use .apply everywhere. It looks much cleaner this way!
 */

package io.github.tritium_launcher.launcher.ui.widgets.constructor_functions

import io.qt.core.Qt
import io.qt.widgets.*

/**
 * Made for visibility in IntelliJ.
 */
@DslMarker
@Target(AnnotationTarget.FUNCTION)
annotation class QObj

@QObj
fun qWidget(parent: QWidget? = null, block: QWidget.() -> Unit = {}): QWidget =
    QWidget(parent).apply(block)

@QObj
fun formLayout(parent: QWidget, block: QFormLayout.() -> Unit = {}): QFormLayout =
    QFormLayout(parent).apply(block)

@QObj
fun formLayout(block: QFormLayout.() -> Unit = {}): QFormLayout =
    QFormLayout().apply(block)

@QObj
fun gridLayout(parent: QWidget, block: QGridLayout.() -> Unit = {}): QGridLayout =
    QGridLayout(parent).apply(block)

@QObj
fun gridLayout(block: QGridLayout.() -> Unit = {}): QGridLayout =
    QGridLayout().apply(block)

@QObj
fun stackedLayout(parent: QWidget, block: QStackedLayout.() -> Unit = {}): QStackedLayout =
    QStackedLayout(parent).apply(block)

@QObj
fun stackedLayout(parentLayout: QLayout, block: QStackedLayout.() -> Unit = {}): QStackedLayout =
    QStackedLayout(parentLayout).apply(block)

@QObj
fun stackedLayout(block: QStackedLayout.() -> Unit = {}): QStackedLayout =
    QStackedLayout().apply(block)

@QObj
fun vBoxLayout(parent: QWidget, block: QVBoxLayout.() -> Unit = {}): QVBoxLayout =
    QVBoxLayout(parent).apply(block)

@QObj
fun vBoxLayout( block: QVBoxLayout.() -> Unit = {}): QVBoxLayout =
    QVBoxLayout().apply(block)

@QObj
fun hBoxLayout(parent: QWidget, block: QHBoxLayout.() -> Unit = {}): QHBoxLayout =
    QHBoxLayout(parent).apply(block)

@QObj
fun hBoxLayout(block: QHBoxLayout.() -> Unit = {}): QHBoxLayout =
    QHBoxLayout().apply(block)


@QObj
fun widget(parent: QWidget? = null, block: QWidget.() -> Unit = {}): QWidget =
    QWidget(parent).apply(block)

@QObj
fun widget(parent: QWidget? = null, windowFlags: Qt.WindowFlags, block: QWidget.() -> Unit = {}): QWidget =
    QWidget(parent, windowFlags).apply(block)

@QObj
fun frame(parent: QWidget? = null, block: QFrame.() -> Unit = {}): QFrame =
    QFrame(parent).apply(block)

@QObj
fun frame(parent: QWidget? = null, windowFlags: Qt.WindowFlags, block: QFrame.() -> Unit = {}): QFrame =
    QFrame(parent, windowFlags).apply(block)

@QObj
fun label(parent: QWidget? = null, block: QLabel.() -> Unit = {}): QLabel =
    QLabel(parent).apply(block)

@QObj
fun label(text: String, parent: QWidget? = null, block: QLabel.() -> Unit = {}): QLabel =
    QLabel(text, parent).apply(block)

@QObj
fun pushButton(parent: QWidget? = null, block: QPushButton.() -> Unit = {}): QPushButton =
    QPushButton(parent).apply(block)

@QObj
fun toolButton(parent: QWidget? = null, block: QToolButton.() -> Unit = {}): QToolButton =
    QToolButton(parent).apply(block)
/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api

import io.qt.core.QMetaObject
import io.qt.core.QObject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow


/**
 * Creates a Default Signal1 connection to [QObject]
 */
inline fun <T> QObject.Signal1Default1<T>.connect(crossinline handler: (T) -> Unit): QMetaObject.Slot1<T> {
    val slot = QMetaObject.Slot1<T> { arg -> handler(arg) }
    this.connect(slot)
    return slot
}

/**
 * Creates a Private Signal0 connection to [QObject]
 */
inline fun QObject.PrivateSignal0.connect(crossinline handler: () -> Unit): QMetaObject.Slot0 {
    val slot = QMetaObject.Slot0 { handler() }
    this.connect(slot)
    return slot
}

/**
 * Creates a Signal0 connection to [QObject]
 */
inline fun QObject.Signal0.connect(crossinline handler: () -> Unit): QMetaObject.Slot0 {
    val slot = QMetaObject.Slot0 { handler() }
    this.connect(slot)
    return slot
}

/**
 * Creates a Signal1 connection to [QObject]
 */
inline fun <T> QObject.Signal1<T>.connect(crossinline handler: (T) -> Unit): QMetaObject.Slot1<T> {
    val slot = QMetaObject.Slot1<T> { arg -> handler(arg) }
    this.connect(slot)
    return slot
}

/**
 * Creates a Signal2 connection to [QObject]
 */
inline fun <A, B> QObject.Signal2<A, B>.connect(crossinline handler: (A, B) -> Unit): QMetaObject.Slot2<A, B> {
    val slot = QMetaObject.Slot2<A, B> { a, b -> handler(a, b) }
    this.connect(slot)
    return slot
}

/**
 * Bridges a Qt Signal0 to a Kotlin Flow<Unit>.
 */
fun QObject.Signal0.asFlow(): Flow<Unit> = callbackFlow {
    val slot = connect { trySend(Unit) }
    awaitClose { disconnect(slot) }
}

/**
 * Bridges a Qt Signal1 to a Kotlin Flow<T>.
 */
fun <T> QObject.Signal1<T>.asFlow(): Flow<T> = callbackFlow {
    val slot = connect { trySend(it) }
    awaitClose { disconnect(slot) }
}

/**
 * Bridges a Qt PrivateSignal0 to a Kotlin Flow<Unit>.
 */
fun QObject.PrivateSignal0.asFlow(): Flow<Unit> = callbackFlow {
    val slot = connect { trySend(Unit) }
    awaitClose { disconnect(slot) }
}

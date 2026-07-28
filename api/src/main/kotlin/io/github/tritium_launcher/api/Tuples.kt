/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api

data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

data class Quintuple<A, B, C, D, E>(
    val first:  A,
    val second: B,
    val third:  C,
    val fourth: D,
    val fifth:  E
)

data class Sextuple<A, B, C, D, E, F>(
    val first:  A,
    val second: B,
    val third:  C,
    val fourth: D,
    val fifth:  E,
    val sixth:  F
)

data class Septuple<A, B, C, D, E, F, G>(
    val first:   A,
    val second:  B,
    val third:   C,
    val fourth:  D,
    val fifth:   E,
    val sixth:   F,
    val seventh: G
)

data class Octuple<A, B, C, D, E, F, G, H>(
    val first:   A,
    val second:  B,
    val third:   C,
    val fourth:  D,
    val fifth:   E,
    val sixth:   F,
    val seventh: G,
    val eighth:  H
)

data class Nonuple<A, B, C, D, E, F, G, H, I>(
    val first:   A,
    val second:  B,
    val third:   C,
    val fourth:  D,
    val fifth:   E,
    val sixth:   F,
    val seventh: G,
    val eighth:  H,
    val ninth:   I
)

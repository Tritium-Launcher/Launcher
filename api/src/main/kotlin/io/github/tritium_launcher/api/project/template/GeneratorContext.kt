/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

import org.slf4j.Logger
import java.nio.file.Path

data class GeneratorContext(
    val projectRoot: Path,
    val variables: Map<String, String>,
    val logger: Logger,
    val workingDir: Path,
    val snapshotDir: Path
)

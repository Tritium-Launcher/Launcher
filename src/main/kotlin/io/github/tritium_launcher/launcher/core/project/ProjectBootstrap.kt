/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.api.formatDurationMs
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.modpack.ModLoader
import io.github.tritium_launcher.api.redactUserPath
import io.github.tritium_launcher.launcher.accounts.MicrosoftAuth
import io.github.tritium_launcher.launcher.git.Git
import io.github.tritium_launcher.launcher.ui.notifications.NotificationMngr
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import kotlinx.coroutines.*

object ProjectBootstrap {
    private val logger = logger()

    suspend fun launch(
        projectRoot: VPath,
        packName: String,
        mcVer: String,
        loader: ModLoader,
        loaderVersion: String,
        gitInit: Boolean = false
    ) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val bootstrapTaskId = ProjectTaskMngr.start(
                projectPath = projectRoot,
                title = "Bootstrapping $packName",
                detail = "Preparing runtime files",
                progressPercent = 5.0
            )
            var bootstrapSucceeded = false
            var failureDetail: String? = null
            logger.info("Background bootstrap start for {} (MC {}, loader {} {})", packName, mcVer, loader.id, loaderVersion)
            try {
                coroutineScope {
                    val mcJob = async {
                        ProjectTaskMngr.update(bootstrapTaskId, detail = "Setting up Minecraft $mcVer")
                        ProjectTaskMngr.updateProgress(bootstrapTaskId, 20.0)
                        val mcStart = System.currentTimeMillis()
                        val ok = MicrosoftAuth.setupMinecraftInstance(mcVer, projectRoot)
                        logger.info("Minecraft setup {} ({})", if(ok) "ok" else "failed", formatDurationMs(System.currentTimeMillis() - mcStart))
                        ProjectTaskMngr.updateProgress(bootstrapTaskId, if (ok) 55.0 else 40.0)
                        ok
                    }

                    val gitJob = async {
                        if(gitInit) {
                            try {
                                logger.info("Initializing git repo in {}", projectRoot.toString().redactUserPath())
                                Git.initRepo(projectRoot)
                            } catch (t: Throwable) {
                                logger.warn("Git init failed in {}", projectRoot.toString().redactUserPath(), t)
                            }
                        }
                    }

                    val mcOk = mcJob.await()
                    if (mcOk) {
                        ProjectTaskMngr.update(
                            bootstrapTaskId,
                            detail = "Installing ${loader.displayName} $loaderVersion"
                        )
                        ProjectTaskMngr.updateProgress(bootstrapTaskId, 70.0)
                        val loaderStart = System.currentTimeMillis()
                        logger.info(
                            "Installing loader {} {} into {}",
                            loader.id,
                            loaderVersion,
                            projectRoot.toString().redactUserPath()
                        )
                        val ok = loader.installClient(loaderVersion, mcVer, projectRoot)
                        logger.info("Loader install {} ({})", if(ok) "ok" else "failed", formatDurationMs(System.currentTimeMillis() - loaderStart))
                        if (ok) {
                            val merged = MicrosoftAuth.writeMergedVersionJson(mcVer, loader.id, loaderVersion, projectRoot)
                            logger.info(
                                "Merged version json written to {}",
                                merged?.toAbsolute()?.toString()?.redactUserPath() ?: "null"
                            )

                            ProjectTaskMngr.updateProgress(bootstrapTaskId, 85.0)

                            ProjectTaskMngr.update(bootstrapTaskId, detail = "Finalizing bootstrap")
                            ProjectTaskMngr.updateProgress(bootstrapTaskId, 95.0)
                            bootstrapSucceeded = true
                        } else {
                            failureDetail = "Failed to install ${loader.displayName}."
                        }
                    } else {
                        logger.warn("Skipping loader install; Minecraft setup failed for {}", packName)
                        failureDetail = "Failed to set up Minecraft runtime."
                    }

                    gitJob.await()
                }
            } catch (t: Throwable) {
                logger.warn("Background bootstrap failed for {}", packName, t)
                failureDetail = t.message?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "Unexpected bootstrap error."
            } finally {
                if (bootstrapSucceeded) {
                    ProjectTaskMngr.update(bootstrapTaskId, detail = "Bootstrap finished")
                    ProjectTaskMngr.updateProgress(bootstrapTaskId, 100.0)
                }
                ProjectTaskMngr.finish(bootstrapTaskId)

                val projectRef = ProjectMngr.getProject(projectRoot)
                if (bootstrapSucceeded) {
                    NotificationMngr.post(
                        id = "bootstrap_success",
                        project = projectRef,
                        description = "Project '$packName' is ready.",
                        metadata = mapOf(
                            "source" to "source.bootstrap",
                            "result" to "success",
                        )
                    )
                } else {
                    val reason = failureDetail ?: "Unknown error."
                    NotificationMngr.post(
                        id = "bootstrap_failure",
                        project = projectRef,
                        description = "Project '$packName' bootstrap failed: $reason",
                        metadata = mapOf(
                            "source" to "source.bootstrap",
                            "result" to "failed",
                        )
                    )
                }
            }
            logger.info("BACKGROUND BOOTSTRAP FINISHED for {} (success={})", packName, bootstrapSucceeded)
        }
    }
}

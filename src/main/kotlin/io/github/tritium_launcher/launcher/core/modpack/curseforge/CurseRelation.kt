package io.github.tritium_launcher.launcher.core.modpack.curseforge

import kotlinx.serialization.Serializable

@Serializable
data class CurseRelation(
    val relations: CurseRelations
)

@Serializable
data class CurseRelations(
    val projects: List<CurseRelatedProject>
)

@Serializable
data class CurseRelatedProject(
    val slug: String,
    val type: List<CurseRelatedProjectType>
)
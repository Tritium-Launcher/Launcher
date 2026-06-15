package io.github.tritium_launcher.launcher.core.source.curseforge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val data: List<CurseModSummary>,
    val pagination: Pagination
)

@Serializable
data class Pagination(
    val index: Int,
    val pageSize: Int,
    val totalCount: Int
)

@Serializable
data class CurseModSummary(
    val id: Int,
    val name: String,
    val summary: String,
    val slug: String,
    val authors: List<CurseAuthor>,
    @SerialName("download_count") val downloadCount: Long = 0,
    val categories: List<CurseCategory>,
    @SerialName("latest_files") val latestFiles: List<CurseFileInfo> = emptyList(),
    val logo: CurseImage? = null
)

@Serializable
data class CurseAuthor(val name: String)

@Serializable
data class CurseCategory(val name: String, val id: Int)

@Serializable
data class CurseFileHash(
    val value: String,
    val algo: Int
)

@Serializable
data class CurseFileInfo(
    val id: Int,
    @SerialName("mod_id") val modId: Int? = null,
    @SerialName("game_id") val gameId: Int? = null,
    val gameVersions: List<String>,
    val modLoaders: List<CurseModLoader>? = null,
    val fileName: String = "",
    val displayName: String = "",
    val downloadUrl: String? = null,
    val description: String? = null,
    @SerialName("download_count") val downloadCount: Long = 0,
    val fileDate: String? = null,
    val hashes: List<CurseFileHash>? = null,
    val releaseType: Int = 1,
    val isAvailable: Boolean = true
)

@Serializable
data class CurseImage(val url: String)

@Serializable
data class CurseModLoader(
    val id: String,
    val primary: Boolean = false
)

@Serializable
data class CurseCategoryResponse(
    val data: List<CurseCategoryItem>
)

@Serializable
data class CurseCategoryItem(
    val id: Int,
    val name: String,
    val slug: String,
    val classId: Int? = null,
    val parentCategoryId: Int? = null,
    val isClass: Boolean = false,
    val iconUrl: String? = null
)

@Serializable
data class CurseModDetailResponse(
    val data: CurseModDetail
)

@Serializable
data class CurseModDetail(
    val id: Int,
    val name: String,
    val summary: String,
    val description: String? = null,
    val authors: List<CurseAuthor>,
    val downloadCount: Long = 0,
    val categories: List<CurseCategory>,
    val links: CurseLinks? = null,
    val latestFiles: List<CurseFileInfo> = emptyList(),
    val logo: CurseImage? = null
)

@Serializable
data class CurseLinks(val websiteUrl: String? = null)

@Serializable
data class CurseFilesResponse(
    val data: List<CurseFileInfo>,
    val pagination: Pagination
)

@Serializable
data class CurseFileResponse(
    val data: CurseFileInfo
)

@Serializable
data class CurseDescriptionResponse(
    val data: String
)

@Serializable
data class CurseFingerprintResponse(
    val data: CurseFingerprintData
)

@Serializable
data class CurseFingerprintData(
    @SerialName("exact_matches") val exactMatches: List<CurseFingerprintMatch> = emptyList(),
    @SerialName("partial_matches") val partialMatches: List<CurseFingerprintMatch> = emptyList(),
)

@Serializable
data class CurseFingerprintMatch(
    val id: Long,
    val file: CurseFileInfo
)

@Serializable
data class CurseModListResponse(
    val data: List<CurseModSummary>
)
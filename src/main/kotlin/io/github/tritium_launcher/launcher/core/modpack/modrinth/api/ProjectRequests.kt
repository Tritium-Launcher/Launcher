package io.github.tritium_launcher.launcher.core.modpack.modrinth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
* Contains responses for Modrinth's API specifically for projects, such as modifying and uploading versions.
* TODO: Figure out if gallery functions should be added.
*/

/**
 * @param icon Allowed values: png, jpg, jpeg, bmp, gif, webp, svg, svgz, rgb
 *
 * Responses:
 * 200: Successful. Returns the project info
 * 400: Bad request
 * 401: Incorrect token scopes or no authorization
 */
@Serializable
data class Create(
    val data: Data,
    val icon: String? = null,
) {
    @Serializable
    data class Data(
        val slug: String,
        val title: String,
        @SerialName("description") val summary: String,
        @SerialName("categories") val primaryCategories: List<ModrinthCategories> = emptyList(),
        val client_side: Side = Side.REQUIRED,
        val server_side: Side = Side.OPTIONAL,
        @SerialName("body") val description: String = "",
        val status: Status = Status.DRAFT,
        val requested_status: RequestedStatus = RequestedStatus.LISTED,
        @SerialName("additional_categories") val secondaryCategories: List<ModrinthCategories> = emptyList(),
        val issues_url: String? = null,
        val source_url: String? = null,
        val wiki_url: String? = null,
        val discord_url: String? = null,
        val donation_urls: List<DonationUrl> = emptyList(),
        val license_id: String = "",
        val license_url: String? = null
    ) {
        // Responses

        //TODO: INCOMPLETE! There appear to be two responses in the docs, waiting to see which is the right one.
        @Serializable
        data class Ok(
            val empty: String
        )

        // I know these two are the same, not sure if I should keep them separate or bundle them together. I kinda like them having proper names.
        @Serializable
        data class BadRequest(
            val error: String,
            val description: String
        )

        @Serializable
        data class Unauthorized(
            val error: String,
            val description: String
        )
    }
}

/**
 * Used with other requests to ensure the slug / project ID is valid.
 *
 * Responses:
 * 200: Successful
 * 404: NotFound / No Authorization
 */
@Serializable
data class CheckProjectNameValidity(
    val idOrSlug: String
) {
    // Responses
    @Serializable
    data class Ok(
        val id: String
    )
}

/**
 * Responses:
 *  204: Expected response to a valid request
 *  401: Incorrect token scopes or no authorization
 *  404: Project not found
 */
@Serializable
data class Modify(
    val idOrSlug: String,

    val slug: String? = null,
    val title: String? = null,
    @SerialName("description") val summary: String? = null,
    val categories: List<String>? = null,
    val client_side: Side? = null,
    val server_side: Side? = null,
    @SerialName("body") val description: String? = null,
    val additional_categories: List<String>? = null,
    val issues_url: String? = null,
    val source_url: String? = null,
    val wiki_url: String? = null,
    val discord_url: String? = null,
    val donation_urls: List<DonationUrl>? = null,
    val license_id: String? = null,
    val license_url: String? = null
) {
    // Responses
    @Serializable
    data class BadRequest(
        val error: String,
        val description: String
    )
}

/**
 * Responses:
 * 204: Successful
 * 400: Bad request
 * 401: Incorrect token scopes or no authorization
 */
@Serializable
data class DeleteIcon(
    val idOrSlug: String
) {
    // Responses
    @Serializable
    data class BadRequest(
        val error: String,
        val description: String
    )

    @Serializable
    data class Unauthorized(
        val error: String,
        val description: String
    )
}

/**
 * @param icon Allowed values: png, jpg, jpeg, bmp, gif, webp, svg, svgz, rgb
 *
 * Responses:
 * 204: Successful
 * 400: Bad request
 */
@Serializable
data class ChangeIcon(
    val idOrSlug: String,
    @SerialName("ext") val icon: String
) {
    // Responses
    @Serializable
    data class BadRequest(
        val error: String,
        val description: String
    )
}

// Version Management

/**
 * Responses:
 * 200: Successful. Returns the versions.
 * 404: Not Found, or No Authorization
 */
@Serializable
data class ListVersions(
    val idOrSlug: String,
    val loaders: List<String>,
    val game_versions: List<String>,
    val featured: Boolean
) {
    // Responses
    @Serializable
    data class Ok(
        val files: List<ModrinthVersion>
    )
}

/**
 * Responses:
 * 204: Successful
 * 401: Incorrect token scopes or no authorization
 * 404: Not Found, or No Authorization
 */
@Serializable
data class DeleteVersion(
    val id: String
) {
    // Responses
    @Serializable
    data class BadRequest(
        val error: String,
        val description: String
    )
}

//TODO: Implement version modifying request

/**
 * Responses:
 * 200: Successful. Returns the version info.
 * 400: Bad request
 * 401: Incorrect token scopes or no authorization
 */
@Serializable
data class CreateVersion(
    val data: ModrinthVersionCreate
) {
    // Responses
    @Serializable
    data class BadRequest(
        val error: String,
        val description: String
    )

    @Serializable
    data class Unauthorized(
        val error: String,
        val description: String
    )
}

//TODO: Implement version scheduling
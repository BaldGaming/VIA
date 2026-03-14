package com.example.via

import com.google.gson.annotations.SerializedName // Maps exact JSON keys from Dropbox to Kotlin variables.
import retrofit2.http.Body // Marks the data sent in the request body.
import retrofit2.http.Header // Adds specific headers (like auth tokens) to the API call.
import retrofit2.http.POST // Defines the HTTP method as POST.
import retrofit2.http.Query // Appends data directly to the URL string.

// Holds the main response containing the list of files.
data class DropboxResponse(val entries: List<DropboxEntry>)

// Holds the specific details of a single file from the Dropbox folder.
data class DropboxEntry(
    val name: String,
    @SerializedName("path_display") val pathDisplay: String
)

// Formats the target folder path to send to Dropbox.
data class ListFolderArgs(val path: String = "")

// Formats the specific file path when asking Dropbox for a playable link.
data class TempLinkRequest(val path: String)

// Holds the direct streaming link returned by Dropbox.
data class TempLinkResponse(val link: String)

// Holds the newly refreshed access token data.
data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Int
)

// The interface defining exactly how the app talks to the Dropbox servers.
interface ApiService {

    // Asks Dropbox for a list of all files inside a specific folder.
    @POST("2/files/list_folder")
    suspend fun listFolder(
        @Header("Authorization") token: String,
        @Body args: ListFolderArgs
    ): DropboxResponse

    // Asks Dropbox to generate a temporary, direct streaming link for an audio file.
    @POST("2/files/get_temporary_link")
    suspend fun getTemporaryLink(
        @Header("Authorization") token: String,
        @Body args: TempLinkRequest
    ): TempLinkResponse

    // Trades the permanent refresh token for a fresh, temporary access token.
    @POST("https://api.dropboxapi.com/oauth2/token")
    suspend fun refreshAccessToken(
        @Query("grant_type") grantType: String = "refresh_token",
        @Query("refresh_token") refreshToken: String,
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String
    ): TokenResponse
}
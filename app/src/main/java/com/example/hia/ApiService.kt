package com.example.via

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class DropboxResponse(val entries: List<DropboxEntry>)
data class DropboxEntry(val name: String, val path_display: String)
data class ListFolderArgs(val path: String = "")

// MISSION 21: The Temporary Link Translator
data class TempLinkRequest(val path: String)
data class TempLinkResponse(val link: String)
// The "Translator" for the login response
data class TokenResponse(
    val access_token: String,
    val expires_in: Int
)

interface ApiService {
    @POST("2/files/list_folder")
    suspend fun listFolder(
        @Header("Authorization") token: String,
        @Body args: ListFolderArgs
    ): DropboxResponse

    // The "Get Playable Link" Request
    @POST("2/files/get_temporary_link")
    suspend fun getTemporaryLink(
        @Header("Authorization") token: String,
        @Body args: TempLinkRequest
    ): TempLinkResponse

    @retrofit2.http.POST("https://api.dropboxapi.com/oauth2/token")
    suspend fun refreshAccessToken(
        @retrofit2.http.Query("grant_type") grantType: String = "refresh_token",
        @retrofit2.http.Query("refresh_token") refreshToken: String,
        @retrofit2.http.Query("client_id") clientId: String,
        @retrofit2.http.Query("client_secret") clientSecret: String
    ): TokenResponse
}
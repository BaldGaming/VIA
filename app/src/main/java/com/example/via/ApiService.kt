package com.example.via

import com.google.gson.annotations.SerializedName // Maps exact JSON keys from Dropbox to Kotlin variables.
import retrofit2.http.Body // Marks the data sent in the request body.
import retrofit2.http.Header // Adds specific headers to the API call.
import retrofit2.http.Headers // Adds static headers to the API call.
import retrofit2.http.POST // Defines the HTTP method as POST.
import retrofit2.http.Query // Appends data directly to the URL string.
import retrofit2.Response // Wraps the API result for status checking.
import okhttp3.ResponseBody // Handles raw response data from the server.
import okhttp3.RequestBody // Handles raw request data for file uploads.

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

// Defines the interface for communicating with Dropbox and Azure services.
interface ApiService {

    // Fetches a list of all files inside a specific Dropbox folder.
    @POST("2/files/list_folder")
    suspend fun listFolder(
        @Header("Authorization") token: String,
        @Body args: ListFolderArgs
    ): DropboxResponse

    // Requests a temporary direct streaming link for an audio file.
    @POST("2/files/get_temporary_link")
    suspend fun getTemporaryLink(
        @Header("Authorization") token: String,
        @Body args: TempLinkRequest
    ): TempLinkResponse

    // Exchanges a refresh token for a new temporary access token.
    @POST("https://api.dropboxapi.com/oauth2/token")
    suspend fun refreshAccessToken(
        @Query("grant_type") grantType: String = "refresh_token",
        @Query("refresh_token") refreshToken: String,
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String
    ): TokenResponse

    // Sends text to Azure to be converted into spoken audio bytes.
    @POST("v1")
    suspend fun getAzureTTS(
        @Header("Ocp-Apim-Subscription-Key") apiKey: String,
        @Header("Content-Type") contentType: String = "application/ssml+xml",
        @Header("X-Microsoft-OutputFormat") outputFormat: String = "riff-24khz-16bit-mono-pcm",
        @Body ssml: okhttp3.RequestBody
    ): Response<ResponseBody>

    // Uploads a file (or empty marker) directly to a specified Dropbox path.
    @Headers("Content-Type: application/octet-stream")
    @POST("2/files/upload")
    suspend fun uploadFile(
        @Header("Authorization") token: String,
        @Header("Dropbox-API-Arg") args: String, // Contains the path
        @Body fileBody: RequestBody
    ): Response<Unit>

    // Fetch the "index.txt" file from DropBox
    @POST("2/files/download")
    suspend fun downloadIndex(
        @Header("Authorization") token: String,
        @Header("Dropbox-API-Arg") args: String
    ): retrofit2.Response<okhttp3.ResponseBody>
}
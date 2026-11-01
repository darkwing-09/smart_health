package com.fitagent.data.remote

import com.fitagent.data.prefs.AuthStore
import com.fitagent.data.remote.dto.ChallengeCreateReq
import com.fitagent.data.remote.dto.ChallengeRes
import com.fitagent.data.remote.dto.ChatReq
import com.fitagent.data.remote.dto.ChatRes
import com.fitagent.data.remote.dto.ErrorRes
import com.fitagent.data.remote.dto.JoinReq
import com.fitagent.data.remote.dto.LoginReq
import com.fitagent.data.remote.dto.MessageRes
import com.fitagent.data.remote.dto.PlanRes
import com.fitagent.data.remote.dto.ProfileRes
import com.fitagent.data.remote.dto.ProfileUpdateReq
import com.fitagent.data.remote.dto.RefreshReq
import com.fitagent.data.remote.dto.RegisterReq
import com.fitagent.data.remote.dto.ScheduleDto
import com.fitagent.data.remote.dto.SyncReq
import com.fitagent.data.remote.dto.SyncRes
import com.fitagent.data.remote.dto.TokenRes
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

interface FitAgentApi {
    @POST("v1/auth/register") suspend fun register(@Body body: RegisterReq): TokenRes
    @POST("v1/auth/login") suspend fun login(@Body body: LoginReq): TokenRes

    @GET("v1/me") suspend fun me(): ProfileRes
    @PATCH("v1/me") suspend fun updateMe(@Body body: ProfileUpdateReq): ProfileRes
    @DELETE("v1/me") suspend fun deleteMe(): retrofit2.Response<Unit>
    @POST("v1/me/sync") suspend fun sync(@Body body: SyncReq): SyncRes
    @PUT("v1/me/schedule") suspend fun putSchedule(@Body body: ScheduleDto): ScheduleDto

    @POST("v1/coach/chat") suspend fun chat(@Body body: ChatReq): ChatRes
    @GET("v1/coach/messages") suspend fun messages(@Query("limit") limit: Int = 100): List<MessageRes>
    @DELETE("v1/coach/messages") suspend fun clearMessages(): retrofit2.Response<Unit>
    @POST("v1/coach/plan") suspend fun generatePlan(): PlanRes
    @GET("v1/coach/plan") suspend fun latestPlan(): retrofit2.Response<PlanRes>

    @GET("v1/challenges") suspend fun challenges(): List<ChallengeRes>
    @POST("v1/challenges") suspend fun createChallenge(@Body body: ChallengeCreateReq): ChallengeRes
    @POST("v1/challenges/join") suspend fun join(@Body body: JoinReq): ChallengeRes
    @GET("v1/challenges/{id}") suspend fun challenge(@Path("id") id: String): ChallengeRes
    @DELETE("v1/challenges/{id}/membership") suspend fun leave(@Path("id") id: String): retrofit2.Response<Unit>
}

val gson: Gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

/** Builds (and rebuilds when the server URL changes) the Retrofit client with token handling. */
class ApiClient(private val auth: AuthStore) {
    @Volatile private var cached: Pair<String, FitAgentApi>? = null

    val api: FitAgentApi
        get() {
            val url = auth.serverUrl.value
            cached?.let { (u, api) -> if (u == url) return api }
            return synchronized(this) {
                val api = build(url)
                cached = url to api
                api
            }
        }

    private fun build(baseUrl: String): FitAgentApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // the AI coach can take a while
            .addInterceptor { chain ->
                val token = auth.accessToken
                val req = if (token != null) chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                else chain.request()
                chain.proceed(req)
            }
            .authenticator(TokenRefresher(baseUrl))
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(FitAgentApi::class.java)
    }

    /** On 401, exchanges the refresh token once and retries; if that fails the user is signed out. */
    private inner class TokenRefresher(private val baseUrl: String) : Authenticator {
        private val plain = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.url.encodedPath.contains("/v1/auth/")) return null
            if (response.priorResponse != null) return null // already retried once
            synchronized(this@ApiClient) {
                val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
                val current = auth.accessToken
                if (current != null && current != sent) {
                    return response.request.newBuilder().header("Authorization", "Bearer $current").build()
                }
                val refresh = auth.refreshToken ?: return null
                val body = gson.toJson(RefreshReq(refresh)).toRequestBody("application/json".toMediaType())
                val refreshed = runCatching {
                    plain.newCall(Request.Builder().url(baseUrl + "v1/auth/refresh").post(body).build()).execute()
                }.getOrNull() ?: return null
                refreshed.use {
                    if (it.code == 401) { auth.signOut(); return null }
                    if (!it.isSuccessful) return null
                    val tokens = gson.fromJson(it.body?.string(), TokenRes::class.java)
                    auth.updateTokens(tokens.accessToken, tokens.refreshToken)
                    return response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
                }
            }
        }
    }
}

/** Turns any failure into a sentence a student can act on. */
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> {
        val detail = runCatching {
            gson.fromJson(response()?.errorBody()?.string(), ErrorRes::class.java)?.detail
        }.getOrNull()
        when {
            detail is String -> detail
            code() == 401 -> "Your session expired — please sign in again."
            code() == 422 -> "Some details look invalid. Please check and try again."
            code() == 429 -> "You're going a bit fast — try again in a minute."
            code() >= 500 -> "The server had a problem. Please try again shortly."
            else -> "Request failed (${code()})."
        }
    }
    is IOException -> "Can't reach the FitAgent server. Check your connection or the server address."
    else -> message ?: "Something went wrong."
}

package com.liferecorder.upload

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.tasks.await

/**
 * Google Play 서비스의 AuthorizationClient로 Drive 액세스 토큰을 받는다.
 * 토큰 갱신은 Play 서비스가 알아서 하므로 앱은 리프레시 토큰을 보관하지 않는다.
 * Cloud Console에 이 앱의 패키지명 + 서명 SHA-1로 Android OAuth 클라이언트가 등록돼 있어야 한다.
 */
object DriveAuth {
    private const val TAG = "DriveAuth"
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** UI 없이 토큰을 얻는다. 아직 사용자 동의가 없으면 null. */
    suspend fun silentToken(ctx: Context): String? = try {
        val r = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        if (r.hasResolution()) null else r.accessToken
    } catch (e: Exception) {
        Log.w(TAG, "silent authorize failed: ${e.message}")
        null
    }

    /** Activity에서 사용. hasResolution()이면 pendingIntent를 띄워 동의를 받는다. */
    fun authorizeTask(ctx: Context): Task<AuthorizationResult> =
        Identity.getAuthorizationClient(ctx).authorize(request())

    fun resultFromIntent(ctx: Context, data: Intent): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
}

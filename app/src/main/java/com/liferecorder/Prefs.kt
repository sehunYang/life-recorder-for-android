package com.liferecorder

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("liferecorder", Context.MODE_PRIVATE)

    /** 사용자가 명시적으로 ON을 눌러 둔 상태인지. 프로세스가 죽어도 유지된다. */
    fun isRecordingEnabled(ctx: Context) = sp(ctx).getBoolean("recording_enabled", false)
    fun setRecordingEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("recording_enabled", v).apply()

    fun wasScreenRecording(ctx: Context) = sp(ctx).getBoolean("screen_was_on", false)
    fun setScreenWasRecording(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("screen_was_on", v).apply()

    fun isWifiOnly(ctx: Context) = sp(ctx).getBoolean("wifi_only", true)
    fun setWifiOnly(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("wifi_only", v).apply()

    /** 자동 업로드를 충전 중일 때만 돌린다 (모뎀 전력을 배터리로 쓰지 않도록). 수동 "지금 업로드"는 무시한다. */
    fun isUploadOnlyCharging(ctx: Context) = sp(ctx).getBoolean("upload_only_charging", true)
    fun setUploadOnlyCharging(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("upload_only_charging", v).apply()

    /** 삼성 통화 녹음 파일도 함께 올릴지. */
    fun isIncludeCalls(ctx: Context) = sp(ctx).getBoolean("include_calls", true)
    fun setIncludeCalls(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("include_calls", v).apply()

    fun importedCallIds(ctx: Context): Set<String> =
        sp(ctx).getStringSet("imported_call_ids", emptySet()) ?: emptySet()
    fun setImportedCallIds(ctx: Context, ids: Set<String>) =
        sp(ctx).edit().putStringSet("imported_call_ids", HashSet(ids)).apply()

    /** 카메라로 찍은 사진·동영상도 함께 올릴지. */
    fun isIncludeCamera(ctx: Context) = sp(ctx).getBoolean("include_camera", false)
    fun setIncludeCamera(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("include_camera", v).apply()

    fun importedCameraIds(ctx: Context): Set<String> =
        sp(ctx).getStringSet("imported_camera_ids", emptySet()) ?: emptySet()
    fun setImportedCameraIds(ctx: Context, ids: Set<String>) =
        sp(ctx).edit().putStringSet("imported_camera_ids", HashSet(ids)).apply()

    /** 문자 메시지를 하루치 JSONL로 올릴지. */
    fun isIncludeSms(ctx: Context) = sp(ctx).getBoolean("include_sms", true)
    fun setIncludeSms(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("include_sms", v).apply()

    /** 마지막으로 내보낸 날짜 (yyyy-MM-dd). 이 다음 날부터 어제까지를 내보낸다. */
    fun smsLastExportDay(ctx: Context): String? = sp(ctx).getString("sms_last_day", null)
    fun setSmsLastExportDay(ctx: Context, day: String) = sp(ctx).edit().putString("sms_last_day", day).apply()

    /** 카카오톡 알림을 기록해 하루치 JSONL로 올릴지. */
    fun isIncludeKakao(ctx: Context) = sp(ctx).getBoolean("include_kakao", true)
    fun setIncludeKakao(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("include_kakao", v).apply()

    /** 진단용. 카카오톡 알림 원본을 통째로 별도 파일에 덤프한다. 용량이 크니 평소엔 끈다. */
    fun isKakaoDump(ctx: Context) = sp(ctx).getBoolean("kakao_dump", false)
    fun setKakaoDump(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("kakao_dump", v).apply()

    fun folderId(ctx: Context, key: String): String? = sp(ctx).getString("folder_$key", null)
    fun setFolderId(ctx: Context, key: String, id: String?) =
        sp(ctx).edit().putString("folder_$key", id).apply()
    fun clearFolderIds(ctx: Context) {
        val e = sp(ctx).edit()
        sp(ctx).all.keys.filter { it.startsWith("folder_") }.forEach { e.remove(it) }
        e.apply()
    }

    fun uploadSession(ctx: Context, fileName: String): String? =
        sp(ctx).getString("session_$fileName", null)
    fun setUploadSession(ctx: Context, fileName: String, uri: String?) =
        sp(ctx).edit().putString("session_$fileName", uri).apply()

    /** 더 이상 존재하지 않는 파일의 업로드 세션을 지운다. */
    fun pruneUploadSessions(ctx: Context, existingFileNames: Set<String>) {
        val e = sp(ctx).edit()
        sp(ctx).all.keys
            .filter { it.startsWith("session_") && it.removePrefix("session_") !in existingFileNames }
            .forEach { e.remove(it) }
        e.apply()
    }
}

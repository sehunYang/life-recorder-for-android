package com.liferecorder

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 파일 배치와 이름 규칙.
 *
 *  audio/audio_<시각>.aac.part   녹음 중 (ADTS: 프로세스가 죽어도 그 시점까지 재생 가능)
 *  audio/audio_<시각>.m4a         녹음 완료 후 remux된 업로드 대상
 *  screen/screen_<시각>.mp4.part  녹화 중
 *  screen/screen_<시각>.mp4        업로드 대상
 *  call/call_<시각>_<원본이름>     통화 녹음에서 복사해 온 업로드 대상
 *  kakao/rawkakao_<날짜>.jsonl.part  오늘치 카카오톡 알림 로그 (계속 이어 쓰는 중)
 *  kakao/kakao_<날짜>.jsonl          확정된 알림 로그, 업로드 대상
 *  sms/sms_<날짜>.jsonl              하루치 문자, 업로드 대상
 *
 * `.part`가 붙은 파일은 업로더가 절대 건드리지 않는다.
 */
object Storage {
    private const val TAG = "Storage"
    const val PART = ".part"

    /** 외부 저장소가 마운트되지 않은 경우엔 내부 저장소로 떨어진다 (상대 경로가 되는 것보다 낫다). */
    private fun baseDir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    fun audioDir(ctx: Context): File = File(baseDir(ctx), "audio").apply { mkdirs() }
    fun screenDir(ctx: Context): File = File(baseDir(ctx), "screen").apply { mkdirs() }
    fun callDir(ctx: Context): File = File(baseDir(ctx), "call").apply { mkdirs() }
    fun smsDir(ctx: Context): File = File(baseDir(ctx), "sms").apply { mkdirs() }
    fun cameraDir(ctx: Context): File = File(baseDir(ctx), "camera").apply { mkdirs() }

    /** 무엇을 언제 올렸는지 남기는 수집 기록. `rawindex_*`는 오늘치라 업로드하지 않는다. */
    fun indexDir(ctx: Context): File = File(baseDir(ctx), "index").apply { mkdirs() }
    fun kakaoDir(ctx: Context): File = File(baseDir(ctx), "kakao").apply { mkdirs() }

    /**
     * 카카오톡 알림에 실려 온 사진을 받아 두는 곳.
     * 알림의 dataUri는 알림이 살아 있는 동안만 읽을 수 있어서 그 자리에서 복사해야 한다.
     */
    fun kakaoMediaDir(ctx: Context): File = File(baseDir(ctx), "kakaomedia").apply { mkdirs() }

    /** 접근성 서비스가 화면에서 읽은 글자. `rawscreentext_*`는 오늘치라 업로드하지 않는다. */
    fun screenTextDir(ctx: Context): File = File(baseDir(ctx), "screentext").apply { mkdirs() }

    /** 어느 앱이 앞에 떠 있었는지의 하루치 기록. */
    fun appDir(ctx: Context): File = File(baseDir(ctx), "app").apply { mkdirs() }

    private fun allDirs(ctx: Context) =
        listOf(
            audioDir(ctx), screenDir(ctx), callDir(ctx), smsDir(ctx), kakaoDir(ctx), kakaoMediaDir(ctx),
            screenTextDir(ctx), cameraDir(ctx), indexDir(ctx), appDir(ctx),
        )
    private fun listAll(ctx: Context): List<File> =
        allDirs(ctx).flatMap { d -> d.listFiles()?.toList() ?: emptyList() }.filter { it.isFile }

    fun newAudioPart(ctx: Context, startMs: Long): File =
        File(audioDir(ctx), "audio_${SegmentClock.stamp(startMs)}.aac$PART")

    fun newScreenPart(ctx: Context, startMs: Long): File =
        File(screenDir(ctx), "screen_${SegmentClock.stamp(startMs)}.mp4$PART")

    /** 업로드 대기 중인 완성 파일. 오래된 것부터. */
    fun finishedFiles(ctx: Context): List<File> =
        listAll(ctx)
            .filter { f -> !f.name.endsWith(PART) && f.length() > 0 }
            // "audio_"/"screen_"/"call_" 접두어를 뗀 시각 부분으로 정렬해 종류가 시간순으로 섞이게 한다.
            .sortedBy { f -> f.name.substringAfter('_') }

    /**
     * 작은 글자 자료인가 — 카톡 · 화면 글자 · 앱 사용 · 문자의 JSONL. 이것만은 모바일 데이터로도 올린다
     * (`Prefs.isTextAnyNetwork`). 진단 덤프(`kakao_dump_`)와 수동 내보내기(`kakao_…_export_`)는 크기를 몰라 뺀다.
     */
    fun isSmallText(f: File): Boolean {
        val n = f.name
        if (!n.endsWith(".jsonl")) return false
        if (n.startsWith("kakao_dump_") || n.contains("_export_")) return false
        return n.startsWith("kakao_") || n.startsWith("screentext_") || n.startsWith("app_") ||
            n.startsWith("sms_") || n.startsWith("diag_")
    }

    /** 파일 이름 접두어로 Drive 폴더 키를 정한다. */
    fun folderKeyOf(f: File): String = when {
        f.name.startsWith("screen_") -> "screen"
        f.name.startsWith("call_") -> "call"
        f.name.startsWith("camera_") -> "camera"
        f.name.startsWith("sms_") -> "sms"
        f.name.startsWith("index_") -> "index"
        f.name.startsWith("app_") -> "app"
        // kakaoimg_ = 알림에서 받은 사진, kakaoexp_ = 내보내기 폴더에서 가져온 미디어.
        f.name.startsWith("kakaoimg_") || f.name.startsWith("kakaoexp_") -> "kakaomedia"
        f.name.startsWith("screentext_") -> "screentext"
        f.name.startsWith("diag_") -> "index"          // 녹음 진단 기록 (DiagLog)
        f.name.startsWith("kakao_") -> "kakao"
        else -> "audio"
    }

    /** 사람이 읽는 파일 크기. */
    fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b > 0 -> "${b / 1024} KB"
        else -> "0"
    }

    fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "md" -> "text/markdown"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "jsonl" -> "application/x-ndjson"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "amr" -> "audio/amr"
        "3gp", "3gpp" -> "audio/3gpp"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** `.part`를 떼어 완성 파일로 만든다. */
    fun finishPart(part: File): File {
        val done = File(part.parentFile, part.name.removeSuffix(PART))
        if (done.exists()) done.delete()
        if (!part.renameTo(done)) Log.w(TAG, "rename failed: ${part.name}")
        return done
    }

    /**
     * 녹음이 끝난 .aac.part를 .m4a로 remux한다. 실패하면 .aac 그대로 완성 파일로 둔다.
     * 중간 상태가 업로더에 노출되지 않도록 항상 .part 이름으로 작업한 뒤 마지막에 rename한다.
     */
    fun finalizeAudioPart(part: File): File? {
        if (!part.exists()) return null
        if (part.length() == 0L) { part.delete(); return null }
        val base = part.name.removeSuffix(PART).removeSuffix(".aac")
        val m4aPart = File(part.parentFile, "$base.m4a$PART")
        val ok = try { AudioRemux.aacToM4a(part, m4aPart) } catch (e: Exception) {
            Log.w(TAG, "remux failed for ${part.name}", e); false
        }
        return if (ok && m4aPart.length() > 0) {
            // 완성본을 먼저 확정하고 원본을 지운다. 이 사이에 죽어도 .m4a는 이미 살아 있다.
            val done = finishPart(m4aPart)
            part.delete()
            done
        } else {
            m4aPart.delete()
            finishPart(part)
        }
    }

    /**
     * 프로세스 시작 직후(Application.onCreate, 아직 어떤 세션도 없을 때) 동기로 호출해서
     * 남아 있는 .part 파일 목록을 찍는다. 이 시점의 .part는 전부 죽은 파일이다.
     * 목록만 찍고 실제 처리는 [processLeftovers]로 백그라운드에서 한다.
     */
    fun snapshotLeftovers(ctx: Context): List<File> = listAll(ctx).filter { f -> f.name.endsWith(PART) }

    private val recoveryLock = Any()

    /**
     *  - audio .m4a.part  → remux 도중 죽은 것. 원본 .aac.part가 남아 있으니 지운다
     *  - audio .aac.part  → remux해서 살린다 (ADTS라 재생 가능)
     *  - screen .mp4.part → moov가 없어 복구 불가. 지운다
     *  - call  *.part     → 복사 도중 죽은 것. 원본이 그대로 있으니 지우면 다음에 다시 가져온다
     *  - kakao_*.part     → 확정/복사 도중 죽은 것. 다음 실행에서 다시 만들어진다
     *  - sms_*.part       → 쓰다가 죽은 것. 해당 날짜를 다시 내보내면 된다
     *  - app_*.part       → 같음
     *
     * `rawkakao_*`(알림 로그) · `rawscreentext_*`(화면 글자) · `rawindex_*`(수집 기록)의 `.jsonl.part`는
     * 계속 이어 쓰는 오늘치 파일이라 절대 건드리지 않는다.
     */
    fun processLeftovers(files: List<File>) = synchronized(recoveryLock) {
        files.filter { it.name.endsWith(".m4a$PART") && it.name.startsWith("audio_") }.forEach { it.delete() }
        files.filter { it.name.endsWith(".aac$PART") }.forEach { f ->
            if (f.exists()) { Log.i(TAG, "recovering ${f.name}"); finalizeAudioPart(f) }
        }
        files.filter {
            it.name.startsWith("screen_") || it.name.startsWith("call_") || it.name.startsWith("camera_") ||
                it.name.startsWith("sms_") || it.name.startsWith("kakao_") || it.name.startsWith("screentext_") ||
                it.name.startsWith("kakaoimg_") || it.name.startsWith("kakaoexp_") ||
                it.name.startsWith("index_") || it.name.startsWith("app_")
        }.forEach { f ->
            if (f.exists()) { Log.i(TAG, "dropping incomplete ${f.name}"); f.delete() }
        }
    }
}

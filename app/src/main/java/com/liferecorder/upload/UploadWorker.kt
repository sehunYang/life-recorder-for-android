package com.liferecorder.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liferecorder.Config
import com.liferecorder.Notifications
import com.liferecorder.Prefs
import com.liferecorder.RecorderState
import com.liferecorder.Storage
import com.liferecorder.kakao.KakaoLog
import com.liferecorder.kakao.KakaoNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 완성된 파일을 오래된 것부터 Drive에 올리고, 크기/MD5가 맞으면 로컬에서 지운다.
 * 청크 단위 재개가 가능하므로 도중에 끊겨도 다음 실행에서 이어 올린다.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 주기 작업과 즉시 작업이 겹쳐도 같은 파일을 두 번 올리지 않도록 프로세스 내 잠금.
        if (!lock.tryLock()) return@withContext Result.success()
        try { run() } finally { lock.unlock() }
    }

    private suspend fun run(): Result {
        val ctx = applicationContext
        importCallRecordings(ctx)
        exportSms(ctx)
        finalizeKakao(ctx)
        RecorderState.refreshPending(ctx)
        val files = Storage.finishedFiles(ctx)
        Prefs.pruneUploadSessions(ctx, files.map { it.name }.toSet())
        if (files.isEmpty()) return Result.success()

        val token = DriveAuth.silentToken(ctx)
        if (token == null) {
            Notifications.showDriveLoginNeeded(ctx)
            RecorderState.update { it.copy(driveLinked = false, lastUploadError = "Google 계정 연결 필요") }
            return Result.retry()
        }
        RecorderState.update { it.copy(driveLinked = true) }
        Notifications.cancel(ctx, Notifications.ID_DRIVE)
        val client = DriveClient(token)

        try {
            val folders = ensureFolders(ctx, client)
            while (!isStopped) {
                val file = Storage.finishedFiles(ctx).firstOrNull() ?: break
                RecorderState.update { it.copy(uploading = file.name, lastUploadError = null) }
                val done = uploadOne(ctx, client, file, folders)
                if (!done) break
                RecorderState.update { it.copy(uploading = null, lastUploadAt = System.currentTimeMillis()) }
                RecorderState.refreshPending(ctx)
            }
            RecorderState.update { it.copy(uploading = null) }
            return if (isStopped) Result.retry() else Result.success()
        } catch (e: DriveClient.AuthException) {
            RecorderState.update { it.copy(uploading = null, driveLinked = false, lastUploadError = e.message) }
            return Result.retry()
        } catch (e: DriveClient.NotFoundException) {
            // 사용자가 Drive에서 폴더를 지웠을 수 있다. 캐시를 비우고 다음에 다시 만든다.
            Log.w(TAG, "folder missing, clearing cache", e)
            Prefs.clearFolderIds(ctx)
            RecorderState.update { it.copy(uploading = null, lastUploadError = e.message) }
            return Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            RecorderState.update { it.copy(uploading = null, lastUploadError = e.message ?: e.toString()) }
            return Result.retry()
        }
    }

    /** 삼성 통화 녹음을 앱 폴더로 복사해 대기열에 넣는다. 실패해도 나머지 업로드는 계속한다. */
    private fun importCallRecordings(ctx: Context) {
        if (!Prefs.isIncludeCalls(ctx)) {
            RecorderState.update { it.copy(callImportNote = null) }
            return
        }
        if (!CallRecordingImporter.hasPermission(ctx)) {
            RecorderState.update { it.copy(callImportNote = "오디오 읽기 권한 필요") }
            return
        }
        try {
            val n = CallRecordingImporter.importNew(ctx)
            RecorderState.update { it.copy(callImportNote = if (n > 0) "${n}개 새로 가져옴" else "새 파일 없음") }
        } catch (e: Exception) {
            Log.w(TAG, "call import failed", e)
            RecorderState.update { it.copy(callImportNote = "가져오기 실패: ${e.message}") }
        }
    }

    /** 어제까지의 문자를 하루 단위 JSONL로 그대로 내보낸다. 실패해도 나머지 업로드는 계속한다. */
    private fun exportSms(ctx: Context) {
        if (!Prefs.isIncludeSms(ctx)) {
            RecorderState.update { it.copy(smsExportNote = null) }
            return
        }
        if (!SmsExporter.hasPermission(ctx)) {
            RecorderState.update { it.copy(smsExportNote = "문자 읽기 권한 필요") }
            return
        }
        try {
            val n = SmsExporter.exportPending(ctx)
            RecorderState.update { it.copy(smsExportNote = if (n > 0) "${n}일치 새로 만듦" else "새 날짜 없음") }
        } catch (e: Exception) {
            Log.w(TAG, "sms export failed", e)
            RecorderState.update { it.copy(smsExportNote = "내보내기 실패: ${e.message}") }
        }
    }

    /** 날이 지난 카카오톡 알림 로그를 업로드 대상으로 확정한다. 내용은 손대지 않는다. */
    private fun finalizeKakao(ctx: Context) {
        if (!Prefs.isIncludeKakao(ctx)) {
            RecorderState.update { it.copy(kakaoNote = null) }
            return
        }
        if (!KakaoNotificationListener.isEnabled(ctx)) {
            RecorderState.update { it.copy(kakaoNote = "알림 접근 권한 필요") }
            return
        }
        try {
            val n = KakaoLog.finalizeCompletedDays(ctx)
            RecorderState.update { it.copy(kakaoNote = if (n > 0) "${n}일치 확정" else "새 날짜 없음") }
        } catch (e: Exception) {
            Log.w(TAG, "kakao finalize failed", e)
            RecorderState.update { it.copy(kakaoNote = "확정 실패: ${e.message}") }
        }
    }

    private fun ensureFolders(ctx: Context, client: DriveClient): Map<String, String> {
        val root = Prefs.folderId(ctx, "root")
            ?: client.ensureFolder(Config.DRIVE_ROOT_FOLDER, null).also { Prefs.setFolderId(ctx, "root", it) }
        val audio = Prefs.folderId(ctx, "audio")
            ?: client.ensureFolder(Config.DRIVE_AUDIO_FOLDER, root).also { Prefs.setFolderId(ctx, "audio", it) }
        val screen = Prefs.folderId(ctx, "screen")
            ?: client.ensureFolder(Config.DRIVE_SCREEN_FOLDER, root).also { Prefs.setFolderId(ctx, "screen", it) }
        val call = Prefs.folderId(ctx, "call")
            ?: client.ensureFolder(Config.DRIVE_CALL_FOLDER, root).also { Prefs.setFolderId(ctx, "call", it) }
        val sms = Prefs.folderId(ctx, "sms")
            ?: client.ensureFolder(Config.DRIVE_SMS_FOLDER, root).also { Prefs.setFolderId(ctx, "sms", it) }
        val kakao = Prefs.folderId(ctx, "kakao")
            ?: client.ensureFolder(Config.DRIVE_KAKAO_FOLDER, root).also { Prefs.setFolderId(ctx, "kakao", it) }
        val kakaoMedia = Prefs.folderId(ctx, "kakaomedia")
            ?: client.ensureFolder(Config.DRIVE_KAKAO_MEDIA_FOLDER, root).also { Prefs.setFolderId(ctx, "kakaomedia", it) }
        return mapOf(
            "audio" to audio, "screen" to screen, "call" to call,
            "sms" to sms, "kakao" to kakao, "kakaomedia" to kakaoMedia,
        )
    }

    /** true면 완료(로컬 삭제됨), false면 중단 요청으로 멈춘 것. 오류는 예외로 올라간다. */
    private fun uploadOne(ctx: Context, client: DriveClient, file: File, folders: Map<String, String>): Boolean {
        val parent = folders.getValue(Storage.folderKeyOf(file))
        val total = file.length()
        var session = Prefs.uploadSession(ctx, file.name)
        var offset = 0L
        var result: DriveClient.Uploaded? = null

        if (session != null) {
            try {
                when (val p = client.queryStatus(session, total)) {
                    is DriveClient.Progress.Continue -> offset = p.nextOffset
                    is DriveClient.Progress.Done -> result = p.uploaded
                }
            } catch (e: DriveClient.SessionGoneException) {
                session = null
            }
        }
        if (session == null) {
            session = client.startSession(file, parent, Storage.mimeOf(file))
            Prefs.setUploadSession(ctx, file.name, session)
            offset = 0L
        }
        Log.i(TAG, "uploading ${file.name} from $offset/$total")
        while (result == null) {
            if (isStopped) return false
            when (val p = client.uploadChunk(session, file, offset, total)) {
                is DriveClient.Progress.Continue -> {
                    // 서버가 같은 Range만 반복하면 같은 청크를 무한 재전송하게 되므로 끊는다.
                    if (p.nextOffset <= offset) throw IOException("업로드가 진행되지 않음 (offset $offset): ${file.name}")
                    offset = p.nextOffset
                }
                is DriveClient.Progress.Done -> result = p.uploaded
            }
        }

        val uploaded = result
        val sizeOk = uploaded.size == null || uploaded.size == total
        val md5Ok = uploaded.md5 == null || uploaded.md5.equals(md5(file), ignoreCase = true)
        if (!sizeOk || !md5Ok) {
            // 이 세션은 못 쓰니 버리고 다음 실행에서 새로 올린다.
            Prefs.setUploadSession(ctx, file.name, null)
            throw IOException(if (!sizeOk) "업로드 크기 불일치 (${uploaded.size} != $total): ${file.name}" else "업로드 MD5 불일치: ${file.name}")
        }
        // 로컬 삭제 → 세션 정리 순서. 사이에 죽어도 다음 실행의 세션 정리에서 고아 세션이 지워진다.
        if (!file.delete()) Log.w(TAG, "local delete failed: ${file.name}")
        Prefs.setUploadSession(ctx, file.name, null)
        Log.i(TAG, "uploaded ${file.name} -> ${uploaded.id}")
        return true
    }

    private fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private val lock = Mutex()
    }
}

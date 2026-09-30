package com.liferecorder.upload

import android.content.Context
import android.util.Log
import com.liferecorder.HourSlice
import com.liferecorder.Prefs
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Drive에 쌓인 수집 기록(`index/index_*.jsonl`)을 읽어 "이미 올린 것" 목록을 되살린다.
 *
 * 소급 수집이 두 번 돌지 않게 막는 것은 앱 설정에 있는 MediaStore ID 목록인데,
 * 앱을 지우면 그게 통째로 사라진다. 그러면 재설치할 때마다 사진 11GB와 통화 4.4GB가
 * 다시 올라간다. 그 목록을 Drive의 기록에서 복원하는 것이 이 클래스의 일이다.
 *
 * 한 번 성공하면 다시 하지 않는다. 기록이 하나도 없어도(첫 설치) 성공으로 친다.
 */
object IndexRestore {
    private const val TAG = "IndexRestore"

    /**
     * 복원이 끝났으면 true. 네트워크 오류 등으로 실패하면 false를 돌려주고,
     * 호출한 쪽은 **ID 기반 수집기를 이번 실행에서 돌리지 말아야 한다.**
     * 복원 전에 수집기를 돌리면 이미 올린 것을 다시 올린다.
     */
    fun ensureRestored(ctx: Context, client: DriveClient, indexFolderId: String): Boolean {
        if (Prefs.isIndexRestored(ctx)) return true
        return try {
            // 기존 설정이 남아 있으면 그대로 두고 그 위에 합친다 (앱을 지우지 않은 경우).
            val calls = HashSet(Prefs.importedCallIds(ctx))
            val cameras = HashSet(Prefs.importedCameraIds(ctx))
            // 문자는 올라간 파일 이름이 곧 진행 지점이다. 하루 파일이면 그 다음 날 자정, 한 시간 조각이면 다음 정각.
            var smsUntil = Prefs.smsExportedUntil(ctx)
                ?: Prefs.smsLastExportDay(ctx)?.let { sliceEnd(it) }
            var lines = 0

            for (entry in client.listFiles(indexFolderId)) {
                if (!entry.name.startsWith("index_") || !entry.name.endsWith(".jsonl")) continue
                val text = client.downloadText(entry.id)
                for (line in text.lineSequence()) {
                    if (line.isBlank()) continue
                    val o = try { JSONObject(line) } catch (e: Exception) { continue }
                    lines++
                    when (val src = o.optString("src").ifEmpty { null }) {
                        null -> Unit
                        else -> when {
                            src.startsWith("call:") -> calls += src.removePrefix("call:")
                            src.startsWith("camera:") -> cameras += src.removePrefix("camera:")
                        }
                    }
                    val name = o.optString("name")
                    if (name.startsWith("sms_") && name.endsWith(".jsonl")) {
                        val end = sliceEnd(name.removePrefix("sms_").removeSuffix(".jsonl"))
                        if (end != null && (smsUntil == null || end > smsUntil!!)) smsUntil = end
                    }
                }
            }

            Prefs.setImportedCallIds(ctx, calls)
            Prefs.setImportedCameraIds(ctx, cameras)
            smsUntil?.let { Prefs.setSmsExportedUntil(ctx, it) }
            Prefs.setIndexRestored(ctx, true)
            Log.i(TAG, "restored from $lines lines: call=${calls.size} camera=${cameras.size} smsUntil=$smsUntil")
            true
        } catch (e: Exception) {
            // 여기서 실패하면 수집기를 건너뛴다. 다음 실행에서 다시 시도한다.
            Log.w(TAG, "restore failed, skipping importers this run", e)
            false
        }
    }

    /** `2026-09-29`(하루) → 다음 날 자정, `2026-09-30_h13`(한 시간) → 14:00. 다른 이름이면 null. */
    private fun sliceEnd(key: String): Long? = runCatching {
        when (key.length) {
            10 -> HourSlice.addDays(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!.time, 1)
            14 -> SimpleDateFormat("yyyy-MM-dd'_h'HH", Locale.US).parse(key)!!.time + 3_600_000L
            else -> null
        }
    }.getOrNull()
}

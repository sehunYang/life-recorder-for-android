package com.liferecorder.upload

import android.content.Context
import android.util.Log
import com.liferecorder.Prefs
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import org.json.JSONObject
import java.io.File

/**
 * 수집 기록 기능을 넣기 전에 이미 Drive에 올라가 있던 파일들의 목록을 한 번 남긴다.
 *
 *   index/index_snapshot_<시각>.jsonl
 *
 * 일별 기록(`index_<날짜>.jsonl`)이 "그날 올린 것"이라면, 이것은 **특정 시점의 재고 목록**이다.
 * 이 시점 이전에 무엇이 수집돼 있었는지를 남기는 것이 목적이라, 한 번만 만들고 끝난다.
 *
 * **`src`를 알 수 없다.** 이미 올라간 파일이 어느 MediaStore 항목에서 왔는지는
 * 그때의 앱 설정에만 있었고 기록으로 남지 않았다. 따라서 이 목록은
 * [IndexRestore]의 복원 근거로는 쓸 수 없고, "무엇이 있었나" 기록으로만 쓸 수 있다.
 */
object IndexSnapshot {
    private const val TAG = "IndexSnapshot"

    /** 이미 만들었으면 true. 실패하면 false를 돌려주고 다음 실행에서 다시 시도한다. */
    fun runOnce(ctx: Context, client: DriveClient, folders: Map<String, String>): Boolean {
        if (Prefs.isIndexSnapshotDone(ctx)) return true
        val stamp = SegmentClock.stamp(System.currentTimeMillis())
        val part = File(Storage.indexDir(ctx), "index_snapshot_$stamp.jsonl${Storage.PART}")
        return try {
            var lines = 0
            part.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((key, folderId) in folders) {
                    // 기록 폴더 자신은 세지 않는다.
                    if (key == "index") continue
                    for (e in client.listFiles(folderId)) {
                        val o = JSONObject()
                            .put("kind", "snapshot")
                            .put("t", e.createdMs)
                            .put("name", e.name)
                            .put("folder", key)
                            .put("bytes", e.bytes)
                            .put("driveId", e.id)
                            .put("md5", e.md5 ?: JSONObject.NULL)
                            .put("src", JSONObject.NULL)
                        w.write(o.toString())
                        w.write("\n")
                        lines++
                    }
                }
            }
            if (lines == 0) part.delete() else Storage.finishPart(part)
            Prefs.setIndexSnapshotDone(ctx, true)
            Log.i(TAG, "snapshot written: $lines entries")
            true
        } catch (e: Exception) {
            // 반쪽짜리 목록을 남기면 안 된다. 지우고 다음 실행에서 처음부터 다시 만든다.
            part.delete()
            Log.w(TAG, "snapshot failed", e)
            false
        }
    }
}

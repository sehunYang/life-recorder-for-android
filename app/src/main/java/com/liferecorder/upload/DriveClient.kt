package com.liferecorder.upload

import com.liferecorder.Config
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.math.min

/** Google Drive REST v3 최소 클라이언트: 폴더 보장 + 재개 가능 업로드. */
class DriveClient(private val token: String) {

    class AuthException : IOException("Drive 인증 만료")
    class SessionGoneException : IOException("업로드 세션 만료")
    class NotFoundException(msg: String) : IOException(msg)

    data class Uploaded(val id: String, val size: Long?, val md5: String?)

    sealed class Progress {
        data class Continue(val nextOffset: Long) : Progress()
        data class Done(val uploaded: Uploaded) : Progress()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun authed(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    fun ensureFolder(name: String, parentId: String?): String =
        findFolder(name, parentId) ?: createFolder(name, parentId)

    private fun findFolder(name: String, parentId: String?): String? {
        val parent = parentId ?: "root"
        val q = "name = '${name.replace("'", "\\'")}' and mimeType = '$FOLDER_MIME' and '$parent' in parents and trashed = false"
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "5")
            .build()
        http.newCall(authed(url.toString()).get().build()).execute().use { r ->
            val body = check(r)
            val files = JSONObject(body).getJSONArray("files")
            return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
        }
    }

    private fun createFolder(name: String, parentId: String?): String {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parentId ?: "root"))
        val req = authed("$API/files?fields=id").post(meta.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { r -> return JSONObject(check(r)).getString("id") }
    }

    data class Entry(val id: String, val name: String)

    /** 폴더 안의 파일 목록. 수집 기록(index)을 되읽을 때 쓴다. 페이지를 끝까지 따라간다. */
    fun listFiles(parentId: String): List<Entry> {
        val out = ArrayList<Entry>()
        var pageToken: String? = null
        do {
            val b = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$parentId' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,name)")
                .addQueryParameter("pageSize", "1000")
            if (pageToken != null) b.addQueryParameter("pageToken", pageToken)
            http.newCall(authed(b.build().toString()).get().build()).execute().use { r ->
                val o = JSONObject(check(r))
                val files = o.getJSONArray("files")
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    out += Entry(f.getString("id"), f.optString("name"))
                }
                pageToken = o.optString("nextPageToken").ifEmpty { null }
            }
        } while (pageToken != null)
        return out
    }

    /** 파일 하나를 텍스트로 내려받는다. 수집 기록은 작아서 통째로 읽어도 된다. */
    fun downloadText(fileId: String): String {
        val req = authed("$API/files/$fileId?alt=media").get().build()
        http.newCall(req).execute().use { r -> return check(r) }
    }

    /** 재개 가능 업로드 세션을 열고 세션 URI를 돌려준다. */
    fun startSession(file: File, parentId: String, mime: String): String {
        val meta = JSONObject().put("name", file.name).put("parents", JSONArray().put(parentId))
        val req = authed("$UPLOAD/files?uploadType=resumable&fields=id,size,md5Checksum")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { r ->
            check(r)
            return r.header("Location") ?: throw IOException("업로드 세션 응답에 Location이 없음")
        }
    }

    /** 세션이 어디까지 받았는지 묻는다. */
    fun queryStatus(session: String, total: Long): Progress {
        val req = authed(session)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r -> return parseUpload(r) }
    }

    fun uploadChunk(session: String, file: File, offset: Long, total: Long): Progress {
        val len = min(Config.UPLOAD_CHUNK_BYTES.toLong(), total - offset).toInt()
        val bytes = ByteArray(len)
        RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(bytes) }
        val end = offset + len - 1
        val req = authed(session)
            .header("Content-Range", "bytes $offset-$end/$total")
            .put(bytes.toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r -> return parseUpload(r) }
    }

    private fun parseUpload(r: Response): Progress = when (r.code) {
        308 -> {
            val range = r.header("Range")
            val next = if (range == null) 0L else range.substringAfter('-').trim().toLong() + 1
            Progress.Continue(next)
        }
        200, 201 -> {
            val o = JSONObject(r.body?.string() ?: "{}")
            Progress.Done(
                Uploaded(
                    id = o.getString("id"),
                    size = o.optString("size").toLongOrNull(),
                    md5 = o.optString("md5Checksum").ifEmpty { null },
                )
            )
        }
        401 -> throw AuthException()
        404, 410 -> throw SessionGoneException()
        else -> throw IOException("HTTP ${r.code}: ${r.body?.string()?.take(300)}")
    }

    private fun check(r: Response): String {
        val body = r.body?.string() ?: ""
        when {
            r.code == 401 -> throw AuthException()
            r.code == 404 -> throw NotFoundException("HTTP 404: ${body.take(200)}")
            !r.isSuccessful -> throw IOException("HTTP ${r.code}: ${body.take(300)}")
        }
        return body
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}

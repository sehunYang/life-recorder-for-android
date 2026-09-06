package com.liferecorder.upload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.liferecorder.Config
import com.liferecorder.Prefs
import com.liferecorder.Storage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 문자 메시지(SMS + MMS)를 하루 단위 JSONL로 그대로 내보낸다.
 * 한 줄에 메시지 하나. 해석하거나 묶지 않는다. 그건 내려받은 뒤에 할 일이다.
 *
 *   sms/sms_yyyy-MM-dd.jsonl
 *   {"t":1788500640000,"kind":"sms","box":1,"incoming":true,"thread":12,
 *    "address":"01012345678","name":"홍길동","body":"안녕"}
 *
 * MMS는 본문 텍스트를 `body`에, 첨부의 콘텐츠 타입 목록을 `attachments`에 담는다.
 * RCS 채팅(삼성/Google 메시지의 "채팅")은 시스템이 제3자 앱에 열어주지 않아 포함되지 않는다.
 */
object SmsExporter {
    private const val TAG = "SmsExporter"

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasContacts(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** 새로 쓴 날짜 파일 수. 오늘은 아직 안 끝났으므로 어제까지만 만든다. */
    fun exportPending(ctx: Context): Int {
        if (!hasPermission(ctx)) return 0
        val today = startOfDay(System.currentTimeMillis())
        val last = Prefs.smsLastExportDay(ctx)?.let { runCatching { dayFormat.parse(it)?.time }.getOrNull() }
        var day = if (last != null) addDays(last, 1) else addDays(today, -Config.SMS_BACKFILL_DAYS)
        var count = 0
        val names = HashMap<String, String?>()
        while (day < today) {
            val end = addDays(day, 1)
            val rows = (readSms(ctx, day, end, names) + readMms(ctx, day, end, names))
                .sortedBy { it.first }
            if (rows.isNotEmpty()) {
                val part = File(Storage.smsDir(ctx), "sms_${dayFormat.format(Date(day))}.jsonl${Storage.PART}")
                part.writeText(rows.joinToString("\n") { it.second.toString() } + "\n", Charsets.UTF_8)
                Storage.finishPart(part)
                count++
            }
            Prefs.setSmsLastExportDay(ctx, dayFormat.format(Date(day)))
            day = end
        }
        return count
    }

    private fun readSms(
        ctx: Context,
        start: Long,
        end: Long,
        names: MutableMap<String, String?>,
    ): List<Pair<Long, JSONObject>> {
        val out = ArrayList<Pair<Long, JSONObject>>()
        val proj = arrayOf(
            Telephony.Sms.DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID,
        )
        val sel = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, proj, sel, arrayOf("$start", "$end"), "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(3)
                if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                val t = c.getLong(0)
                val address = c.getString(1) ?: ""
                val o = JSONObject()
                    .put("t", t)
                    .put("kind", "sms")
                    .put("box", type)
                    .put("incoming", type == Telephony.Sms.MESSAGE_TYPE_INBOX)
                    .put("thread", c.getLong(4))
                    .put("address", address)
                    .put("name", nameOf(ctx, address, names) ?: JSONObject.NULL)
                    .put("body", c.getString(2) ?: "")
                out += t to o
            }
        }
        return out
    }

    /** MMS는 date가 초 단위이고, 주소와 본문이 별도 테이블에 있다. */
    private fun readMms(
        ctx: Context,
        start: Long,
        end: Long,
        names: MutableMap<String, String?>,
    ): List<Pair<Long, JSONObject>> {
        val out = ArrayList<Pair<Long, JSONObject>>()
        val proj = arrayOf(
            Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.THREAD_ID,
        )
        val sel = "${Telephony.Mms.DATE} >= ? AND ${Telephony.Mms.DATE} < ?"
        ctx.contentResolver.query(
            Telephony.Mms.CONTENT_URI, proj, sel,
            arrayOf("${start / 1000}", "${end / 1000}"), "${Telephony.Mms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val box = c.getInt(2)
                if (box == Telephony.Mms.MESSAGE_BOX_DRAFTS) continue
                val incoming = box == Telephony.Mms.MESSAGE_BOX_INBOX
                val t = c.getLong(1) * 1000L
                val address = mmsAddress(ctx, id, incoming) ?: ""
                val (body, attachments) = mmsContent(ctx, id)
                val o = JSONObject()
                    .put("t", t)
                    .put("kind", "mms")
                    .put("box", box)
                    .put("incoming", incoming)
                    .put("thread", c.getLong(3))
                    .put("address", address)
                    .put("name", nameOf(ctx, address, names) ?: JSONObject.NULL)
                    .put("body", body)
                    .put("attachments", JSONArray(attachments))
                out += t to o
            }
        }
        return out
    }

    private fun mmsAddress(ctx: Context, mmsId: Long, incoming: Boolean): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        // PduHeaders: 137 = FROM, 151 = TO
        val wantType = if (incoming) 137 else 151
        ctx.contentResolver.query(uri, arrayOf("address", "type"), "type = ?", arrayOf("$wantType"), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(0)
                    if (!a.isNullOrBlank() && a != "insert-address-token") return a
                }
            }
        return null
    }

    /** 본문 텍스트와 첨부 콘텐츠 타입 목록. */
    private fun mmsContent(ctx: Context, mmsId: Long): Pair<String, List<String>> {
        val text = StringBuilder()
        val attachments = ArrayList<String>()
        val uri = Uri.parse("content://mms/part")
        ctx.contentResolver.query(uri, arrayOf("_id", "ct", "text"), "mid = ?", arrayOf("$mmsId"), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val partId = c.getLong(0)
                    val ct = c.getString(1) ?: ""
                    when {
                        ct == "text/plain" -> {
                            val body = c.getString(2) ?: readPartData(ctx, partId)
                            if (body.isNotBlank()) {
                                if (text.isNotEmpty()) text.append('\n')
                                text.append(body)
                            }
                        }
                        ct == "application/smil" -> Unit // 레이아웃 정보라 내용이 아니다
                        ct.isNotBlank() -> attachments += ct
                    }
                }
            }
        return text.toString() to attachments
    }

    private fun readPartData(ctx: Context, partId: Long): String = try {
        ctx.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    } catch (e: Exception) {
        Log.w(TAG, "part read failed $partId", e); ""
    }

    private fun nameOf(ctx: Context, address: String, cache: MutableMap<String, String?>): String? {
        if (address.isBlank()) return null
        return cache.getOrPut(address) { lookupName(ctx, address) }
    }

    private fun lookupName(ctx: Context, address: String): String? {
        if (!hasContacts(ctx) || !address.any { it.isDigit() }) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun addDays(ms: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis
}

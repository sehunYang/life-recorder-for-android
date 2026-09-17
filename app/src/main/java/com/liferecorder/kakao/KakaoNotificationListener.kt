package com.liferecorder.kakao

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.liferecorder.Config
import com.liferecorder.Prefs
import org.json.JSONObject

/**
 * 카카오톡 알림을 가로채 메시지를 그대로 기록한다. 해석하거나 합치지 않는다.
 *
 * MessagingStyle 규약상 보낸 사람(person)이 null이면 기기 주인이 보낸 메시지다.
 * 그 값을 `fromMe`로 그대로 남긴다. 카카오톡이 실제로 내 발화를 실어 보내는지는
 * 진단 덤프(kakao_dump_*.jsonl)로 확인할 수 있다.
 *
 * 알림에 안 뜨는 것은 여기에도 없다. 알림을 끈 방, 방해 금지 시간대,
 * 카카오톡에서 "메시지 내용 표시"를 꺼둔 경우가 그렇다.
 */
class KakaoNotificationListener : NotificationListenerService() {

    /**
     * 연결되는 순간 알림창에 이미 떠 있는 것들을 먼저 훑는다.
     * 앱이 죽었다 살아난 사이에 쌓인 알림, 안 읽고 모아둔 알림을 여기서 건진다.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        KakaoLog.writeListenerEvent(this, "connected")
        try {
            val active = activeNotifications ?: return
            Log.i(TAG, "connected, scanning ${active.size} active notifications")
            for (sbn in active) handleSafely(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "initial scan failed", e)
        }
    }

    /** 시스템이 연결을 끊는 경우가 있어 다시 붙여달라고 요청한다. */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "disconnected, requesting rebind")
        KakaoLog.writeListenerEvent(this, "disconnected")
        try {
            requestRebind(ComponentName(this, KakaoNotificationListener::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handleSafely(sbn)

    private fun handleSafely(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "handle failed", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName != Config.KAKAO_PACKAGE) return
        if (!Prefs.isIncludeKakao(this)) return
        val n = sbn.notification ?: return

        // 진단 모드: 알림 원본을 통째로 별도 파일에 남긴다.
        if (Prefs.isKakaoDump(this)) {
            try {
                val d = KakaoNotificationDump.dump(sbn)
                KakaoLog.writeDump(this, d)
                // logcat은 긴 줄을 자르므로 파일이 원본이다. 여기서는 확인용으로만 찍는다.
                Log.i(DUMP_TAG, d.toString().take(3500))
            } catch (e: Exception) {
                Log.w(TAG, "dump failed", e)
            }
        }

        // 여러 알림을 묶은 요약 알림은 아래 개별 알림과 내용이 겹친다.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: Exception) {
            null
        }

        if (style != null && style.messages.isNotEmpty()) {
            // 카카오톡은 conversationTitle을 채우지 않는다. 그룹채팅에서 android.title은 방 이름이
            // 아니라 그때 말한 사람 이름이라, 이걸 방으로 쓰면 한 방이 사람 수만큼 쪼개진다.
            // 방 식별은 roomId(shortcutId)로 하고, 이름은 확실할 때만 남긴다.
            val room = style.conversationTitle?.toString()
                ?: conversation
                ?: title?.takeIf { !style.isGroupConversation }
            // historicMessages도 같이 쓴다. 채워져 있으면 더 거슬러 올라간 내용이 들어 있다.
            for (m in style.historicMessages) writeMessage(sbn, n, style, room, m, historic = true)
            for (m in style.messages) writeMessage(sbn, n, style, room, m, historic = false)
            return
        }

        // MessagingStyle이 아닌 경우. 요약("메시지 5개")이나 내용 표시를 끈 안내 문구일 수 있다.
        // 임의로 버리지 않고 style=plain으로 표시만 해서 남긴다. 판단은 내려받은 뒤에 한다.
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val record = baseRecord(sbn, n)
            .put("t", sbn.postTime)
            .put("room", conversation ?: title ?: UNKNOWN)
            .put("sender", title ?: UNKNOWN)
            .put("text", text)
            .put("style", KakaoLog.STYLE_PLAIN)
        KakaoLog.write(
            this, sbn.postTime, record,
            "plain|${userOf(sbn)}|${sbn.postTime}|${roomId(sbn, n)}|${text.hashCode()}",
        )
    }

    private fun writeMessage(
        sbn: StatusBarNotification,
        n: Notification,
        style: NotificationCompat.MessagingStyle,
        room: String?,
        m: NotificationCompat.MessagingStyle.Message,
        historic: Boolean,
    ) {
        val text = m.text?.toString().orEmpty()
        val person = m.person
        // MessagingStyle 규약: senderPerson이 null이면 기기 주인이 보낸 것.
        val fromMe = person == null
        val me = style.user
        val sender = person?.name?.toString() ?: me?.name?.toString() ?: ME
        // 카카오톡이 메시지마다 붙이는 고유 번호. 알림이 같은 메시지를 다시 실어 보내도 이 값은 같다.
        // Long 범위가 JS의 안전 정수를 넘으므로 문자열로 남긴다 (roomId와 같은 이유).
        val chatLogId = m.extras.get("chatLogId")?.toString()

        val record = baseRecord(sbn, n)
            .put("t", m.timestamp)
            .put("room", room ?: JSONObject.NULL)
            .put("sender", sender)
            .put("text", text)
            .put("style", KakaoLog.STYLE_MESSAGING)
            .put("fromMe", fromMe)
            .put("historic", historic)
            .put("isGroup", style.isGroupConversation)
            .put("senderKey", person?.key ?: JSONObject.NULL)
            .put("senderUri", person?.uri ?: JSONObject.NULL)
            // 이 알림을 받은 계정의 카카오톡 표시 이름. 듀얼 메신저면 계정마다 다르다.
            .put("me", me?.name?.toString() ?: JSONObject.NULL)
            .put("meKey", me?.key ?: JSONObject.NULL)
            .put("chatLogId", chatLogId ?: JSONObject.NULL)
            .put("dataMimeType", m.dataMimeType ?: JSONObject.NULL)
            .put("dataUri", m.dataUri?.toString() ?: JSONObject.NULL)
            // 사진은 지금 받아 두지 않으면 나중에 못 읽는다. 받아 둔 파일 이름을 같이 남긴다.
            .put("mediaFile", saveMedia(sbn, n, m) ?: JSONObject.NULL)

        // chatLogId가 있으면 그것이 곧 메시지의 정체다. 없을 때만 계정 + 발화 시각 + 방ID + 보낸이 + 본문으로 본다.
        // 방 이름(room)은 알림마다 흔들려서 키에 넣으면 같은 메시지가 여러 번 저장된다.
        val key = if (chatLogId != null) "log|${userOf(sbn)}|${roomId(sbn, n)}|$chatLogId"
        else "msg|${userOf(sbn)}|${m.timestamp}|${roomId(sbn, n)}|${personKey(person)}|${text.hashCode()}"
        KakaoLog.write(this, m.timestamp, record, key)
    }

    /**
     * 사진이 딸려 있으면 받아 둔다. 알림이 살아 있는 지금만 읽을 수 있어서 미루지 않고 여기서 한다.
     * 이미 받아 둔 사진이면 파일을 다시 만들지 않고 이름만 돌려준다.
     */
    private fun saveMedia(
        sbn: StatusBarNotification,
        n: Notification,
        m: NotificationCompat.MessagingStyle.Message,
    ): String? {
        val uri = m.dataUri ?: return null
        val mime = m.dataMimeType.orEmpty()
        // 카카오톡은 "image/"처럼 하위 타입 없이 준다. 사진이 아닌 것은 URI 자체가 오지 않는다.
        if (!mime.startsWith("image", ignoreCase = true)) return null
        return try {
            KakaoMedia.save(this, uri, m.timestamp, userOf(sbn), roomId(sbn, n))
        } catch (e: Exception) {
            Log.w(TAG, "media save failed", e)
            null
        }
    }

    private fun personKey(p: Person?): String =
        p?.key ?: p?.uri ?: p?.name?.toString() ?: "me"

    /**
     * 듀얼 메신저(갤럭시)로 카카오톡을 두 개 쓰면 패키지명은 같고 안드로이드 사용자만 다르다.
     * 기본은 "0", 복제본은 "95" 같은 값이다. sbn.key의 첫 필드가 그 사용자 ID다.
     * 두 계정에 같은 이름의 방이 있어도 섞이지 않도록 따로 남기고 중복 제거 키에도 넣는다.
     */
    private fun userOf(sbn: StatusBarNotification): String =
        sbn.key?.substringBefore('|')?.takeIf { it.isNotEmpty() } ?: "0"

    /**
     * 방을 가리키는 안정적인 값. 카카오톡은 방마다 shortcutId를 주고 tag에도 같은 값을 넣는다.
     * 방 이름과 달리 알림마다 흔들리지 않아서 이것을 방의 정체로 삼는다.
     */
    private fun roomId(sbn: StatusBarNotification, n: Notification): String =
        n.shortcutId ?: sbn.tag ?: "${sbn.packageName}:${sbn.id}"

    /** 방 식별과 중복 제거에 쓸 수 있는 알림 쪽 원본 값들. */
    private fun baseRecord(sbn: StatusBarNotification, n: Notification): JSONObject = JSONObject()
        .put("kind", "message")
        .put("posted", sbn.postTime)
        .put("user", userOf(sbn))
        .put("roomId", roomId(sbn, n))
        .put("key", sbn.key)
        .put("tag", sbn.tag ?: JSONObject.NULL)
        .put("id", sbn.id)
        .put("shortcut", n.shortcutId ?: JSONObject.NULL)
        .put("group", n.group ?: JSONObject.NULL)
        .put("channel", n.channelId ?: JSONObject.NULL)

    companion object {
        private const val TAG = "KakaoListener"
        private const val DUMP_TAG = "KakaoDump"
        private const val UNKNOWN = "(알 수 없음)"
        private const val ME = "나"

        /** 사용자가 설정에서 이 앱에 알림 접근을 허용했는지. */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(ctx.packageName)
        }
    }
}

package com.liferecorder.kakao

import android.app.Notification
import android.app.Person
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person as AndroidXPerson
import org.json.JSONArray
import org.json.JSONObject

/**
 * 카카오톡 알림 하나를 통째로 JSON으로 찍는다. 진단용이라 아무것도 걸러내지 않는다.
 *
 * 이 덤프 하나로 다음이 한 번에 확인된다.
 *  - 내가 보낸 메시지가 EXTRA_MESSAGES에 들어오는지 (sender가 비어 있는 항목이 있는지)
 *  - 사진/이모티콘 등이 어떤 텍스트와 data_mime_type/data_uri로 오는지
 *  - EXTRA_HISTORIC_MESSAGES가 채워지는지, 몇 개까지 오는지
 *  - 방 식별에 쓸 키(shortcut_id, group, key, conversation_title, person key/uri)가 뭐가 있는지
 *  - Message.time이 발화 시각인지 알림이 뜬 시각인지 (posted와 비교)
 */
object KakaoNotificationDump {

    fun dump(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val o = JSONObject()
        o.put("kind", "dump")
        o.put("captured", System.currentTimeMillis())

        o.put("sbn", JSONObject().apply {
            put("key", sbn.key)
            put("id", sbn.id)
            put("tag", sbn.tag ?: JSONObject.NULL)
            put("packageName", sbn.packageName)
            put("postTime", sbn.postTime)
            put("groupKey", sbn.groupKey ?: JSONObject.NULL)
            put("overrideGroupKey", sbn.overrideGroupKey ?: JSONObject.NULL)
            put("isClearable", sbn.isClearable)
            put("isOngoing", sbn.isOngoing)
        })

        o.put("notification", JSONObject().apply {
            put("flags", n.flags)
            put("isGroupSummary", n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            put("category", n.category ?: JSONObject.NULL)
            put("channelId", n.channelId ?: JSONObject.NULL)
            put("group", n.group ?: JSONObject.NULL)
            put("sortKey", n.sortKey ?: JSONObject.NULL)
            put("shortcutId", n.shortcutId ?: JSONObject.NULL)
            put("when", n.`when`)
            put("number", n.number)
            put("tickerText", n.tickerText?.toString() ?: JSONObject.NULL)
        })

        // 이게 원본이다. android.messages / android.messages.historic 배열이 여기 통째로 들어 있다.
        o.put("extras", bundleToJson(n.extras, depth = 0))

        // 우리가 실제로 읽는 경로(AndroidX MessagingStyle)가 무엇을 돌려주는지도 나란히 남긴다.
        o.put("messagingStyle", messagingStyleToJson(n))
        return o
    }

    private fun messagingStyleToJson(n: Notification): Any {
        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: Exception) {
            null
        } ?: return JSONObject.NULL
        return JSONObject().apply {
            put("conversationTitle", style.conversationTitle?.toString() ?: JSONObject.NULL)
            put("isGroupConversation", style.isGroupConversation)
            put("user", personToJson(style.user))
            put("messageCount", style.messages.size)
            put("historicCount", style.historicMessages.size)
            put("messages", JSONArray().apply {
                for (m in style.messages) put(messageToJson(m))
            })
            put("historicMessages", JSONArray().apply {
                for (m in style.historicMessages) put(messageToJson(m))
            })
        }
    }

    private fun messageToJson(m: NotificationCompat.MessagingStyle.Message): JSONObject = JSONObject().apply {
        put("text", m.text?.toString() ?: JSONObject.NULL)
        put("timestamp", m.timestamp)
        // person이 null이면 규약상 기기 주인(= 내가 보낸 메시지)이다.
        put("personIsNull", m.person == null)
        put("person", personToJson(m.person))
        put("dataMimeType", m.dataMimeType ?: JSONObject.NULL)
        put("dataUri", m.dataUri?.toString() ?: JSONObject.NULL)
        put("extras", bundleToJson(m.extras, depth = 0))
    }

    private fun personToJson(p: AndroidXPerson?): Any {
        if (p == null) return JSONObject.NULL
        return JSONObject().apply {
            put("name", p.name?.toString() ?: JSONObject.NULL)
            put("key", p.key ?: JSONObject.NULL)
            put("uri", p.uri ?: JSONObject.NULL)
            put("isBot", p.isBot)
            put("isImportant", p.isImportant)
        }
    }

    private fun frameworkPersonToJson(p: Person): JSONObject = JSONObject().apply {
        put("name", p.name?.toString() ?: JSONObject.NULL)
        put("key", p.key ?: JSONObject.NULL)
        put("uri", p.uri ?: JSONObject.NULL)
        put("isBot", p.isBot)
        put("isImportant", p.isImportant)
    }

    private fun bundleToJson(b: Bundle?, depth: Int): Any {
        if (b == null) return JSONObject.NULL
        if (depth > 4) return "(too deep)"
        val o = JSONObject()
        for (key in b.keySet()) {
            o.put(key, try {
                valueToJson(b.get(key), depth)
            } catch (e: Exception) {
                "(error: ${e.javaClass.simpleName})"
            })
        }
        return o
    }

    private fun valueToJson(v: Any?, depth: Int): Any = when (v) {
        null -> JSONObject.NULL
        is CharSequence -> v.toString()
        is Number, is Boolean -> v
        is Uri -> v.toString()
        is Bundle -> bundleToJson(v, depth + 1)
        is Person -> frameworkPersonToJson(v)
        is Array<*> -> JSONArray().apply { for (e in v) put(valueToJson(e, depth + 1)) }
        is Iterable<*> -> JSONArray().apply { for (e in v) put(valueToJson(e, depth + 1)) }
        is Parcelable -> "(${v.javaClass.name})"
        else -> "(${v.javaClass.name}) $v"
    }
}

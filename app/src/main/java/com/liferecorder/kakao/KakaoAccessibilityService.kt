package com.liferecorder.kakao

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.liferecorder.Config
import com.liferecorder.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 카카오톡 화면에 보이는 글자를 그대로 남긴다. 해석하지 않는다.
 *
 * 알림에는 내가 보낸 메시지, 방을 열어 둔 동안 받은 메시지, 그룹방 이름이 없다.
 * 이 셋은 전부 화면에는 있다. 접근성 서비스는 화면의 뷰 계층을 글자로 주므로 OCR이 아니라 원문이다.
 * 말풍선이 내 것인지 상대 것인지는 노드의 좌표(l·r)에 남아 있으니 내려받은 뒤에 판단한다.
 *
 * 카카오톡 패키지의 이벤트만 받는다(res/xml/kakao_accessibility.xml). 다른 앱 화면은 읽지 않는다.
 * 설정 > 접근성에서 사용자가 직접 켜야 한다.
 */
class KakaoAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    /** 최근에 본 노드(방·글·위치 → 본 시각). 화면이 바뀔 때마다 전체를 다시 쓰지 않고 새로 나타난 것만 남긴다. */
    private val recent = LinkedHashMap<String, Long>(64, 0.75f, true)
    private var lastActivity: String? = null
    private var lastTitle: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        KakaoScreenLog.writeServiceEvent(this, "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        KakaoScreenLog.writeServiceEvent(this, "disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != Config.KAKAO_PACKAGE) return
        if (!Prefs.isIncludeKakaoScreen(this)) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastActivity = event.className?.toString()
        }
        // 글자 하나 바뀔 때마다 이벤트가 수십 개 몰려온다. 잠깐 모았다가 한 번만 읽는다.
        if (scanPending) return
        scanPending = true
        handler.postDelayed({ scanPending = false; scanSafely() }, SCAN_DELAY_MS)
    }

    private fun scanSafely() {
        try {
            scan()
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
        }
    }

    private fun scan() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != Config.KAKAO_PACKAGE) return
        val title = root.window?.title?.toString()
        val now = System.currentTimeMillis()
        expire(now)

        val nodes = JSONArray()
        val rect = Rect()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || visited >= MAX_NODES || depth > MAX_DEPTH) return
            visited++
            val cls = n.className?.toString().orEmpty()
            val text = n.text?.toString() ?: n.contentDescription?.toString()
            // 입력창은 타자 치는 중간 상태가 계속 바뀌어 잡음만 된다. 보낸 뒤 말풍선으로 다시 잡힌다.
            if (!text.isNullOrBlank() && n.isVisibleToUser && !cls.endsWith("EditText")) {
                n.getBoundsInScreen(rect)
                val vid = n.viewIdResourceName
                val key = "$title|$vid|$text|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (recent.put(key, now) == null) {
                    nodes.put(
                        JSONObject()
                            .put("vid", vid?.removePrefix("${Config.KAKAO_PACKAGE}:id/") ?: JSONObject.NULL)
                            .put("cls", cls.substringAfterLast('.'))
                            .put("text", text)
                            .put("l", rect.left).put("t", rect.top).put("r", rect.right).put("b", rect.bottom)
                    )
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)

        if (nodes.length() == 0 && title == lastTitle) return
        lastTitle = title
        KakaoScreenLog.write(
            this, now,
            JSONObject()
                .put("kind", "screen")
                .put("t", now)
                .put("activity", lastActivity ?: JSONObject.NULL)
                .put("title", title ?: JSONObject.NULL)
                .put("nodes", nodes),
        )
    }

    /** 오래된 것부터 지운다. access-order 맵이라 앞쪽이 가장 오래 안 본 것이다. */
    private fun expire(now: Long) {
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > RECENT_TTL_MS) it.remove() else break
        }
    }

    companion object {
        private const val TAG = "KakaoA11y"
        private const val SCAN_DELAY_MS = 700L
        private const val RECENT_TTL_MS = 30_000L
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40

        /** 사용자가 설정 > 접근성에서 이 서비스를 켰는지. */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return flat != null && flat.contains(ctx.packageName) && flat.contains(KakaoAccessibilityService::class.java.simpleName)
        }
    }
}

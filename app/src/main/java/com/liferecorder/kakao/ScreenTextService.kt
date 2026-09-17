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
import com.liferecorder.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 화면에 보이는 글자를 앱을 가리지 않고 그대로 남긴다. 해석하지 않는다.
 *
 * 화면 녹화(mp4)에서 글자를 다시 읽으려면 OCR이 필요하다. 접근성 서비스는 화면의 뷰 계층을
 * 글자로 주므로 OCR 없이 원문이 남는다. 카카오톡이면 알림에 없는 내 발화·열어 둔 방의 메시지·
 * 그룹방 이름이, 다른 앱이면 문서·게시글·거래 내역이 그대로 온다.
 *
 * 무엇을 버릴지는 여기서 정하지 않는다. 내려받은 쪽이 정한다. 예외는 둘뿐이다 —
 * 비밀번호 입력란(시스템이 가리는 것)과 이 앱 자신의 화면.
 *
 * 설정 > 접근성에서 사용자가 직접 켜야 한다.
 */
class ScreenTextService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    /** 최근에 본 노드(앱·창·글·위치 → 본 시각). 화면이 바뀔 때마다 전체를 다시 쓰지 않고 새로 나타난 것만 남긴다. */
    private val recent = LinkedHashMap<String, Long>(256, 0.75f, true)
    private var lastActivity: String? = null
    private var lastPkg: String? = null
    private var lastTitle: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenTextLog.writeServiceEvent(this, "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenTextLog.writeServiceEvent(this, "disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!Prefs.isIncludeScreenText(this)) return
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
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return
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
            // 비밀번호 칸은 시스템이 가린 채로 주지만 그마저 남기지 않는다.
            // 타자 치는 중인 입력창은 글자마다 바뀌어 잡음이 된다. 포커스가 떠난 뒤 한 번에 잡는다.
            val skip = n.isPassword || (cls.endsWith("EditText") && n.isFocused)
            if (!text.isNullOrBlank() && n.isVisibleToUser && !skip) {
                n.getBoundsInScreen(rect)
                val vid = n.viewIdResourceName
                val key = "$pkg|$title|$vid|$text|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (recent.put(key, now) == null) {
                    nodes.put(
                        JSONObject()
                            .put("vid", vid?.substringAfter(":id/") ?: JSONObject.NULL)
                            .put("cls", cls.substringAfterLast('.'))
                            .put("text", text)
                            .put("l", rect.left).put("t", rect.top).put("r", rect.right).put("b", rect.bottom)
                    )
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)

        if (nodes.length() == 0 && pkg == lastPkg && title == lastTitle) return
        lastPkg = pkg
        lastTitle = title
        ScreenTextLog.write(
            this, now,
            JSONObject()
                .put("kind", "screen")
                .put("t", now)
                .put("pkg", pkg)
                .put("activity", lastActivity ?: JSONObject.NULL)
                .put("title", title ?: JSONObject.NULL)
                .put("nodes", nodes),
        )
    }

    /** 오래된 것부터 지운다. access-order 맵이라 앞쪽이 가장 오래 안 본 것이다. */
    private fun expire(now: Long) {
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > RECENT_TTL_MS || recent.size > MAX_RECENT) it.remove() else break
        }
    }

    companion object {
        private const val TAG = "ScreenText"
        private const val SCAN_DELAY_MS = 700L
        private const val RECENT_TTL_MS = 120_000L
        private const val MAX_RECENT = 4000
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40

        /** 사용자가 설정 > 접근성에서 이 서비스를 켰는지. */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return flat != null && flat.contains(ctx.packageName) && flat.contains(ScreenTextService::class.java.simpleName)
        }
    }
}

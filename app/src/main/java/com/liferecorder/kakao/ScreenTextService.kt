package com.liferecorder.kakao

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.liferecorder.Prefs
import com.liferecorder.PrivateScreen
import org.json.JSONArray
import org.json.JSONObject

/**
 * 화면에 보이는 글자를 앱을 가리지 않고 그대로 남긴다. 해석하지 않는다.
 *
 * 화면 녹화(mp4)에서 글자를 다시 읽으려면 OCR이 필요하다. 접근성 서비스는 화면의 뷰 계층을
 * 글자로 주므로 OCR 없이 원문이 남는다. 카카오톡이면 알림에 없는 내 발화·열어 둔 방의 메시지·
 * 그룹방 이름이, 다른 앱이면 문서·게시글·거래 내역이 그대로 온다.
 *
 * 무엇을 버릴지는 여기서 정하지 않는다. 내려받은 쪽이 정한다. 예외는 넷뿐이다 —
 * 비밀번호 입력란(시스템이 가리는 것), 이 앱 자신의 화면, **인증 화면 전체**, 그리고
 * **비공개 앱**(Brave, Chrome 시크릿 탭)이 떠 있는 동안의 화면.
 *
 * 비공개 앱 판정은 화면 녹화도 쓴다 (`PrivateScreen`). 화면 글자 모으기를 꺼 두어도 판정은 돈다.
 *
 * 인증 화면을 통째로 버리는 이유 (2026-09-19 추가): `isPassword` 는 입력란에만 붙는 표시라서,
 * 금융 앱이 버튼으로 그린 PIN 판은 그 그물을 빠져나간다. 뱅크샐러드 PIN 화면에서 섞인 숫자판
 * 0~9가 그대로 올라온 것을 실제로 확인했다. 숫자판만으로 PIN 이 되지는 않지만(어느 자리를
 * 눌렀는지는 모으지 않는다) 남길 값어치가 없고, 나중에 좌표를 함께 모으면 그 순간 PIN 이 된다.
 *
 * 설정 > 접근성에서 사용자가 직접 켜야 한다.
 */
class ScreenTextService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    /** 최근에 본 노드(앱·창·글·위치 → 본 시각). 화면이 바뀔 때마다 전체를 다시 쓰지 않고 새로 나타난 것만 남긴다. */
    private val recent = LinkedHashMap<String, Long>(256, 0.75f, true)
    /** 입력창(앱·뷰 id → 직전 스캔에서 본 글). 두 번 연속 같을 때만 남겨 타자 치는 중간 상태를 거른다. */
    private val editing = HashMap<String, String>()
    private var lastActivity: String? = null
    private var lastPkg: String? = null
    private var lastTitle: String? = null
    private var privatePending = false
    private var windowChangedAt = 0L
    private val recheckPrivate = Runnable { checkPrivateSafely() }
    /**
     * Chrome 이 지금 시크릿 모드인지. 시크릿 **웹 페이지**에는 접근성으로 보이는 표시가 없다
     * (2026-09-26 실측: 툴바·뷰 트리가 일반 탭과 같다). 그래서 모드가 바뀌는 순간만 보고 기억한다 —
     * 시크릿 새 탭 화면, 탭 전환기에서 고른 쪽, "새 시크릿 탭"·"시크릿 탭에서 열기" 누름.
     * Chrome 을 떠났다 돌아와도 같은 탭이 열리므로 떠날 때 지우지 않는다.
     */
    private var chromeIncognito = false
    /** 직전 판정에서 최근 앱 화면에 Brave 카드가 보였는지. */
    private var recentsShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenTextLog.writeServiceEvent(this, "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 판정할 수 없게 되었다. 비공개로 둔 채 남기면 화면 녹화가 영영 멈춘다.
        PrivateScreen.set(this, null)
        ScreenTextLog.writeServiceEvent(this, "disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        watchPrivate(event, pkg)
        if (pkg == packageName) return
        if (!Prefs.isIncludeScreenText(this)) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 대화상자·팝업도 같은 이벤트로 오고 그때 className 은 뷰 클래스다. 액티비티만 기억한다.
            event.className?.toString()?.takeIf { it.endsWith("Activity") }?.let { lastActivity = it }
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && PrivateScreen.reason.value == null) {
            noteScroll(event, pkg)
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
        if (PrivateScreen.reason.value != null) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return
        val title = root.window?.title?.toString()
        val now = System.currentTimeMillis()
        expire(now)

        val nodes = JSONArray()
        val rect = Rect()
        var visited = 0
        // 비밀번호 화면인지 판정할 재료. 한 노드씩 거르는 것으로는 안 되기 때문이다 — 아래 참조.
        var sawPasswordField = false
        var sawAuthVid = false
        val digits = HashSet<String>()
        // 이번 스캔에서 남길 후보. **비밀번호 화면으로 판정되면 통째로 버린다.**
        // `recent` 는 여기서 건드리지 않는다 — 버린 화면을 "이미 봤다" 고 기억하면,
        // 같은 자리에 나중에 들어온 진짜 내용이 조용히 사라진다.
        val pending = ArrayList<Pair<String, JSONObject>>(64)

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || visited >= MAX_NODES || depth > MAX_DEPTH) return
            visited++
            val cls = n.className?.toString().orEmpty()
            val text = n.text?.toString() ?: n.contentDescription?.toString()
            val vid = n.viewIdResourceName
            if (n.isPassword) sawPasswordField = true
            if (vid != null && AUTH_VIDS.any { vid.contains(it, ignoreCase = true) }) sawAuthVid = true
            // 숫자판 버튼은 `contentDescription` 으로 오는 일이 많고, 그때 **뒤에 공백이 붙는다**
            // (2026-09-20 실측: 뱅크샐러드 PIN 판이 `"0 "`~`"9 "`). 다듬지 않으면 길이 1 검사를
            // 빠져나가 이 백스톱이 있으나 마나가 된다. 뷰 id 가 없는 앱에서는 이것만 남는다.
            val one = text?.trim()
            if (one != null && one.length == 1 && one[0] in '0'..'9') digits.add(one)
            // 비밀번호 칸은 시스템이 가린 채로 주지만 그마저 남기지 않는다.
            // 재생 막대(SeekBar)는 1초마다 "3분 중 0분 41초"가 바뀌어 초당 한 줄이 된다. 내용이 아니라 상태다.
            var skip = n.isPassword || cls.endsWith("SeekBar") || cls.endsWith("ProgressBar")
            // 입력창은 글자마다 바뀌어 잡음이 된다. 직전 스캔과 같은 글일 때만(= 타자를 멈췄을 때) 남긴다.
            // 클래스 이름은 믿지 않는다 — 카카오톡 입력창은 MultiAutoCompleteTextView 다.
            if (!skip && n.isEditable && text != null) {
                val prev = editing.put("$pkg|$vid", text)
                if (prev != text) skip = true
            }
            if (!text.isNullOrBlank() && n.isVisibleToUser && !skip) {
                n.getBoundsInScreen(rect)
                val key = "$pkg|$title|$vid|$text|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                pending.add(
                    key to JSONObject()
                        .put("vid", vid?.substringAfter(":id/") ?: JSONObject.NULL)
                        .put("cls", cls.substringAfterLast('.'))
                        .put("text", text)
                        .put("l", rect.left).put("t", rect.top).put("r", rect.right).put("b", rect.bottom)
                )
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)

        // ── 비밀번호 화면은 통째로 버린다 ──────────────────────────────────
        //
        // `isPassword` 만으로는 모자란다. 그것은 **입력란**에 붙는 표시이고, 금융 앱의 PIN 판은
        // 대개 버튼·글자 뷰로 그려진다 (실측 2026-09-18: 뱅크샐러드 PIN 화면에서
        // `passwordCircle1~4` 와 섞인 숫자판 0~9가 그대로 올라왔다).
        //
        // 숫자판만으로 PIN 이 되지는 않는다 — 어느 자리를 눌렀는지는 모으지 않으므로.
        // 그래도 남길 이유가 없고, 나중에 좌표를 함께 모으게 되면 그 순간 PIN 이 된다.
        // **쓸모가 없고 위험이 커질 수 있는 것은 애초에 남기지 않는다.**
        //
        // 세 신호 가운데 하나라도 걸리면 이 화면은 없던 것으로 한다.
        // 화면 글자로는 판정하지 않는다 — `AUTH_VIDS` 설명에 그 이유가 있다.
        val keypad = digits.size >= KEYPAD_DIGITS
        if (sawPasswordField || sawAuthVid || keypad) return

        for ((key, node) in pending) {
            if (recent.put(key, now) == null) nodes.put(node)
        }

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

    /**
     * 창이 바뀌면 곧바로, 브라우저 안의 변화(탭 전환 등)는 잠깐 모아서 비공개 앱 여부를 다시 본다.
     * 창 전환을 기다리지 않는 이유 — 그 사이의 프레임이 영상에 남는다.
     */
    private fun watchPrivate(e: AccessibilityEvent, pkg: String) {
        // Brave 가 보낸 이벤트면 창 목록을 기다리지 않고 바로 가린다. 막 뜬 창은 한동안 root 가 없어
        // 창 목록으로는 늦게 잡힌다 (실측 2026-09-26: 첫 화면 뒤 0.9초, 그 사이 다섯 프레임이 남았다).
        if (BRAVE_PKGS.any { pkg.startsWith(it) } && PrivateScreen.reason.value == null) {
            Log.i(TAG, "private screen: $BRAVE_REASON (event)")
            PrivateScreen.set(this, BRAVE_REASON)
        }
        val windowChanged = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (windowChanged) {
            windowChangedAt = SystemClock.uptimeMillis()
            checkPrivateSafely()
            // 창이 막 바뀐 때는 root 가 아직 없을 수 있다. 조금 뒤에 한 번 더 본다.
            if (!privatePending) {
                privatePending = true
                handler.postDelayed({ privatePending = false; checkPrivateSafely() }, PRIVATE_RECHECK_MS)
            }
            return
        }
        // 최근 앱 화면은 넘기면(스크롤) Brave 카드가 들어오고 나간다. 창은 그대로라 이벤트로 다시 본다.
        if (pkg !in CHROME_PKGS && !recentsShown) return
        if (pkg in CHROME_PKGS && e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) noteChromeClick(e)
        if (privatePending) return
        privatePending = true
        handler.postDelayed({ privatePending = false; checkPrivateSafely() }, PRIVATE_DELAY_MS)
    }

    private fun checkPrivateSafely() {
        try {
            checkPrivate()
        } catch (e: Exception) {
            Log.w(TAG, "private check failed", e)
        }
    }

    /**
     * 화면에 떠 있는 앱 창을 모두 본다 (앞에 있는 창만 보면 알림창·분할 화면 뒤의 브라우저를 놓친다).
     * Brave 는 창이 있기만 하면, Chrome 은 시크릿 표시가 보일 때 비공개다.
     */
    private fun checkPrivate() {
        var reason: String? = null
        recentsShown = false
        // 앱 창인데 아직 내용(root)을 못 읽은 것. 그것이 Brave 일 수 있으니 이때는 풀지 않는다.
        var unknown = false
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root
            val pkg = root?.packageName?.toString()
            if (root == null || pkg == null) { unknown = true; continue }
            if (BRAVE_PKGS.any { pkg.startsWith(it) }) {
                reason = BRAVE_REASON
            } else if (hasBraveTaskCard(root)) {
                recentsShown = true
                if (reason == null) reason = RECENTS_REASON
            } else if (pkg in CHROME_PKGS) {
                chromeIncognitoMode(root)?.let { chromeIncognito = it }
                if (chromeIncognito && reason == null) reason = PrivateScreen.CHROME_INCOGNITO
            }
        }
        // 다만 오래 붙들지는 않는다 — root 를 끝내 주지 않는 창이 있으면 녹화가 영영 멈춘다.
        if (reason == null && unknown && SystemClock.uptimeMillis() - windowChangedAt < UNKNOWN_HOLD_MS) {
            handler.removeCallbacks(recheckPrivate)
            handler.postDelayed(recheckPrivate, PRIVATE_RECHECK_MS)
            return
        }
        if (reason != PrivateScreen.reason.value) {
            Log.i(TAG, "private screen: $reason")
            PrivateScreen.set(this, reason)
        }
    }

    /**
     * Chrome 창에서 모드를 알려 주는 표시를 찾는다. 시크릿이면 true, 일반이면 false, 표시가 없으면 null.
     *
     * - 시크릿 새 탭 화면: 뷰 id `new_tab_incognito_*`
     * - 일반 새 탭 화면: 시크릿 모드 바로가기 버튼 `incognito_button` (일반 모드에만 있다)
     * - 탭 전환기: "시크릿 탭" 칸이 선택돼 있으면 시크릿, 다른 칸이 선택돼 있으면 일반
     *
     * 낱말("시크릿")만으로 판정하지 않는다 — 일반 새 탭 화면에 "시크릿 모드" 버튼이 있다.
     * 웹 본문(WebView 아래)은 보지 않는다.
     */
    private fun chromeIncognitoMode(root: AccessibilityNodeInfo): Boolean? {
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int): Boolean? {
            if (n == null || visited >= MAX_NODES || depth > MAX_DEPTH) return null
            visited++
            if (n.className?.toString() == "android.webkit.WebView") return null
            // 탭을 옮겨도 이전 화면(예: 시크릿 새 탭)이 보이지 않는 채로 트리에 남는다 (2026-09-26 실측).
            // 보이지 않는 노드는 표시로 치지 않는다. 자식은 보일 수 있으니 계속 내려간다.
            if (!n.isVisibleToUser) {
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
                return null
            }
            val vid = n.viewIdResourceName?.substringAfter(":id/")
            if (vid != null && vid.startsWith("new_tab_incognito")) return true
            if (vid == "incognito_button") return false
            val desc = n.contentDescription?.toString()
            if (desc != null && INCOGNITO_PANE.matches(desc.trim())) return n.isSelected
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return walk(root, 0)
    }

    /**
     * 최근 앱 화면에 Brave 카드가 보이는지. 카드에는 앱이 마지막으로 그린 화면이 썸네일로 들어가고,
     * 그 화면은 Brave 창이 아니라 런처 창이라 위의 Brave 판정에 걸리지 않는다.
     * (Chrome 시크릿 탭 썸네일은 Chrome 이 FLAG_SECURE 로 가린다.)
     *
     * 카드는 뷰 id 에 `task` 가 들어가고 설명이 앱 이름이다 — One UI 는 `taskView` 에
     * content-desc "Brave" (2026-09-26 실측). 홈 화면 아이콘도 이름이 "Brave" 라서 id 로 가른다.
     */
    private fun hasBraveTaskCard(root: AccessibilityNodeInfo): Boolean {
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (n == null || visited >= MAX_NODES || depth > MAX_DEPTH) return false
            visited++
            val vid = n.viewIdResourceName?.substringAfter(":id/")
            val desc = n.contentDescription?.toString()
            if (vid != null && vid.contains("task", ignoreCase = true) && desc != null &&
                desc.startsWith("Brave", ignoreCase = true) && n.isVisibleToUser
            ) return true
            for (i in 0 until n.childCount) if (walk(n.getChild(i), depth + 1)) return true
            return false
        }
        return walk(root, 0)
    }

    /** "새 시크릿 탭"·"시크릿 탭에서 열기"를 누르면 시크릿으로 들어간다. 그 뒤 화면에는 표시가 없을 수 있다. */
    private fun noteChromeClick(e: AccessibilityEvent) {
        val said = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "")).trim()
        if (INCOGNITO_OPEN.none { said.contains(it, ignoreCase = true) }) return
        chromeIncognito = true
        checkPrivateSafely()
    }

    private var lastScrollAt = 0L
    private var lastScrollKey: String? = null

    /**
     * 스크롤 한 번을 한 줄로. 화면 글자(무엇이 보였나)만으로는 "끝까지 읽었나·어떤 리듬으로 내렸나"를
     * 모른다. 위치와 전체 길이를 그대로 적고 해석은 소비자가 한다.
     * 플링 한 번에 이벤트가 수십 개 온다. 250ms 안의 것과 값이 같은 것은 버린다.
     */
    private fun noteScroll(e: AccessibilityEvent, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastScrollAt < SCROLL_MIN_GAP_MS) return
        val y = e.scrollY
        val max = e.maxScrollY
        val from = e.fromIndex
        val to = e.toIndex
        val key = "$pkg|$y|$max|$from|$to"
        if (key == lastScrollKey) return
        lastScrollAt = now
        lastScrollKey = key
        ScreenTextLog.write(
            this, now,
            JSONObject()
                .put("kind", "scroll")
                .put("t", now)
                .put("pkg", pkg)
                .put("cls", e.className?.toString()?.substringAfterLast('.') ?: JSONObject.NULL)
                // 웹·스크롤뷰: 픽셀 위치와 끝. 목록(RecyclerView): 보이는 항목 번호와 전체 개수. 없는 쪽은 -1
                .put("y", y).put("max", max).put("dy", e.scrollDeltaY)
                .put("from", from).put("to", to).put("count", e.itemCount),
        )
    }

    /** 오래된 것부터 지운다. access-order 맵이라 앞쪽이 가장 오래 안 본 것이다. */
    private fun expire(now: Long) {
        if (editing.size > 200) editing.clear()
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > RECENT_TTL_MS || recent.size > MAX_RECENT) it.remove() else break
        }
    }

    companion object {
        private const val TAG = "ScreenText"
        private const val SCAN_DELAY_MS = 700L
        private const val SCROLL_MIN_GAP_MS = 250L
        private const val PRIVATE_DELAY_MS = 150L
        private const val PRIVATE_RECHECK_MS = 400L
        private const val UNKNOWN_HOLD_MS = 2_000L
        private const val BRAVE_REASON = "Brave 사용 중"
        private const val RECENTS_REASON = "최근 앱에 Brave"

        /** 창이 떠 있기만 하면 화면 녹화·화면 글자를 멈추는 앱. 베타·나이틀리도 같은 접두어다. */
        private val BRAVE_PKGS = listOf("com.brave.browser")
        /** 시크릿 탭일 때만 멈추는 Chrome (정식·베타·개발·카나리). */
        private val CHROME_PKGS = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
        /** 탭 전환기의 시크릿 칸 이름. 일반 칸은 "일반 탭 3개" 처럼 오므로 겹치지 않는다. */
        private val INCOGNITO_PANE = Regex("""시크릿 탭( \d+개)?|(\d+ )?incognito tabs?""", RegexOption.IGNORE_CASE)
        /** 누르면 시크릿으로 들어가는 메뉴·버튼. */
        private val INCOGNITO_OPEN = listOf("새 시크릿 탭", "시크릿 탭에서", "new incognito tab", "in incognito")
        private const val RECENT_TTL_MS = 120_000L
        private const val MAX_RECENT = 4000
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40

        /**
         * 한 화면에 서로 다른 한 자리 숫자가 이만큼 있으면 숫자판으로 본다.
         *
         * 8로 잡은 이유: PIN 판은 0~9 열 개가 다 있지만, 스크롤·가림으로 몇 개가 안 보일 수 있다.
         * 반대로 평범한 화면에 서로 다른 한 자리 숫자가 여덟 개나 따로 떨어져 있는 일은 드물다.
         * 계산기·전화 키패드도 함께 걸리는데, 그쪽도 남길 값어치가 없으니 문제가 되지 않는다.
         */
        private const val KEYPAD_DIGITS = 8

        /**
         * 인증 화면임을 알려 주는 **뷰 id**. 글자가 아니라 id 로 보는 이유가 있다.
         *
         * 처음에는 화면 글자에서 "비밀번호"·"PIN"·"OTP" 를 찾으려 했는데, 실제 데이터(2026-09-18
         * 7,689 화면)에 대보니 오탐이 압도적이었다 — `PIN` 이 유튜브 채널명 `@CampingCamping9`
         * 에, `OTP` 가 런처의 앱 이름 `나이스OTP` 에, `비밀번호` 가 광고 문자 본문에 걸렸다.
         * 그 문자를 통째로 버리면 진짜 내용을 잃는다.
         *
         * 뷰 id 는 개발자가 붙인 이름이라 사용자 글이 섞이지 않는다. `pin` 만으로는 `spinner`
         * 까지 걸리므로 쓰지 않고, 확실한 조합만 둔다.
         */
        private val AUTH_VIDS = listOf("password", "passcode", "pincode", "pinpad", "keypad")

        /** 사용자가 설정 > 접근성에서 이 서비스를 켰는지. */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return flat != null && flat.contains(ctx.packageName) && flat.contains(ScreenTextService::class.java.simpleName)
        }
    }
}

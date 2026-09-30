package com.liferecorder.upload

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.liferecorder.Config
import com.liferecorder.HourSlice
import com.liferecorder.Prefs
import com.liferecorder.PrivateScreen
import com.liferecorder.Storage
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 어느 앱이 앞에 떠 있었는지를 JSONL로 그대로 내보낸다 — 오늘치는 한 시간 조각, 소급분은 하루 단위. 해석하지 않는다.
 *
 *   app/app_yyyy-MM-dd.jsonl
 *   {"t":1788500640000,"kind":"app","event":"resumed","pkg":"com.kakao.talk",
 *    "cls":"com.kakao.talk.activity.chatroom.ChatRoomActivity","app":"카카오톡"}
 *   {"t":1788500700000,"kind":"app","event":"screen_off","pkg":"android","cls":null,"app":null}
 *
 * 시스템이 남긴 UsageEvents를 읽는 것이라 이 앱이 죽어 있던 동안의 것도 되살아난다.
 * 다만 시스템은 며칠치만 들고 있으므로 오래 안 돌리면 그 앞은 비어 있다.
 * 설정 > 사용 정보 접근에서 이 앱을 허용해야 한다.
 *
 * 비공개 앱은 남기지 않는다. Brave 는 사건 전부를, Chrome 은 시크릿 탭이 떠 있던 구간
 * (`PrivateScreen.intervals`, 앞뒤 [PRIVATE_MARGIN_MS] 여유)의 사건을 버린다.
 * Chrome 은 일반 탭을 쓰다 시크릿으로 넘어가도 액티비티가 그대로라 사건이 없다 —
 * 그때는 그 앞의 resumed 가 남아 사용 시간이 시크릿 구간까지 이어져 보인다.
 */
object AppUsageExporter {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val BRAVE_PKGS = listOf("com.brave.browser")
    private val CHROME_PKGS = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
    /** 시크릿 판정은 Chrome 이 앞에 뜬 뒤 조금 늦다. 그 resumed 까지 지우도록 앞뒤로 넓힌다. */
    private const val PRIVATE_MARGIN_MS = 3_000L

    /** 남기는 사건만. 포그라운드 서비스·구성 변경·대기 버킷 같은 나머지는 버린다. */
    private val eventNames = mapOf(
        UsageEvents.Event.ACTIVITY_RESUMED to "resumed",
        UsageEvents.Event.ACTIVITY_PAUSED to "paused",
        UsageEvents.Event.ACTIVITY_STOPPED to "stopped",
        UsageEvents.Event.SCREEN_INTERACTIVE to "screen_on",
        UsageEvents.Event.SCREEN_NON_INTERACTIVE to "screen_off",
        UsageEvents.Event.KEYGUARD_SHOWN to "keyguard_shown",
        UsageEvents.Event.KEYGUARD_HIDDEN to "keyguard_hidden",
        UsageEvents.Event.DEVICE_SHUTDOWN to "shutdown",
        UsageEvents.Event.DEVICE_STARTUP to "startup",
    )

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 새로 쓴 파일 수. 끝난 구간만 만든다 — 소급하는 지난 날은 하루 파일, 오늘은 한 시간 조각
     * (`app_2026-09-30_h13.jsonl`, v0.8~ [HourSlice]). 사건이 없는 구간은 파일 없이 넘어간다.
     */
    fun exportPending(ctx: Context): Int {
        if (!hasPermission(ctx)) return 0
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val pm = ctx.packageManager
        val labels = HashMap<String, String?>()
        val now = System.currentTimeMillis()
        val from = Prefs.appExportedUntil(ctx)
            // v0.7 에서 올라온 기기: 마지막으로 내보낸 날의 다음 날 자정부터
            ?: Prefs.appLastExportDay(ctx)?.let { runCatching { dayFormat.parse(it)?.time }.getOrNull() }
                ?.let { HourSlice.addDays(it, 1) }
            ?: HourSlice.addDays(HourSlice.dayStart(now), -Config.APP_USAGE_BACKFILL_DAYS)
        var count = 0
        val hidden = PrivateScreen.intervals(ctx, PrivateScreen.CHROME_INCOGNITO)
        for (w in HourSlice.windows(from, HourSlice.exportableUntil(now))) {
            val lines = readDay(usm, pm, labels, hidden, w.start, w.end)
            if (lines.isNotEmpty()) {
                val part = File(Storage.appDir(ctx), "app_${w.key}.jsonl${Storage.PART}")
                part.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
                Storage.finishPart(part)
                count++
            }
            Prefs.setAppExportedUntil(ctx, w.end)
        }
        return count
    }

    private fun readDay(
        usm: UsageStatsManager,
        pm: PackageManager,
        labels: MutableMap<String, String?>,
        hidden: List<LongRange>,
        start: Long,
        end: Long,
    ): List<String> {
        val out = ArrayList<String>()
        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            val name = eventNames[e.eventType] ?: continue
            val pkg = e.packageName ?: "android"
            if (BRAVE_PKGS.any { pkg.startsWith(it) }) continue
            if (pkg in CHROME_PKGS && hidden.any { e.timeStamp in (it.first - PRIVATE_MARGIN_MS)..(it.last + PRIVATE_MARGIN_MS) }) continue
            out += JSONObject()
                .put("t", e.timeStamp)
                .put("kind", "app")
                .put("event", name)
                .put("pkg", pkg)
                .put("cls", e.className ?: JSONObject.NULL)
                .put("app", labelOf(pm, pkg, labels) ?: JSONObject.NULL)
                .toString()
        }
        return out
    }

    /** 사람이 읽는 앱 이름. 다른 앱을 보려면 QUERY_ALL_PACKAGES가 있어야 한다 (매니페스트). */
    private fun labelOf(pm: PackageManager, pkg: String, cache: MutableMap<String, String?>): String? =
        cache.getOrPut(pkg) {
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
}

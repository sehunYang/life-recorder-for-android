package com.liferecorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 모바일 데이터로도 올라가는 것은 작은 글자 자료뿐이어야 한다 — 녹음·화면이 섞이면 데이터 요금이 터진다. */
class StorageTextTest {
    private fun t(name: String) = Storage.isSmallText(File(name))

    @Test fun hourSlicesAndDailyTextAreSmall() {
        listOf(
            "kakao_2026-10-01_h08.jsonl", "screentext_2026-10-01_h08.jsonl",
            "app_2026-10-01_h08.jsonl", "sms_2026-10-01_h08.jsonl",
            "kakao_2026-09-30.jsonl", "app_2026-09-29.jsonl",
        ).forEach { assertTrue(it, t(it)) }
    }

    @Test fun mediaAndDiagnosticsAreNot() {
        listOf(
            "audio_2026-10-01_08-00-00.m4a", "screen_2026-10-01_08-00-00.mp4",
            "call_2026-10-01_08-00-00_x.m4a", "camera_2026-10-01_08-00-00_x.jpg",
            "kakaoimg_2026-10-01_08-00-00_1_r_h.jpg", "kakao_dump_2026-10-01.jsonl",
            "kakao_2026-10-01_08-00-00_export_KakaoTalkChats.txt",
            "kakao_2026-10-01_08-00-00_export_chat.jsonl", "index_2026-10-01.jsonl",
            "kakao_2026-10-01_h08.jsonl.part",
        ).forEach { assertFalse(it, t(it)) }
    }
}

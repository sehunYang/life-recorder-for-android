package com.liferecorder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class Status(
    val recordingEnabled: Boolean = false,
    val audioRecording: Boolean = false,
    val audioError: String? = null,
    val screenRecording: Boolean = false,
    val screenStoppedReason: String? = null,
    /** 화면 꺼짐/발열로 캡처 입력만 잠시 끊긴 상태. 세션은 살아 있다. */
    val screenPausedReason: String? = null,
    val currentSegmentStart: Long = 0L,
    val pendingFiles: Int = 0,
    val pendingBytes: Long = 0L,
    val uploading: String? = null,
    val lastUploadAt: Long = 0L,
    val lastUploadError: String? = null,
    val driveLinked: Boolean = false,
    /** 통화 녹음 가져오기 상태 표시용 ("3개 가져옴", "권한 필요" 등). */
    val callImportNote: String? = null,
    /** 문자 내보내기 상태 표시용. */
    val smsExportNote: String? = null,
    /** 카카오톡 기록 상태 표시용. */
    val kakaoNote: String? = null,
)

/** 서비스, 업로드 워커, UI가 공유하는 프로세스 내 상태. 단일 프로세스라서 그대로 공유된다. */
object RecorderState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(f: (Status) -> Status) = _status.update(f)

    /** 디스크를 읽으므로 백그라운드 스레드에서 호출할 것. */
    fun refreshPending(ctx: Context) {
        val files = Storage.finishedFiles(ctx)
        val bytes = files.sumOf { it.length() }
        update { it.copy(pendingFiles = files.size, pendingBytes = bytes) }
    }
}

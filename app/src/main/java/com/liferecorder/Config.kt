package com.liferecorder

/** 녹음/녹화/업로드 품질과 동작을 결정하는 상수. 필요하면 여기만 바꾸면 된다. */
object Config {
    // 마이크 녹음: AAC-LC, 48kHz 모노 160kbps (음성 식별에 충분한 품질)
    /** STT 목적이라 VOICE_RECOGNITION(음성 인식용 튜닝). 삼성 자체 처리가 거슬리면 MIC나 UNPROCESSED로 바꿔 비교. */
    const val AUDIO_SOURCE = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_BITRATE = 160_000
    const val AUDIO_CHANNELS = 1

    // 화면 녹화: 해상도 2/3 축소(약 720px 폭), 최대 5fps, 1.2Mbps 상한. 본문 크기 글씨까지 읽히는 수준.
    // (정지 화면일 때는 거의 0에 가깝고, 계속 조작할 때 시간당 최대 약 540MB)
    const val SCREEN_SCALE = 0.67f
    const val SCREEN_FPS = 5
    const val SCREEN_BITRATE = 1_200_000
    const val SCREEN_IFRAME_INTERVAL_SEC = 5
    /** 화면이 멈춰 있어도 이 간격마다 이전 프레임을 반복 인코딩해 타임라인이 끊기지 않게 한다. 길수록 인코더가 덜 깨어난다. */
    const val SCREEN_REPEAT_FRAME_US = 10_000_000L

    /** 이 발열 단계 이상이면 화면 캡처 입력을 끊는다 (PowerManager.THERMAL_STATUS_*). 소리는 계속. */
    const val SCREEN_PAUSE_THERMAL = android.os.PowerManager.THERMAL_STATUS_MODERATE
    /**
     * 발열이 기준 아래로 내려간 뒤 이만큼 지나야 캡처를 다시 붙인다.
     * 바로 붙이면 경계에서 켜졌다 꺼졌다를 반복해 열이 빠질 틈이 없다.
     */
    const val SCREEN_THERMAL_RESUME_DELAY_MS = 3 * 60_000L

    /** 정각까지 남은 시간이 이보다 짧으면 다음 정각까지 하나의 세그먼트로 합친다. */
    const val MIN_SEGMENT_MS = 5_000L

    // Google Drive 재개 가능 업로드 청크 (256KB 배수여야 한다)
    const val UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024

    const val DRIVE_ROOT_FOLDER = "LifeRecorder"
    const val DRIVE_AUDIO_FOLDER = "audio"
    const val DRIVE_SCREEN_FOLDER = "screen"
    const val DRIVE_CALL_FOLDER = "call"
    const val DRIVE_SMS_FOLDER = "sms"
    const val DRIVE_KAKAO_FOLDER = "kakao"
    const val DRIVE_KAKAO_MEDIA_FOLDER = "kakao-media"

    /** 카카오톡 패키지명. 알림 가로채기 대상. */
    const val KAKAO_PACKAGE = "com.kakao.talk"


    /** 통화 녹음 파일이 있는 공용 저장소 상대 경로(MediaStore RELATIVE_PATH, LIKE 패턴). 앞이 T전화, 뒤가 삼성 기본 전화. */
    val CALL_RECORDING_PATHS = listOf("Recordings/TPhoneCallRecords/%", "Recordings/Call/%", "Call/%")

    /** 문자 내보내기를 처음 켤 때 며칠 전까지 거슬러 올라갈지. */
    const val SMS_BACKFILL_DAYS = 30
}

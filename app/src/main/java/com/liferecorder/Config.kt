package com.liferecorder

/** 녹음/녹화/업로드 품질과 동작을 결정하는 상수. 필요하면 여기만 바꾸면 된다. */
object Config {
    // 마이크 녹음: AAC-LC, 48kHz 모노 160kbps (음성 식별에 충분한 품질)
    /** STT 목적이라 VOICE_RECOGNITION(음성 인식용 튜닝). 삼성 자체 처리가 거슬리면 MIC나 UNPROCESSED로 바꿔 비교. */
    const val AUDIO_SOURCE = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_BITRATE = 160_000
    const val AUDIO_CHANNELS = 1

    // 화면 녹화: 해상도 1/2 축소(약 540px 폭), 최대 5fps, 0.8Mbps 상한.
    // 앱 UI 글씨(상품명·가격·메뉴)는 비전 모델이 읽는 수준 (실측). 발열 때문에 0.67에서 내렸다:
    // 가상 디스플레이 합성은 화면이 바뀔 때마다(최대 120Hz) GPU가 이 크기로 한 장씩 그리므로
    // 픽셀 수가 곧 열이다. (정지 화면일 때는 거의 0, 계속 조작할 때 시간당 최대 약 360MB)
    const val SCREEN_SCALE = 0.5f
    const val SCREEN_FPS = 5
    const val SCREEN_BITRATE = 800_000
    const val SCREEN_IFRAME_INTERVAL_SEC = 5
    /** 화면이 멈춰 있어도 이 간격마다 이전 프레임을 반복 인코딩해 타임라인이 끊기지 않게 한다. 길수록 인코더가 덜 깨어난다. */
    const val SCREEN_REPEAT_FRAME_US = 10_000_000L

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
    const val DRIVE_CAMERA_FOLDER = "camera"
    const val DRIVE_INDEX_FOLDER = "index"

    /** 카카오톡 패키지명. 알림 가로채기 대상. */
    const val KAKAO_PACKAGE = "com.kakao.talk"


    /** 통화 녹음 파일이 있는 공용 저장소 상대 경로(MediaStore RELATIVE_PATH, LIKE 패턴). 앞이 T전화, 뒤가 삼성 기본 전화. */
    val CALL_RECORDING_PATHS = listOf("Recordings/TPhoneCallRecords/%", "Recordings/Call/%", "Call/%")

    /** 문자 내보내기를 처음 켤 때 며칠 전까지 거슬러 올라갈지. */
    const val SMS_BACKFILL_DAYS = 30

    /** 카메라로 찍은 사진·동영상이 있는 공용 저장소 상대 경로(MediaStore RELATIVE_PATH, LIKE 패턴). */
    val CAMERA_PATHS = listOf("DCIM/Camera/%")

    /**
     * 사진첩 소급분을 한 번에 다 복사하면 폰 저장공간이 찬다(실측: 11GB).
     * 업로드 작업 한 번에 이만큼까지만 가져오고 나머지는 다음 실행으로 미룬다.
     * 업로드가 끝난 파일은 지워지므로, 여러 번에 걸쳐 조금씩 옮겨진다.
     */
    const val CAMERA_BUDGET_BYTES = 1024L * 1024 * 1024
}

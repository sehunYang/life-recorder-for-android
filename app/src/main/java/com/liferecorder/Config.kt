package com.liferecorder

/** 녹음/녹화/업로드 품질과 동작을 결정하는 상수. 필요하면 여기만 바꾸면 된다. */
object Config {
    // 마이크 녹음: AAC-LC, 48kHz 스테레오 256kbps
    /**
     * CAMCORDER — 삼성은 이 경로에 잡음 제거·음량 조절을 걸고 마이크 두 개를 따로 준다.
     * 실측(2026-09-27, Flip 5, 문밖→방 안 약 3m, 선풍기·공기청정기): 말소리 대 잡음이
     * VOICE_RECOGNITION 17.1dB · MIC 36.1dB · CAMCORDER 37.6dB · 삼성 녹음 앱 31.7dB.
     * VOICE_RECOGNITION 은 사실상 무처리 원음에 음량만 올린 것이라 선풍기 소리까지 같이 커졌다.
     * 두 채널은 서로 다른 마이크다 (상관 0.39). 하나가 막혀도 다른 쪽이 남는다.
     */
    const val AUDIO_SOURCE = android.media.MediaRecorder.AudioSource.CAMCORDER
    const val AUDIO_SAMPLE_RATE = 48_000
    /** 스테레오라 채널당 128kbps. 시간당 약 115MB (모노 160kbps 때 약 72MB). */
    const val AUDIO_BITRATE = 256_000
    const val AUDIO_CHANNELS = 2

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
    /**
     * 인코딩된 화면 프레임을 파일에 쓰기 전에 붙드는 시간. 비공개 앱은 창이 뜬 뒤에 판정되므로
     * 그 사이 프레임을 되돌려 버릴 여유다. 되돌리는 폭([SCREEN_PRIVATE_LOOKBACK_US])보다 길어야 한다.
     */
    const val SCREEN_HOLD_US = 1_500_000L
    /**
     * 비공개 앱이 판정되면 이만큼 앞선 프레임부터 버린다. 실측(2026-09-26) 판정 지연은 실행 뒤
     * 0.2초 안쪽이고 여는 애니메이션은 0.3초 남짓이다. 넉넉히 1초.
     */
    const val SCREEN_PRIVATE_LOOKBACK_US = 1_000_000L

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
    const val DRIVE_APP_FOLDER = "app"
    const val DRIVE_SCREEN_TEXT_FOLDER = "screen-text"

    /** 앱 사용 기록을 처음 켤 때 며칠 전까지 거슬러 올라갈지. 시스템이 UsageEvents를 들고 있는 기간 안에서. */
    const val APP_USAGE_BACKFILL_DAYS = 7

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

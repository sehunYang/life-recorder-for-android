# 폰에 설치하고 테스트하기

Galaxy Z Flip 5 기준. 순서대로 따라가면 된다.

빌드된 APK 위치:
```
app\build\outputs\apk\debug\app-debug.apk
```

adb 위치 (PATH에 없으므로 전체 경로를 쓰거나 아래처럼 PATH에 추가):
```powershell
$env:PATH += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
```

---

## 1단계. 폰을 개발자 모드로

1. 설정 > 휴대전화 정보 > 소프트웨어 정보
2. **빌드번호**를 7번 연속 탭 → "개발자가 되었습니다" 표시
3. 설정 > 개발자 옵션 > **USB 디버깅** 켜기
4. USB 케이블로 PC 연결
5. 폰에 "USB 디버깅을 허용하시겠습니까?" 팝업 → **항상 허용** 체크 후 확인
6. 연결 모드는 "파일 전송(MTP)"으로 두는 게 안정적이다

연결 확인:
```powershell
adb devices
```
`device`로 끝나는 줄이 하나 나와야 한다. `unauthorized`면 폰의 팝업을 확인하지 않은 것이다.

## 2단계. 설치

```powershell
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

`Success`가 뜨면 된다.

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 먼저 지운다: `adb uninstall com.liferecorder`
- 삼성에서 "USB로 앱 설치 확인" 팝업이 뜨면 허용
- USB 없이 하려면 APK를 폰으로 복사해 내 파일에서 실행. "출처를 알 수 없는 앱 설치"를 허용해야 한다

## 3단계. 앱 권한 (앱 안에서)

앱을 실행하고 위에서부터 순서대로.

1. **배터리 최적화 제외 요청** 버튼 → 허용
2. 카카오톡 카드의 **알림 접근 허용하기** → 목록에서 Life Recorder 켜기 → 뒤로
3. **기록 시작 (ON)** → 권한 요청이 순서대로 뜬다. 전부 허용
   - 마이크, 알림, 음악 및 오디오(통화 녹음 읽기), SMS, 연락처
4. **화면 녹화 동의 팝업** → "전체 화면" 선택 후 시작
5. 함께 모으기 카드의 **접근성 설정 열기** → 설치된 앱 > Life Recorder 켜기 → 뒤로
6. 함께 모으기 카드의 **사용 정보 접근 허용하기** → 목록에서 Life Recorder 켜기 → 뒤로

상단 알림에 `녹음 중 · 화면 녹화 중`이 보이면 정상이다.
"화면 글자 읽기" 줄에 "모든 앱 화면에 보이는 글자를 그대로 모으는 중", 앱 사용 기록 줄에 권한 문구가 사라지면 5·6번도 된 것이다.

## 4단계. 안 죽게 만드는 폰 설정

1. 설정 > 배터리 > 백그라운드 사용 제한 > **절전 예외 앱**에 Life Recorder 추가
2. 설정 > 앱 > Life Recorder > 배터리 → **제한 없음**
3. 설정 > 배터리 > 절전 모드가 켜져 있으면 끈다

### 4-1. 화면 녹화가 잠금화면에서 안 끊기게 (필수)

이걸 안 하면 **화면이 꺼져 잠길 때마다 안드로이드가 화면 녹화를 강제 종료한다.**
로그에 `Stopping MediaProjection due to reason: STOP_REASON_KEYGUARD`로 찍힌다.
자는 동안 내내 잠겨 있으니 화면 기록이 통째로 비게 된다.

PC에서 한 번만 해 두면 된다:
```powershell
adb shell appops set com.liferecorder PROJECT_MEDIA allow
```

이렇게 하면 두 가지가 같이 해결된다.
- 잠금화면이 떠도 화면 녹화가 끊기지 않는다 (키가드 종료 정책에서 빠진다)
- 화면 녹화 동의 창이 아예 뜨지 않고 즉시 승인된다

확인:
```powershell
adb shell appops get com.liferecorder PROJECT_MEDIA
# PROJECT_MEDIA: allow 가 나와야 한다
```

앱을 다시 설치해도 이 설정은 남는다. 되돌리려면 `allow`를 `default`로 바꾸면 된다.

혹시 이 설정 없이 쓰다가 프로젝션이 끊기더라도, 잠금을 풀면 앱이 스스로 다시 붙는다
(`RecordingService.maybeResumeScreen`). 다만 그때는 동의 창이 한 번 뜬다.

## 5단계. 동작 확인

5분쯤 두고 파일이 생기는지 본다.

```powershell
adb shell ls -l /sdcard/Android/data/com.liferecorder/files/audio
adb shell ls -l /sdcard/Android/data/com.liferecorder/files/screen
```

- 정각이 지나기 전에는 `.part` 파일 하나가 계속 커진다. 그게 정상이다
- 정각을 넘기면 `.part`가 사라지고 `audio_...m4a`, `screen_...mp4`가 생긴다
- 빨리 확인하고 싶으면 앱을 OFF 했다가 다시 ON 하면 그 시점에 세그먼트가 닫힌다

로그로 보기:
```powershell
adb logcat -s RecordingService:V AudioRecorder:V ScreenRecorder:V UploadWorker:V
```

## 6단계. 카카오톡 진단 (가장 중요)

알림에 실제로 무엇이 들어오는지 확인하는 단계다.

**준비**
1. 앱 > 카카오톡 카드 > **진단: 알림 원본 덤프** 켜기
2. 카카오톡 설정 > 알림에서 **메시지 내용 표시**가 켜져 있는지 확인
3. 테스트할 대화방의 알림이 꺼져 있지 않은지 확인
4. 로그 창을 띄워둔다:
```powershell
adb logcat -c
adb logcat -s KakaoDump
```

**시나리오** (순서대로, 각 단계 사이에 10초쯤 둔다)

| 순서 | 할 일 | 확인하려는 것 |
|---|---|---|
| 1 | 카카오톡을 백그라운드로 두고 상대에게 메시지 받기 | 기본 동작 |
| 2 | **내가 메시지 한 건 보내기** | 1번 질문: 내 발화가 들어오는가 |
| 3 | **채팅방을 연 채로** 상대에게 메시지 받기 | 2번 질문: 앞에 떠 있을 때도 알림이 오는가 |
| 4 | 사진 한 장 받기 | 3번: dataUri가 채워지는가, 텍스트는 뭐로 오는가 |
| 5 | 이모티콘 받기 | 3번 |
| 6 | 상대가 메시지 삭제 | 3번 |
| 7 | 읽지 않고 5건 이상 몰아서 받기 | 4·5번: historicCount, messageCount |
| 8 | 대화를 읽어서 알림 지우고, 새 메시지 받기 | 9번: 배열이 리셋되는가 |
| 9 | 그룹채팅에서 한 건 받기 | 6번: isGroupConversation, shortcutId |

**결과 회수**

```powershell
adb pull /sdcard/Android/data/com.liferecorder/files/kakao ./kakao-test
```

`kakao_dump_<날짜>.jsonl.part` 파일이 원본이다. 한 줄이 알림 하나다.

권한 거부가 뜨면 이렇게 한 파일만 빼낸다:
```powershell
adb exec-out run-as com.liferecorder cat /sdcard/Android/data/com.liferecorder/files/kakao/kakao_dump_2026-09-03.jsonl.part > dump.jsonl
```

**덤프에서 볼 곳**

| 질문 | 볼 위치 |
|---|---|
| 1. 내 발화 | `messagingStyle.messages[].personIsNull` 이 `true`인 항목이 있는가 |
| 2. 포그라운드 | 3번 시나리오에서 덤프 줄이 새로 생겼는가 |
| 3. 미디어 | `dataMimeType`, `dataUri`, 그리고 `text`에 뭐가 들어왔는가 |
| 4. historic | `messagingStyle.historicCount` |
| 5. 최대 개수 | `messagingStyle.messageCount`의 최댓값 |
| 6. 방 식별 | `notification.shortcutId`, `sbn.key`, `sbn.tag`, `isGroupConversation`, `person.key` |
| 8. 시각 | `messages[].timestamp` 와 `sbn.postTime` 비교 |

### 6단계 결과 (2026-09-05, 메시지 277건 / 덤프 9건 기준)

| 질문 | 결론 |
|---|---|
| 1. 내 발화 | **안 들어온다.** 277건 전부 `fromMe=false`. 카카오톡은 내가 보낸 메시지를 알림으로 띄우지 않는다. 알림만으로는 내 발화를 못 모은다 → 대화 내보내기로 보완해야 한다 |
| 2. 포그라운드 | 미검증. 채팅방을 연 채로 받는 시나리오를 따로 해봐야 한다 |
| 3. 미디어 | 사진은 `text="사진을 보냈습니다."` + `dataMimeType="image/"` + `dataUri`(카카오톡 FileProvider)가 온다. 앱이 그 자리에서 받아 `kakaomedia/`에 저장하고 레코드에 `mediaFile`을 남긴다. **동영상은 텍스트만 오고 mime·uri가 없어 불가능하다.** 듀얼 메신저 계정의 사진도 못 받는다(프로필 경계를 넘는 URI 권한이 없다) |
| 4. historic | `historicCount`는 항상 0. 카카오톡은 historicMessages를 안 채운다 |
| 5. 최대 개수 | 관측 최댓값 3. 샘플이 적어 확정은 아니다 |
| 6. 방 식별 | `shortcutId == tag`가 277/277 일치. 이게 방의 고유 ID다. `isGroupConversation`도 정확하다. **방 이름은 알림에 없다** — `android.title`은 그때 말한 사람 이름이다 |
| 8. 시각 | 메시지 시각과 게시 시각 차이 중앙값 62ms. 다만 최대 35분까지 벌어진다(안 읽은 메시지가 나중 알림에 다시 실릴 때). 그러니 `messages[].timestamp`를 써야 한다 |

여기서 버그를 하나 찾아 고쳤다. 방 이름이 없는데도 `android.title`을 방으로 쓰고 있어서
그룹채팅 한 개가 발신자 수만큼 쪼개졌고, 중복 제거 키에 그 값이 들어가 **전체의 21%가
중복 저장**되고 있었다. 이제 `roomId`(shortcutId)로 방을 식별하고 중복 제거도 그것으로 한다.

듀얼 메신저(카카오톡 2개)는 **기본 앱 하나로 둘 다 잡힌다.** 레코드의 `user` 필드가
`"0"`(기본) / `"95"`(듀얼)로 구분해 준다. 듀얼 공간에 Life Recorder를 따로 설치할 필요 없다.

### 6단계 추가 결과 (2026-09-17, 메시지 5,176건 / 덤프 114건 기준)

| 질문 | 결론 |
|---|---|
| 2. 포그라운드 | 여전히 미검증. `channel` 값의 70%가 `quiet_new_message`라 조용한 알림은 잡히는데, 그것이 방을 열어 둔 상태인지는 6-1단계 시나리오로 확인한다 |
| 6. 방 이름 | 알림 extras의 `android.hiddenConversationTitle`·`subText`·`infoText`도 전부 null. **알림에는 방 이름이 없다.** 화면 읽기(6-1)의 `title`로 얻는다 |
| 6. 사람 키 | `person.key`는 **방마다 다르다.** 같은 이름에 키 3~4개가 붙고 방별로 갈린다. 받는 계정 자신의 키도 방마다 다르다. 사람 단위 안정 키는 카카오톡이 주지 않는다 |
| 7. 중복 | extras에 카카오톡이 넣는 **`chatLogId`** (메시지 고유 번호)가 있다. 이제 이걸 기록하고 중복 제거 키로 쓴다. 앱 재시작 뒤 같은 알림이 와도 같은 값이다 |
| 9. 계정 | 두 계정이 계속 같이 수집된다 (`user` 0 약 90%, 95 약 10%). `me` 필드가 각 계정의 표시 이름이라 어느 쪽인지 바로 보인다 |

## 6-1단계. 화면 읽기와 앱 사용 기록 확인

접근성 서비스와 사용 정보 접근이 실제로 파일을 만드는지 본다. 3단계 5·6번이 먼저다.

**화면 글자 읽기**

1. 로그 창: `adb logcat -s ScreenText ScreenTextLog`
2. 카카오톡에서 **그룹채팅방 하나를 연다** → 몇 초 뒤 로그가 찍히는지
3. **내가 메시지 한 건 보낸다** → 다시 찍히는지
4. 방을 나가 **브라우저나 메모 앱**을 연다 → 그 앱 줄도 생기는지 (`pkg`가 바뀐다)
5. 파일 확인:
```powershell
adb shell ls -l /sdcard/Android/data/com.liferecorder/files/screentext
adb exec-out run-as com.liferecorder cat /sdcard/Android/data/com.liferecorder/files/screentext/rawscreentext_<오늘>.jsonl.part
```

| 볼 곳 | 기대 |
|---|---|
| `pkg` | 2·3번은 `com.kakao.talk`, 4번은 그 앱의 패키지명 |
| 방 이름 | 2번 직후 레코드의 `vid:"toolbar_default_title_text"` 노드 (그룹방은 뒤에 인원수). `title`은 `"카카오톡"`일 뿐이다 |
| 내 메시지 | 3번에서 보낸 본문이 `vid:"message"`, **`r >= 1030`** 인 노드로. 치는 동안 `message_edit_text` 줄이 글자마다 생기면 안 된다 (멈췄을 때 한 번은 생길 수 있다) |
| 상대 메시지 | `vid:"message"`, `l == 170`. 위에 `nickname` 노드 |
| `activity` | 채팅방이면 `…ChatRoomHolderActivity` |
| 줄 수 | 화면이 바뀔 때마다 한 줄. 가만히 두면 늘지 않아야 한다 (같은 앱·창에서 2분 안에 본 글자는 다시 안 쓴다). 유튜브 재생 중에도 초당 한 줄이 생기면 안 된다 |
| 비밀번호 | 어느 앱이든 비밀번호 칸에 친 것은 **나오면 안 된다** |

`vid` 값은 앱 버전에 따라 다르다. 위 값은 2026-09-17 카카오톡 기준 실측이다.
하루 돌린 뒤 파일 크기를 본다 — 앱을 가리지 않으므로 스크롤이 많은 날은 수 MB가 될 수 있다.

**2026-09-17 실측 (7분 세션)**: 앱 13개 전환이 전부 `pkg`로 잡혔고, 그룹방 이름·보낸 메시지 말풍선(`l=377 r=1036`)이 잡혔다.
발견한 문제 둘 — 카카오톡 입력창이 `EditText`가 아니라 `MultiAutoCompleteTextView`라 글자마다 24줄이 남았고,
유튜브 `SeekBar`가 초당 한 줄을 만들었다. 둘 다 고쳤다 (`isEditable` + 두 번 연속 같을 때만, `SeekBar` 제외).
안 되는 것도 확인했다 — 삼성 인터넷 웹 본문(서비스 설정을 바꿔도, `uiautomator dump`로 떠도 0건)과 에뮬레이터 화면(`LOADING…` 뿐).

브라우저 본문이 오는지 다시 볼 때는 앱이 아니라 시스템 도구로 먼저 판정한다 — 이게 0이면 앱 쪽에서 할 수 있는 게 없다:
```powershell
adb shell uiautomator dump /sdcard/ui.xml; adb exec-out cat /sdcard/ui.xml | Select-String 'WebView' | Measure-Object
```

**앱 사용 기록**

앱 사용 기록은 문자처럼 **어제까지만** 만든다. 켠 당일에는 파일이 없는 것이 정상이다.
바로 확인하려면 켜고 하루 지난 뒤 **지금 업로드**를 누르고:
```powershell
adb shell ls -l /sdcard/Android/data/com.liferecorder/files/app
```
`app_<어제>.jsonl`이 있고, 안에 `resumed`/`screen_off`/`keyguard_shown` 줄이 시각순으로 있으면 된다.
처음 켜면 최대 7일 전까지 거슬러 만든다 (시스템이 들고 있는 만큼만).

## 7단계. 마무리

- 테스트가 끝나면 **진단 덤프를 끈다**. 켜두면 용량이 계속 늘어난다
- 계속 기록할 게 아니면 앱에서 **OFF**
- 로컬에 쌓인 파일 확인: `adb shell du -sh /sdcard/Android/data/com.liferecorder/files`

## 8단계. Google Drive 연결 (나중에 해도 됨)

이 단계를 안 해도 녹음과 진단 테스트는 다 된다. 파일이 폰에 쌓일 뿐이다.

1. https://console.cloud.google.com 에서 프로젝트 생성
2. **API 및 서비스 > 라이브러리** → `Google Drive API` 사용 설정
3. **OAuth 동의 화면** → 외부(External) → 범위에 아래를 추가
   ```
   https://www.googleapis.com/auth/drive.file
   ```
   - 추가하는 곳: API 및 서비스 > OAuth 동의 화면 > **데이터 액세스** > 범위 추가 또는 삭제
     (예전 UI는 OAuth 동의 화면 2단계 "범위"). 바로가기: https://console.cloud.google.com/auth/scopes
   - 목록 검색에 안 나오면 같은 화면의 **범위를 수동으로 추가**에 위 URL을 붙여넣는다
   - Drive 전체가 아니라 이 앱이 만든 파일에만 접근하는 범위다 (`DriveAuth.kt`와 같은 값이어야 한다)
   - **대상(Audience)** 메뉴에서 테스트 사용자에 본인 계정 추가
   - **앱 게시(프로덕션)는 하지 않는다.** 게시하려면 브랜딩 탭의 홈페이지·개인정보처리방침 URL과
     Search Console로 소유 확인된 **승인된 도메인**이 필요하다. 개인용 앱에는 과하다
   - 테스트 상태는 인증이 약 7일마다 만료될 수 있다. 만료되면 "Google Drive 연결 필요" 알림이
     뜨고 한 번 탭해서 다시 연결하면 된다 (이 알림은 무음 채널이라 소리는 안 난다)
4. **사용자 인증 정보 만들기 > OAuth 클라이언트 ID**
   - 유형: **Android**
   - 패키지 이름: `com.liferecorder`
   - SHA-1 인증서 지문: **빌드하는 PC마다 다르므로 각자 구해야 한다.**
     아래 명령이 찍어 주는 `SHA1:` 값을 그대로 붙여넣는다.
     ```powershell
     keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
     ```
     (릴리스 키로 서명해 설치할 거면 그 키의 지문도 별도 클라이언트로 등록한다)
5. 앱에서 **Google 계정 연결** → 계정 선택 → Drive 권한 허용
6. **지금 업로드** 버튼으로 즉시 확인. 자동 업로드는 충전 중에만 돈다

## 문제가 생기면

| 증상 | 원인과 조치 |
|---|---|
| `adb devices`가 비어 있음 | USB 디버깅이 꺼졌거나 케이블이 충전 전용. 다른 케이블로 시도 |
| `unauthorized` | 폰의 허용 팝업을 확인. 안 뜨면 개발자 옵션 > USB 디버깅 승인 취소 후 재연결 |
| 화면 녹화가 안 켜짐 | 동의 팝업을 놓쳤다. 앱에서 "화면 녹화 재개" 또는 OFF 후 다시 ON |
| 알림에 "화면 녹화가 중단됨" | 대개 잠금화면 때문이다. 4-1의 `appops` 설정을 했는지 확인. 안 했으면 탭해서 다시 승인 |
| 밤사이 화면 파일만 비어 있음 | 4-1을 안 한 것이다. 키가드가 뜰 때마다 프로젝션이 종료된다 |
| 카카오톡 덤프가 안 찍힘 | 알림 접근이 꺼졌다. 설정 > 알림 > 알림 접근에서 다시 켠다 |
| 앱이 조용히 죽음 | 4단계 절전 설정을 다시 확인 |
| 소리가 안 녹음됨 | 다른 앱이 마이크를 잡고 있거나 통화 중이다 |

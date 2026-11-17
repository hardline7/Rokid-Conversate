# Rokid 기본 STT 연결 검증

> 후속 회의 앱이 구현됐다. 현재 런처 조작·자료·기록·Rokid AI 사용법은 [USAGE.md](USAGE.md), 구현 검증 범위는 [BUILD-STATUS.md](BUILD-STATUS.md)를 따른다. 아래 조작법은 초기 STT 진단 화면의 기록이다.

2026-11-17, 실제 연결된 RG_glasses / Android 12에서 검증했다. 회의 앱 전체가 아니라 STT 연결 프로토타입이다.

## 결론

**일반 앱에서 기본 STT를 시작하고 중간/확정 결과를 받았다. 기본 자막 화면은 열리지 않았고 우리 앱은 살아 있었다. 종료 시 subtitle 오디오 파이프라인도 닫혔다.**

기본 자막 장면을 여는 대신 MasterAssistService의 일반 GATT 제어 명령으로 폰에 Accessibility_start를 직접 전달한다. 이 경로는 로컬 Accessibility_start 화면 알림을 만들지 않는다. 이번 테스트에서 폰도 시작 화면을 여는 알림을 반환하지 않았다. 앱 신원 위장, 서명 우회, 루팅, 시스템 APK 패치는 하지 않았다.

## 사용

1. 안경과 폰의 Rokid 앱을 연결한다. 폰의 STT 서비스/네트워크가 필요하다.
2. 안경 런처에서 **Conversate**를 연다. 이제 M0 측정 화면 대신 STT 화면이 열린다.
3. 탭으로 시작, 다시 탭으로 종료한다. 두 번 탭은 인식 중이면 종료, 대기 중이면 앱을 닫는다.
4. 앱이 화면 뒤로 가거나 화면이 꺼져 onStop이 호출되면, 앱이 시작한 인식을 종료 요청한다.
5. 확정 문장만 앱 전용 `files/transcripts/*.jsonl`에 저장된다. 음성 파일은 앱에서 저장하지 않는다. 현재 텍스트 파일은 별도 암호화하지 않은 앱 전용 저장소다.

PC에서 읽을 수 있는 Markdown과 원본 JSONL을 내보낸다.

```powershell
.\tools\export_stt.ps1
```

결과 위치는 `tools/out/stt/`. 개발용 APK의 run-as로 내보내므로 release 배포용 내보내기 API는 별도 작업이다. 최종 결과는 경로/번호별 최신 항목을 사용한다. 같은 발화의 확정 정정은 새 줄로 기록한다.

## 구현 규약

- 서비스: `com.rokid.os.sprite.assistserver/com.rokid.os.sprite.assist.MasterAssistService`
- 서버 descriptor: `com.rokid.os.sprite.assist.server.IAssistServer`
- transact 1: String packageName, strongBinder callback; transact 2: String packageName; transact 3: String packageName, String controlJson. 모두 동기 호출이다.
- callback descriptor: `com.rokid.os.sprite.assist.client.IAssistClient`
- callback 1: typed RegisterResult(현재 필드 없음). callback 2: typed AssistMessage(long messageId, String packageName, String infoType, long time, String message). callback 2는 false로 반환하여 원래 런처의 메시지를 소비하지 않는다.
- callback 3: String, String, byte[]; 원본 byte[]를 응답에 돌려주고 내용을 사용하지 않는다.

시작:

```json
{"type":"cmd_phone_gatt_send_data","data":{"cmd":"Accessibility","key":"Accessibility_start"}}
```

종료:

```json
{"type":"cmd_phone_gatt_send_data","data":{"cmd":"Accessibility","key":"Accessibility_end","data":"{\"isExit\":true}"}}
```

실제 수신 형식(ASR은 대문자):

```json
{"type":"cmd_bluetooth_accessibility_data","data":{"key":"Accessibility_ASR","data":"{\"audio_path\":\"1\",\"content\":\"확인하겠습니다.\",\"final\":true,\"number\":1,\"role\":\"\"}"}}
```

`data.data`는 JSON 문자열이다. `final=false`는 화면에만 표시하고 `true`일 때 저장한다. `number`는 이번 세션 안에서만 식별자로 쓴다. `audio_path`도 식별자에 포함한다. `role`은 원문 메타데이터로 보존하며 실제 화자 식별 성능은 확인하지 않았다.

`RokidSttClient`가 Binder와 시작/종료 명령을 격리하고, `RokidTranscript`가 엄격한 파싱을 담당한다. 회의 앱의 STT 입력으로 해당 이벤트를 연결하면 된다. 현재 SttActivity가 그 예제다. 기존 ProbeActivity는 adb 명시 실행으로 유지했다.

## 증거와 검증 범위

정적 REA CLI 분석은 `tools/rokid-stt/*.json`, `launcher-analysis/`, `coexistence/`에 보존했다. Android 엔진은 JADX headless MCP 0.7.1이며 다운로드 JAR SHA-256은 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`이다. 연결된 MCP에 엔진 환경 변수가 없어, CLI 프로세스에만 REA_JADX_MCP_JAR를 지정했다. 글로벌 MCP 설정은 바꾸지 않았다.

- AssistServer APK: version 0.4.1 / 4124, SHA-256 `c93ce34b6f67f47dc2f20f9f6c634b314665487dc5753ccd4d47e7175f5b2878`.
- Launcher APK SHA-256 `e01cef136eec33b06bfd29e9a245b76f7f4152746c611c90c99d8a3516f79cbb`.
- Manifest REA Evidence `ev_c632c59afbbb12e742bbff402e3164bd9ddbbe29cc029074ac54a4db97673deb`: MasterAssistService exported, component permission 없음. 이것만으로 런타임 성공을 주장하지 않는다.
- 19:25:46 시작 → 한국어 발화 3개의 중간/확정 결과를 앱 PID 5863에서 수신. 일반 제어 처리 로그 `aiui=false`. `headless-result-log.txt` 및 REA 로그 Evidence `ev_6e68b6f654ff4230944fb93d1f698425f9f7d1eb3905b93279cf79e101102f7a` 참조.
- 종료 후에도 foreground 앱이 SttActivity였음: `ev_ccf6150b54437165ed77f4f33de279d018bc5f6e269b8217ba692e27bead62f3`.
- 최종 설치본에서 탭 시작 후 PC 한국어 합성 음성 일부("확인하겠습니다.")를 수신했고, 화면 표시 및 실제 JSONL 저장을 확인했다. 스크린샷 `screens/transcript/screen.png`, Evidence `ev_3acb4fe520b589ba14d228f36b504d061ede11922b50f4c093d25e452969dbfd`.
- 탭 종료 후 `AudioCapture.stopRecord(subtitle)`와 `PipelineGear subtitle teardown` 확인. 미착용/잠든 상태에서는 시작하지 않으며, 시작 직후 종료와 폰의 비동기 처리가 경합할 때 종료를 1.5초 뒤 한 번 더 보낸다.
- `gradlew.bat testDebugUnitTest assembleDebug` 성공. 테스트 3개: 실제 중간/확정 프레임 파싱, 비정상/다른 메시지 배제, 중간 결과 미저장·확정 중복 제거·정정 및 경로 구분 저장.

테스트는 연결된 폰/계정/펌웨어에서의 짧은 세션이다. 60분 배터리, 재연결, 거리별 인식률, 화자 분리, 서비스 요금/사용량 정책은 검증하지 않았다. 무료 또는 무제한이라는 결론은 내리지 않았다. 펌웨어/폰 앱 변경으로 내부 규약이 바뀔 수 있다. 강제 종료/전원 차단에서는 Activity 정리 코드가 실행되지 않을 수 있으므로 폰에서 자막을 끌 수 있어야 한다.

## 빌드 환경

기존 local.properties가 가리키던 사용자 Android SDK가 작업 도중 없어져 프로젝트의 `tools/android-sdk`에 공식 SDK platform 35 / build-tools 35.0.0을 준비했다. local.properties는 이 경로를 사용하며 다운로드 APK/JAR/SDK는 gitignore 처리했다. 빌드 도구 버전을 35.0.0으로 명시했다. JDK 21, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21을 사용했다.

# Idle operation / 대기 전력 동작 — v0.3.2

## 한국어

이전 AI 도우미는 홈 화면에 `CONVERSATE AI`와 상태를 표시하고, 작업이 없어도 매초 작업 조회·5초마다 상태 전송을 했습니다. 네이티브 서비스도 매초 점검했습니다. 계속 모델 추론을 하는 것은 아니었지만 불필요한 대기 작업이 있었습니다.

- 위젯의 글자·상태 렌더링을 제거했습니다. 호스트 연결을 유지하는 빈 앵커만 남습니다.
- 작업 조회는 서버에서 최대 25초 기다립니다. 새 작업이 등록되면 즉시 깨웁니다. 별도 주기적 heartbeat는 작업 중 또는 구형 APK와 연결할 때만 사용합니다.
- 모델은 요약·용어·Cue·번역 등 실제 작업이 있을 때 생성하고, 성공·실패 뒤 해제합니다. 토큰 없는 실행은 자동 AI 테스트를 하지 않습니다.
- 회의·처리할 작업이 없고 앱 화면이 숨겨지면 네이티브 점검을 30초 간격으로 낮춥니다. 인식 중에만 기존 WakeLock을 유지합니다.
- 숨겨진 웹 탭에서는 상태 조회를 건너뜁니다. 기존 브라우저는 새로고침해야 합니다.

서비스는 로컬 HTTPS 관리 화면과 AI 연결을 제공하기 위해 상주합니다. 프로세스 상주와 지속적인 추론·마이크 사용은 구별해야 합니다. 대기 전력이 0이거나 완전 종료된 상태는 아닙니다. 폰·Rokid 호스트의 전력 사용까지 이 앱이 제어하지는 않습니다.

### 2026-11-17 실기 확인

연결된 안경에서 홈 화면 문구가 사라졌고, 합성 RAG 발화의 요약·용어 풀이 생성에 성공했습니다. 회의가 없는 홈 상태 55초 동안 작업 조회는 2회, 추가 heartbeat는 0회, 서비스 점검은 1회였습니다. AI 연결은 유지되었고 점검 간격은 30초로 확인했습니다. 이전 매초 조회 대비 요청 횟수 감소이며, 배터리 절감률을 뜻하지 않습니다. USB 충전 상태여서 실제 배터리 소모율·장시간 사용량은 측정하지 않았습니다.

검증: JVM 37개, 도우미 모의 시계/모델 테스트 7개, Android lint/build, AIX 프로젝트 검사, 웹 회귀 테스트 통과. 도우미 테스트는 대기 중 모델 미생성, 작업 즉시 수신, 처리 중 heartbeat, 구버전 호환, 늦게 생성된 세션 정리를 확인합니다.

## English

The previous helper displayed a persistent home label, polled every second and sent idle heartbeats every five seconds. The native service also checked every second. This was background waiting, not continuous model inference, but it caused unnecessary work.

The helper now renders an empty anchor, waits up to 25 seconds on each local job request and wakes immediately when a job arrives. Periodic heartbeats run only during a job or with an older APK. A model session is created only for actual queued work, including summaries, cues and localization, and destroyed afterwards. Launching without pairing no longer runs an automatic model probe. Hidden, idle native service checks run every 30 seconds; hidden web tabs skip state polling.

The local HTTPS service and AI connection remain resident. Idle use is not zero power. On the connected glasses, a 55-second idle sample made two job requests, no extra heartbeats and one service check, while AI remained available. The home label was absent and a synthetic RAG summary/definition test passed. These are request counts, not a battery-saving percentage: the glasses were USB-powered and battery drain was not measured.

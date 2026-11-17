# App lifecycle / 실행·종료 동작 — v0.3.3

## 한국어

v0.3.2의 ‘숨겨진 상태에서도 30초 점검’ 설계를 변경했습니다.

- 메뉴에서 앱 실행: 회의 서비스, STT Binder 연결, HTTPS 관리 화면을 시작하고 도우미에 활성 이벤트를 보냅니다.
- 홈으로 나가기·앱 화면 종료: 음성 인식을 중지합니다. 인식 중이었다면 마지막 문장을 최대 2.5초 수신한 뒤 서비스를 종료합니다. 기록은 유지하며 자동으로 녹취를 재개하지 않습니다. 진행 중인 기록은 interrupted로 보존합니다.
- 회의 서비스 종료: 웹 서버·STT 연결·WakeLock·서비스 점검 타이머를 종료하고, 도우미의 모델 세션·HTTP 조회·heartbeat·재시도 타이머를 취소합니다. 진행 중인 최종 요약도 중단되므로 다시 앱을 열고 기록에서 재생성할 수 있습니다.
- 활성 상태의 도우미 작업 조회는 기존 25초 long polling을 사용합니다. 앱이 닫히면 조회 자체가 없습니다.

네이티브 프로세스와 Rokid 호스트 안에는 **수동 대기 중인 인증된 루프백 WebSocket**이 남습니다. 새 앱 실행 이벤트를 전달하는 용도이며 ping·주기적 타이머가 없습니다. 마이크·모델·웹 관리 서비스를 유지하는 방식은 아니지만, 프로세스·소켓과 메모리가 완전히 제거되는 것은 아닙니다. 배터리 소비 0 또는 OS 프로세스 완전 종료를 주장하지 않습니다.

### 실기 검증 (2026-11-17)

연결된 안경에서 합성 RAG 요약·용어 생성 성공 후 홈으로 나갔습니다. 회의 서비스가 사라지고 HTTPS 접속이 닫힌 것을 확인했습니다. 그 뒤 55초 동안 도우미 HTTP 요청 증가 0, 작업 조회 증가 0, heartbeat 증가 0을 확인했습니다. 수동 lifecycle 연결만 1개 유지되었습니다. 이는 v0.3.2의 55초당 2회 조회/1회 점검과 다른 동작입니다. USB 충전 상태이므로 배터리 소모율은 측정하지 않았습니다.

### 남은 제한

정상적인 홈 ↔ 앱 이동과 프로세스가 유지된 상태의 재실행을 대상으로 합니다. 재부팅·강제 종료·OS 메모리 회수로 lifecycle 채널이 끊긴 뒤 자동 재연결은 구현되지 않았습니다. **앱을 연 뒤 PC에서 Install-Helper.ps1을 다시 실행**해야 합니다. 재접속 타이머를 몰래 남겨 이 제한을 가리지 않습니다. 새 설치에서도 APK·도우미를 함께 준비해야 합니다. 일반 앱의 직접 도우미 실행 요청은 실제 펌웨어에서 CALLER_UNAUTHORIZED로 거절되었습니다. DUMP 권한을 추가하는 방법도 해결책이 아니었으며, 최종 APK는 이 권한을 요청하지 않습니다.

검증: JVM 37개, helper 9개, Android lint/build, AIX check. 새 helper 테스트는 비활성 앱의 조회·모델·타이머 0, 재실행, 채널 종료 후 재시도 타이머가 없는 것을 확인합니다.

## English

v0.3.3 replaces v0.3.2's hidden 30-second checks with app-owned operation. Opening the meeting screen starts the service, STT connection and HTTPS server. Leaving it stops recognition (allowing up to 2.5 seconds for final events), preserves records and shuts those services down. In-progress meetings are stored as interrupted; capture does not resume automatically. Pending AI work is cancelled, and final recaps can be regenerated from history after reopening.

An authenticated passive loopback WebSocket remains in the cached native process and Rokid host. It has no periodic ping or reconnect timer, and only carries app lifecycle signals. This is not complete process/socket removal or a claim of zero battery consumption.

On the connected glasses, a synthetic summary/term test passed. After returning home, the meeting service was absent and HTTPS stopped accepting connections. The next 55 seconds produced no helper HTTP requests, job polls or heartbeats, with one passive lifecycle connection remaining. Battery drain was not measured while USB-powered.

**Automatic AI reconnection after reboot, force-stop or OS process reclamation is not implemented. Open Conversate and rerun Install-Helper.ps1 from the PC in that case.** Direct helper launch from an ordinary APK was rejected by the firmware, even with a development DUMP grant; the final APK requests no DUMP permission. This preview does not hide this limitation with background retry timers.

Validation: 37 JVM tests, 9 helper tests, Android lint/build and AIX check.

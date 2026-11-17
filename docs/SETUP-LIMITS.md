# APK-only AI setup: current limits

## 한국어

현재 검증한 펌웨어에서는 **APK 하나만 설치해서 AI 도우미의 최초 설치·배치·실행까지 자동으로 끝내는 기능은 구현되지 않았습니다.** 배포 ZIP은 개발자 설치 절차를 묶은 것이며, 일반 사용자용 APK 단독 설치 경험과 같지 않습니다.

### 왜 APK에 도우미를 넣는 것만으로 해결되지 않나요?

Node.js는 도우미를 검사·패키징하고 배포 명령을 보내는 개발 도구입니다. 개발자가 도우미를 미리 패키징하면 사용자의 Node.js 준비 과정은 줄일 수 있습니다. 그러나 APK에 AIX를 동봉하는 것과 **Rokid 호스트에 설치·위젯 실행 권한을 얻는 것**은 별개입니다.

현재 기기와 일치하는 AssistServer APK에서 확인한 사항:

- `DevelopAgentCommandProvider`와 `DevelopAgentApplyReceiver`는 `android.permission.DUMP`로 보호됩니다.
- 일반 AIUI 서비스는 Rokid의 서명 권한으로 보호되며 AIUI Activity는 외부에 공개되지 않습니다.
- 실제 기기에서 DUMP의 보호 수준은 `signature|privileged|development`입니다. 일반 앱의 권한 요청 대화상자로 받을 수 있는 권한이 아닙니다.
- 공식 AIX CLI의 개발 배포는 ADB shell의 `content call`로 이 provider를 호출합니다. 일반 APK에서 동일한 ContentResolver 코드를 쓰는 것만으로 shell 권한을 얻지는 않습니다.

그래서 현재 APK만으로 설치가 끝난다고 안내하지 않습니다. 루팅·시스템 서명 변경·계정 자격증명 복사로 해결한 것도 아닙니다. 이미 설치되고 실행 중인 도우미의 재연결과, 새 장치에 도우미를 최초 설치하는 일도 구분해야 합니다.

### 일반 사용자 배포를 위한 경로

Rokid는 AIUI Studio에서 AIX를 패키징·업로드하고 심사를 거쳐 Hi Rokid Agent Store에 배포하는 경로를 문서화했습니다. 이 경로를 이용하면 개발자 CLI를 사용자에게 요구하지 않는 배포를 검토할 수 있습니다.

다만 현재 프로젝트의 스토어 등록은 완료되지 않았고, 스토어에서 설치한 도우미와 APK 사이의 최초 연결 승인, 위젯 배치·재시작도 새로 설계·검증해야 합니다. 공식 배포 문서는 중국 본토 사용자를 명시하므로, 한국과 다른 지역 계정의 지원 범위도 별도로 확인해야 합니다. 스토어 등록만으로 APK 자동 연결까지 해결됐다고 주장하지 않습니다.

### 코드에 한국어가 있어도 외국어로 표시되나요?

네. `L10n.t("서비스 연결 · 등록 요청 완료")`의 한국어는 번역 사전의 원문 키입니다. 영어에서는 `Service connected · Registration requested`를 반환합니다. 다른 언어는 번역 팩을 준비하는 동안 영어를 사용합니다.

네이티브 원문 키 183개 모두 카탈로그에 있고, 영어 값 316개에서 한국어 잔존과 자리표시자 불일치가 없는 것을 테스트했습니다. `e.message` 같은 OS·Rokid 오류 상세는 제공자의 원문으로 남을 수 있습니다. 누락된 미래 키도 원문으로 돌아올 수 있으므로, 원문 키 전체를 검사하는 회귀 테스트를 추가했습니다.

## English

**APK-only first-time installation and launch of the AI helper is not implemented on the verified firmware.** The Windows ZIP packages the developer setup; it is not a consumer APK-only onboarding flow.

Prebuilding the AIX package can remove the need for end users to build it with Node.js. It does not grant the ordinary APK access to the protected Rokid deployment provider. The analyzed `DevelopAgentCommandProvider` and `DevelopAgentApplyReceiver` require `android.permission.DUMP`; the installed device reports `signature|privileged|development` protection. The official CLI reaches this interface from ADB shell. Bundling a package does not turn an app into a shell/system caller.

The documented consumer distribution route is AIUI Studio review and the Hi Rokid Agent Store. This helper has not been approved or published there. Secure first-time pairing, widget placement and restart behavior still need design and testing, and the publishing document specifically mentions Chinese-mainland users; other regions must be checked separately.

Korean literals passed to `L10n.t` are source catalogue keys, not necessarily displayed Korean text. The English catalogue covers all 183 native literal calls with 316 non-Korean English values and matching placeholders. OEM error details remain verbatim. Automated source-coverage tests now guard against missing future native catalogue entries.

## Evidence and scope

- Verified installed AssistServer: `0.4.1` / code `4124`; SHA-256 `c93ce34b6f67f47dc2f20f9f6c634b314665487dc5753ccd4d47e7175f5b2878`, matching the analyzed local artifact.
- REA manifest Evidence: `ev_c632c59afbbb12e742bbff402e3164bd9ddbbe29cc029074ac54a4db97673deb`.
- Saved provider method Evidence: `ev_0bcbd662212f3a6af2e230f8bf1b2593d230bace6a8287c3442009c802d4618f`.
- Official AIX CLI `0.13.0`: `content://com.rokid.aiui.develop`, prepare/apply/status and widget configuration/open operations. Static inspection does not prove every possible integration path absent; these are the verified restrictions of the current route.
- Device checks were read-only. No meeting was restarted, no permissions were granted and no helper was reinstalled for this investigation. Raw extracted artifacts remain excluded from the repository.

Primary documentation:

- [AIUI Studio Global QuickStart](https://open.rokid.com/aiui/guide/quickstart/quickstart?lang=en-US&version=0.19.0)
- [Submit for Review and Publish](https://open.rokid.com/aiui/guide/bundle/publish?lang=en-US&version=0.19.0)
- [Official CLI workflow](https://open.rokid.com/aiui/guide/debug/cli_device_debug?lang=en-US&version=0.19.0)

# APK와 Rokid AI 도우미 설치

[한국어](INSTALL.ko.md) · [English](INSTALL.en.md) · [프로젝트](https://github.com/hardline7/Rokid-Conversate)

**앱을 직접 빌드할 필요 없이 릴리스 파일로 설치할 수 있습니다.** 안경 앱과 AI 도우미는 두 구성 요소입니다. 자막은 APK에서 연결하고, AI 요약·용어 풀이는 AI 도우미 위젯까지 설치해야 동작합니다.

## 다운로드할 파일

[v0.3.1 릴리스](https://github.com/hardline7/Rokid-Conversate/releases/tag/v0.3.1)의 Assets에서 받으세요.

| 파일 | 용도 |
|---|---|
| `Rokid-Conversate-v0.3.1-Windows-Setup.zip` | 권장. APK, AI 도우미 소스, 자동 설치 스크립트, 한영 안내가 함께 들어 있습니다. |
| `Rokid-Conversate-v0.3.1.apk` | APK만 수동 설치할 때 사용합니다. 이것만으로 AI 위젯이 설치되지는 않습니다. |
| `SHA256SUMS.txt` | 다운로드한 APK와 설치 ZIP의 SHA-256을 확인합니다. |

GitHub가 자동 제공하는 Source code ZIP 대신, **Windows-Setup.zip**을 선택해야 APK가 함께 들어 있습니다.

## 준비물

1. 실제 검증 장치는 **Rokid RG_glasses / Android 12(API 32)**입니다. 다른 모델·펌웨어는 동작이 다를 수 있습니다.
2. 안경을 폰의 Rokid 앱과 연결하고, 기본 자막과 Rokid AI를 사용할 수 있는지 확인합니다.
3. USB 데이터 케이블로 안경을 PC에 연결하고 USB 디버깅을 켭니다. 안경에 승인 창이 뜨면 본인 PC의 연결을 승인합니다.
4. [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)를 준비하고 `adb`가 PATH에서 실행되게 합니다.
5. [Node.js](https://nodejs.org/) **20 이상**과 함께 제공되는 npm을 설치합니다. 설치 후 PowerShell을 다시 엽니다.

```powershell
adb devices -l
node --version
npm --version
```

안경 상태는 `device`여야 합니다. `unauthorized`라면 안경에서 USB 디버깅 승인을 확인합니다. 이 설치에는 Android Studio, Android SDK 전체, JDK, Gradle 빌드가 필요하지 않습니다.

## 권장: Windows에서 한 번에 설치

1. **진행 중인 회의를 먼저 종료**합니다. 업데이트 중에는 인식을 계속할 수 없습니다.
2. Windows Setup ZIP을 폴더에 **완전히 압축 해제**합니다. ZIP 내부에서 스크립트를 바로 실행하지 마세요.
3. 압축을 푼 폴더에서 PowerShell을 열고 실행합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-Conversate.ps1
```

장치가 여러 개라면 대상 안경 ID를 지정합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-Conversate.ps1 -Serial <장치-ID>
```

설치기는 다음 작업을 수행합니다.

- APK의 SHA-256과 장치 연결·필수 도구를 확인합니다.
- 앱이 아직 인식 스트림을 소유한 경우에는 업데이트를 시작하지 않습니다. 일시정지된 회의도 먼저 정상 종료해 주세요.
- APK를 `adb install -r`로 설치하고 안경 앱을 엽니다.
- 버전이 고정된 공식 `@yodaos-pkg/aix-cli@0.13.0`을 준비합니다.
- Rokid Developer Mode를 켜고 프로젝트의 AI 도우미 위젯을 설치·실행합니다.
- 위젯이 연결되도록 홈 화면을 잠시 열고 Conversate로 돌아옵니다.

개발자 모드는 도우미 실행을 위해 유지됩니다. 설치기는 회의를 시작하지 않습니다. 첫 설치 때 npm 패키지 다운로드와 Rokid 도우미 등록에 네트워크 연결이 필요합니다.

## 안경과 브라우저에서 확인

1. 안경에서 Conversate가 열리고 **Rokid AI 연결됨** 상태가 나오는지 기다립니다. 언어 설정에 따라 문구는 다릅니다.
2. 안경에 표시된 `https://<안경-IP>:8443` 주소를 같은 네트워크의 폰·PC 브라우저에서 엽니다.
3. 자체 서명 인증서 경고는 본인 안경의 주소인지 확인한 뒤 해당 장치에 대해 허용합니다.
4. **현재 안경의 6자리 PIN**을 입력합니다. 앱 서비스 재시작 후에는 PIN이 바뀝니다.
5. 설정의 Rokid AI 연결 테스트를 실행합니다. 실제 회의 기록에는 테스트 내용이 저장되지 않습니다.
6. 자료와 표시 옵션을 선택하고, 참석자에게 알린 뒤 회의를 시작합니다.

기본 화면 상단은 발화 묶음 요약입니다. 스와이프로 이전·다음 요약을 보고, 탭으로 요약/Cue/자료를 전환합니다. 실시간 자막은 독립적으로 켜고 끌 수 있습니다.

## APK만 수동 설치

```powershell
adb install -r .\Rokid-Conversate-v0.3.1.apk
adb shell am start -W -n app.conversate.glass/.meeting.MeetingActivity
```

그다음 Windows Setup ZIP을 풀고 같은 폴더에서 도우미만 설치합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-Helper.ps1
# 여러 장치:
powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-Helper.ps1 -Serial <장치-ID>
```

단순히 `.aix` 파일을 복사하는 것으로는 연결이 완성되지 않습니다. 도우미를 실행할 때 앱이 생성한 **장치별 로컬 연결용 비밀값**을 전달해야 하므로 설치 스크립트를 사용합니다. Rokid 계정 토큰이나 외부 AI API 키를 입력할 필요는 없습니다.

macOS/Linux에서도 APK는 ADB로 설치할 수 있습니다. 이 릴리스의 AI 도우미 자동 설치기는 **Windows에서 검증한 PowerShell 방식**입니다. 다른 OS의 도우미 설치 절차까지 검증했다고 주장하지 않습니다.

## 외부 API와 로컬 처리의 정확한 의미

**별도 외부 AI API 가입·키·개발자 서버 없이, 로컬 앱·로컬 기록·Rokid 기본 STT와 Rokid AI를 사용합니다.** OpenAI/Claude 등 제3자 AI API를 직접 호출하는 기능은 없습니다.

앱과 브라우저의 연결은 안경의 HTTPS 서버, 앱과 도우미의 연결은 인증된 `127.0.0.1` 루프백입니다. 도우미는 Rokid 호스트의 공식 `LanguageModel` 기능을 호출합니다. 따라서 **“API 호출이 전혀 없다” 또는 “AI까지 완전 오프라인”이라는 뜻은 아닙니다.** Rokid의 폰·클라우드 통신, 계정 한도와 서비스 정책은 그대로 적용될 수 있습니다. 앱은 Rokid의 서버 처리를 오프라인으로 바꾸지 않습니다.

## 업데이트와 서명

이번 프리뷰는 **개발/debug 서명 APK**입니다. 도우미 설치에 사용하는 `adb run-as` 연결 방식 때문에 일반 release APK로 단순히 교체할 수 없습니다. 개인 키나 기존 장치의 런타임 연결 비밀값은 배포 ZIP에 포함하지 않습니다.

- 같은 서명의 APK는 회의 종료 후 `install -r`로 업데이트합니다.
- 직접 빌드한 APK와 배포 APK는 서명이 다를 수 있습니다. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 뜨면 설치를 멈추고 기존 기록을 Markdown으로 먼저 내보내세요.
- 앱을 삭제하면 로컬 기록과 암호화 키를 잃을 수 있습니다. 서명 문제를 해결하려고 자동으로 삭제하는 동작은 넣지 않았습니다.
- 자동 업데이트 기능은 없습니다. 새 버전 릴리스에서 새 설치 패키지를 받으세요.

## 문제가 생기면

| 증상 | 해결 |
|---|---|
| `adb`를 못 찾음 | Platform-Tools 폴더를 PATH에 넣고 새 PowerShell 창을 엽니다. |
| 장치를 찾지 못함 | 충전 전용이 아닌 USB 데이터 케이블, USB 디버깅과 승인 창을 확인합니다. |
| Node.js 오류 | Node.js 20 이상과 npm 설치 후 터미널을 다시 엽니다. |
| 진행 중 인식 경고 | 회의를 정상 종료하고 폰의 기본 자막도 종료됐는지 확인합니다. |
| AI 연결 대기 | 폰의 Rokid 연결·AI 사용 가능 상태를 확인하고 홈 화면을 잠시 열었다 복귀합니다. 필요하면 회의 종료 후 `Install-Helper.ps1`를 다시 실행합니다. |
| 다른 언어에서 영어 표시 | 한국어·영어는 내장, 그 외 UI는 Rokid AI 번역 준비 중 영어로 표시됩니다. |
| 브라우저 연결 실패 | 현재 안경 주소·같은 네트워크·현재 PIN을 확인합니다. |

더 자세한 기능과 검증 범위는 [한국어 README](https://github.com/hardline7/Rokid-Conversate/blob/main/README.md)를 참고하세요.

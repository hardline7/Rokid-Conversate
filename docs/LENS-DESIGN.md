# Lens interface — v0.3.1

## 화면 구성

- **상태바:** 회의 상태, 경과 시간, 배터리를 작은 글자와 아이콘으로 정리합니다.
- **탭:** 현재 화면을 윤곽선과 밝기 차이로 구분합니다. 탭 조작은 기존처럼 요약/회의 → Cue → 자료 순서입니다.
- **요약:** 상단 카드에 순서, 최신/이전 상태, 제목과 짧은 본문을 표시합니다.
- **용어:** 정의를 요약과 분리해 표시합니다. 해당 요약에 용어가 없으면 그 영역은 비워 둡니다.
- **자막:** 하단 구분선 아래에 낮은 밝기로 표시합니다. 큰 글자는 최근 2줄, 기본/작은 글자는 최근 3줄입니다.
- **대기:** 연결 상태, 관리 주소와 큰 6자리 PIN을 한눈에 확인할 수 있게 배치했습니다.
- **확인·자료·Cue:** 같은 여백·윤곽·글자 체계를 사용합니다. 회의 제어와 저장 동작은 변경하지 않았습니다.

검정은 안경에서 빛을 내지 않는 영역으로 사용하고 녹색만 그립니다. 넓은 채움·그라디언트·반복 장식 애니메이션은 넣지 않았습니다. 화면 크기는 480×640, 기존에 측정한 안전 영역은 x16..463/y40..559입니다.

요약 본문은 최대 72자/4줄, 용어 정의는 최대 40자/2줄에 맞추며 필요한 경우 글자를 조금 줄입니다. 자료 페이지는 서비스에서 나눈 약 140자 단위를 사용합니다. 아주 긴 자료 제목·상태 레이블에는 말줄임이 적용될 수 있고, 전체 원문은 웹에서 봅니다.

## Native rendering checks

`lens-preview` is an opt-in developer-only Android module. It copies the production `MeetingLensView` and `L10n` sources during the build and renders them to PNGs with the device's actual fonts. It has no activity, microphone/network permissions, meeting service or access to the meeting app's private data. All fixture text, addresses and PINs are synthetic.

The instrumentation targets **its own separate package**, not the production meeting process. Do not change the target to `app.conversate.glass`, as instrumenting that package could interrupt a meeting.

```powershell
.\gradlew.bat -PlensPreview=true :lens-preview:assembleDebug
adb install -r lens-preview/build/outputs/apk/debug/lens-preview-debug.apk
adb shell am instrument -w app.conversate.glass.lenspreview/.LensPreviewInstrumentation
```

Expected result: `rendered=36`, `INSTRUMENTATION_CODE: -1`, and no `error` result. ADB can return a successful shell exit even if instrumentation reports an error; inspect the result text.

Cases cover Korean/English ready, summary, older summary, captions off, pause, cue history, notes, waiting, confirmation, offline, large text, maximum text, cue-only, cue-only with captions off, end confirmation, empty lists and a 140-character notes page. Checks require green-only pixels, nonempty rendering and no light outside the safe rectangle or against its lower clipping edge.

PNGs are written to `files/lens-previews` inside the preview package. Retrieve them using `adb exec-out run-as app.conversate.glass.lenspreview ...` with a binary-safe tool. After copying the images:

```powershell
adb uninstall app.conversate.glass.lenspreview
```

Do not uninstall the actual meeting app. The normal release build excludes this preview module, and the release packager uploads only the production APK.

## Limits

Native raster checks and screenshot review do not measure perceived brightness through the optics, comfort over a long meeting, battery performance or every translated label. Those still need wear testing. Meeting control gestures and STT/AI integration remain the existing implementation; this update does not add APK-only AI helper provisioning.

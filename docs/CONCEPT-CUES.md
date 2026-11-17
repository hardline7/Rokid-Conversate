# 대화 속 키워드 풀이 / Contextual concept cues

조사·구현: 2026-11-18 · v0.3.4

## G2에서 확인한 동작

Even의 공식 제품 소개는 Concepts를 대화 중 전문 용어와 낯선 개념을 짧게 설명하는 Cue로 소개합니다. 용어 뜻을 찾아보거나 질문하느라 대화를 멈추는 일을 줄이는 용도입니다. Answers는 준비 자료의 정보, Bios는 언급된 인물의 배경, Suggestions는 대화에 도움이 될 제안으로 구분합니다. [공식 제품 소개](https://www.evenrealities.com/en-GB/conversate)

공식 지원 문서는 대화를 실시간 분석해 맥락에 맞는 Cue를 만들고, 자동 팝업 여부와 표시 시간을 설정하며 Cue 목록에서 다시 확인하는 흐름을 설명합니다. 최신 2.4.0 문서는 일곱 범주도 안내합니다. [공식 사용 안내](https://support.evenrealities.com/hc/en-us/articles/14273795154319-Conversate)

**공개 자료만으로 내부 키워드 선정 모델·프롬프트·임계값을 알아낼 수는 없습니다.** 모든 풀이가 실시간 웹 검색으로 검증된다는 근거도 확인하지 못했습니다. 아래 흐름은 공개된 사용자 경험을 참고해 우리 앱에 설계한 구현이며, G2 내부 알고리즘이나 Pro Mode의 복제라고 주장하지 않습니다.

## Rokid 구현

1. Rokid 기본 STT의 **확정 발화**만 사용합니다. 부분 인식으로 용어 카드를 만들지 않습니다.
2. 요약 생성과 별도로, 새 발화와 앞선 최대 6개 발화의 맥락을 Rokid AI에 전달합니다. 약 1.2초 멈춤, 6초 이상 이어지는 발화 또는 새 확정 발화 3개가 분석 계기입니다. 요청 간격은 최소 8초이고 다른 AI 작업 중에는 기다립니다. 응답 시간 보장은 아닙니다.
3. AI가 새 전문 용어나 약어 하나를 선택합니다. 일반 단어·모호한 약어·회사 내부 용어·불확실한 음성 인식은 건너뛰라고 지시합니다. 이미 표시한 개념 목록도 전달합니다. 단어 사전이나 하드코딩한 RAG 목록으로 대체하지 않습니다.
4. 앱은 용어와 발화 ID, 정확한 언급 구절이 실제 입력에 있는지 검사합니다. RAG가 FRAGMENT 안에 있다는 이유로 일치시키지 않습니다. 개념 설명은 모델 신뢰도 0.9 이상을 요구하고 모호한 설명을 거절합니다. 이는 진실성을 증명하는 절차는 아니며, 모델의 일반 지식 풀이를 웹 검증 결과로 표시하지 않습니다.
5. 새 Cue는 **요약 아래에 약 9초 표시**하고 AI Cue 탭에 점으로 알립니다. 요약은 유지합니다. 안경테 한 번 탭으로 AI Cue 상세 화면, 스와이프로 저장된 이전·다음 Cue를 봅니다. 설정에서 Cue 자동 표시를 끄면 팝업 없이 목록만 유지합니다. AI Cue 자체를 끄면 분석도 중지합니다.
6. 동일 개념은 한 회의에 한 번만 표시하고 과다 알림을 제한합니다(분당 최대 4개, 표시 간 최소 13초). 30초 넘게 묵은 미처리 발화는 새 팝업의 대상으로 쓰지 않습니다.
7. Cue에는 원래 발화의 ID와 언급 구절을 보관합니다. 웹의 Cue 목록에서 원래 언급을 확인할 수 있습니다. 상세 화면에는 **Rokid AI · 일반 지식**으로 표시하며 사전 자료 인용과 구별합니다.

예: ‘이번 AI 검색 시스템에 RAG를 적용해 봅시다’ → RAG의 검색 증강 생성 개념을 설명합니다. 회의의 도입 결정으로 바꾸어 요약하지 않습니다. 실제 회의에서 모든 용어를 검출하거나 모든 설명이 정확하다고 보장하지 않습니다.

별도 외부 AI API/키를 추가하지 않았고 출력 언어는 Rokid 설정을 따릅니다. Rokid의 네트워크·계정 의존성은 유지됩니다. 앱 종료 후 조회·AI 작업을 멈추는 v0.3.3 동작과 재부팅/강제 종료 복구 제한도 그대로입니다. [실행·종료 제한](POWER.md)

## 확인한 것과 남은 것

- 실제 안경의 Rokid AI가 합성 RAG 발화에서 Concepts Cue와 설명, 정확한 언급 구절을 반환했고 앱 검증을 통과했습니다. 이 테스트는 회의를 시작하거나 기록을 추가하지 않습니다.
- JVM 43개: 확정 발화 배치, 요청 간격, 오래된 발화 건너뛰기, 약어 경계, 발화 근거, 신뢰도, 중복 방지 및 자동 표시 설정 저장 등을 확인했습니다.
- 한국어/영어 네이티브 렌더 42종에 새 팝업·상세·팝업 꺼짐을 포함했습니다. 웹 데스크톱/모바일에서 번역·설정 저장·지식 출처 표시를 확인했습니다.
- 장시간 실제 회의에서의 용어 검출률, 오탐률과 광학 착용 가독성은 별도 검증이 필요합니다. 합성 입력의 성공을 실제 회의 전체 성공으로 확대하지 않습니다.

## English

Even's public documentation describes contextual concept explanations, automatic cue presentation and a saved Cue List; its internal selection algorithm and prompts are not public. Our implementation recreates that interaction using Rokid's own STT and AI, without adding an external AI API key.

New committed utterances trigger cue analysis independently of summary creation. The model receives recent context and already-explained terms. Native validation requires the actual term, utterance ID and verbatim triggering excerpt, a high concept-confidence score and no vague definition. Acronym fragments do not count as mentions. Confidence is a filter, not a guarantee of factual correctness.

New cues appear below the summary for about nine seconds, with a dot on the AI Cue tab. Single-tap cycles to the full cue; swipe browses history. Auto pop-up can be disabled while keeping cues in history. Disabling AI Cue stops cue generation. Concepts are deduplicated per session, rate limited and labelled as general model knowledge rather than verified research. The web history retains the triggering speech excerpt.

Real-device synthetic RAG generation and validation passed, along with 43 JVM tests, 42 bilingual native rendering cases and companion browser regressions. Long-meeting detection/false-positive rates and optical comfort remain unmeasured. Existing lifecycle/reboot recovery limits still apply.

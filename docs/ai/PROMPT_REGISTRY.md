# Prompt Registry

> Last updated: 2026-05-26
> 모든 LLM 프롬프트 ID의 카탈로그·버전 관리 — banking-review-llm.md §9, llm-pipeline.md §2.

신규 프롬프트 추가 시 본 문서에 항목 추가 필수. promptVer 변경 시 변경 이력에 기록.

---

## 프롬프트 목록

| ID | 버전 | 소유 서비스 | 백엔드 | 용도 |
|----|------|-----------|--------|------|
| `purpose_analysis` | v1 | `PurposeAnalysisService` | YAML | 신청 사유 plausibility·specificity 분석 |
| `review_report_track1` | v1 | `ReviewReportService` | YAML | Track 1 자동승인 심사 리포트 |
| `review_report_track2` | v1 | `ReviewReportService` | YAML | Track 2 자동반려 심사 리포트 |
| `review_report_track3` | v1 | `ReviewReportService` | YAML | Track 3 사람심사 리포트 |
| `agent_reasoning_summary` | v1 | `PreReviewAgentService` | 인라인 | Track 3 에이전트 위험도 요약 (1~2문장) |
| `rejection_reason_draft` | v1 | `RejectionReasonAgentService` | 인라인 | Track 2 거절 통보문 초안 (고객 전달용) |

---

## 프롬프트 상세

### `purpose_analysis` v1

- **파일**: `agents/auto-loan-review/src/main/resources/prompts/purpose_analysis_v1.yml`
- **용도**: 신청 사유(목적 코드 + 자유 텍스트)와 페르소나 일관성을 분석해 plausibility·specificity(0~1) 및 RedFlag 목록 반환
- **입력 키**: `persona_summary`, `purpose_cd`, `product_code`, `amount_kw`, `period_mo`
- **출력 스키마**: `PurposeAnalysis` record (`plausibility`, `specificity`, `redFlags`, `reasoning`)
- **트리거**: `AutoReviewEventListener` Step 1 (비동기 파이프라인)
- **PII 보호**: `PiiAwareChatClient` 통과 — 이름·주민번호·계좌 마스킹
- **비용 구간**: ~200 input / ~100 output tokens

---

### `review_report_track1` / `review_report_track2` / `review_report_track3` v1

- **파일**: `agents/auto-loan-review/src/main/resources/prompts/review_report_track{1|2|3}_v1.yml`
- **용도**: RuleEngine 트랙 결정 결과를 받아 트랙별 톤의 심사 리포트 생성
- **트랙별 산출**:
  - Track 1: 1문단 summary + strengths, citations ≥ 1
  - Track 2: 2문단 (거절 사유 + 통보문 초안) + riskFactors, citations ≥ 2
  - Track 3: 3문단 (위험/강점/권고) + riskFactors + strengths
- **출력 스키마**: `ReviewReport` record
- **트리거**: `AutoReviewEventListener` Step 2 (비동기 파이프라인)
- **grounding**: `GroundingValidator` — citation ID 정책 인덱스 검증, Track 2 인용 ≥ 2 강제
- **비용 구간**: ~400 input / ~500 output tokens

---

### `agent_reasoning_summary` v1

- **백엔드**: 인라인 system prompt (`PreReviewAgentService.SYSTEM_PROMPT`)
- **용도**: Track 3 에이전트가 PolicyFlag·PurposeAnalysis·시뮬레이션 결과를 종합해 1~2문장 위험도 요약 생성
- **입력**: 트랙·decision_score·pd_score·정책 경고·사유 분석·시뮬레이션 결과 텍스트
- **출력 스키마**: `AgentReasoningSummary` record (`summary`)
- **트리거**: `PreReviewAgentService.generateReasoningSummary()` — Track 3 only, 비율 제한 통과 시
- **폴백**: `LlmRequestRateMeter` 초과 또는 `LlmCallException` → `templateSummary()` 사용
- **루프 가드**: `AgentLoopGuard` LLM 호출 ≤ 2회
- **비용 구간**: ~300 input / ~100 output tokens

---

### `rejection_reason_draft` v1

- **백엔드**: 인라인 system prompt (`RejectionReasonAgentService.SYSTEM_PROMPT`)
- **용도**: Track 2 거절 케이스에서 고객 전달용 거절 통보문 초안 생성 (여신전문금융업법 §17 준수)
- **입력 키**: 상품 코드, 세그먼트, 거절 사유 코드 목록, 정책 근거 (rationale)
- **출력 스키마**: `RejectionNoticeDraft` record (`notice`)
- **트리거**: `PreReviewAgentService.buildTrack2Opinion()` → `RejectionReasonAgentService.draft()`
- **폴백**: kill switch / 비율 제한 / LLM 예외 → `templateNotice()` 사용
- **준법 마킹**: 호출 시 `AgentOpinion.policyFlags`에 `COMPLIANCE_REVIEW_REQUIRED` 자동 추가
- **비용 구간**: ~150 input / ~100 output tokens

---

## 변경 이력

| 날짜 | ID | 변경 | 담당 |
|------|----|------|------|
| 2026-05-26 | `purpose_analysis` v1 | 최초 등록 (llm-pipeline.md L2) | ai-service |
| 2026-05-26 | `review_report_track{1,2,3}` v1 | 최초 등록 (llm-pipeline.md L7) | ai-service |
| 2026-05-26 | `agent_reasoning_summary` v1 | 신규 등록 (pre-review-agent-plan A4) | ai-service |
| 2026-05-26 | `rejection_reason_draft` v1 | 신규 등록 (pre-review-agent-plan A8) | ai-service |

---

## 운영 원칙

1. **버전 올림**: system prompt 또는 user prompt template 변경 시 `promptVer` +1, 본 문서 이력 추가
2. **동결 범위**: 거버넌스 동결 프롬프트 변경 시 신용정책위 의결 후 반영
3. **PII 검토**: 신규 입력 필드 추가 시 `PiiAwareChatClient` 마스킹 대상 여부 확인 필수
4. **비용 추적**: `LlmCostMeter` `prompt_id` 태그로 프롬프트별 토큰 비용 Grafana 대시보드 조회
5. **Stub 지원**: `StubLlmClient` 에 promptId case 추가 필수 (CI 결정론 테스트용)

# AI 시스템 운영 전략 — 이 레포에 무엇이 있고 무엇이 없는가

> 대상 독자: 이 레포의 AI 기능을 배포·운영·개선하는 사람
> 범위: 섀도 모드 · 카나리 배포 · 회귀 테스트 · 트레이스 · 자가 치유

AI 시스템은 코드가 그대로여도 결과가 달라진다. 모델이 바뀌고, 데이터가 바뀌고,
같은 입력에도 다른 답이 나온다. 그래서 "배포했으니 끝" 이 성립하지 않는다.

이 문서는 다섯 가지 장치를 **이 레포 기준으로** 정리한다. 일반론은 짧게 두고,
실제로 어디에 구현돼 있는지와 **아직 없는 것**을 분명히 적는다.

---

## 1. 섀도 모드 — 사용자 영향 없이 새 것을 돌려 본다

새 구현을 기존 것과 나란히 돌리되 결과는 버린다. 사용자는 기존 것만 본다.

### 이 레포에서

**있다.** auto-loan-review 가 RAG 백엔드를 섀도로 돌린다.

```yaml
# agents/auto-loan-review/src/main/resources/application.yml
ai.rag.backend:        ${AI_RAG_BACKEND:inline}          # 실제로 쓰는 것
ai.shadow.rag-backend: ${AI_SHADOW_RAG_BACKEND:inline}   # 같이 돌려 보는 것
```

`ShadowModeService` 가 별도 스레드풀(`shadowExecutor`)에서 실행한다.
**풀을 나눈 것이 핵심이다** — 같은 풀을 쓰면 섀도 부하가 실제 응답을 느리게 만들어,
"영향 없이 실험한다" 는 전제가 깨진다.

### 무엇을 봐야 하는가

섀도의 값어치는 **두 결과가 얼마나 다른지**에 있다. 지금은 백엔드별로 나뉜
검색 품질 지표로 비교할 수 있다.

```promql
rag:top_score:avg30m{backend="inline"}   vs   {backend="es"}
rag:low_relevance_rate:ratio30m
```

### 짝지어 비교한다

`ShadowComparisonEvaluator` 가 실제 실행과 섀도 실행을 대조해 `diverged` 와
`divergeReasons` 를 만든다. 결과는 `ShadowResultRepository` 에 남는다.

불일치는 **전용 지표**로 나간다.

```promql
ai_shadow_divergence_total{track, backend}
```

**성능 지표와 섞지 않는 것이 중요하다.** 예전에는 이것을
`ai.agent.disagreement.total` 로 올렸는데, 그 지표는 AI 근거와 Track 결정이
어긋난 비율 — 이 서비스의 성능 축이다. 섀도 불일치를 거기 섞으면 섀도를 켜는
순간 성능 지표가 오염된다. 새 백엔드를 시험했을 뿐인데 "AI 판단이 나빠졌다" 로
보인다. 섀도의 전제가 "사용자 영향 없이 비교한다" 인데, 그 비교가 성능 지표를
흔들면 전제가 깨진다.

---

## 2. 카나리 배포 — 일부에게만 먼저 연다

전체가 아니라 소수에게 먼저 적용하고, 문제 없으면 넓힌다.

### 이 레포에서

**없다.** 대신 **기능 플래그로 단계적 도입**을 한다.

```
deposit.transfer.approval.required   승인 토큰 필수 여부
deposit.fds.enabled                  이상거래 사전 점검 (기본 false)
fds.investigation.enabled            조사 인계
ai.rag.backend                       RAG 백엔드 선택
```

플래그는 카나리가 아니다. **전부 켜지거나 전부 꺼진다.** 사용자 일부만 새 경로로
보내려면 라우팅 계층이 필요한데 지금은 없다.

### 그래도 안전한 이유

새 통제를 넣을 때 **기본값을 끄고 시작한다.** `TransferApprovalGate` 도
`FdsPreCheckGate` 도 그렇게 도입했다. 켜기 전에 로그로 "그 경로를 타는 요청이
0인가" 를 확인한다 — `TransferApprovalGate` 가 남기는 "승인 토큰 없이 처리됨"
로그가 그 용도다.

카나리만큼 촘촘하지는 않지만, **되돌리는 데 배포가 필요 없다는 점**은 같다.

---

## 3. 회귀 테스트 — 잘 되던 것이 망가졌는지 본다

### 이 레포에서

**있다.** 세 종류가 층을 이룬다.

| 층 | 무엇 | 어디 |
|---|---|---|
| 에이전트 평가 | 루프 분기·fail-closed·HITL 게이트 | `.github/workflows/eval-*.yml` |
| 구조 검사 | 스크레이프 대상·대시보드 라벨·룰 참조·스키마 대조 | `ScrapeTargetConsistencyTest`, `EntitySchemaConsistencyTest` |
| 계약 검사 | 프런트-백엔드 응답 형태 | `web/e2e/*.contract.spec.ts` |

**LLM 키 없이 돈다.** `TRIAGE_LLM_PROVIDER` 미설정이면 mock 이라 결정적으로 검증된다.
실제 API 를 치면 비용도 들고 결과도 매번 달라져 회귀 판정이 불가능하다.

### 이 레포가 특히 신경 쓰는 것

**"조용히 아무 일도 안 하게 되는" 회귀**다. AI 기능은 망가져도 예외가 안 나고
화면도 멀쩡하다. 그래서 테스트를 쓸 때마다 **고치기 전 상태로 되돌려 실패하는지**
확인한다. 통과만 확인한 테스트는 아무것도 지키지 못한 적이 여러 번 있었다.

### 검색 품질 평가셋

**있다.** `agents/auto-loan-review/src/test/resources/rag/retrieval-eval-set.json` —
질의와 그 질의가 찾아야 할 문서 id 의 짝이다.

| 구성 | 무엇 |
|---|---|
| 평가셋 | 질의 11개 · 정책 문서 8종 |
| 계산 | `RetrievalEvaluator` — Recall@K · MRR · Precision@K |
| 러너 | `RetrievalEvalRunner` — 검색기를 함수로 받아 집계 |
| 건강 검사 | `RetrievalEvalSetTest` — 정답이 실제 코퍼스에 있는지 대조 |

**질의를 정책 문장에서 베끼지 않았다.** 그대로 베끼면 어떤 검색기든 만점을 내고,
그 점수는 아무것도 말해주지 않는다. 대신 심사역이 실제로 쓸 표현(`집 살 때 담보
얼마나 인정해주나`), 정식 명칭(`총부채원리금상환비율`), 반대 방향 표현
(`미성년자도 신청 가능한가요` ↔ 정책은 `최소 연령 19세`)을 섞었다.

**평가셋도 썩는다.** 정책 id 가 바뀌면 평가는 없는 정답을 요구하게 되고, 멀쩡한
검색기가 계속 실패로 나와 결국 아무도 그 숫자를 안 본다. `RetrievalEvalSetTest` 가
`application.yml` 의 정책 id 와 대조해 그때 실패한다.

**검색기를 함수로 받는 이유**는 RAG 경로가 둘이기 때문이다. 같은 평가셋을 같은
계산으로 태워야 inline 과 es 를 견줄 수 있다 — 각자 다른 방법으로 재면 숫자가
나와도 비교가 안 된다. 이것이 섀도 모드의 값어치를 여는 열쇠다.

### 아직 없는 것

**실제 백엔드에 대한 정기 측정이 없다.** 평가셋과 계산은 준비됐지만, pgvector·
Elasticsearch 를 띄운 환경에서 주기적으로 돌려 추이를 남기는 자리는 아직 없다.

---

## 4. 트레이스 — AI 가 어떻게 그 답에 이르렀는지 기록한다

### 이 레포에서

**있다.** 자체 구현이다.

```
agents/harness-core/trace/AgentTracer      실행 단계 기록
agents/harness-core/audit/AgentAuditLog    권고·결정 감사 기록
agents/harness-core/audit/AuditImmutabilityVerifier
```

마지막이 이 레포의 특징이다. **기동할 때 감사로그 변조방지 트리거가 살아 있는지
확인한다.** 기록을 남기는 것과 그 기록을 못 고치게 하는 것은 다른 문제다.

조사 에이전트는 도구 선택 이유를 `tool_log` 에 남기고, `_rationale_chain` 이
그것을 사람이 읽을 사슬로 만든다 — 왜 그렇게 판단했는지가 남는다.

### 상용 도구와의 관계

Arize Phoenix·LangSmith 같은 도구가 같은 일을 한다. 갈아타는 것보다
**이미 있는 채택률·disagreement 를 Phoenix 의 Eval 개념으로 정리하는 편**이
실익이 크다 — 특히 Dataset/Experiment 루프는 이 레포의
"심사원 결정이 곧 학습 레이블" 과 같은 구조다.

### 아직 없는 것

**로그·지표·트레이스가 서로 연결돼 있지 않다.** 지표에서 이상을 보고 그 시점의
트레이스로 바로 갈 수 없다. Loki·Tempo 를 붙이면 이어지지만 지금은 없다.

---

## 5. 자가 치유 — 망가지면 스스로 되돌린다

### 이 레포에서

**여러 겹으로 있다.** 공통 원칙은 **"한 부분의 고장이 전체를 세우지 않는다"** 이다.

| 상황 | 동작 | 어디 |
|---|---|---|
| 이상탐지 모델 장애 | 룰만으로 계속 판정 | `PostHocDetectionService` |
| 탐지기 장애 | **금액 구간으로 갈림** — 소액 통과, 고액 보류 | `FdsPreCheckGate` |
| Redis 장애 | 축소 판정(`degraded`)으로 표시하고 진행 | `RiskStateStore` |
| 조사 에이전트 장애 | 탐지는 계속, 인계 실패만 계수 | `InvestigationDispatcher` |
| 보강 조회 실패 | 해당 건만 포기, 스트림 유지 | `PaymentCompletedConsumer` |
| LLM 실패 | 휴리스틱 스코어러로 대체 | `HeuristicDecisionScorer` |
| 사이드카 장애 | 서킷브레이커 | resilience4j (OCR·LLM·위조분석) |

### 두 가지를 구분한다

**fail-open 과 fail-closed 를 상황마다 다르게 정한다.**

- 탐지기가 죽었을 때 **소액은 통과**시킨다 — 전부 막으면 탐지기 장애가 결제 장애가 된다
- 같은 상황에서 **고액은 진행하지 않는다** — 점검 없이 나가면 FDS 를 둔 의미가 없다
- 승인 토큰 검증은 **언제나 fail-closed** — 확인되지 않은 승인으로 돈을 보내지 않는다

"장애 시 어떻게 할지" 를 미리 정해 두는 것이 자가 치유의 실체다.
정하지 않으면 그때그때 다르게 동작하고, 아무도 그것을 모른다.

### 가장 조심할 것

**자가 치유는 문제를 숨긴다.** fail-soft 로 넘어간 건은 겉으로 아무 일도 없어 보인다.
그래서 이 레포는 **넘어간 이유를 반드시 지표로 남긴다.**

```
fds_enrich_failed_total          보강 실패 — 룰이 조용히 못 잡게 되는 경로
fds_event_consumed_total{outcome="unenriched"}
fds_investigation_dispatch_total{outcome="failed"}
ai_agent_fallback_total{reason}
```

실제로 이 지표들이 오늘 두 건의 결함을 드러냈다 — 사전 점검이 401 로 막혀 있던 것과
보강 조회가 404 이던 것. 둘 다 fail-soft 가 가려서 화면으로는 멀쩡했다.

---

## 정리 — 있는 것과 없는 것

| 장치 | 상태 | 비고 |
|---|---|---|
| 섀도 모드 | **있음** | 불일치 전용 지표로 분리됨 |
| 카나리 | **없음** | 기능 플래그로 단계적 도입만 |
| 회귀 테스트 | **있음** | 검색 평가셋 추가됨, 정기 측정은 없음 |
| 트레이스 | **있음** | 로그·지표·트레이스 상관분석 없음 |
| 자가 치유 | **있음** | 넘어간 이유를 지표로 남기는 것이 핵심 |

우선순위를 매기면 **실제 백엔드에 대한 정기 측정**이 먼저다. 평가셋과 계산은
준비됐으므로, 백엔드를 띄운 환경에서 돌려 추이를 남기기만 하면 된다.
그것이 붙어야 "새 백엔드가 더 나은가" 에 숫자로 답할 수 있고, 섀도 모드도
비로소 제 값을 한다.

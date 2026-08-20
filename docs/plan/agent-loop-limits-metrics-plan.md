# 에이전트 루프 한도(도구 ≤6 · LLM ≤2 · 턴 5) 근거 계측 & 설정화 계획

<!-- plan-status -->
> **상태: 🔨 진행 중** — 루프 한도 상수는 있고 근거 계측이 없다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


루프 가드 magic number 3종의 **경험적 근거**를 만들기 위한 계획.
현재 이 숫자들은 [pre-review-agent-plan.md §가드레일](pre-review-agent-plan.md)에 "평문 단언"으로만 존재하며 분포·비용 역산 근거가 없다.

## 배경 — 지금 숫자가 박혀 있는 곳

| 숫자 | 위치 | 상태 |
|---|---|---|
| `MAX_TURNS = 5` | `review-ai-gateway` `AgenticLoop.java:29` | 하드코딩 `static final`, 설정화 안 됨 |
| `maxToolCalls = 6` | `auto-loan-review` `AgentProperties.java:23` | 설정화됨, 근거는 plan 문서 인용뿐 |
| `maxLlmCalls = 2` | `auto-loan-review` `AgentProperties.java:24` | 설정화됨, 근거는 plan 문서 인용뿐 |

근거 도출은 두 갈래:
- **분포**: 정상 종료(`outcome=SUCCESS`) run의 run당 도구/LLM/턴 사용량 → p95/p99에 상한.
- **비용 역산**: run당 토큰/USD 예산 ÷ 턴당 평균 토큰 → 허용 턴/호출 상한.
- 두 상한 중 **min** 채택.

두 서비스가 계측 파사드를 분리 보유(`AgentMetricsRecorder` / `GatewayMetrics`)하므로 계획도 서비스별로 나뉜다.

---

# (a) 분포 메트릭 추가 — 상세 설계

목표: run당 사용량 **분포(DistributionSummary)** 신설. 현재는 전부 Counter(누적합)뿐이라 "정상 run의 p95 도구 호출 수"를 못 뽑는 것이 유일한 공백.

> **percentile 주의**: 일반 `DistributionSummary`는 count/sum/max만 노출. percentile을 읽으려면 `.publishPercentileHistogram()` 또는 `.publishPercentiles(0.5,0.95,0.99)` + `.minimumExpectedValue(1).maximumExpectedValue(8)` 지정. 값 범위 1~6 정수라 버킷 소량.

## A-1. auto-loan-review (도구·LLM 호출 분포)

**메트릭 추가** — `AgentMetricsRecorder.java`
- 신규 2종:
  - `ai.agent.tool.calls.per_run` (DistributionSummary, 태그 `track`+`outcome`)
  - `ai.agent.llm.calls.per_run` (DistributionSummary, 태그 `track`+`outcome`)
- 신규 메서드 `recordPerRunGuardCounts(Track, AgentOutcome, int toolCount, int llmCount)`.
- 클래스 javadoc "메트릭 목록(18종)" → **20종으로 갱신** (이 주석이 대시보드 계약 역할).

**피드 위치** — `PreReviewAgentService.runTrack3` (`PreReviewAgentService.java:92`)
- 문제: `guard`가 `runTrack3` 지역변수(L93)이고 폴백 경로(L98/111/119 `loopGuardFallback`, L144 grounding, L154 catch)가 early return → 성공 경로(L147)에서만 기록하면 분포가 성공쪽으로 편향됨.
- 설계: `runTrack3`를 `try/finally`로 감싸 **모든 종료 지점에서 1회** `recordPerRunGuardCounts(TRACK_3, resolvedOutcome, guard.getToolCallCount(), guard.getLlmCallCount())` 기록. `resolvedOutcome`은 반환 직전 결정(성공=SUCCESS, loopGuard/grounding/catch=FALLBACK/ERROR).
- 쿼리 시 `outcome="SUCCESS"`만 필터 → "정상 작업 분포".
- Track1/Track2는 guard 미사용(결정적·소수 호출)이라 제외. magic number 6은 Track3 가드레일이므로 정합.
- 기존 `recordRun(track, outcome, duration)`과 동일 outcome 판정 로직 공유 권장(중복 계산 방지).

## A-2. review-ai-gateway (턴 분포)

**메트릭 추가** — `GatewayMetrics.java`
- 신규: `aigateway.loop.turns.per_run` (DistributionSummary, 태그 `type`+`outcome`). `outcome ∈ {completed, timeout}`.
- 신규 메서드 `recordLoopTurns(String type, int turns, boolean timedOut)`. percentile 설정 동일.

**피드 위치** — `AgenticAuditAnalysisService.analyze` (`AgenticAuditAnalysisService.java:83`~87)
- `loopResult.turnsUsed()` + `loopResult.timedOut()` 둘 다 이미 손에 있음. 기존 `recordLoopTimeout`(L86) 옆에 한 줄 추가.

**(선택) 도구 호출 분포**: gateway 루프는 현재 턴 수만 노출, 누적 도구 실행 횟수 미집계(`AgenticLoop.appendToolResults`). 필요 시 `AgenticLoop`에 toolCall 카운터 + `AgenticLoopResult.toolCallsUsed` 필드 추가가 선행. **별도 확장으로 분리**.

## A-3. 산출물·검증
- 대시보드: `AgentMetricsTags` 주석 경고대로 Grafana JSON에 패널 추가(p95/p99 of `..per_run{outcome="SUCCESS"}`).
- 테스트: `AgentMetricsRecorderTest` 신규 메서드 단건 검증(DistributionSummary count/percentile). PreReviewAgentService 테스트에 폴백 경로 기록 1케이스.

---

# (b) MAX_TURNS 설정화 — 패치 계획

목표: `AgenticLoop.MAX_TURNS`(`static final 5`)를 설정 바인딩으로 전환. 형제격 `maxToolCalls`는 이미 외부화돼 있어 **parity 맞추기**. 기본값 5 유지 → 동작 불변.

## B-1. 설정 프로퍼티 신설 (`AgentProperties` 패턴 복제)
- 신규 `AgenticLoopProperties` (record, `@Validated`, `@ConfigurationProperties(prefix="aigateway.agent")`):
  - `maxTurns` `@DefaultValue("5") @Min(1) int`
- review-ai-gateway에 `@ConfigurationProperties` 스캔이 없으면 앱 설정에 `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties(AgenticLoopProperties.class)` 추가.

## B-2. `AgenticLoop` 주입 전환
- `AgenticLoop.java:29` `static final int MAX_TURNS = 5` 제거 → 생성자 주입 필드(`@RequiredArgsConstructor`이므로 final 필드 추가).
- `run()` 시작부 `int maxTurns = props.maxTurns();` → L55 for, L61/71/72 로그·반환의 `MAX_TURNS` 전부 지역변수 참조로 교체.
- `FALLBACK_JSON`은 static 유지.

## B-3. application.yml
```yaml
aigateway:
  agent:
    max-turns: 5   # 근거: 정상종료 run turns p95 + run당 토큰예산 역산 중 min (이 문서 참조)
```

## B-4. 테스트 영향 (필수)
- `AgenticLoopTest`가 패키지-private `MAX_TURNS` 상수 참조 → 컴파일 깨짐. 생성자에 `new AgenticLoopProperties(5)` 주입, 상수 참조를 `props.maxTurns()`/테스트 상수로 치환. "maxTurns 초과 시 INSUFFICIENT_DATA" 케이스는 작은 값(예 2) 주입으로 검증.

## B-5. 문서
- `pre-review-agent-plan.md §가드레일 표`에 `턴 ≤ N (AgenticLoop, aigateway.agent.max-turns)` 행 추가 + 근거 한 줄. 이 표가 6/2/5 세 숫자의 정본 근거 자리.

---

# 의존 순서 (근거 도출 파이프라인)

```
(a) 분포 메트릭 ship → 운영 수집 → p95/p99 + 비용역산(min) → (b) 기본값 확정 + plan 문서 근거 기재
```

- (a) = 데이터 생산, (b) = 그 데이터의 소비 채널(설정화). (a) 먼저 머지 → 수집 후 숫자 재산정.
- 비용 역산 입력은 이미 존재: `ai.agent.cost.usd.total`·`tokens.*`(`AgentMetricsRecorder.java:113-130`), `daily-token-cap`, 요청당 `max-tokens`. 신규 계측은 (a)의 분포뿐.

## 커밋 분리
1. `feat(metrics): ...per_run 분포 메트릭 추가` (A-1/A-2 프로덕션)
2. `test(metrics): ...분포 기록 검증`
3. `refactor(aigateway): MAX_TURNS 설정화` (B-1~B-3)
4. `test(aigateway): AgenticLoopTest props 주입 전환`
5. `docs(plan): 가드레일 숫자 근거 기재`

# Agentic Tool 관측성 구현 가이드

> **대상**: `review-ai-gateway` 서비스에 tool 단위 메트릭을 추가할 개발자
> **현황**: `GatewayMetrics`는 분석 전체의 duration·토큰만 계측. 개별 tool 호출수·실패율·턴수 지표 없음.

---

## 1. 현재 메트릭 현황

`GatewayMetrics.java`가 현재 노출하는 메트릭:

| 메트릭 이름 | 타입 | 태그 | 설명 |
|---|---|---|---|
| `aigateway_analysis_duration_seconds` | Timer | `type` | 분석 전체 소요 시간 |
| `aigateway_tokens_input` | Counter | `type` | 누적 입력 토큰 (전 턴 합산) |
| `aigateway_tokens_output` | Counter | `type` | 누적 출력 토큰 (전 턴 합산) |

`type` 태그 값: `BIAS_DETECTION`, `COMPLIANCE_VERIFICATION`

---

## 2. 추가해야 할 메트릭

| 메트릭 이름 | 타입 | 태그 | 설명 |
|---|---|---|---|
| `aigateway_tool_calls_total` | Counter | `tool`, `status` | tool 호출 횟수 (`status`: `success`/`error`) |
| `aigateway_tool_duration_seconds` | Timer | `tool` | tool 실행 소요 시간 |
| `aigateway_agentic_turns` | DistributionSummary | `analysis_type` | 루프 당 사용된 턴 수 |

### 태그 상세

**`tool`** — `ToolCall.name()` 값 그대로 사용. 현재 등록된 값:
- `get_policy_citation`
- `get_similar_cases`
- `get_cohort_stats`
- `get_reviewer_history`

**`status`** — `success` (정상 결과 반환) / `error` (exception 발생 → error JSON 반환)

---

## 3. 구현 위치

### 3-1. `GatewayMetrics.java` — 메서드 추가

```
agents/review-ai-gateway/src/main/java/com/bank/aigateway/observability/GatewayMetrics.java
```

아래 세 메서드를 추가한다.

```java
// tool 호출 1건 카운트
public void recordToolCall(String toolName, String status) {
    Counter.builder("aigateway.tool.calls")
           .tag("tool", toolName)
           .tag("status", status)
           .register(registry)
           .increment();
}

// tool 실행 시간 측정
public Timer.Sample startToolTimer() {
    return Timer.start(registry);
}

public void recordToolDuration(Timer.Sample sample, String toolName) {
    sample.stop(Timer.builder("aigateway.tool.duration")
                     .tag("tool", toolName)
                     .register(registry));
}

// 루프 턴 수 기록
public void recordTurnsUsed(String analysisType, int turns) {
    DistributionSummary.builder("aigateway.agentic.turns")
                       .tag("analysis_type", analysisType)
                       .register(registry)
                       .record(turns);
}
```

---

### 3-2. `AgenticLoop.java` — tool 실행 지점 계측

```
agents/review-ai-gateway/src/main/java/com/bank/aigateway/agent/AgenticLoop.java
```

현재 tool 실행은 `executeQuietly()` 내부에서 일어난다. `GatewayMetrics`를 주입한 뒤 아래 위치에 계측을 추가한다.

**주입 (생성자 또는 필드):**
```java
private final GatewayMetrics metrics;  // 생성자에 추가
```

**tool 실행 래퍼에 계측 삽입 (executeQuietly 또는 호출 부분):**
```java
private String executeWithMetrics(ToolCall toolCall, Function<ToolCall, String> executor) {
    Timer.Sample sample = metrics.startToolTimer();
    try {
        String result = executor.apply(toolCall);
        metrics.recordToolCall(toolCall.name(), "success");
        return result;
    } catch (Exception e) {
        metrics.recordToolCall(toolCall.name(), "error");
        return buildErrorJson(toolCall.name(), e.getMessage());
    } finally {
        metrics.recordToolDuration(sample, toolCall.name());
    }
}
```

기존 `toolExecutor.apply(toolCall)` 호출을 `executeWithMetrics(toolCall, toolExecutor)`로 교체한다.

---

### 3-3. `AgenticAuditAnalysisService.java` — 턴 수 기록

```
agents/review-ai-gateway/src/main/java/com/bank/aigateway/audit/AgenticAuditAnalysisService.java
```

`agenticLoop.run()` 호출 직후, `loopResult.turnsUsed()`가 이미 채워져 있으므로:

```java
AgenticLoopResult loopResult = agenticLoop.run(...);
metrics.recordTurnsUsed(req.analysisType(), loopResult.turnsUsed());
// 이하 기존 코드 유지
```

---

## 4. 의존성·설정 변경 없음

`build.gradle`에 이미 `micrometer-registry-prometheus`가 선언되어 있고,  
`application.yml`의 `/actuator/prometheus` 엔드포인트도 이미 열려 있다.  
추가 설정 없이 위 코드만 삽입하면 Prometheus가 자동으로 수집한다.

---

## 5. Grafana 패널 예시 PromQL

대시보드 파일: `docs/monitoring/INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md` 참고 (기존 섹션에 패널 추가)

### tool 호출 수 (tool별)
```promql
sum by (tool) (rate(aigateway_tool_calls_total[5m]))
```

### tool 실패율
```promql
sum by (tool) (rate(aigateway_tool_calls_total{status="error"}[5m]))
/
sum by (tool) (rate(aigateway_tool_calls_total[5m]))
```

### tool 평균 실행 시간
```promql
rate(aigateway_tool_duration_seconds_sum[5m])
/
rate(aigateway_tool_duration_seconds_count[5m])
```

### 분석 타입별 평균 턴 수
```promql
rate(aigateway_agentic_turns_sum[5m])
/
rate(aigateway_agentic_turns_count[5m])
```

### 턴 수 분포 (p95)
```promql
histogram_quantile(0.95,
  sum by (le, analysis_type) (rate(aigateway_agentic_turns_bucket[5m]))
)
```

---

## 6. 검증 방법

1. 로컬에서 `review-ai-gateway` 실행
2. `POST /internal/audit/analyze` 호출 (tool 호출이 발생하는 payload 사용)
3. `GET http://localhost:{port}/actuator/prometheus` 응답에서 다음 항목 확인:

```
aigateway_tool_calls_total{tool="get_policy_citation",status="success",...}
aigateway_tool_duration_seconds_count{tool="get_policy_citation",...}
aigateway_agentic_turns_count{analysis_type="BIAS_DETECTION",...}
```

---

## 7. 관련 파일 경로 요약

| 역할 | 파일 |
|---|---|
| 메트릭 메서드 추가 | `agents/review-ai-gateway/src/main/java/com/bank/aigateway/observability/GatewayMetrics.java` |
| tool 실행 계측 | `agents/review-ai-gateway/src/main/java/com/bank/aigateway/agent/AgenticLoop.java` |
| 턴 수 기록 | `agents/review-ai-gateway/src/main/java/com/bank/aigateway/audit/AgenticAuditAnalysisService.java` |
| ToolCall 레코드 | `agents/review-ai-gateway/src/main/java/com/bank/aigateway/llm/agentic/ToolCall.java` |
| AgenticLoopResult (turnsUsed 포함) | `agents/review-ai-gateway/src/main/java/com/bank/aigateway/agent/AgenticLoopResult.java` |
| Prometheus 설정 확인 | `agents/review-ai-gateway/src/main/resources/application.yml` |

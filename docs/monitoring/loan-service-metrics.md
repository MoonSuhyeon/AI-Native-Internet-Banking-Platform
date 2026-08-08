# 여신 AI 메트릭 명세 및 Observability 가이드

> **대상 독자**: 인프라·모니터링 담당자  
> **범위**: auto-loan-review, advisory-service, ai-service(RAG)  
> **주의**: 서류 심사(doc-agent)는 이 문서가 아니라
> [`doc-agent-metrics.md`](doc-agent-metrics.md) 를 본다. 예전 제목이 "doc-agent" 로
> 잘못 달려 있어 혼동의 원인이었다.  
> **메트릭 노출**: `GET /actuator/prometheus` (Spring Boot Actuator + Micrometer)  
> **구현 파일**: Micrometer 코드는 각 서비스 `observability/` 패키지 참고

---

## 1. 메트릭 전체 목록

### 1-1. AI 에이전트 파이프라인 (`auto-loan-review`)

> 구현 클래스: `AgentMetricsRecorder`

| 메트릭 이름 (Prometheus) | 타입 | 라벨 | 설명 |
|---|---|---|---|
| `ai_agent_runs_total` | Counter | `track`, `outcome` | 에이전트 실행 완료 수 |
| `ai_agent_latency_seconds` | Histogram | `track`, `outcome` | 에이전트 전체 처리 소요 시간 |
| `ai_agent_tool_calls_total` | Counter | `tool_name`, `status` | 개별 Tool 호출 수 |
| `ai_agent_llm_calls_total` | Counter | `model`, `outcome` | LLM 단일 호출 수 |
| `ai_agent_llm_latency_seconds` | Histogram | `model` | LLM 응답 소요 시간 |
| `ai_agent_tokens_input_total` | Counter | `model` | LLM 입력 토큰 누적 |
| `ai_agent_tokens_output_total` | Counter | `model` | LLM 출력 토큰 누적 |
| `ai_agent_cost_usd_total` | Counter | `model` | 추정 LLM 비용 누적 (USD) |
| `ai_agent_rpm_remaining` | Gauge | — | 현재 분 남은 RPM 슬롯 |
| `ai_agent_rpd_remaining` | Gauge | — | 오늘 남은 RPD 슬롯 |
| `ai_agent_disagreement_total` | Counter | `track` | 에이전트 판단 ↔ 트랙 분기 불일치 수 |
| `ai_agent_fallback_total` | Counter | `reason` | 폴백 발생 수 |
| `ai_agent_hard_fail_total` | Counter | `reason` | 하드 규칙 차단 수 |
| `ai_audit_log_size_bytes` | Histogram | — | 감사 로그 단건 크기 (bytes) |

**라벨 값 참고**

| 라벨 | 가능한 값 |
|---|---|
| `track` | `STANDARD`, `EXPRESS`, `MANUAL` 등 (Track enum) |
| `outcome` | `SUCCESS`, `FALLBACK`, `ERROR` |
| `status` | `OK`, `ERROR` |
| `reason` | FallbackReason / HardFailReason enum 값 |

---

### 1-2. LLM 호출 비용·속도 제한 (`auto-loan-review`)

> 구현 클래스: `LlmCostMeter`, `LlmRequestRateMeter`

| 메트릭 이름 (Prometheus) | 타입 | 라벨 | 설명 |
|---|---|---|---|
| `llm_tokens_input_total` | Counter | `model`, `prompt_id` | 입력 토큰 누적 |
| `llm_tokens_output_total` | Counter | `model`, `prompt_id` | 출력 토큰 누적 |
| `llm_calls_total` | Counter | `prompt_id`, `status` | LLM 호출 수 (상태별) |
| `llm_latency_seconds` | Histogram | `prompt_id` | LLM 응답 시간 분포 |
| `llm_daily_tokens_total` | Gauge | — | 당일 누적 입출력 토큰 합계 |

**`llm_calls_total` status 값**: `success` / `error` / `fallback` / `cap_exceeded`

---

### 1-3. 어드바이저리 (`advisory-service`)

> 구현 클래스: `AdvisoryMetrics`

| 메트릭 이름 (Prometheus) | 타입 | 라벨 | 설명 |
|---|---|---|---|
| `advisory_report_published_total` | Counter | `rule_cd`, `severity` | 어드바이저리 리포트 발행 수 |
| `advisory_ack_response_total` | Counter | `response_cd` | 담당자 Ack 응답 수 |
| `advisory_critical_gate_blocked_total` | Counter | — | CRITICAL 어드바이저리로 인한 약정 게이트 차단 수 |
| `advisory_open_reports` | Gauge | `severity` | 미처리(OPEN/VIEWED) 리포트 수 |
| `advisory_evaluate_duration_seconds` | Histogram | `mode` | 룰 평가 소요 시간 |

**`severity` 값**: `INFO`, `WARN`, `CRITICAL`

---

### 1-4. RAG 검색·적재 (`ai-service`)

> 구현 클래스: `RagMetrics`, `AgentMetricsRecorder`

| 메트릭 이름 (Prometheus) | 타입 | 라벨 | 설명 |
|---|---|---|---|
| `rag_ingest_duration_seconds` | Histogram | `docType`, `status` | RAG 문서 적재 한 사이클 소요 시간 |
| `rag_search_duration_seconds` | Histogram | `profile` | RAG 검색 한 호출 소요 시간 |
| `rag_chunk_total` | Gauge | — | 현재 적재된 청크 총 수 |
| `rag_search_latency_seconds` | Histogram | `corpus` | 검색 소요 시간 (corpus별) |
| `rag_search_miss_total` | Counter | `corpus` | 검색 결과 없음 횟수 |
| `rag_chunk_count` | Histogram | `corpus` | 검색당 반환 청크 수 |
| `rag_citation_count_per_report` | Histogram | `track` | 리포트 1건당 RAG citation 수 |

**`rag_ingest_duration_seconds` status 값**: `success` / `skip` / `fail`

---

## 2. 알람 권장 임계치

| 알람 이름 | 조건 (PromQL) | 심각도 | 의미 |
|---|---|---|---|
| AgentHighErrorRate | `rate(ai_agent_runs_total{outcome="ERROR"}[5m]) / rate(ai_agent_runs_total[5m]) > 0.05` | WARNING | 에이전트 오류율 5% 초과 |
| AgentHighFallbackRate | `rate(ai_agent_fallback_total[5m]) / rate(ai_agent_runs_total[5m]) > 0.20` | WARNING | 폴백률 20% 초과 — LLM 품질 또는 규칙 과다 확인 필요 |
| AgentSlowP95 | `histogram_quantile(0.95, rate(ai_agent_latency_seconds_bucket[10m])) > 30` | WARNING | 에이전트 p95 처리시간 30초 초과 |
| LlmDailyCap80 | `llm_daily_tokens_total / <daily_token_cap> > 0.80` | WARNING | 일일 토큰 cap 80% 도달 |
| LlmDailyCapExceeded | `increase(llm_calls_total{status="cap_exceeded"}[1h]) > 0` | CRITICAL | cap 초과 차단 발생 |
| LlmRpmLow | `ai_agent_rpm_remaining < 3` | WARNING | 분당 요청 슬롯 3개 미만 |
| LlmRpdLow | `ai_agent_rpd_remaining < 50` | WARNING | 일간 요청 슬롯 50개 미만 |
| AdvisoryCriticalOpen | `advisory_open_reports{severity="CRITICAL"} > 0` | CRITICAL | CRITICAL 어드바이저리 미처리 잔존 |
| AdvisoryGateBlocked | `increase(advisory_critical_gate_blocked_total[1h]) > 3` | WARNING | 1시간 내 게이트 차단 3건 초과 |
| RagIngestFailRate | `rate(rag_ingest_duration_seconds_count{status="fail"}[30m]) > 0.1` | WARNING | RAG 적재 실패율 증가 |
| AgentDisagreementSpike | `increase(ai_agent_disagreement_total[1h]) > 10` | WARNING | 에이전트 판단 불일치 급증 — 편향 점검 필요 |

---

## 3. 권장 Grafana 패널 (PromQL 예시)

### 패널 1 — 에이전트 처리량 (Stat)
```promql
# 분당 에이전트 실행 수
rate(ai_agent_runs_total[5m]) * 60
```

### 패널 2 — 에이전트 결과 분포 (Pie Chart)
```promql
increase(ai_agent_runs_total[1h])
# legend: {{outcome}}
```

### 패널 3 — 에이전트 처리 지연시간 분위 (Time Series)
```promql
# p50
histogram_quantile(0.50, rate(ai_agent_latency_seconds_bucket[10m]))
# p95
histogram_quantile(0.95, rate(ai_agent_latency_seconds_bucket[10m]))
# p99
histogram_quantile(0.99, rate(ai_agent_latency_seconds_bucket[10m]))
```

### 패널 4 — LLM 일일 토큰 소비 (Gauge + Threshold)
```promql
llm_daily_tokens_total
# 임계치: 80% = yellow, 100% = red
```

### 패널 5 — LLM 비용 누적 (Stat)
```promql
ai_agent_cost_usd_total
```

### 패널 6 — LLM 호출 상태 분포 (Bar Gauge)
```promql
increase(llm_calls_total[1h])
# legend: {{status}} / {{prompt_id}}
```

### 패널 7 — RPM / RPD 슬롯 잔여 (Gauge)
```promql
ai_agent_rpm_remaining
ai_agent_rpd_remaining
```

### 패널 8 — 폴백 원인 분포 (Bar Chart)
```promql
increase(ai_agent_fallback_total[1h])
# legend: {{reason}}
```

### 패널 9 — 하드 차단 원인 분포 (Bar Chart)
```promql
increase(ai_agent_hard_fail_total[1h])
# legend: {{reason}}
```

### 패널 10 — CRITICAL 어드바이저리 미처리 (Stat, red alert)
```promql
advisory_open_reports{severity="CRITICAL"}
```

### 패널 11 — RAG 검색 지연시간 (Time Series)
```promql
histogram_quantile(0.95, rate(rag_search_duration_seconds_bucket[10m]))
# legend: {{profile}}
```

### 패널 12 — RAG 적재 상태 (Bar Chart)
```promql
increase(rag_ingest_duration_seconds_count[1h])
# legend: {{docType}} / {{status}}
```

---

## 4. SLO 후보

| SLO 이름 | 목표 | 측정 방식 | 측정 기간 |
|---|---|---|---|
| 에이전트 p95 처리시간 | ≤ 30초 | `histogram_quantile(0.95, ...)` | 10분 롤링 |
| 에이전트 성공률 | ≥ 95% | `outcome="SUCCESS"` 비율 | 1시간 롤링 |
| LLM 폴백률 | ≤ 20% | `fallback / runs` | 1시간 롤링 |
| CRITICAL 어드바이저리 처리 시간 | ≤ 4시간 | `advisory_open_reports{severity="CRITICAL"}` 연속 유지 시간 | 개별 건 추적 |
| 일일 토큰 cap 준수 | cap 초과 0건/일 | `llm_calls_total{status="cap_exceeded"}` | 일별 |
| RAG 적재 성공률 | ≥ 99% | `status="success"` 비율 | 1시간 롤링 |

---

## 5. 인프라 담당자 체크리스트

- [ ] `prometheus.yml`에 각 서비스 scrape 타겟 추가 (`/actuator/prometheus`, 15s 간격)
- [ ] Grafana datasource에 Prometheus 연결
- [ ] 위 PromQL로 패널 구성 후 대시보드 JSON export → `infra/grafana/` 저장
- [ ] §2 알람을 Alertmanager rule로 등록 (Slack 또는 PagerDuty 연동)
- [ ] `daily_token_cap` 값을 Prometheus label 또는 recording rule로 상수화 (패널 4 임계치용)

# doc-agent 메트릭 & 모니터링 가이드

> 대상 독자: Grafana 대시보드 구성 담당자, SRE  
> 목적: doc-agent(포트 8087)가 노출하는 Prometheus 메트릭 전체 목록, 권장 패널 구성, 알림 PromQL 제공  
> Prometheus scrape 엔드포인트: `http://doc-agent:8087/actuator/prometheus`

---

## 1. 메트릭 전체 목록

### 1-1. 파이프라인 처리 (커스텀)

doc-agent는 아래 커스텀 메트릭을 `MeterRegistry`로 기록한다.  
각 단계 타이머는 `Timer` 타입이며, `_seconds_count / _seconds_sum / _seconds_max` 3개 시계열로 분기된다.

| 메트릭명 | 타입 | 레이블 | 설명 |
|---------|------|--------|------|
| `doc_agent_pipeline_seconds` | Timer | `doc_type`, `status` | L1→L5 전체 파이프라인 처리 시간 |
| `doc_agent_ingest_seconds` | Timer | `doc_code` | L1 Ingest (포맷 검증 + MinIO 업로드) |
| `doc_agent_ocr_seconds` | Timer | `engine` | L3 OCR 사이드카 응답 시간 |
| `doc_agent_table_ocr_seconds` | Timer | — | L3b PP-StructureV2 테이블 파싱 시간 |
| `doc_agent_llm_extract_seconds` | Timer | `doc_type` | L4 Ollama LLM 추출 시간 |
| `doc_agent_forgery_analyze_seconds` | Timer | `doc_code` | L4b 위변조 분석 사이드카 응답 시간 |
| `doc_agent_verify_seconds` | Timer | `product_id` | L5 룰+진위확인+합산 처리 시간 |

| 메트릭명 | 타입 | 레이블 | 설명 |
|---------|------|--------|------|
| `doc_agent_submission_total` | Counter | `doc_type`, `status` | 제출 건수 (status: AUTO_PASS / NEEDS_RESUBMIT / HOLD / LOCKED / CLEARED) |
| `doc_agent_forgery_score` | Histogram | `doc_code` | 위변조 집계 점수 분포 (0.0 ~ 1.0+) |
| `doc_agent_forgery_signal_total` | Counter | `category`, `signal_type` | 탐지된 위변조 시그널 누적 (META / VISUAL / SEMANTIC / EXTERNAL) |
| `doc_agent_human_review_pending` | Gauge | — | 현재 HOLD(PENDING) 상태 미결 건수 |
| `doc_agent_human_review_duration_hours` | Histogram | — | HOLD → 심사원 결정까지 소요 시간 |
| `doc_agent_legal_hold_total` | Gauge | — | legal_hold=true 활성 건수 |
| `doc_agent_fraud_audit_published_total` | Counter | — | 감사팀 이관(fraud-audit) 이벤트 발행 수 |

### 1-2. 사이드카 CircuitBreaker (Resilience4j 자동)

Resilience4j가 아래 메트릭을 자동 생성한다.

| 메트릭명 | 레이블 | 설명 |
|---------|--------|------|
| `resilience4j_circuitbreaker_state` | `name` | 0=CLOSED, 1=OPEN, 2=HALF_OPEN |
| `resilience4j_circuitbreaker_calls_total` | `name`, `kind` | kind: successful / failed / not_permitted |
| `resilience4j_circuitbreaker_failure_rate` | `name` | 최근 슬라이딩 윈도우 실패율 (%) |

`name` 레이블 값: `ocr`, `llm`, `forgery`, `identityVerify`

### 1-3. HTTP 요청 (Spring Actuator 기본)

| 메트릭명 | 레이블 | 설명 |
|---------|--------|------|
| `http_server_requests_seconds` | `uri`, `method`, `status` | 엔드포인트별 응답 시간 |
| `http_client_requests_seconds` | `uri`, `method`, `status` | RestClient(사이드카·외부 API) 요청 시간 |

주요 URI:
- `POST /api/documents/submit` — 파이프라인 트리거
- `POST /api/documents/{id}/review` — 심사원 결정
- `PATCH /api/documents/{id}/legal-hold/enable·disable`

### 1-4. JVM / 시스템 (Spring Actuator 기본)

| 메트릭명 | 설명 |
|---------|------|
| `jvm_memory_used_bytes{area="heap"}` | Heap 사용량 |
| `jvm_gc_pause_seconds` | GC 정지 시간 |
| `jvm_threads_live_threads` | 활성 스레드 수 |
| `process_cpu_usage` | 프로세스 CPU 사용률 |
| `hikaricp_connections_active` | DB 커넥션 풀 사용 중 |

### 1-5. Kafka 발행 (Spring Kafka Micrometer 자동)

| 메트릭명 | 레이블 | 설명 |
|---------|--------|------|
| `spring_kafka_template_seconds` | `result` (success/failure) | 발행 응답 시간 |
| `kafka_producer_record_send_total` | `topic` | 토픽별 발행 건수 |
| `kafka_producer_record_error_total` | `topic` | 토픽별 발행 실패 수 |

주요 토픽: `doc-agent.extracted` / `doc-agent.routed` / `doc-agent.fraud.audit`

---

## 2. 커스텀 메트릭 등록 위치

> 대시보드 담당자는 아래 클래스에서 MeterRegistry 주입 방식을 확인한다.

```
agents/doc-agent/src/main/java/com/bank/docagent/
├── submission/service/SubmissionPipelineService.java  ← doc_agent_pipeline_seconds
├── forgery/service/ForgeryAnalysisService.java        ← doc_agent_forgery_*
├── review/service/HumanReviewService.java             ← doc_agent_human_review_*
└── submission/service/LegalHoldService.java           ← doc_agent_legal_hold_total
```

메트릭 등록 패턴 (예시):

```java
// Timer
Timer.builder("doc_agent_pipeline_seconds")
     .tag("doc_type", docType.name())
     .tag("status", finalStatus.name())
     .register(meterRegistry)
     .record(() -> runPipeline(...));

// Counter
Metrics.counter("doc_agent_submission_total",
    "doc_type", docType.name(), "status", finalStatus.name()).increment();

// Gauge
Metrics.gauge("doc_agent_human_review_pending", submissionRepo,
    r -> r.countByHumanReviewStatus(HumanReviewStatus.PENDING));
```

---

## 3. Prometheus 스크레이프 설정

`prometheus.yml`에 추가:

```yaml
scrape_configs:
  - job_name: doc-agent
    static_configs:
      - targets: ['doc-agent:8087']
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
```

Docker Compose 환경에서는 `doc-agent` 컨테이너명으로 자동 DNS 해석된다.

---

## 4. Grafana 대시보드 패널 구성 가이드

대시보드 이름: **doc-agent — 서류 검증 파이프라인**  
권장 행(Row) 구성:

---

### Row 1: 처리량 & 상태

**패널 1 — 분당 제출 건수 (stat)**
```promql
rate(doc_agent_submission_total[1m]) * 60
```
- Threshold: Green < 100 / Yellow ≥ 100 / Red ≥ 500

**패널 2 — 상태별 비율 (pie chart)**
```promql
sum by(status) (increase(doc_agent_submission_total[1h]))
```
- 색상 매핑: AUTO_PASS=green, NEEDS_RESUBMIT=yellow, HOLD=orange, LOCKED=red

**패널 3 — 심사원 대기 건수 (stat)**
```promql
doc_agent_human_review_pending
```
- Threshold: Green=0 / Yellow ≥ 5 / Red ≥ 20

---

### Row 2: 파이프라인 레이턴시

**패널 4 — 전체 파이프라인 P50/P95/P99 (time series)**
```promql
histogram_quantile(0.50, rate(doc_agent_pipeline_seconds_bucket[5m]))
histogram_quantile(0.95, rate(doc_agent_pipeline_seconds_bucket[5m]))
histogram_quantile(0.99, rate(doc_agent_pipeline_seconds_bucket[5m]))
```

**패널 5 — 단계별 평균 처리 시간 (bar gauge)**
```promql
rate(doc_agent_ingest_seconds_sum[5m])         / rate(doc_agent_ingest_seconds_count[5m])
rate(doc_agent_ocr_seconds_sum[5m])            / rate(doc_agent_ocr_seconds_count[5m])
rate(doc_agent_forgery_analyze_seconds_sum[5m])/ rate(doc_agent_forgery_analyze_seconds_count[5m])
rate(doc_agent_llm_extract_seconds_sum[5m])    / rate(doc_agent_llm_extract_seconds_count[5m])
rate(doc_agent_verify_seconds_sum[5m])         / rate(doc_agent_verify_seconds_count[5m])
```
레전드: `Ingest / OCR / Forgery / LLM / Verify`

---

### Row 3: 위변조 탐지

**패널 6 — 위변조 점수 분포 (heatmap)**
```promql
rate(doc_agent_forgery_score_bucket[5m])
```

**패널 7 — 시그널 유형별 탐지 속도 (time series)**
```promql
rate(doc_agent_forgery_signal_total[5m])
```
레전드: `{{category}}-{{signal_type}}`

**패널 8 — 감사팀 이관 누적 (stat)**
```promql
doc_agent_fraud_audit_published_total
```

---

### Row 4: 사이드카 안정성

**패널 9 — CircuitBreaker 상태 (stat × 4)**
```promql
resilience4j_circuitbreaker_state{name="ocr"}
resilience4j_circuitbreaker_state{name="llm"}
resilience4j_circuitbreaker_state{name="forgery"}
resilience4j_circuitbreaker_state{name="identityVerify"}
```
- Value mapping: 0=CLOSED(green) / 1=OPEN(red) / 2=HALF_OPEN(yellow)

**패널 10 — 사이드카 실패율 (time series)**
```promql
resilience4j_circuitbreaker_failure_rate
```
레전드: `{{name}}`

**패널 11 — Kafka 발행 에러 (time series)**
```promql
rate(kafka_producer_record_error_total[5m])
```
레전드: `{{topic}}`

---

### Row 5: JVM & DB

**패널 12 — Heap 사용량 (time series)**
```promql
jvm_memory_used_bytes{area="heap", application="doc-agent"}
```

**패널 13 — GC 정지 시간 (time series)**
```promql
rate(jvm_gc_pause_seconds_sum[1m])
```

**패널 14 — DB 커넥션 (time series)**
```promql
hikaricp_connections_active{pool="HikariPool-1"}
hikaricp_connections_pending{pool="HikariPool-1"}
```

---

## 5. 알림 규칙 (Alerting Rules)

`prometheus/alerts/doc-agent.yml` 파일로 관리:

```yaml
groups:
  - name: doc-agent
    rules:

      # 사이드카 CircuitBreaker OPEN
      - alert: DocAgentCircuitBreakerOpen
        expr: resilience4j_circuitbreaker_state{job="doc-agent"} == 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "doc-agent CircuitBreaker OPEN: {{ $labels.name }}"
          description: "{{ $labels.name }} 사이드카 회로가 열렸습니다. 1분 이상 지속 중."

      # 파이프라인 P95 > 30초
      - alert: DocAgentPipelineSlowP95
        expr: |
          histogram_quantile(0.95,
            rate(doc_agent_pipeline_seconds_bucket{job="doc-agent"}[5m])
          ) > 30
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "doc-agent 파이프라인 P95 지연 > 30s"
          description: "P95 응답시간 {{ $value | humanizeDuration }} — LLM/OCR 부하 확인"

      # 위변조 점수 > 0.7 비율 급증 (5분 내 HOLD 건이 전체의 30% 초과)
      - alert: DocAgentHighForgeryRate
        expr: |
          rate(doc_agent_submission_total{status="HOLD"}[5m])
          /
          rate(doc_agent_submission_total[5m])
          > 0.3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "HOLD 비율 30% 초과 — 위변조 급증 또는 임계치 오류 가능성"

      # 심사원 미결 건 20건 초과
      - alert: DocAgentHumanReviewBacklog
        expr: doc_agent_human_review_pending > 20
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "심사원 검토 대기 {{ $value }}건 — 20건 초과"

      # Kafka fraud-audit 발행 실패
      - alert: DocAgentFraudAuditPublishFail
        expr: rate(kafka_producer_record_error_total{topic="doc-agent.fraud.audit"}[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "감사팀 이관 이벤트 발행 실패"
          description: "doc-agent.fraud.audit 토픽 발행 오류. 위변조 확정 건 유실 위험."

      # JVM Heap > 85%
      - alert: DocAgentHeapHigh
        expr: |
          jvm_memory_used_bytes{job="doc-agent", area="heap"}
          /
          jvm_memory_max_bytes{job="doc-agent", area="heap"}
          > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "doc-agent Heap 사용률 {{ $value | humanizePercentage }}"
```

---

## 6. 임계치 조정 기준 (로드맵)

| 임계치 | 현재 잠정값 | 조정 시점 | 방법 |
|--------|------------|-----------|------|
| 위변조 HOLD 기준 (`THRESHOLD_HOLD`) | 0.7 | 골든셋 2,000건 축적 후 | ROC 곡선 → Youden 지수 최적점 |
| 위변조 RESUBMIT 기준 (`THRESHOLD_RESUBMIT`) | 0.3 | 동일 | FPR 5% 이하 조건으로 조정 |
| CircuitBreaker 실패율 임계치 | 50% (기본값) | 사이드카 안정화 후 | Resilience4j `failureRateThreshold` |
| P95 알림 기준 | 30s | LLM 평균 레이턴시 측정 후 | avg × 3 배수로 재설정 |

---

## 7. 로그 연계 (Loki / Grafana Explore)

doc-agent는 JSON 구조화 로그를 출력한다. `submissionId` 필드로 파이프라인 추적이 가능하다.

```logql
# 특정 제출 건 전체 흐름 추적
{job="doc-agent"} | json | submissionId = "<UUID>"

# HOLD 결정 로그만 필터
{job="doc-agent"} |= "심사원 결정 완료" | json | decision = "CONFIRMED_FORGERY"

# CircuitBreaker fallback 발생
{job="doc-agent"} |= "sidecar 장애"
```

Grafana 패널에서 **Data Links** → `Explore` 연결 시:  
`/explore?orgId=1&left={"datasource":"Loki","queries":[{"expr":"{job=\"doc-agent\"} | json | submissionId = \"${__data.fields.submissionId}\""}]}`

---

## 8. 메트릭 미구현 안내

> 아래 메트릭은 코드 구조가 준비되어 있으나 `MeterRegistry` 주입이 아직 추가되지 않았다.  
> 대시보드 구성 전 각 서비스 클래스에 `MeterRegistry` 의존성을 주입하고 위 4절 패턴으로 등록해야 한다.

- `doc_agent_pipeline_seconds` — `SubmissionPipelineService`에 Timer wrap 추가
- `doc_agent_forgery_score` — `ForgeryAnalysisService`에 `DistributionSummary` 추가
- `doc_agent_human_review_pending` — `HumanReviewService` 또는 스케줄러에서 Gauge 갱신
- `doc_agent_legal_hold_total` — `LegalHoldService`에 Counter 추가

JVM / HTTP / Kafka / Resilience4j 메트릭은 의존성 추가만으로 자동 등록된다 (`spring-boot-actuator` + `micrometer-registry-prometheus`).

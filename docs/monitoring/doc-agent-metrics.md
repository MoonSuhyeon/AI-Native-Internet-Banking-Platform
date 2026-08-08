# doc-agent 메트릭 & 모니터링 가이드

> 대상 독자: Grafana 대시보드 구성 담당자, SRE
> 지표 노출: `GET /actuator/prometheus` (컨테이너 내부 8087, 호스트 8091)
> 대시보드: `infra/grafana/provisioning/dashboards/doc-agent.json` (uid `doc-agent`, "서류 심사 에이전트")
> 구현: `agents/doc-agent/.../observability/DocAgentMetrics.java`

이 문서는 **실제로 나오는 것만** 적는다. 구현되지 않은 것은 §6 에 따로 모았다.
없는 지표를 표로 적어 두면 그것을 보고 대시보드와 알림을 만들었다가 조용히 빈다 —
지표는 틀려도 아무도 에러를 내지 않기 때문에 한참 뒤에야 드러난다.

---

## 1. 무엇을 재는가

이 에이전트의 성능은 처리량이 아니라 **자동 판정을 사람이 뒤집는 비율**로 드러난다.

파이프라인이 `HOLD` 를 내면 심사원이 둘 중 하나로 결정한다.

| 심사원 결정 | 의미 |
|---|---|
| `CONFIRMED_FORGERY` | 자동 판정이 맞았다 (위조 확정) |
| `CLEARED` | 자동 판정을 뒤집었다 (정상 서류였다) |

**확정률 = CONFIRMED_FORGERY / 전체 결정** 이 핵심 지표다.

레포의 다른 에이전트도 같은 축을 쓴다 — auto-loan-review 의 `ai_agent_disagreement_total`,
fraud-agent 의 `fraud_recommendation_reviewed_total`.

---

## 2. 구현된 지표

`DocAgentMetrics` 가 기록한다. Micrometer 는 **처음 기록될 때** 미터를 등록하므로,
기동 직후 스크레이프에는 아직 안 보이는 것이 정상이다.

| 지표 | 타입 | 라벨 | 설명 |
|---|---|---|---|
| `doc_agent_human_review_total` | Counter | `decision` | 심사원 결정 수. **채택률의 분자·분모** |
| `doc_agent_verdict_total` | Counter | `status`, `doc_type` | 파이프라인 자동 판정 수 |
| `doc_agent_pipeline_duration_seconds` | Timer | `status` | 서류 한 건 전체 처리 시간 |
| `doc_agent_stage_duration_seconds` | Timer | `stage`, `outcome` | 단계별 소요 시간 |
| `doc_agent_pipeline_failed_total` | Counter | `stage` | 예외로 끝난 수 |

라벨 값:

- `decision` — `CONFIRMED_FORGERY` / `CLEARED`
- `status` — `AUTO_PASS` / `HOLD` / `NEEDS_RESUBMIT` 등 `VerifyStatus`, 파이프라인 실패 시 `ERROR`
- `stage` — `ingest` / `forgery` / `ocr` / `extract`
- `outcome` — `ok` / `error`

**왜 자동 판정 분포를 같이 두는가.** 채택률만 보면 "HOLD 를 남발해서 사람 일이 늘었다"와
"판정이 정확해졌다"가 구분되지 않는다. `doc_agent_verdict_total` 의 status 비율이 그 맥락을 준다.

**왜 단계를 나누는가.** 앞 단계들이 외부 사이드카 호출(OCR·위조분석·LLM추출)이다.
합쳐 재면 느려졌을 때 어느 사이드카가 문제인지 알 수 없다.

**왜 실패를 따로 세는가.** 실패는 판정을 만들지 못해 `verdict` 에 잡히지 않는다.
따로 세지 않으면 "서류가 안 들어왔다"와 "처리가 터졌다"가 구별되지 않는다.

라벨에 제출 ID·심사원 ID 는 넣지 않는다 — 카디널리티가 터지고, 개인 단위 추적은
지표가 아니라 감사로그가 할 일이다.

### 공통 라벨

모든 지표에 `application=doc-agent`, `environment=<프로필>` 이 붙는다
(`management.metrics.tags`). 이게 없으면 `application` 으로 묶는 대시보드 패널에서
이 서비스만 통째로 빠진다 — 그래프가 비는 게 아니라 아예 안 나온다.

---

## 3. 기본 제공 지표

Spring Boot Actuator + Micrometer 가 자동 등록한다 (총 110여 계열).

| 계열 | 내용 |
|---|---|
| `http_server_requests_seconds` | HTTP 요청 수·지연 (`uri`, `status`, `method`) |
| `jvm_*`, `process_*`, `system_*` | 힙·GC·스레드·CPU |
| `hikaricp_connections_*` | DB 커넥션 풀 |
| `spring_data_repository_invocations_*` | 리포지토리 호출 |

**Resilience4j 서킷브레이커 지표는 확인되지 않았다.** 코드에는 서킷브레이커가
네 곳(OCR·테이블OCR·LLM추출·위조분석)에 있지만, 확인 시점의 스크레이프에는
`resilience4j_*` 가 없었다. 다만 그때 파이프라인이 `ingest` 에서 실패해
사이드카 호출 전에 끝났으므로, 브레이커가 아직 생성되지 않았을 수 있다.
대시보드에 넣기 전에 사이드카를 띄운 상태에서 다시 확인할 것.

---

## 4. 수집 설정

`infra/prometheus/prometheus.yml`:

```yaml
  - job_name: 'doc-agent'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8091']
```

컨테이너명(`doc-agent:8087`)이 아니라 **호스트 포트**를 쓴다. doc-agent 는 `doc` 프로필이라
평소에 떠 있지 않고, Prometheus 와 같은 네트워크에 없을 수 있다.

포트가 compose 에 안 열려 있으면 지표가 아무리 정확해도 하나도 수집되지 않는다.
이 레포에서 실제로 있었던 일이라(auto-loan-review, 두 달간) `common` 모듈의
`ScrapeTargetConsistencyTest` 가 스크레이프 대상과 compose 포트가 어긋나면 실패시킨다.

---

## 5. 대시보드

`doc-agent.json` (uid `doc-agent`) — 9패널.

| 행 | 패널 |
|---|---|
| 1 | 자동 판정 확정률 · 검토 대기 유입(HOLD) · 파이프라인 실패율 · 처리 시간 p95 |
| 2 | 심사원 결정 추이 · 자동 판정 분포 |
| 3 | 단계별 소요 시간 p95 · 단계별 실패 |
| 4 | 서류 유형별 HOLD 비율 |

확정률 쿼리는 분모가 0일 때를 막아 둔다.

```promql
sum(rate(doc_agent_human_review_total{decision="CONFIRMED_FORGERY"}[30m]))
/
(sum(rate(doc_agent_human_review_total[30m])) > 0)
```

**해석 주의.** 확정률이 낮으면 에이전트가 멀쩡한 서류를 잡아 사람 일만 늘린다는 뜻이다.
반대로 1.0 에 붙어 있으면 기준이 보수적이라 잡아야 할 것을 놓치고 있을 수 있다 —
`doc_agent_verdict_total` 의 AUTO_PASS 비율과 같이 봐야 한다.

**표본이 적을 때.** `rate()` 는 창 안의 첫 샘플을 증가분으로 세지 않는다.
결정이 몇 건뿐이면 확정률이 1.0 으로 보일 수 있는데 결함이 아니다.

---

## 6. 아직 구현되지 않은 것

아래는 이전 설계 문서에 있던 항목이다. **코드에 없다.** 대시보드나 알림에 넣으면
데이터 없이 빈 패널이 된다. 쓸모가 있어 남겨 두되, 구현 전에는 쓰지 말 것.

| 지표(안) | 타입 | 왜 있으면 좋은가 |
|---|---|---|
| `doc_agent_human_review_pending` | Gauge | 미결 HOLD 적체. 지금은 유입(rate)만 보여 밀린 양을 모른다 |
| `doc_agent_human_review_duration_hours` | Histogram | HOLD → 결정까지 걸린 시간. 심사 SLA |
| `doc_agent_forgery_score` | Histogram | 위조 점수 분포. 임계값 조정 근거 |
| `doc_agent_forgery_signal_total` | Counter | 어떤 시그널이 실제로 잡히는지 |
| `doc_agent_legal_hold_total` | Gauge | 법적 보존 대상 건수 |
| `doc_agent_fraud_audit_published_total` | Counter | 감사팀 이관 이벤트 발행 수 |

앞의 둘(적체·소요 시간)이 운영에 가장 아쉽다. 지금 지표로는 "사람에게 얼마나 넘어가는지"는
알아도 "그게 처리되고 있는지"는 모른다.

### 지표 추가 시

`DocAgentMetrics` 에 메서드를 더하고 호출부를 연결한다. **호출부 연결까지 테스트할 것** —
지표 클래스만 검증하는 테스트는 서비스가 그 클래스를 부르지 않게 되어도 통과한다.
실제로 이 레포에서 그런 테스트를 만들었다가 계측 호출을 지워도 전부 초록이어서
`HumanReviewMetricsWiringTest` 를 따로 뒀다.

노출까지 보는 테스트(`MetricsExposureTest`)에는 `@AutoConfigureObservability` 가 필요하다.
Boot 는 테스트 컨텍스트에서 지표 내보내기를 기본으로 꺼서, 없으면 운영에서 멀쩡한 설정도
404 로 보인다 — 없는 문제를 쫓게 된다.

---

## 7. 로컬에서 띄우기

```bash
docker compose --profile doc up -d doc-agent
curl -s http://localhost:8091/actuator/prometheus | grep '^doc_agent'
```

기동 직후에는 결과가 비어 있다(§2 의 지연 등록). 한 건 태우면 나온다.

```bash
curl -X POST http://localhost:8091/api/documents/submit \
  -F applicationId=APP-1 -F docCode=INCOME -F file=@sample.pdf
```

사이드카(OCR·위조분석)가 없으면 파이프라인은 실패하지만,
`doc_agent_pipeline_failed_total{stage="ingest"}` 로 어디서 멈췄는지는 그대로 보인다.

> `doc_code` 는 varchar(10) 이다. `INCOME_CERT`(11자)처럼 긴 값을 넣으면 DB 에서 잘린다.

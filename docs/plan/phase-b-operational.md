# Phase B — 운영 준비 (Operational Readiness) 실행 계획

<!-- plan-status -->
> **상태: 🔨 진행 중** — 미완 체크 31개. 관측·배치 일부만
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> Last updated: 2026-05-26 (v1.0)
> 선행 완료: Phase A (A0 ~ A10) — Pre-Review Agent, Rejection Reason Agent
> 패키지 루트: `com.bank.ai`
> 기술 스택: Spring Boot 3.3.5 / Java 17 / PostgreSQL / Flyway / Micrometer / Spring Batch

---

## 전체 목표

Phase A가 에이전트 "파이프라인 동작"을 완성했다면, Phase B는 **규제 대응·모니터링·배포 안전망**을 구축한다.
여신전문금융업법, 금융감독원 AI 가이드라인(2023·2024), 개인정보보호법, 신용정보법의 4대 규제 축을 기준으로
5개 하위 항목(B1~B5)을 순서대로 구현한다.

---

## B1. Audit Log 영구 보존

### 1. 목표와 규제 근거

**목표**: 에이전트가 생성한 모든 `AgentOpinion` (reasoning, tool 호출 내역, LLM 원문 응답)을
불변 감사 로그로 별도 테이블에 영구 보존하여 사후 규제 조사·내부 감사에 대응한다.

**규제 근거**:
- 여신전문금융업법 제52조의2: "신용카드업자 등은 자동화 시스템을 이용하여 여신 심사를 하는 경우…
  심사 과정 및 결과를 5년간 보존하여야 한다."
- 금융감독원 AI 리스크 관리 가이드라인(2024) §4.2: AI 의사결정 보조 시스템은 입력·중간 추론·출력 전 과정을
  추적 가능(traceable)하게 보존해야 하며, 변경·삭제 불가 구조를 권고.
- 개인정보보호법 제28조의2(가명정보 처리): 저장 시 마스킹 여부 기록 포함.

**불변성 원칙**: INSERT-ONLY 테이블. UPDATE/DELETE 모두 DB 트리거로 차단.

---

### 2. 패키지 구조

```
com.bank.ai.audit
├── AuditLogService              — 저장 진입점, @Transactional(propagation=REQUIRES_NEW)
├── AuditLogRepository           — JdbcTemplate 기반 (JPA 엔티티 불필요)
├── AgentAuditRecord             — record: revId, requestSnapshot, opinionJson, toolCallsJson, rawLlmResponse, piiMasked, createdAt
├── AuditLogProperties           — @ConfigurationProperties("ai.audit")
├── AuditLogAutoConfig           — @ConditionalOnProperty("ai.audit.enabled")
└── AuditImmutabilityEnforcer    — ApplicationRunner: DB 트리거 등록 검증
```

---

### 3. DB 스키마 (V3 마이그레이션)

파일: `src/main/resources/db/migration/V3__create_agent_audit_log.sql`

```sql
-- ============================================================
-- V3: 에이전트 감사 로그 테이블 (불변 INSERT-ONLY)
-- 보존 정책: 5년 (여신전문금융업법 §52의2)
-- ============================================================

CREATE TABLE agent_audit_log (
    id                  BIGSERIAL       PRIMARY KEY,
    rev_id              BIGINT          NOT NULL,
    schema_version      VARCHAR(10)     NOT NULL DEFAULT 'v1',
    track               VARCHAR(16)     NOT NULL,
    request_snapshot    JSONB           NOT NULL,
    opinion_json        JSONB           NOT NULL,
    tool_calls_json     JSONB           NOT NULL DEFAULT '[]',
    raw_llm_response    TEXT,
    pii_masked          BOOLEAN         NOT NULL DEFAULT TRUE,
    fallback_reason     VARCHAR(64),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    retention_until     DATE            NOT NULL
        GENERATED ALWAYS AS (CAST(created_at AS DATE) + INTERVAL '5 years') STORED,

    CONSTRAINT chk_schema_version   CHECK (schema_version IN ('v1')),
    CONSTRAINT chk_track            CHECK (track IN ('TRACK_1','TRACK_2','TRACK_3')),
    CONSTRAINT chk_opinion_size     CHECK (pg_column_size(opinion_json) < 65536),
    CONSTRAINT chk_request_size     CHECK (pg_column_size(request_snapshot) < 131072)
);

-- 조회 성능: rev_id 단건 조회, 날짜 범위 리포트
CREATE INDEX idx_aal_rev_id     ON agent_audit_log(rev_id);
CREATE INDEX idx_aal_created_at ON agent_audit_log(created_at DESC);

-- INSERT-ONLY 보장 트리거
CREATE OR REPLACE FUNCTION fn_aal_block_mutate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent_audit_log is INSERT-ONLY. UPDATE/DELETE is forbidden.';
END;
$$;

CREATE TRIGGER trg_aal_no_update
    BEFORE UPDATE ON agent_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_aal_block_mutate();

CREATE TRIGGER trg_aal_no_delete
    BEFORE DELETE ON agent_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_aal_block_mutate();

-- 보존 기간 만료 파티션 분리용 뷰 (실제 삭제는 DBA 승인 프로세스)
CREATE VIEW agent_audit_log_expired AS
    SELECT * FROM agent_audit_log WHERE retention_until < CURRENT_DATE;
```

---

### 4. 핵심 클래스 설계

**`AgentAuditRecord`**
```java
// com.bank.ai.audit.AgentAuditRecord
public record AgentAuditRecord(
    Long revId,
    String track,
    String requestSnapshotJson,   // PII 마스킹 후
    String opinionJson,
    String toolCallsJson,
    String rawLlmResponse,        // nullable — 폴백 시 null
    boolean piiMasked,
    String fallbackReason         // nullable
) {}
```

**`AuditLogService`**
```java
// com.bank.ai.audit.AuditLogService
@Service
public class AuditLogService {

    // INSERT-ONLY; 별도 트랜잭션 — 파이프라인 롤백과 독립 보장
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AgentAuditRecord entry) { ... }

    // 단건 조회 (admin, 감사)
    public Optional<AgentAuditRecord> findByRevId(Long revId) { ... }

    // 날짜 범위 (리포트 생성용)
    public List<AgentAuditRecord> findByDateRange(LocalDate from, LocalDate to, int limit) { ... }
}
```

**`AuditImmutabilityEnforcer`**
```java
// com.bank.ai.audit.AuditImmutabilityEnforcer
@Component
@ConditionalOnProperty("ai.audit.enabled")
public class AuditImmutabilityEnforcer implements ApplicationRunner {
    // 기동 시 UPDATE/DELETE 트리거 존재 여부 쿼리 → 없으면 IllegalStateException
    @Override
    public void run(ApplicationArguments args) { ... }
}
```

**`AutoReviewEventListener` 연결점**:
`PreReviewAgentService.run()` 반환 직후 `AuditLogService.record(...)` 호출.
AuditLog 저장 실패는 에러 로그만 남기고 파이프라인 결과를 막지 않는다 (별도 트랜잭션).

---

### 5. 설정 properties

```yaml
ai:
  audit:
    enabled: ${AI_AUDIT_ENABLED:true}
    include-raw-llm-response: ${AI_AUDIT_RAW_LLM:false}   # 원문 LLM 응답 저장 여부 (개인정보 위험 ↑)
    max-request-snapshot-bytes: 131072
    max-opinion-bytes: 65536
```

---

### 6. 테스트 전략

| TC 이름 | 어노테이션 | 검증 내용 |
|---------|-----------|----------|
| `AuditLogService_record_insertsRow` | `@SpringBootTest` + Testcontainers PG | INSERT 후 `findByRevId`로 round-trip 검증 |
| `AuditLogService_record_isIndependentTransaction` | `@SpringBootTest` | 외부 트랜잭션 롤백 후에도 감사 로그 잔존 확인 |
| `AuditLogImmutability_updateThrows` | `@SpringBootTest` | `JdbcTemplate.update("UPDATE agent_audit_log SET …")` → PSQLException 검증 |
| `AuditLogImmutability_deleteThrows` | `@SpringBootTest` | DELETE 시도 → PSQLException 검증 |
| `AuditImmutabilityEnforcer_failsFastIfTriggerMissing` | `@ExtendWith(MockitoExtension)` | 트리거 부재 시 `IllegalStateException` |
| `AuditLogService_piiMaskedFlag_isTrue` | `@SpringBootTest` | 저장된 `request_snapshot` 에 전화번호 패턴 미존재 확인 |
| `AuditLogService_retentionUntil_isFiveYears` | `@SpringBootTest` | `retention_until = created_at + 5년` DB 컬럼 computed 검증 |

---

### 7. 단계별 커밋 테이블

| # | 타입 | 스코프 | 한글 subject |
|---|------|--------|-------------|
| B1-1 | feat | audit | V3 마이그레이션 + agent_audit_log INSERT-ONLY 트리거 |
| B1-2 | feat | audit | AuditLogService + AgentAuditRecord + AuditLogRepository |
| B1-3 | feat | audit | AuditImmutabilityEnforcer 기동 검증 + AuditLogAutoConfig |
| B1-4 | feat | audit | AutoReviewEventListener → AuditLogService 연결 |
| B1-5 | test | audit | AuditLogService 단위·불변성·PII 마스킹 테스트 |

---

### 8. 완료 기준 (Definition of Done)

- [ ] V3 Flyway 마이그레이션 적용 완료 (로컬 + CI)
- [ ] `agent_audit_log` UPDATE/DELETE 트리거 동작 확인
- [ ] `REQUIRES_NEW` 격리: 파이프라인 롤백 후에도 감사 로그 보존 확인
- [ ] `retention_until` 컬럼 자동 계산 검증 (`created_at + 5 years`)
- [ ] `ai.audit.include-raw-llm-response=false` 기본값으로 `raw_llm_response = null` 확인
- [ ] 전체 TC 그린

---

## B2. Observability — Micrometer 메트릭 + Grafana

### 1. 목표와 규제 근거

**목표**: AI 파이프라인 전체의 성능·오류·비용·공정성 메트릭을 Micrometer로 수집하고 Grafana 대시보드를 제공.
이상 감지 알림(`AlertManager`)과 SLA 추적 기반 마련.

**규제 근거**:
- 금융감독원 AI 리스크 관리 가이드라인(2024) §5: "AI 시스템은 지속적인 성과 모니터링 및 이상 감지 체계를 갖춰야 한다."
- 여신전문금융업법 시행령 제6조의4: 자동 심사 결과의 정기 모니터링 및 이의신청 처리 체계 의무.

---

### 2. 패키지 구조

```
com.bank.ai.metrics
├── AgentMetricsRecorder         — 중앙 메트릭 기록기, MeterRegistry 의존
├── AgentMetricsProperties       — @ConfigurationProperties("ai.metrics")
├── AgentMetricsTags             — 공통 태그 상수 (track, outcome, tool_name 등)
├── LlmCostSummary               — 비용 집계 VO (inputTokens, outputTokens, estimatedUsdCost)
└── AgentMetricsAutoConfig       — @ConditionalOnBean(MeterRegistry)
```

---

### 3. 메트릭 정의 (전체 목록)

| 메트릭 이름 | 타입 | 태그 | 설명 |
|-----------|------|------|------|
| `ai.agent.runs.total` | Counter | track, outcome(SUCCESS/FALLBACK/ERROR) | 에이전트 실행 건수 |
| `ai.agent.latency.seconds` | Timer | track, outcome | 전체 agent run 소요 시간 |
| `ai.agent.tool.calls.total` | Counter | tool_name, status(OK/ERROR) | 도구 호출 건수 |
| `ai.agent.llm.calls.total` | Counter | model, outcome | LLM 호출 건수 |
| `ai.agent.llm.latency.seconds` | Timer | model | LLM 응답 시간 |
| `ai.agent.tokens.input.total` | Counter | model | 입력 토큰 합산 |
| `ai.agent.tokens.output.total` | Counter | model | 출력 토큰 합산 |
| `ai.agent.cost.usd.total` | Counter | model | 추정 비용(USD) |
| `ai.agent.rpm.remaining` | Gauge | — | 현재 분당 잔여 슬롯 |
| `ai.agent.rpd.remaining` | Gauge | — | 당일 잔여 요청 슬롯 |
| `ai.agent.disagreement.total` | Counter | track | reasoning vs. track 불일치 건수 |
| `ai.agent.fallback.total` | Counter | reason(FallbackReason enum) | 폴백 사유별 건수 |
| `ai.agent.hard.fail.total` | Counter | reason(HardFailReason enum) | 하드 페일 사유별 건수 |
| `ai.audit.log.size.bytes` | DistributionSummary | — | 감사 로그 단건 크기 |

---

### 4. 핵심 클래스 설계

**`AgentMetricsRecorder`**
```java
// com.bank.ai.metrics.AgentMetricsRecorder
@Component
public class AgentMetricsRecorder {

    private final MeterRegistry registry;

    // 에이전트 실행 결과 기록
    public void recordRun(Track track, AgentOutcome outcome, Duration duration) { ... }

    // 도구 호출 기록
    public void recordToolCall(String toolName, boolean success) { ... }

    // LLM 호출 기록
    public void recordLlmCall(String model, AgentOutcome outcome,
                               Duration latency, LlmCostSummary cost) { ... }

    // 폴백 기록
    public void recordFallback(FallbackReason reason) { ... }

    // 불일치 기록
    public void recordDisagreement(Track track) { ... }

    // 하드 페일 기록
    public void recordHardFail(HardFailReason reason) { ... }

    // 감사 로그 크기 기록
    public void recordAuditLogSize(int bytes) { ... }
}
```

**`AgentMetricsTags`** — 태그 상수 클래스
```java
public final class AgentMetricsTags {
    public static final String TRACK       = "track";
    public static final String OUTCOME     = "outcome";
    public static final String TOOL_NAME   = "tool_name";
    public static final String MODEL       = "model";
    public static final String REASON      = "reason";
    public static final String STATUS      = "status";
    private AgentMetricsTags() {}
}
```

**`LlmRequestRateMeter` 연동**: `rpm.remaining`, `rpd.remaining` Gauge는
`LlmRequestRateMeter.getRpmRemaining()`, `getRpdRemaining()` 을 직접 참조.

---

### 5. Grafana 대시보드 구성 (JSON provisioning)

파일: `src/main/resources/grafana/dashboards/ai-agent-overview.json`

패널 구성:
1. **에이전트 성공률** (Track별 SUCCESS / FALLBACK / ERROR 비율, Pie chart)
2. **Agent P95 지연** (Histogram heatmap, 목표선 = 30s)
3. **LLM RPM 소비** (Time series, 상한선 15 오버레이)
4. **일별 RPD 소비** (Gauge, 1500 기준 신호등)
5. **도구 호출 분포** (Bar chart, tool_name별)
6. **Fallback 사유 분포** (Stacked bar, FallbackReason)
7. **추정 비용 누적** (Counter → Rate(1d), USD)
8. **Disagreement 건수** (Single stat, 임계 빨간 강조)

---

### 6. 설정 properties

```yaml
ai:
  metrics:
    enabled: ${AI_METRICS_ENABLED:true}
    cost-per-1k-input-tokens: 0.00015    # Gemini 2.5 Flash 기준 (USD)
    cost-per-1k-output-tokens: 0.00060

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    tags:
      application: auto-loan-review
      environment: ${SPRING_PROFILES_ACTIVE:local}
```

---

### 7. 테스트 전략

| TC 이름 | 어노테이션 | 검증 내용 |
|---------|-----------|----------|
| `AgentMetricsRecorder_recordRun_incrementsCounter` | `@ExtendWith(MockitoExtension)` | `ai.agent.runs.total` counter + 1 검증 |
| `AgentMetricsRecorder_recordFallback_taggedByReason` | `@ExtendWith(MockitoExtension)` | `FallbackReason.LLM_RATE_LIMITED` 태그 검증 |
| `AgentMetricsRecorder_recordLlmCall_timerRecorded` | `@ExtendWith(MockitoExtension)` | Timer mean > 0 검증 |
| `AgentMetricsRecorder_rpmGauge_reflectsRateMeter` | `@SpringBootTest` | Gauge 값이 `LlmRequestRateMeter.getRpmRemaining()` 와 일치 |
| `PrometheusEndpoint_exposesAgentMetrics` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | `/actuator/prometheus` 텍스트에 `ai_agent_runs_total` 포함 |
| `AgentMetricsAutoConfig_disabledWhenPropertyFalse` | `@SpringBootTest(properties="ai.metrics.enabled=false")` | `AgentMetricsRecorder` 빈 부재 확인 |

---

### 8. 단계별 커밋 테이블

| # | 타입 | 스코프 | 한글 subject |
|---|------|--------|-------------|
| B2-1 | feat | metrics | AgentMetricsRecorder + AgentMetricsTags + LlmCostSummary |
| B2-2 | feat | metrics | RPM/RPD Gauge LlmRequestRateMeter 연동 |
| B2-3 | feat | metrics | PreReviewAgentService + AuditLogService 메트릭 기록 연결 |
| B2-4 | feat | metrics | Grafana 대시보드 JSON provisioning 파일 |
| B2-5 | test | metrics | AgentMetricsRecorder 단위 + Prometheus 엔드포인트 통합 테스트 |

---

### 9. 완료 기준 (Definition of Done)

- [ ] `/actuator/prometheus` 에 14개 메트릭 전부 노출 확인
- [ ] Gauge `ai.agent.rpm.remaining` 이 `LlmRequestRateMeter` 와 실시간 동기화
- [ ] Grafana 대시보드 JSON provisioning 적용 후 8개 패널 렌더링
- [ ] `ai.metrics.enabled=false` 시 빈 미등록 확인
- [ ] 전체 TC 그린

---

## B3. Shadow Mode

### 1. 목표와 규제 근거

**목표**: 에이전트 의사결정을 실제 승인/거절 결과에 반영하지 않고 병렬 실행하여
에이전트 정확도를 측정하는 **Shadow(Canary) 모드**를 구현.
신규 모델·프롬프트·정책 변경 시 위험 없이 A/B 검증 가능.

**규제 근거**:
- 금융감독원 AI 리스크 관리 가이드라인(2024) §6.1: "AI 모델 변경 시 충분한 검증 단계를 거쳐야 하며,
  단계적 배포(Canary/Shadow)를 권장."
- 금융위원회 혁신금융서비스 운영 기준(2023): 알고리즘 심사 시스템 변경은 최소 2주 shadow 검증 필요.

---

### 2. 패키지 구조

```
com.bank.ai.shadow
├── ShadowModeService            — shadow run 실행 및 비교 결과 저장
├── ShadowComparisonResult       — record: revId, prodOpinion, shadowOpinion, diverged, divergeReason
├── ShadowRunProperties          — @ConfigurationProperties("ai.shadow")
├── ShadowComparisonEvaluator    — prod vs. shadow opinion 비교 로직
└── ShadowResultRepository       — shadow_run_result 테이블 접근
```

---

### 3. DB 스키마 (V4 마이그레이션)

파일: `src/main/resources/db/migration/V4__create_shadow_run_result.sql`

```sql
-- ============================================================
-- V4: Shadow Mode 비교 결과 테이블
-- ============================================================

CREATE TABLE shadow_run_result (
    id                  BIGSERIAL       PRIMARY KEY,
    rev_id              BIGINT          NOT NULL,
    prod_opinion_json   JSONB           NOT NULL,
    shadow_opinion_json JSONB           NOT NULL,
    diverged            BOOLEAN         NOT NULL DEFAULT FALSE,
    diverge_reasons     TEXT[]          NOT NULL DEFAULT '{}',
    prod_track          VARCHAR(16)     NOT NULL,
    shadow_track        VARCHAR(16)     NOT NULL,
    prod_decision_score NUMERIC(5,4),
    shadow_decision_score NUMERIC(5,4),
    shadow_model        VARCHAR(64)     NOT NULL,
    shadow_prompt_version VARCHAR(32)  NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_prod_track   CHECK (prod_track IN ('TRACK_1','TRACK_2','TRACK_3')),
    CONSTRAINT chk_shadow_track CHECK (shadow_track IN ('TRACK_1','TRACK_2','TRACK_3'))
);

CREATE INDEX idx_srr_rev_id     ON shadow_run_result(rev_id);
CREATE INDEX idx_srr_created_at ON shadow_run_result(created_at DESC);
CREATE INDEX idx_srr_diverged   ON shadow_run_result(diverged) WHERE diverged = TRUE;
```

---

### 4. 핵심 클래스 설계

**`ShadowModeService`**
```java
// com.bank.ai.shadow.ShadowModeService
@Service
@ConditionalOnProperty("ai.shadow.enabled")
public class ShadowModeService {

    private final PreReviewAgentService shadowAgentService; // shadow용 별도 빈
    private final ShadowComparisonEvaluator evaluator;
    private final ShadowResultRepository repository;
    private final AgentMetricsRecorder metricsRecorder;

    // 프로덕션 파이프라인 완료 후 @Async("shadowExecutor")로 호출
    @Async("shadowExecutor")
    public CompletableFuture<ShadowComparisonResult> runShadow(
            Long revId,
            AutoReviewRequest request,
            TrackDecision trackDecision,
            AgentOpinion prodOpinion) { ... }
}
```

**`ShadowComparisonEvaluator`**
```java
// com.bank.ai.shadow.ShadowComparisonEvaluator
@Component
public class ShadowComparisonEvaluator {

    // diverge 판단 기준: track 불일치, |decisionScore차| > 0.10, risk_level 불일치
    public ShadowComparisonResult evaluate(
            Long revId,
            AgentOpinion prod,
            AgentOpinion shadow,
            String shadowModel,
            String shadowPromptVersion) { ... }
}
```

**Shadow 전용 Executor 설정** (`LlmAsyncConfig`에 추가):
```java
@Bean("shadowExecutor")
public Executor shadowExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(2);
    exec.setMaxPoolSize(4);
    exec.setQueueCapacity(50);
    exec.setThreadNamePrefix("shadow-");
    return exec;
}
```

---

### 5. 설정 properties

```yaml
ai:
  shadow:
    enabled: ${AI_SHADOW_ENABLED:false}
    model: gemini-2.5-flash                    # shadow 전용 모델 (prod와 동일 또는 다른 버전)
    prompt-version: v2-candidate               # 테스트할 프롬프트 버전
    diverge-score-threshold: 0.10              # decisionScore 차이 임계
    sampling-rate: 1.0                         # 0.0~1.0, 1.0 = 전건 shadow
    timeout-seconds: 45                        # shadow는 prod보다 여유 있게
    async-executor: shadowExecutor
```

---

### 6. 테스트 전략

| TC 이름 | 어노테이션 | 검증 내용 |
|---------|-----------|----------|
| `ShadowModeService_disabled_noShadowRun` | `@SpringBootTest(properties="ai.shadow.enabled=false")` | 빈 미생성 확인 |
| `ShadowComparisonEvaluator_sameTrack_notDiverged` | `@ExtendWith(MockitoExtension)` | track 동일 + score 차 < 0.10 → `diverged=false` |
| `ShadowComparisonEvaluator_differentTrack_diverged` | `@ExtendWith(MockitoExtension)` | Track 2 vs Track 3 → `diverged=true`, reasons 포함 |
| `ShadowComparisonEvaluator_scoreGapAboveThreshold_diverged` | `@ExtendWith(MockitoExtension)` | score 차 0.15 → `diverged=true` |
| `ShadowModeService_runShadow_persistsResult` | `@SpringBootTest` + Testcontainers | shadow 실행 후 `shadow_run_result` INSERT 확인 |
| `ShadowModeService_doesNotAffectProdOpinion` | `@SpringBootTest` | shadow 실행 후 `loan_review.agent_opinion_json` 불변 확인 |
| `ShadowResultRepository_findDivergedByDateRange` | `@SpringBootTest` | `diverged=true` 필터 조회 검증 |

---

### 7. 단계별 커밋 테이블

| # | 타입 | 스코프 | 한글 subject |
|---|------|--------|-------------|
| B3-1 | feat | shadow | V4 마이그레이션 + shadow_run_result 스키마 |
| B3-2 | feat | shadow | ShadowComparisonEvaluator + ShadowComparisonResult |
| B3-3 | feat | shadow | ShadowModeService + shadowExecutor 비동기 설정 |
| B3-4 | feat | shadow | AutoReviewEventListener shadow 트리거 연결 |
| B3-5 | test | shadow | ShadowComparisonEvaluator 단위 + ShadowModeService 통합 테스트 |

---

### 8. 완료 기준 (Definition of Done)

- [ ] `ai.shadow.enabled=false` 기본값 — prod 배포에 영향 없음 확인
- [ ] Shadow 실행이 prod `agent_opinion_json` 을 변경하지 않음 확인
- [ ] `shadow_run_result` INSERT + `diverged` 인덱스 동작 확인
- [ ] Diverge 판단 3가지 기준(track/score/risk) 단위 테스트 통과
- [ ] Shadow 실패 시 prod 파이프라인 영향 없음 (`@Async` 격리 확인)
- [ ] 전체 TC 그린

---

## B4. PSI Drift 감지 + 공정성 리포트

### 1. 목표와 규제 근거

**목표**:
- **PSI (Population Stability Index)**: `AutoReviewRequest` 입력 변수 분포가 훈련 시점 대비
  운영 중 표류(drift)하는지 주간 배치로 감지하여 모델 재학습 신호를 제공.
- **공정성 리포트**: 연령·직업 유형 등 민감 속성 기준 승인률 격차를 월별 집계.

**규제 근거**:
- 금융감독원 AI 리스크 관리 가이드라인(2024) §7: "모델 성능 저하 및 입력 데이터 분포 변화를
  정기적으로 모니터링해야 하며, 유의미한 변화 발생 시 모델을 재검토해야 한다."
- 신용정보법 제22조(신용정보 활용 공정성): 자동화 의사결정 시스템은 특정 집단에 대한
  차별적 결과 발생 여부를 모니터링해야 한다.
- EU AI Act Article 9(Risk Management): High-risk AI systems require continuous monitoring.
- PSI 임계: 0.1 미만 = 안정, 0.1~0.2 = 주의, 0.2 이상 = 재학습 권고.

---

### 2. 패키지 구조

```
com.bank.ai.drift
├── PsiDriftDetector             — PSI 계산 핵심 로직
├── PsiDriftReport               — record: featureName, psi, status(STABLE/WARNING/CRITICAL), baseline, current
├── PsiBaselineRepository        — psi_baseline 테이블 접근 (훈련 시점 분포 저장)
├── PsiDriftBatchJob             — Spring Batch Job: 주간 PSI 계산
├── PsiDriftJobConfig            — BatchJobConfig (Step, Tasklet)
├── FairnessReportService        — 월별 집단별 승인률 집계
├── FairnessReport               — record: period, groupKey, approvalRate, sampleCount, flagged
├── FairnessReportRepository     — fairness_report 테이블 접근
└── DriftAlertService            — PSI CRITICAL 시 메트릭 + 로그 경보
```

---

### 3. DB 스키마 (V5 마이그레이션)

파일: `src/main/resources/db/migration/V5__create_psi_drift_tables.sql`

```sql
-- ============================================================
-- V5: PSI Drift 감지 + 공정성 리포트 테이블
-- ============================================================

-- (1) 훈련 시점 기준 분포 (Baseline)
CREATE TABLE psi_baseline (
    id              BIGSERIAL       PRIMARY KEY,
    feature_name    VARCHAR(128)    NOT NULL,
    bucket_index    SMALLINT        NOT NULL,
    bucket_low      NUMERIC(18,6),
    bucket_high     NUMERIC(18,6),
    baseline_ratio  NUMERIC(8,6)    NOT NULL,
    baseline_date   DATE            NOT NULL,
    model_version   VARCHAR(64)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uq_psi_baseline UNIQUE (feature_name, bucket_index, model_version)
);

-- (2) 주간 PSI 계산 결과
CREATE TABLE psi_drift_result (
    id              BIGSERIAL       PRIMARY KEY,
    feature_name    VARCHAR(128)    NOT NULL,
    calc_week       DATE            NOT NULL,   -- 주 시작일 (월요일)
    psi_value       NUMERIC(8,6)    NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    sample_count    INT             NOT NULL,
    model_version   VARCHAR(64)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_psi_status CHECK (status IN ('STABLE','WARNING','CRITICAL')),
    CONSTRAINT uq_psi_week    UNIQUE (feature_name, calc_week, model_version)
);

CREATE INDEX idx_pdr_feature_week ON psi_drift_result(feature_name, calc_week DESC);
CREATE INDEX idx_pdr_status       ON psi_drift_result(status) WHERE status != 'STABLE';

-- (3) 월별 공정성 리포트
CREATE TABLE fairness_report (
    id              BIGSERIAL       PRIMARY KEY,
    report_month    DATE            NOT NULL,   -- 월 시작일 (1일)
    group_key       VARCHAR(64)     NOT NULL,   -- 예: "age_band:30s", "job_type:SELF_EMPLOYED"
    approval_rate   NUMERIC(5,4)    NOT NULL,
    sample_count    INT             NOT NULL,
    overall_rate    NUMERIC(5,4)    NOT NULL,   -- 전체 승인률 (기준)
    rate_gap        NUMERIC(5,4)    NOT NULL GENERATED ALWAYS AS (approval_rate - overall_rate) STORED,
    flagged         BOOLEAN         NOT NULL DEFAULT FALSE,  -- |gap| > 0.05
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uq_fairness UNIQUE (report_month, group_key)
);

CREATE INDEX idx_fr_month   ON fairness_report(report_month DESC);
CREATE INDEX idx_fr_flagged ON fairness_report(flagged) WHERE flagged = TRUE;
```

---

### 4. 핵심 클래스 설계

**`PsiDriftDetector`**
```java
// com.bank.ai.drift.PsiDriftDetector
@Component
public class PsiDriftDetector {

    // PSI = Σ (current_ratio - baseline_ratio) × ln(current_ratio / baseline_ratio)
    public double calculatePsi(List<Double> baselineRatios, List<Double> currentRatios) { ... }

    // PsiDriftReport.status 결정
    public PsiStatus classify(double psi) {
        // psi < 0.10 → STABLE, 0.10~0.20 → WARNING, ≥ 0.20 → CRITICAL
    }

    // 현재 데이터의 버킷별 비율 계산
    public List<Double> bucketize(List<Double> values, List<double[]> bucketBounds) { ... }
}
```

**`PsiDriftBatchJob`** (Spring Batch Tasklet)
```java
// com.bank.ai.drift.PsiDriftBatchJob
@Component
public class PsiDriftBatchJob implements Tasklet {

    // Step: 지난 주 loan_review 데이터 집계 → PSI 계산 → psi_drift_result INSERT
    @Override
    public RepeatStatus execute(StepContribution contribution,
                                ChunkOrientedTasklet context) { ... }
}
```

**`PsiDriftJobConfig`**
```java
// com.bank.ai.drift.PsiDriftJobConfig
@Configuration
@EnableBatchProcessing
public class PsiDriftJobConfig {

    @Bean
    public Job psiDriftJob(JobRepository jobRepository, Step psiDriftStep) { ... }

    @Bean
    public Step psiDriftStep(JobRepository jobRepository,
                              PlatformTransactionManager txManager,
                              PsiDriftBatchJob tasklet) { ... }
}
```

PSI 대상 피처 (초기 6개): `requestedAmountKw`, `requestedPeriodMo`, `dsr`, `ltv`, `creditScore`, `annualIncomeKw`

**`FairnessReportService`**
```java
// com.bank.ai.drift.FairnessReportService
@Service
public class FairnessReportService {

    // 월별 배치 호출 — loan_review 테이블에서 집단별 승인률 집계
    public List<FairnessReport> generateMonthlyReport(YearMonth month) { ... }

    // 집단 정의: ageBand (20s/30s/40s/50s/60+), jobType (EMPLOYED/SELF_EMPLOYED/OTHER)
    private String resolveGroupKey(AutoReviewRequest req) { ... }
}
```

**`DriftAlertService`**
```java
// com.bank.ai.drift.DriftAlertService
@Component
public class DriftAlertService {

    private final AgentMetricsRecorder metricsRecorder;

    // CRITICAL PSI → Counter + WARN 로그 (AlertManager webhook 선택적)
    public void alert(PsiDriftReport report) { ... }
}
```

추가 메트릭:
- `ai.drift.psi.value{feature_name}` — Gauge (주간 최신 PSI)
- `ai.drift.psi.critical.total` — Counter (CRITICAL 발생 건수)
- `ai.fairness.flagged.groups.total{report_month}` — Gauge

---

### 5. 설정 properties

```yaml
ai:
  drift:
    enabled: ${AI_DRIFT_ENABLED:true}
    psi-features:
      - requestedAmountKw
      - requestedPeriodMo
      - dsr
      - ltv
      - creditScore
      - annualIncomeKw
    psi-warning-threshold: 0.10
    psi-critical-threshold: 0.20
    fairness-gap-threshold: 0.05      # |집단 승인률 - 전체 승인률| 임계
    model-version: ${AI_MODEL_VERSION:v1}
    batch:
      psi-cron: "0 0 3 * * MON"      # 매주 월요일 새벽 3시
      fairness-cron: "0 0 4 1 * *"   # 매월 1일 새벽 4시

spring:
  batch:
    job:
      enabled: false                  # 수동 트리거 기본값
    jdbc:
      initialize-schema: always
```

---

### 6. 테스트 전략

| TC 이름 | 어노테이션 | 검증 내용 |
|---------|-----------|----------|
| `PsiDriftDetector_psiZero_whenDistributionIdentical` | `@ExtendWith(MockitoExtension)` | 동일 분포 → PSI ≈ 0 |
| `PsiDriftDetector_psiCritical_whenDistributionShifted` | `@ExtendWith(MockitoExtension)` | 극단 분포 이동 → PSI ≥ 0.20, CRITICAL |
| `PsiDriftDetector_classify_warningBand` | `@ExtendWith(MockitoExtension)` | PSI=0.15 → WARNING |
| `PsiDriftBatchJob_execute_insertsResult` | `@SpringBootTest` + 2040년 데이터 격리 | Batch Step 실행 후 `psi_drift_result` INSERT 확인 |
| `FairnessReportService_generateMonthlyReport_flagsHighGap` | `@SpringBootTest` + 2040년 데이터 격리 | 승인률 격차 0.08 → `flagged=true` |
| `FairnessReportService_overallRate_isCorrect` | `@SpringBootTest` | 전체 승인률 계산 정확도 검증 |
| `DriftAlertService_alertOnCritical_incrementsCounter` | `@ExtendWith(MockitoExtension)` | CRITICAL 시 `ai.drift.psi.critical.total` +1 |
| `PsiDriftJobConfig_jobRunsWithoutException` | `@SpringBootTest` | Spring Batch Job 전체 실행 → COMPLETED 상태 |

**날짜 격리 원칙 적용**: 배치 테스트는 2040년 합성 데이터 사용 (DB 공유 환경 충돌 방지).

---

### 7. 단계별 커밋 테이블

| # | 타입 | 스코프 | 한글 subject |
|---|------|--------|-------------|
| B4-1 | feat | drift | V5 마이그레이션 + psi_baseline·psi_drift_result·fairness_report 스키마 |
| B4-2 | feat | drift | PsiDriftDetector PSI 계산 + 버킷화 로직 |
| B4-3 | feat | drift | PsiDriftBatchJob + PsiDriftJobConfig Spring Batch 설정 |
| B4-4 | feat | drift | FairnessReportService 월별 집단 집계 |
| B4-5 | feat | drift | DriftAlertService + Micrometer Gauge 연동 |
| B4-6 | test | drift | PsiDriftDetector 단위 + Batch Job 통합 + FairnessReport 통합 테스트 |

---

### 8. 완료 기준 (Definition of Done)

- [ ] PSI 계산 수식 단위 테스트: 동일 분포 → 0, 극단 이동 → CRITICAL
- [ ] Spring Batch Job `psiDriftJob` COMPLETED 상태 확인
- [ ] `fairness_report.rate_gap` Generated Column 자동 계산 확인
- [ ] `flagged=true` 조건 (|gap| > 0.05) 정상 동작
- [ ] `ai.drift.psi.value` Gauge Prometheus 노출 확인
- [ ] 배치 테스트 격리: 2040년 데이터 사용, 다른 연도 데이터 오염 없음
- [ ] 전체 TC 그린

---

## B5. Admin Endpoint

### 1. 목표와 규제 근거

**목표**: 관리자가 브라우저/CLI에서 에이전트 상태를 조회하고, AgentOpinion 재생성·
폴백 재시도·설정 변경을 안전하게 수행할 수 있는 보안 REST API를 제공한다.

**규제 근거**:
- 여신전문금융업법 제52조의3: "자동 심사 시스템에 대한 이의신청 처리 및 수동 개입 수단을 갖춰야 한다."
- 금융감독원 AI 가이드라인(2024) §8: "AI 시스템 운영 담당자는 AI 결정을 사후 조회·수정·무효화할 수 있어야 한다."
- NIST AI RMF Govern 1.2: Human oversight and intervention capabilities required.

**보안 원칙**:
- Spring Security `ROLE_AI_ADMIN` 권한 필수
- 모든 관리 조작 → `AuditLogService` 별도 기록 (행위 감사)
- Read-only 엔드포인트 (`GET`) 와 변경 엔드포인트 (`POST/DELETE`) 권한 분리

---

### 2. 패키지 구조

```
com.bank.ai.admin
├── AgentAdminController         — REST API 진입점 (@RestController)
├── AgentAdminService            — 비즈니스 로직, 재생성·조회·설정 변경
├── AdminActionAuditService      — 관리 행위 감사 기록 (별도 테이블)
├── AgentStatusResponse          — DTO: 현재 에이전트 상태, 메트릭 스냅샷
├── RegenerateOpinionRequest     — DTO: revId, reason (재생성 사유 필수)
├── AdminAuditRecord             — record: adminUser, action, targetRevId, reason, createdAt
└── AdminSecurityConfig          — SecurityFilterChain for /admin/ai/**
```

---

### 3. DB 스키마 (V6 마이그레이션)

파일: `src/main/resources/db/migration/V6__create_admin_audit.sql`

```sql
-- ============================================================
-- V6: Admin 행위 감사 로그
-- ============================================================

CREATE TABLE admin_action_audit (
    id              BIGSERIAL       PRIMARY KEY,
    admin_user      VARCHAR(128)    NOT NULL,
    action          VARCHAR(64)     NOT NULL,
    target_rev_id   BIGINT,
    request_body    JSONB,
    result          VARCHAR(32)     NOT NULL,   -- SUCCESS / FAILURE
    failure_reason  TEXT,
    ip_address      INET,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_action CHECK (action IN (
        'REGENERATE_OPINION',
        'TOGGLE_AGENT',
        'FLUSH_RATE_METER',
        'QUERY_STATUS',
        'QUERY_AUDIT_LOG',
        'QUERY_PSI_REPORT',
        'QUERY_FAIRNESS_REPORT',
        'QUERY_SHADOW_DIVERGED'
    )),
    CONSTRAINT chk_result CHECK (result IN ('SUCCESS','FAILURE'))
);

CREATE INDEX idx_aaa_admin_user  ON admin_action_audit(admin_user);
CREATE INDEX idx_aaa_created_at  ON admin_action_audit(created_at DESC);
CREATE INDEX idx_aaa_action      ON admin_action_audit(action);
```

---

### 4. 핵심 클래스 설계

**`AgentAdminController`**
```java
// com.bank.ai.admin.AgentAdminController
@RestController
@RequestMapping("/admin/ai")
@PreAuthorize("hasRole('AI_ADMIN')")
public class AgentAdminController {

    // 에이전트 전체 상태 조회
    @GetMapping("/status")
    public AgentStatusResponse getStatus() { ... }

    // 단건 AgentOpinion 조회 (감사 로그 포함)
    @GetMapping("/opinions/{revId}")
    public AgentOpinion getOpinion(@PathVariable Long revId) { ... }

    // AgentOpinion 강제 재생성 (이의신청 처리용)
    @PostMapping("/opinions/{revId}/regenerate")
    public AgentOpinion regenerate(@PathVariable Long revId,
                                    @RequestBody @Valid RegenerateOpinionRequest req) { ... }

    // 에이전트 ON/OFF 토글
    @PostMapping("/toggle")
    @PreAuthorize("hasRole('AI_SUPER_ADMIN')")
    public Map<String, Object> toggle(@RequestParam boolean enabled) { ... }

    // RPM/RPD 카운터 리셋 (장애 복구용)
    @PostMapping("/rate-meter/flush")
    @PreAuthorize("hasRole('AI_SUPER_ADMIN')")
    public Map<String, Object> flushRateMeter() { ... }

    // PSI Drift 리포트 조회
    @GetMapping("/drift/psi")
    public List<PsiDriftReport> getPsiReports(
            @RequestParam @DateTimeFormat(iso=ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=ISO.DATE) LocalDate to) { ... }

    // 공정성 리포트 조회
    @GetMapping("/drift/fairness")
    public List<FairnessReport> getFairnessReports(
            @RequestParam String yearMonth) { ... }

    // Shadow Diverge 건 조회
    @GetMapping("/shadow/diverged")
    public List<ShadowComparisonResult> getDiverged(
            @RequestParam @DateTimeFormat(iso=ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "100") int limit) { ... }

    // 감사 로그 조회
    @GetMapping("/audit/{revId}")
    public AgentAuditRecord getAuditLog(@PathVariable Long revId) { ... }
}
```

**`AgentAdminService`**
```java
// com.bank.ai.admin.AgentAdminService
@Service
public class AgentAdminService {

    private final PreReviewAgentService agentService;
    private final AuditLogService auditLogService;
    private final AdminActionAuditService actionAuditService;
    private final AgentProperties agentProperties;
    private final LlmRequestRateMeter rateMeter;
    private final AgentMetricsRecorder metricsRecorder;

    // 재생성: 기존 opinion 덮어쓰기 + 감사 기록
    @Transactional
    public AgentOpinion regenerateOpinion(Long revId, String adminUser,
                                           String reason) { ... }

    // 에이전트 ON/OFF (런타임 Properties 변경 — @RefreshScope or ConfigurableApplicationContext)
    public boolean toggleAgent(boolean enabled, String adminUser) { ... }

    // RPM/RPD 리셋
    public void flushRateMeter(String adminUser) { ... }

    // 상태 스냅샷
    public AgentStatusResponse buildStatus() { ... }
}
```

**`AgentStatusResponse`**
```java
// com.bank.ai.admin.AgentStatusResponse
public record AgentStatusResponse(
    boolean agentEnabled,
    int rpmRemaining,
    int rpdRemaining,
    long totalRunsToday,
    long fallbacksToday,
    long disagreementsToday,
    String currentModel,
    String currentPromptVersion,
    boolean shadowModeEnabled,
    boolean driftDetectionEnabled
) {}
```

**`AdminSecurityConfig`**
```java
// com.bank.ai.admin.AdminSecurityConfig
@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/admin/ai/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/admin/ai/**").hasRole("AI_ADMIN")
                .requestMatchers(HttpMethod.POST, "/admin/ai/**").hasRole("AI_ADMIN")
                .anyRequest().denyAll()
            )
            .httpBasic(Customizer.withDefaults())   // 초기; 운영 시 JWT 교체
            .csrf(csrf -> csrf.disable())           // REST API
            .build();
    }
}
```

---

### 5. 설정 properties

```yaml
ai:
  admin:
    enabled: ${AI_ADMIN_ENABLED:true}
    regenerate-requires-reason: true        # 재생성 시 reason 필드 필수 여부
    max-regenerate-per-day: 50             # 남용 방지 일일 재생성 상한

spring:
  security:
    user:
      name: ${AI_ADMIN_USER:ai-admin}
      password: ${AI_ADMIN_PASSWORD}       # 환경변수 필수
      roles: AI_ADMIN
```

---

### 6. 테스트 전략

| TC 이름 | 어노테이션 | 검증 내용 |
|---------|-----------|----------|
| `AgentAdminController_getStatus_returns200` | `@WebMvcTest(AgentAdminController)` + `@WithMockUser(roles="AI_ADMIN")` | 200 + AgentStatusResponse 구조 검증 |
| `AgentAdminController_getStatus_unauthorized_returns401` | `@WebMvcTest` | 인증 없이 접근 → 401 |
| `AgentAdminController_regenerate_withoutReason_returns400` | `@WebMvcTest` + `@WithMockUser` | reason 누락 → 400 BadRequest |
| `AgentAdminController_regenerate_callsService` | `@WebMvcTest` + `@MockBean` | `AgentAdminService.regenerateOpinion` 호출 1회 검증 |
| `AgentAdminController_toggle_requiresSuperAdmin` | `@WebMvcTest` + `@WithMockUser(roles="AI_ADMIN")` | SUPER_ADMIN 없이 toggle → 403 |
| `AgentAdminService_regenerate_overwritesOpinion` | `@SpringBootTest` | 재생성 후 `loan_review.agent_opinion_json` 갱신 확인 |
| `AgentAdminService_regenerate_recordsAdminAudit` | `@SpringBootTest` | `admin_action_audit` INSERT + action=REGENERATE_OPINION 확인 |
| `AgentAdminService_flushRateMeter_resetsCounters` | `@ExtendWith(MockitoExtension)` | `rateMeter.flush()` 호출 후 remaining = 최대값 |
| `AdminActionAuditService_record_insertsRow` | `@SpringBootTest` | `admin_action_audit` INSERT round-trip |

---

### 7. 단계별 커밋 테이블

| # | 타입 | 스코프 | 한글 subject |
|---|------|--------|-------------|
| B5-1 | feat | admin | V6 마이그레이션 + admin_action_audit 스키마 |
| B5-2 | feat | admin | AdminSecurityConfig + ROLE_AI_ADMIN 보안 설정 |
| B5-3 | feat | admin | AgentAdminController 전체 엔드포인트 |
| B5-4 | feat | admin | AgentAdminService 재생성·토글·flushRateMeter 로직 |
| B5-5 | feat | admin | AdminActionAuditService 행위 감사 기록 |
| B5-6 | test | admin | AgentAdminController WebMvcTest + AgentAdminService 통합 테스트 |

---

### 8. 완료 기준 (Definition of Done)

- [ ] `GET /admin/ai/status` → 200, AgentStatusResponse 전 필드 채워짐
- [ ] 인증 없이 접근 → 401
- [ ] `AI_ADMIN` 권한으로 `POST /admin/ai/toggle` → 403 (SUPER_ADMIN 필요)
- [ ] 재생성 API 호출 후 `loan_review.agent_opinion_json` 갱신 + `admin_action_audit` INSERT
- [ ] `reason` 없는 재생성 요청 → 400
- [ ] `ai.admin.enabled=false` 시 컨트롤러 빈 미등록 또는 404 반환
- [ ] 전체 TC 그린

---

## 전체 Phase B 단계 요약

| 순서 | 항목 | 마이그레이션 | 커밋 수 |
|------|------|------------|--------|
| B1 | Audit Log 영구 보존 | V3 | 5 |
| B2 | Observability + Grafana | — | 5 |
| B3 | Shadow Mode | V4 | 5 |
| B4 | PSI Drift + 공정성 리포트 | V5 | 6 |
| B5 | Admin Endpoint | V6 | 6 |

**단계 진입 전제**: 각 항목은 이전 항목 전체 TC 그린 후 착수.
**커밋 규칙**: `feat(…)`와 `test(…)` 항상 별도 커밋, body·불릿 없는 한 줄 메시지.

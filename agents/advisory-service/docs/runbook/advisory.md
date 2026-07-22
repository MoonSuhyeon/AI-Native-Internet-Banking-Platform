# 📕 어드바이저리 운영 룬북

> **대상**: 운영자(SRE/ops), 감사관, 심사관 리드
> **연관**: [도입 계획](../plan/loan_review_advisory.md) · [API 가이드](../api/advisory.md) · [Grafana 대시보드](../../infra/grafana/provisioning/dashboards/advisory.json)

본 문서는 LON 심사관 어드바이저리 모듈의 *운영 사고 대응 절차* 와 *룰 운영 권한* 을 정리한다. 매번 다른 처리 대신 동일 절차를 반복할 수 있게 한다.

---

## 0. 빠른 참조 (Cheat sheet)

| 상황 | 1차 조치 | 도구 |
|---|---|---|
| 약정 생성이 `LOAN_192` 로 갑자기 막힘 | 게이트 차단 빈도 확인 → 룰 정상 발화면 ack 안내, 오작동이면 룰 비활성화 | [§1](#1-critical-게이트-차단-대응) |
| 특정 룰의 리포트가 폭증 | 룰 비활성화 → 임계치 완화 후 재활성 | [§2](#2-리포트-폭증-대응) |
| 일배치 실패 (`/batch-evaluate` 5xx) | 로그 확인 → 수동 재실행 또는 다음 일자에 보강 | [§3](#3-일배치-실패-대응) |
| 거절 신청인이 결정 변경 후 통지 요청 | 이의신청 채널로 위임 (어드바이저리 범위 밖) | [§4](#4-신청인-통지-의무-체크리스트) |
| Grafana 게이지 0 으로 고정 (gauge stuck) | loan-service 재시작 + 액세스 로그 확인 | [§5](#5-관측-이상-대응) |

---

## 1. CRITICAL 게이트 차단 대응

### 1.1 정상 차단 vs 오작동 구분

`advisory_critical_gate_blocked_total` 의 rate 가 평소보다 높아지면:

1. Grafana 패널 "CRITICAL 게이트 차단 추세" 확인
2. `GET /api/advisory/reports?severityCd=CRITICAL&advrStatusCd=OPEN` (AUDITOR) 로 미해결 CRITICAL 목록 조회
3. 각 리포트의 `signals` 검토:
   - DSR/LTV 가 실제 한도 초과인지 (운영 데이터 검증)
   - `signal_detail` JSON 의 임계치/관측값 비교

**정상**: 룰이 의도대로 발화 → 심사관 ack 안내. 후속 단계는 ack 후 자동 재개.

**오작동 (False Positive)**: 룰 임계치가 너무 좁아서 정상 케이스도 차단. §1.2 룰 임계치 완화 또는 §1.3 비활성화 수순.

### 1.2 룰 임계치 임시 완화

`rule_params` JSON 으로 임계치를 조정한다 (현재 룰은 코드 상수 사용 — Phase 2 MVP 한계, Phase 5-4 튜닝 시 코드에서 `rule_params` 로 이전 예정).

코드 상수 변경이 필요한 경우 (응급):
1. 운영 사고로 등록 (INC-YYYY-NNNN)
2. 핫픽스 브랜치 → 룰 클래스의 임계치 상수 수정 → 배포
3. 배포 전 정성 검증: 단위 테스트 (`AdvisoryRuleAndGateUnitTest`) 통과

### 1.3 룰 비활성화 (응급)

운영 사고 시 가장 빠른 차단:

```bash
curl -X PUT "http://loan-service/api/advisory/rules/{ruleId}" \
     -H "X-Actor-Role: ADMIN" -H "Content-Type: application/json" \
     -d '{
       "activeYn": "N",
       "changeReasonCd": "INCIDENT",
       "changeRemark": "INC-2026-0512 false positive 폭증 대응"
     }'
```

- `STATUS_HISTORY` 에 BEFORE/AFTER 자동 적재 (사후 감사 가능)
- 비활성화 후 evaluator 가 해당 룰 skip — 신규 리포트 발행 중단
- 기존 미해결 리포트는 그대로 — 별도 ack 또는 RESOLVED 처리

재활성:
```bash
curl -X PUT "http://loan-service/api/advisory/rules/{ruleId}" \
     -H "X-Actor-Role: ADMIN" -H "Content-Type: application/json" \
     -d '{"activeYn":"Y","changeReasonCd":"RESTORE","changeRemark":"INC-2026-0512 종료"}'
```

### 1.4 우회 권한 — *불가*

운영 장애 시에도 admin 이 *개별 리포트의 게이트를 우회* 하는 경로는 제공하지 않는다. 우회가 필요하면 §1.3 룰 비활성화 후 약정 진행. 우회 권한 도입은 plan §10-4 미해결 이슈.

---

## 2. 리포트 폭증 대응

`advisory_report_published_total` rate 가 평소 대비 5배 이상 증가:

1. 룰별 분포 확인 (Grafana 1번 패널)
2. 폭증 룰 식별 → 어떤 코호트/매칭이 트리거 원인인지 신호 sample 검토
3. **알람 피로 방지 우선순위**:
   - WARN 폭증 → 룰 임계치 완화 검토
   - CRITICAL 폭증 → 게이트 차단 영향 큼, §1.3 비활성화 + 핫픽스

### 2.1 BIAS_* 룰 폭증 (가장 흔한 케이스)
peer 표준편차가 작은 코호트에서 한 reviewer 가 이상치로 잡힘. 코호트 표본이 작거나 reviewer 가 1~2명만 처리하는 코호트는 통계 의미가 낮음 — 표본 임계 (현재 30) 상향 검토.

### 2.2 PEER_DECISION_DIVERGENCE 폭증
유사 신청자 매칭 윈도우(±5점/±500bps)가 너무 넓거나 좁음. 매칭 N 조정 → §1.2.

---

## 3. 일배치 실패 대응

`POST /api/internal/advisory/batch-evaluate` 가 5xx 반환 또는 SnapshotRunResult 이상값:

1. loan-service 로그 검사 (`HikariCP`, `EntityManager` 예외)
2. 트랜잭션 롤백 여부 확인 — 멱등 INSERT 이므로 부분 실패 시 재실행 안전
3. 수동 재실행:
```bash
curl -X POST "http://loan-service/api/internal/advisory/batch-evaluate?baseDate=YYYYMMDD" \
     -H "X-Actor-Role: ADMIN"
```
4. snapshot 만 다시 적재하고 평가는 별도:
```bash
curl -X POST "http://loan-service/api/internal/advisory/snapshot?baseDate=YYYYMMDD" \
     -H "X-Actor-Role: ADMIN"
```

### 3.1 DB 락 / 동시성 이슈
배치 중 본심사 / 약정 API 가 동시 호출 → reportRepo INSERT 가 row lock 으로 지연. 일배치는 새벽 비업무 시간에 실행 (운영 합의).

---

## 4. 신청인 통지 의무 체크리스트

> 본 항목은 어드바이저리 도구의 *범위 밖* (plan §10-6). 이의신청 채널 설계 시 연계 필요.

| 시나리오 | 통지 의무 | 담당 |
|---|---|---|
| 거절 신청 + BIAS 리포트 발행 + ack 결과 결론 유지 | 통상 거절 통지 (기존 절차) | 심사관 |
| 거절 신청 + BIAS 리포트 발행 + ack 결과 결론 변경 → 승인 전환 | 별도 통지 양식 (현재 미정) → **법무·CS 합의 필요** | 운영자 + CS |
| 승인 신청 + CRITICAL 리포트 + ack 결과 결론 유지 | 통상 승인 통지 | 심사관 |
| 승인 신청 + CRITICAL 리포트 + ack 결과 결론 변경 → 거절 전환 | 거절 통지 (사유: 사후 검토) → **법무 검토 후 발송** | 운영자 + CS |

**원칙**: 어드바이저리 리포트 자체는 신청인에게 노출하지 않는다 (운영/감사 도구).

---

## 5. 관측 이상 대응

### 5.1 `advisory_open_reports` gauge 가 0 으로 고정
- 원인: gauge supplier 가 `reportRepo.countOpenBySeverity()` 호출. DB 연결 끊김 또는 트랜잭션 외 호출 시 0 반환 가능
- 조치: loan-service 재시작 → gauge 재등록

### 5.2 histogram bucket 분포 이상
- `advisory_evaluate_duration_seconds` 의 p99 가 1s 초과 → 룰 lookup 쿼리 부하 검토
- BATCH 모드 evaluate 가 분 단위 → snapshot row 가 누적되면 정상. 임계는 §2.1 표본 임계 상향과 연결

---

## 6. 권한·로그·감사

- 룰 변경: `STATUS_HISTORY` (BEFORE/AFTER 스냅샷 + 변경 사유 코드 + 비고)
- ack: `REVIEW_ADVISORY_ACK` append-only — 같은 리포트 다중 ack 시 모두 적재
- 검색: Phase 6 RAG 도입 시 `ADVISORY_RETRIEVAL_LOG` 추가 예정
- 운영자 액션 모두 `created_by` 에 actor id 기록

---

## 7. 에스컬레이션

| 심각도 | 1차 대응 | 에스컬레이션 |
|---|---|---|
| CRITICAL 게이트 폭증 (>10/h) | 운영자 룰 비활성 + 사고 등록 | 도메인 리드 즉시 |
| 일배치 실패 (3일 연속) | 백업 데이터 보강 | 운영팀장 |
| 룰 임계치 잘못 설정 (false positive 50%↑) | §1.2/§1.3 | 도메인 리드 + 법무 (통지 영향 시) |
| pgvector / RAG 검색 장애 (Phase 6 이후) | retriever skip → 룰 평가 유지 | 인프라팀 |

---

## 8. 관련 문서

- 도입 계획: [docs/plan/loan_review_advisory.md](../plan/loan_review_advisory.md)
- 진척도: [docs/plan/loan_review_advisory_progress.md](../plan/loan_review_advisory_progress.md)
- API: [docs/api/advisory.md](../api/advisory.md)
- ERD: [docs/loan_erd.md](../loan_erd.md#stage-25-심사관-어드바이저리-review_advisory_)
- Grafana 대시보드: `infra/grafana/provisioning/dashboards/advisory.json`
- Prometheus 지표 정의: `services/loan-service/src/main/java/com/bank/loan/advisory/observability/AdvisoryMetrics.java`

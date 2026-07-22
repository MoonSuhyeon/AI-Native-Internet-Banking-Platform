# 🔌 어드바이저리 외부 API

> **상태**: Phase 4 완료 시 운영 가능
> **연관 문서**: [loan_review_advisory.md](../plan/loan_review_advisory.md), [loan_erd.md](../loan_erd.md#stage-25-심사관-어드바이저리-review_advisory_)
>
> Swagger UI : `http://<host>:8083/swagger-ui.html` (`springdoc` 자동 등록 — 본 문서는 *권한·에러·운영 가이드* 정리)

---

## 1. 권한 매트릭스

권한은 임시로 **`X-Actor-Role`** 헤더(`REVIEWER` / `AUDITOR` / `ADMIN`)로 전달한다. Spring Security 도입 시 SecurityContext 의 role 로 교체 예정.

| Method | Path | REVIEWER | AUDITOR | ADMIN |
|---|---|:---:|:---:|:---:|
| `GET` | `/api/advisory/reports` | ✅ (본인 대상만) | ✅ | ✅ |
| `GET` | `/api/advisory/reports/{advrId}` | ✅ (본인 대상만 / 타인=404) | ✅ | ✅ |
| `POST` | `/api/advisory/reports/{advrId}/view` | ✅ (본인 대상만) | ✅ | ✅ |
| `POST` | `/api/advisory/reports/{advrId}/ack` | ✅ (본인 대상만) | ✅ | ✅ |
| `GET` | `/api/advisory/rules` | ❌ 403 | ✅ | ✅ |
| `PUT` | `/api/advisory/rules/{ruleId}` | ❌ 403 | ❌ 403 | ✅ |
| `GET` | `/api/advisory/stats/reviewers/{reviewerId}` | ❌ 403 | ✅ | ✅ |
| `POST` | `/api/internal/advisory/snapshot?baseDate=YYYYMMDD` | (internal) | (internal) | ✅ |
| `POST` | `/api/internal/advisory/batch-evaluate?baseDate=YYYYMMDD` | (internal) | (internal) | ✅ |

> REVIEWER 의 *본인 대상* 판단은 리포트의 `target_reviewer_id == actorId` 매칭. 타인 리포트 접근은 *존재 자체를 숨기기 위해* `404 LOAN_190` 반환.
> internal 엔드포인트는 외부 노출 차단(network gateway) 가정 + ADMIN 만 호출.

---

## 2. 엔드포인트 요약

### 2.1 리포트 조회·조작

#### `GET /api/advisory/reports`
- 쿼리: `targetReviewerId`, `revId`, `advisoryTypeCd`, `severityCd`, `advrStatusCd` (전부 옵셔널, AND 결합)
- REVIEWER : `targetReviewerId` 는 actorId 강제. AUDITOR/ADMIN : 필터 자유.
- 응답: `AdvisoryReportListResponse` (`totalCount`, `items[]`)

#### `GET /api/advisory/reports/{advrId}`
- 응답: `AdvisoryReportDetailResponse` — 본문 + `signals[]` + `acks[]` 이력
- 미존재 / REVIEWER 가 타인 리포트 시도 → `404 LOAN_190`

#### `POST /api/advisory/reports/{advrId}/view`
- 상태 `OPEN → VIEWED` 전이, `first_viewed_at` 최초 1회만 적재
- 응답: `AdvisoryReportSummaryResponse`

#### `POST /api/advisory/reports/{advrId}/ack`
- 요청 본문 — `ackResponseCd`(필수), `decisionChangeYn`("Y"|"N"), `ackReasonCd`, `ackRemark`, `beforeDecisionCd`, `afterDecisionCd`
- 동작 — `REVIEW_ADVISORY_ACK` row append + 리포트 `OPEN/VIEWED → ACKED` 전이
- 응답: 201 + `AdvisoryAckResponse` (`advkId`, `ackedAt`, ...)
- RESOLVED 리포트 ack 시도 → `409 LOAN_191`

### 2.2 룰 카탈로그

#### `GET /api/advisory/rules`
- AUDITOR/ADMIN 만. Soft Delete 되지 않은 전체 룰 (활성·비활성 모두 노출)
- 응답: `AdvisoryRuleListResponse`

#### `PUT /api/advisory/rules/{ruleId}`
- ADMIN 만. 활성 토글 + 임계치(rule_params) + 버전 + 효력기간 + 설명 변경 가능
- null 필드는 미변경. 변경 시 `STATUS_HISTORY` 에 BEFORE/AFTER 스냅샷 적재
- 요청 본문 — `activeYn`("Y"|"N"), `ruleParams`(JSON), `ruleVersion`, `effectiveStartDate/End`(YYYYMMDD), `ruleDesc`, `changeReasonCd`, `changeRemark`

### 2.3 운영 통계

#### `GET /api/advisory/stats/reviewers/{reviewerId}`
- AUDITOR/ADMIN. 응답: `ReviewerAckStatsResponse`
- `totalReports`, `unresolvedCount`, `ackResponseCounts`(코드별 카운트), `ruleTriggerCounts`(룰코드별 빈도)

### 2.4 Internal 배치 트리거

#### `POST /api/internal/advisory/snapshot?baseDate=YYYYMMDD`
- 어제자(또는 지정 일자) `LOAN_REVIEW` 를 코호트(EMPLOYMENT_TYPE/LOAN_PURPOSE) × reviewer 로 집계 → `REVIEWER_DECISION_SNAPSHOT` 적재 (멱등)

#### `POST /api/internal/advisory/batch-evaluate?baseDate=YYYYMMDD`
- 스냅샷 적재 + 활성 BATCH 룰 전체 평가 + 발행 리포트별 `AdvisoryReportPublishedEvent` 통지

---

## 3. 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `LOAN_190` | 404 | 어드바이저리 리포트를 찾을 수 없음 (또는 REVIEWER 의 타인 리포트 접근) |
| `LOAN_191` | 409 | RESOLVED 리포트에 ack 시도 |
| `LOAN_192` | 409 | 미해결 CRITICAL 리포트로 약정 생성 차단 (ack 필요) |
| `COMMON_403` | 403 | 권한 부족 (`X-Actor-Role` role 미만족) |
| `COMMON_400` | 400 | 헤더/요청 본문 validation 실패 |

---

## 4. 활성 룰 카탈로그 (현재)

| `rule_cd` | severity | mode | 트리거 조건 |
|---|---|---|---|
| `DSR_THRESHOLD_OVERRIDE` | CRITICAL | SYNC | DSR FAIL × 본심사 APPROVED → 약정 게이트 차단 |
| `LTV_THRESHOLD_OVERRIDE` | CRITICAL | SYNC | LTV FAIL × 본심사 APPROVED → 약정 게이트 차단 |
| `BIAS_REJECT_RATE_DEVIATION` | WARN | BATCH | 코호트 거절율 동료 평균 +2σ 초과 (sample ≥ 30) |
| `BIAS_APPROVAL_RATE_DEVIATION` | WARN | BATCH | 코호트 승인율 동료 평균 -2σ 미만 (sample ≥ 30) |
| `PEER_DECISION_DIVERGENCE` | WARN | BATCH | 유사 신청자 90일 그룹 70:30 분기 + 본 건 소수 결정 |

---

## 5. 운영 시나리오

### 5.1 일배치 — 매일 새벽 운영자가 실행
```bash
curl -X POST "http://loan-service/api/internal/advisory/batch-evaluate?baseDate=$(date -d 'yesterday' +%Y%m%d)" \
     -H "X-Actor-Role: ADMIN"
```

### 5.2 심사관 — 자신에게 발행된 리포트 확인 후 ack
```bash
# 1) 미해결 리포트 목록
curl "http://loan-service/api/advisory/reports?advrStatusCd=OPEN" \
     -H "X-Actor-Role: REVIEWER"

# 2) 상세 + 근거 신호
curl "http://loan-service/api/advisory/reports/12345" \
     -H "X-Actor-Role: REVIEWER"

# 3) 조회 마킹
curl -X POST "http://loan-service/api/advisory/reports/12345/view" \
     -H "X-Actor-Role: REVIEWER"

# 4) ack 등록 (결론 유지)
curl -X POST "http://loan-service/api/advisory/reports/12345/ack" \
     -H "X-Actor-Role: REVIEWER" -H "Content-Type: application/json" \
     -d '{"ackResponseCd":"MAINTAIN","decisionChangeYn":"N","ackReasonCd":"REVIEWER_JUDGMENT"}'
```

### 5.3 감사관 — 심사관별 통계 확인
```bash
curl "http://loan-service/api/advisory/stats/reviewers/12345" \
     -H "X-Actor-Role: AUDITOR"
```

### 5.4 운영자 — 룰 임시 비활성화 (운영 사고 대응)
```bash
curl -X PUT "http://loan-service/api/advisory/rules/3" \
     -H "X-Actor-Role: ADMIN" -H "Content-Type: application/json" \
     -d '{"activeYn":"N","changeReasonCd":"INCIDENT","changeRemark":"INC-2026-0512 false positive 폭증 대응"}'
```
변경 후 `STATUS_HISTORY` 에 BEFORE/AFTER 스냅샷이 자동 적재되어 사후 감사 가능.

---

## 6. 알려진 한계 (Phase 4 시점)

- `X-Actor-Role` 헤더 기반 권한 — Spring Security 미도입. 운영 배포 전 SecurityContext 연동 필요.
- `findAll()` 기반 페이징 미적용 — 운영 데이터 누적 시 Pageable 도입 필요 (Phase 5 튜닝).
- ack 외부 API 의 actor 식별은 `CurrentActorProvider` (테스트=SYSTEM). 실제 SSO 연동은 미구현.

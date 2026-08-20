# 소규모 서비스 API 명세서 (통합)

auto-loan-review · doc-agent · review-ai-gateway 와, loan-service 안에 있는 advisory 도메인의 REST 엔드포인트 상세 명세를 한 문서로 묶었다. 컨트롤러·DTO 소스에서 추출해 정리한다.

> **`master-service` 는 이 레포에 존재한 적이 없다.** 예전 이 문서가 공통코드 서비스로
> 소개했지만 `CodeMasterController` 는 코드에 없다. 아래 그 자리에 무엇이 실제로
> 있는지 적어 뒀다.

> 대형 서비스는 개별 문서 참조: [loan](loan-service-api-spec.md) · [customer](customer-service-api-spec.md) · [deposit](core-banking-deposit-api-spec.md) · [payment](core-banking-payment-api-spec.md).
> 엔드포인트 전체 목록은 [api-spec.md](api-spec.md) 참조.

## 공통 사항

- 모든 서비스가 **API Gateway 의 JWT 검증을 신뢰**하고 자체 Spring Security 는 `anyRequest().permitAll()` 이거나 미설정이다(내부망/게이트웨이 전제).
- 응답 규약은 서비스마다 다르다 — 각 서비스 절 머리말 참고.
  - 공통 `ApiResponse` envelope: `{ "code": "OK", "message": "OK", "data": {} }`
  - envelope 미사용 서비스는 응답 DTO/`Map` 을 직접 반환.

---

## 공통코드 — 서비스가 아니다

예전 이 문서는 `master-service` 라는 공통코드 서비스와 `CodeMasterController` 의
엔드포인트 5개를 소개했다. **둘 다 코드에 없다.** 그 명세를 보고 붙이면 존재하지
않는 주소를 부르게 된다.

실제로 있는 것은 `code_master`·`cust_code_master` **테이블과 시드 데이터**뿐이다.
검증에 쓰이지 않는다 — `@ValidCode` 같은 애너테이션이 0건이고, 코드값의 정본은
각 도메인의 상수(`BankRole`, `ReviewAdvisoryReport.STATUS_*` 등)다.

공통코드를 실제로 쓰려면 서비스가 아니라 **검증 지점**부터 정해야 한다.
[`OPEN_ITEMS.md` §5](OPEN_ITEMS.md) 의 "마스터 코드 연동" 항목이 그 자리다.

---

## auto-loan-review (AI 자동심사)

대출 자동심사·임베딩 배치. AutoReviewController 는 공통 `ApiResponse` 사용, EmbeddingBatchController 는 응답 DTO 직접 반환. 자체 에러코드 `AiErrorCode`(AI_001~003). Security `permitAll`.

**엔드포인트 4개 / 컨트롤러 3개**

### 에러코드 (AiErrorCode)

| 코드 | HTTP | 설명 |
|---|---|---|
| `AI_001` | 503 | 추론 서버에 연결할 수 없습니다 |
| `AI_002` | 502 | 추론 요청이 실패했습니다 |
| `AI_003` | 503 | 자동심사 서비스가 일시 중단 상태입니다 |

### AutoReviewController

`base: /api/ai`

#### `POST` `/api/ai/auto-review`

**요청 본문**: `AutoReviewRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `revId` | `Long` |  |
| `age` | `Integer` | 필수, 최소값 |
| `maritalStatus` | `String` |  |
| `militaryStatus` | `String` |  |
| `familyType` | `String` |  |
| `housingType` | `String` |  |
| `educationLevel` | `String` |  |
| `bachelorsField` | `String` |  |
| `occupation` | `String` |  |
| `district` | `String` |  |
| `province` | `String` |  |
| `applicantSegment` | `String` |  |
| `annualIncomeKw` | `Long` |  |
| `totalAssetKw` | `Long` |  |
| `totalDebtKw` | `Long` |  |
| `collateralDebtKw` | `Long` |  |
| `creditDebtKw` | `Long` |  |
| `dsr` | `Double` |  |
| `ltv` | `Double` |  |
| `monthlyCashflowMeanKw` | `Long` |  |
| `monthlyCashflowStdKw` | `Long` |  |
| `delinquencyHistory24m` | `Integer` |  |
| `creditScoreProxy` | `Integer` |  |
| `requestedAmountKw` | `Long` |  |
| `requestedPeriodMo` | `Integer` |  |
| `purposeCd` | `String` |  |
| `purposeRedFlag` | `Boolean` |  |
| `regionRiskBand` | `Integer` |  |
| `nChildren` | `Integer` |  |
| `employmentYears` | `Integer` |  |
| `bureauHasRecord` | `Boolean` |  |
| `bureauNActive` | `Integer` |  |
| `bureauMaxStatus24m` | `Integer` |  |

**응답**: `AutoReviewResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `modelVersion` | `String` |  |
| `decision` | `String` |  |
| `score` | `double` |  |
| `proba` | `Map<String, Double>` |  |
| `pdScore` | `Double` |  |
| `pdModelVersion` | `String` |  |

#### `POST` `/api/ai/auto-review/evaluate`

**요청 본문**: `AutoReviewRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `revId` | `Long` |  |
| `age` | `Integer` | 필수, 최소값 |
| `maritalStatus` | `String` |  |
| `militaryStatus` | `String` |  |
| `familyType` | `String` |  |
| `housingType` | `String` |  |
| `educationLevel` | `String` |  |
| `bachelorsField` | `String` |  |
| `occupation` | `String` |  |
| `district` | `String` |  |
| `province` | `String` |  |
| `applicantSegment` | `String` |  |
| `annualIncomeKw` | `Long` |  |
| `totalAssetKw` | `Long` |  |
| `totalDebtKw` | `Long` |  |
| `collateralDebtKw` | `Long` |  |
| `creditDebtKw` | `Long` |  |
| `dsr` | `Double` |  |
| `ltv` | `Double` |  |
| `monthlyCashflowMeanKw` | `Long` |  |
| `monthlyCashflowStdKw` | `Long` |  |
| `delinquencyHistory24m` | `Integer` |  |
| `creditScoreProxy` | `Integer` |  |
| `requestedAmountKw` | `Long` |  |
| `requestedPeriodMo` | `Integer` |  |
| `purposeCd` | `String` |  |
| `purposeRedFlag` | `Boolean` |  |
| `regionRiskBand` | `Integer` |  |
| `nChildren` | `Integer` |  |
| `employmentYears` | `Integer` |  |
| `bureauHasRecord` | `Boolean` |  |
| `bureauNActive` | `Integer` |  |
| `bureauMaxStatus24m` | `Integer` |  |

**응답**: `AutoReviewEvaluateResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `modelVersion` | `String` |  |

### EmbeddingBatchController

`base: /api/internal/embeddings`

#### `POST` `/api/internal/embeddings/batch`

내부 임베딩 배치 적재 엔드포인트 — D3-1.

**요청 본문**: `EmbeddingBatchRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `items` | `List<ChunkBatchItem>` |  |

**응답**: `EmbeddingBatchResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `upserted` | `int` |  |

### HealthController

`base: (루트)`

#### `GET` `/health`

**응답**: `Map (동적 필드)`

---

## advisory — loan-service 안의 도메인 (심사 자문 RAG)

> **별도 서비스가 아니다.** `com.bank.loan.advisory.*` 패키지로 loan-service 안에
> 있다. 예전에는 `agents/advisory-service` 가 별도 서비스처럼 보였지만 **빌드에
> 포함된 적이 없었다** — settings.gradle·build.gradle·Dockerfile·compose 정의가
> 전부 없었고 메인 클래스도 없었다([결정 문서](decisions/core-banking-merge.md) 계열의
> 정리). 지금 `agents/advisory-service/src` 는 비어 있다.

본사 심사 자문 리포트·규칙·통계·RAG. 공통 `ApiResponse` envelope 사용. 인가는
게이트웨이가 주입한 `X-User-Role` 로 판정한다(`/api/advisory/**`·
`/api/internal/advisory/**` 둘 다 게이트웨이 라우트가 있다).

**엔드포인트 27개 / 컨트롤러 8개**

### AdvisoryRagController

`base: /api/advisory/reports`

#### `GET` `/api/advisory/reports/{advrId}/citations`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `advrId` | `Long` |

**응답**: `PolicyCitationResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advrId` | `Long` |  |
| `totalCount` | `int` |  |
| `citations` | `List<CitationItem>` |  |

#### `GET` `/api/advisory/reports/{advrId}/similar-cases`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `advrId` | `Long` |

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `topK` | `int` | - |

**응답**: `SimilarCaseResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advrId` | `Long` |  |
| `totalCount` | `int` |  |
| `cases` | `List<CaseItem>` |  |

### AdvisoryReportController

`base: /api/advisory/reports`

#### `GET` `/api/advisory/reports`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `targetReviewerId` | `Long` | - |
| `revId` | `Long` | - |
| `advisoryTypeCd` | `String` | - |
| `severityCd` | `String` | - |
| `advrStatusCd` | `String` | - |

**응답**: `AdvisoryReportListResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `totalCount` | `int` |  |
| `items` | `List<AdvisoryReportSummaryResponse>` |  |

#### `GET` `/api/advisory/reports/{advrId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `advrId` | `Long` |

**응답**: `AdvisoryReportDetailResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advrId` | `Long` |  |
| `revId` | `Long` |  |
| `ruleId` | `Long` |  |
| `advisoryTypeCd` | `String` |  |
| `severityCd` | `String` |  |
| `advrStatusCd` | `String` |  |
| `advrTitle` | `String` |  |
| `advrSummary` | `String` |  |
| `advrPayload` | `String` |  |
| `targetReviewerId` | `Long` |  |
| `generatedAt` | `OffsetDateTime` |  |
| `firstViewedAt` | `OffsetDateTime` |  |
| `resolvedAt` | `OffsetDateTime` |  |
| `signals` | `List<AdvisorySignalResponse>` |  |
| `acks` | `List<AdvisoryAckHistoryItem>` |  |

#### `POST` `/api/advisory/reports/{advrId}/ack`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `advrId` | `Long` |

**요청 본문**: `AdvisoryAckRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `ackResponseCd` | `String` | 필수(공백불가) |
| `decisionChangeYn` | `String` | 형식제약 |
| `ackReasonCd` | `String` |  |
| `ackRemark` | `String` |  |
| `beforeDecisionCd` | `String` |  |
| `afterDecisionCd` | `String` |  |

**응답**: `AdvisoryAckResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advkId` | `Long` |  |
| `advrId` | `Long` |  |
| `ackResponseCd` | `String` |  |
| `decisionChangeYn` | `String` |  |
| `ackedAt` | `OffsetDateTime` |  |
| `ackReviewerId` | `Long` |  |

#### `POST` `/api/advisory/reports/{advrId}/view`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `advrId` | `Long` |

**응답**: `AdvisoryReportSummaryResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advrId` | `Long` |  |
| `revId` | `Long` |  |
| `ruleId` | `Long` |  |
| `advisoryTypeCd` | `String` |  |
| `severityCd` | `String` |  |
| `advrStatusCd` | `String` |  |
| `advrTitle` | `String` |  |
| `targetReviewerId` | `Long` |  |
| `generatedAt` | `OffsetDateTime` |  |
| `firstViewedAt` | `OffsetDateTime` |  |
| `resolvedAt` | `OffsetDateTime` |  |

### AdvisoryRuleController

`base: /api/advisory/rules`

#### `GET` `/api/advisory/rules`

**응답**: `AdvisoryRuleListResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `totalCount` | `int` |  |
| `items` | `List<AdvisoryRuleResponse>` |  |

#### `PUT` `/api/advisory/rules/{ruleId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `ruleId` | `Long` |

**요청 본문**: `UpdateAdvisoryRuleRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `activeYn` | `String` | 형식제약 |
| `ruleParams` | `String` |  |
| `ruleVersion` | `String` |  |
| `effectiveStartDate` | `String` | 형식제약 |
| `effectiveEndDate` | `String` | 형식제약 |
| `ruleDesc` | `String` |  |
| `changeReasonCd` | `String` |  |
| `changeRemark` | `String` |  |

**응답**: `AdvisoryRuleResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `ruleId` | `Long` |  |
| `ruleCd` | `String` |  |
| `ruleName` | `String` |  |
| `advisoryTypeCd` | `String` |  |
| `ruleCategoryCd` | `String` |  |
| `severityCd` | `String` |  |
| `ruleParams` | `String` |  |
| `ruleVersion` | `String` |  |
| `activeYn` | `String` |  |
| `effectiveStartDate` | `String` |  |
| `effectiveEndDate` | `String` |  |
| `ruleDesc` | `String` |  |

### AdvisoryStatsController

`base: /api/advisory/stats`

#### `GET` `/api/advisory/stats/reviewers/{reviewerId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `reviewerId` | `Long` |

**응답**: `ReviewerAckStatsResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `reviewerId` | `Long` |  |
| `totalReports` | `long` |  |
| `unresolvedCount` | `long` |  |
| `ackResponseCounts` | `Map<String, Long>` |  |
| `ruleTriggerCounts` | `Map<String, Long>` |  |

### AuditOpinionController

`base: /api/advisory/audit`

#### `GET` `/api/advisory/audit/opinions/by-report/{advrId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `advrId` | `Long` |

**응답**: `List<AiAuditOpinionResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `opinionId` | `Long` |  |
| `advrId` | `Long` |  |
| `revId` | `Long` |  |
| `reviewerId` | `Long` |  |
| `analysisTypeCd` | `String` |  |
| `conclusionCd` | `String` |  |
| `reasoningSummary` | `String` |  |
| `confidenceScore` | `Double` |  |
| `inputTokens` | `Integer` |  |
| `outputTokens` | `Integer` |  |
| `generatedAt` | `OffsetDateTime` |  |

#### `GET` `/api/advisory/audit/opinions/by-reviewer/{reviewerId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `reviewerId` | `Long` |

**응답**: `List<AiAuditOpinionResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `opinionId` | `Long` |  |
| `advrId` | `Long` |  |
| `revId` | `Long` |  |
| `reviewerId` | `Long` |  |
| `analysisTypeCd` | `String` |  |
| `conclusionCd` | `String` |  |
| `reasoningSummary` | `String` |  |
| `confidenceScore` | `Double` |  |
| `inputTokens` | `Integer` |  |
| `outputTokens` | `Integer` |  |
| `generatedAt` | `OffsetDateTime` |  |

#### `GET` `/api/advisory/audit/opinions/recent`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `limit` | `int` | - |

**응답**: `List<AiAuditOpinionResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `opinionId` | `Long` |  |
| `advrId` | `Long` |  |
| `revId` | `Long` |  |
| `reviewerId` | `Long` |  |
| `analysisTypeCd` | `String` |  |
| `conclusionCd` | `String` |  |
| `reasoningSummary` | `String` |  |
| `confidenceScore` | `Double` |  |
| `inputTokens` | `Integer` |  |
| `outputTokens` | `Integer` |  |
| `generatedAt` | `OffsetDateTime` |  |

#### `GET` `/api/advisory/audit/quarantine`

**응답**: `List<QuarantineReportResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advrId` | `Long` |  |
| `revId` | `Long` |  |
| `targetReviewerId` | `Long` |  |
| `advisoryTypeCd` | `String` |  |
| `severityCd` | `String` |  |
| `advrTitle` | `String` |  |
| `quarantinedAt` | `OffsetDateTime` |  |
| `generatedAt` | `OffsetDateTime` |  |

#### `GET` `/api/advisory/audit/risk-scores/top/bias`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `limit` | `int` | - |

**응답**: `List<ReviewerRiskScoreResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `reviewerId` | `Long` |  |
| `biasScore` | `double` |  |
| `complianceScore` | `double` |  |
| `evaluationCount` | `int` |  |
| `lastEvaluatedAt` | `OffsetDateTime` |  |

#### `GET` `/api/advisory/audit/risk-scores/top/compliance`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `limit` | `int` | - |

**응답**: `List<ReviewerRiskScoreResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `reviewerId` | `Long` |  |
| `biasScore` | `double` |  |
| `complianceScore` | `double` |  |
| `evaluationCount` | `int` |  |
| `lastEvaluatedAt` | `OffsetDateTime` |  |

#### `GET` `/api/advisory/audit/risk-scores/{reviewerId}`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `reviewerId` | `Long` |

**응답**: `ReviewerRiskScoreResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `reviewerId` | `Long` |  |
| `biasScore` | `double` |  |
| `complianceScore` | `double` |  |
| `evaluationCount` | `int` |  |
| `lastEvaluatedAt` | `OffsetDateTime` |  |

### InternalAdvisoryBatchController

`base: /api/internal/advisory`

#### `POST` `/api/internal/advisory/batch-evaluate`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `baseDate` | `String` | O |

**응답**: `BatchEvaluationResult`

| 필드 | 타입 | 제약 |
|---|---|---|
| `baseDate` | `String` |  |
| `snapshot` | `SnapshotRunResult` |  |
| `reportsPublished` | `int` |  |

#### `POST` `/api/internal/advisory/snapshot`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `baseDate` | `String` | O |

**응답**: `SnapshotRunResult`

| 필드 | 타입 | 제약 |
|---|---|---|
| `baseDate` | `String` |  |
| `reviewCount` | `int` |  |
| `inserted` | `int` |  |
| `skipped` | `int` |  |

### InternalAdvisoryRagController

`base: /api/internal/advisory`

#### `GET` `/api/internal/advisory/documents`

**응답**: `List<DocumentSummaryResponse>`

| 필드 | 타입 | 제약 |
|---|---|---|
| `docId` | `Long` |  |
| `docCd` | `String` |  |
| `docTitle` | `String` |  |
| `docCategoryCd` | `String` |  |
| `docVersion` | `String` |  |
| `activeYn` | `String` |  |
| `createdAt` | `OffsetDateTime` |  |

#### `POST` `/api/internal/advisory/documents`

**요청 본문**: `DocumentRegisterRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `docCd` | `String` | 필수(공백불가), 길이제한 |
| `docTitle` | `String` | 필수(공백불가), 길이제한 |
| `docCategoryCd` | `String` | 필수(공백불가), 길이제한 |
| `docVersion` | `String` | 필수(공백불가), 길이제한 |
| `effectiveStartDate` | `String` | 형식제약 |
| `effectiveEndDate` | `String` | 형식제약 |
| `sourceUri` | `String` | 길이제한 |
| `docDesc` | `String` | 길이제한 |

**응답**: `DocumentRegisterResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `docId` | `Long` |  |
| `docCd` | `String` |  |
| `docTitle` | `String` |  |
| `docVersion` | `String` |  |
| `activeYn` | `String` |  |
| `chunkCount` | `int` |  |

#### `PUT` `/api/internal/advisory/documents/{docId}/activate`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `docId` | `Long` |

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `active` | `boolean` | - |

**응답**: `Void`

#### `POST` `/api/internal/advisory/index/cases`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `revId` | `Long` | - |
| `overturnYn` | `String` | - |

**응답**: `IndexCasesResult`

| 필드 | 타입 | 제약 |
|---|---|---|
| `indexedCount` | `int` |  |
| `lastCaseIdxId` | `Long` |  |

#### `POST` `/api/internal/advisory/rag/case-index/backfill`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `from` | `String` | - |
| `to` | `String` | - |
| `dryRun` | `boolean` | - |

**응답**: `BackfillResultResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `processed` | `int` |  |
| `skipped` | `int` |  |
| `failed` | `int` |  |
| `dryRun` | `boolean` |  |

### InternalAdvisoryToolController

`base: /api/internal/advisory`

#### `GET` `/api/internal/advisory/cohort-stats`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `dimension` | `String` | O |
| `value` | `String` | O |

**응답**: `CohortStatsResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `dimension` | `String` |  |
| `value` | `String` |  |
| `latestSnapshotDate` | `String` |  |
| `reviewerCount` | `int` |  |
| `totalReviews` | `int` |  |
| `totalApproved` | `int` |  |
| `totalRejected` | `int` |  |
| `avgApproveRateBps` | `double` |  |
| `avgRejectRateBps` | `double` |  |

#### `GET` `/api/internal/advisory/policy-citations`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `query` | `String` | O |

**응답**: `PolicyCitationResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advrId` | `Long` |  |
| `totalCount` | `int` |  |
| `citations` | `List<CitationItem>` |  |

#### `GET` `/api/internal/advisory/reviewer-history`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `reviewerId` | `Long` | O |
| `days` | `int` | - |

**응답**: `ReviewerHistoryResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `reviewerId` | `Long` |  |
| `days` | `int` |  |
| `totalCount` | `int` |  |
| `approvedCount` | `int` |  |
| `rejectedCount` | `int` |  |
| `approvalRate` | `double` |  |

#### `GET` `/api/internal/advisory/similar-cases`

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `revId` | `Long` | O |
| `topK` | `int` | - |

**응답**: `SimilarCaseResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `advrId` | `Long` |  |
| `totalCount` | `int` |  |
| `cases` | `List<CaseItem>` |  |

---

## doc-agent (서류 제출·검토)

서류 제출(멀티파트)·추출·휴먼리뷰·법적보존. envelope 없이 `Map`/응답 DTO 직접 반환. Security `permitAll`. 식별자는 `UUID`.

**엔드포인트 6개 / 컨트롤러 4개**

### DocumentSubmissionController

`base: /api/documents`

#### `POST` `/api/documents/submit`

> `multipart/form-data` 업로드

**Query/Form 파라미터**

| 이름 | 타입 | 필수 |
|---|---|---|
| `applicationId` | `String` | O |
| `docCode` | `String` | O |
| `productId` | `String` | - |
| `file` | `MultipartFile` | O |

**응답**: `ExtractionResult`

| 필드 | 타입 | 제약 |
|---|---|---|
| `schemaVersion` | `String` |  |
| `submissionId` | `UUID` |  |
| `applicationId` | `String` |  |
| `docCode` | `String` |  |
| `docType` | `String` |  |
| `verifyStatus` | `VerifyStatus` |  |
| `documentVerification` | `VerificationBlock` |  |
| `extractedData` | `StructuredData` |  |
| `ocrRegions` | `List<OcrRegion>` |  |
| `maskedText` | `String` |  |
| `pipelineStage` | `String` |  |

### HealthController

`base: (루트)`

#### `GET` `/health`

**응답**: `Map (동적 필드)`

### HumanReviewController

`base: /api/documents`

#### `GET` `/api/documents/queue`

CLEARED | CONFIRMED_FORGERY

**응답**: `List<Map<String, Object>>`

#### `POST` `/api/documents/{submissionId}/review`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `submissionId` | `UUID` |

**요청 본문**: `ReviewRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `decision` | `HumanReviewStatus` |  |

**응답**: `Map (동적 필드)`

### LegalHoldController

`base: /api/documents`

#### `PATCH` `/api/documents/{submissionId}/legal-hold/disable`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `submissionId` | `UUID` |

**응답**: `Map (동적 필드)`

#### `PATCH` `/api/documents/{submissionId}/legal-hold/enable`

**Path 파라미터**

| 이름 | 타입 |
|---|---|
| `submissionId` | `UUID` |

**응답**: `Map (동적 필드)`

---

## review-ai-gateway (심사 AI 게이트웨이)

감사 분석 AI 게이트웨이. envelope 없이 응답 DTO 직접 반환. 내부 전용 경로(`/internal/**`).

**엔드포인트 2개 / 컨트롤러 2개**

### AuditAnalysisController

`base: /internal/audit`

#### `POST` `/internal/audit/analyze`

**요청 본문**: `AuditAnalysisRequest`

| 필드 | 타입 | 제약 |
|---|---|---|
| `analysisType` | `String` | 필수(공백불가) |
| `reviewerId` | `Long` | 필수 |
| `reviewOpinionText` | `String` |  |
| `signals` | `List<SignalSummary>` |  |
| `ragChunks` | `List<RagChunk>` |  |

**응답**: `AuditAnalysisResponse`

| 필드 | 타입 | 제약 |
|---|---|---|
| `analysisType` | `String` |  |
| `conclusion` | `String` |  |
| `reasoningSummary` | `String` |  |
| `confidenceScore` | `double` |  |
| `inputTokens` | `int` |  |
| `outputTokens` | `int` |  |
| `citedChunkIds` | `List<Long>` |  |

### HealthController

`base: /internal`

#### `GET` `/internal/ping`

**응답**: `Map (동적 필드)`

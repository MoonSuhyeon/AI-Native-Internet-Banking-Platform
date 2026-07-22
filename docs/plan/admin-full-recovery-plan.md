# admin/user 화면 전면 복구 계획

작성일: 2026-06-08
선행 문서: `plan/admin-full-connection-roadmap.md`(Phase 0~2 연결 로드맵)

## Context (왜 이 작업을 하는가)

`plan/admin-full-connection-roadmap.md`의 Phase 0~2 화면들이 실제로는 대부분 동작하지 않는 상태였다.
사용자 신고: 계약 모니터링/심사 상세 필드 누락/자동심사 시뮬레이터/RAG·자문규칙·감사·검토큐 전부 실패.

직접 조사로 밝혀진 **근본 원인은 "백엔드 부재"가 아니라 대부분 "설정·연결·데이터" 문제**였다:

- **advisory(자문규칙·감사·RAG)는 이미 loan-service(8083)에 흡수되어 동작 중**이다.
  `services/loan-service/build.gradle`의 `sourceSets`가 `../advisory-service/src`를 포함해 같은 JVM에서
  컴파일·실행된다. `/api/advisory/rules`는 **404가 아닌 403**(엔드포인트 살아있음, 권한만 막힘).
  → 별도 서비스 빌드·배선 불필요. (사용자 결정: "loan-service에 흡수" = 이미 그렇게 되어 있음)
- **프론트가 죽은/엉뚱한 포트를 호출** 중: advisory→8084(payment), RAG(ai-api)→8086(빈자리).
  실제 백엔드는 둘 다 8083(loan).
- **ai-service는 폐기 확정**(커밋 f8db8217, auto-loan-review로 대체). RAG 문서관리 화면은
  advisory의 RAG API(8083)로 연결해야 하나, `ai-api.ts`는 옛 ai-service 경로라 **경로 재작성 필요**.
- **ES는 docker-compose에 정의돼 있으나 `start.ps1` infra 목록에서 빠져** 안 떠서
  auto-loan-review(8089, 자동심사 시뮬레이터)가 DOWN.
- 신용정보보고서·알림 등은 **데이터(시드) 부재**.

### 사용자 질문 직접 답변
- **start.bat(start.ps1)에 loan-service 있나?** → ✅ 있음(line 88). 단 새 계약 모니터링 API 반영하려면 재시작 필요.
- **ai-service 있나?** → ❌ 없음(폐기됨). 되살리지 않는다.
- **advisory-service 있나?** → ❌ 독립 실행 항목으로는 없음. 하지만 loan-service에 소스셋으로 흡수되어 8083에서 이미 동작.
- **ES 실행법?** → docker-compose에 `elasticsearch`(8.15.0, :9200) 정의됨. `start.ps1`의 `$infra` 배열에
  `"elasticsearch"`만 추가하면 자동 기동. 수동: `docker compose up -d elasticsearch`.

---

## 작업 단계 (메모리 규칙: 한 단계씩 커밋 → 보고 → 멈춤. feat/test 분리. 커밋 한 줄.)

### 이미 완료 (커밋 대기)
- 심사 상세 필드명 불일치 수정: `web/app/(admin)/admin/loan/review/[applId]/page.tsx`
  - `estimatedLimit→estimatedLimitAmt`, `creditScore→cevalScore`, `creditGrade→cevalGrade`,
    `pdValue(×100)→pdBps(/100)`, `ratioBps→dsrRatioBps`, `limitBps→dsrLimitBps`
  - DB(loan_db)에는 데이터 존재 확인됨(applId 9003). 단순 필드명 오타였음.

### Phase A — 즉시 복구 (백엔드 이미 동작, 설정/프론트만)

1. **계약 모니터링 복구**: loan-service 재시작(구버전 실행 중이라 `/api/admin/loan-contracts` 미반영).
   - 검증: `curl -H "X-User-Id:1" -H "X-User-Role:ROLE_OPS" http://localhost:8083/api/admin/loan-contracts`

2. **advisory 화면 연결**(자문규칙·감사·통계·리포트):
   - `web/.env.local`: `NEXT_PUBLIC_ADVISORY_API_URL` 8084→**8083**
   - `web/lib/advisory-api.ts:4` 기본값 `http://localhost:8084`→**8083**
   - advisory-api.ts 경로는 백엔드와 **이미 100% 일치**(`/api/advisory/rules`, `/audit/opinions/recent`,
     `/audit/risk-scores/top/bias`, `/audit/quarantine`, `/stats/reviewers/{id}` 전부 확인). 경로 수정 불요.

3. **advisory 403 권한 해결**: `/api/advisory/*`가 헤더 있어도 403.
   - 점검: `services/loan-service/src/main/java/com/bank/loan/security/GatewayHeaderAuthFilter.java`
     (X-User-Role 파싱 형식)와 `config/SecurityConfig.java`(현재 advisory 규칙 없음 → anyRequest authenticated).
   - 조치: SecurityConfig에 `/api/advisory/**` 역할 규칙 추가(자문/감사 = HQ 계열 role), 프론트 인증 헤더 경로 확인.

### Phase B — RAG 문서관리(`admin/ai/rag-documents`) 연결 (경로 재작성)

4. `web/lib/ai-api.ts` 재작성: 폐기된 ai-service 경로(`/internal/rag/documents`, `/rag/search`)를
   advisory RAG 경로로 교체.
   - 실제 백엔드: `InternalAdvisoryRagController`(`/api/internal/advisory` + `/documents`,
     `/documents/{docId}/activate`, `/index/cases`, `/rag/case-index/backfill`),
     `AdvisoryRagController`(리포트/similar-cases/citations).
   - `NEXT_PUBLIC_AI_API_URL` 8086→**8083**. 응답 타입(RagDocument 등) advisory DTO에 맞춰 정렬.
   - ⚠️ 경로/DTO 매핑은 백엔드 컨트롤러 시그니처 재확인 후 1:1 정렬 필요(가장 손이 가는 단계).

### Phase C — 인프라 / 데이터

5. **ES 기동**: `start.ps1` line 53-59 `$infra` 배열에 `"elasticsearch"` 추가.
   - 효과: auto-loan-review(8089) health UP → 자동심사 시뮬레이터(`admin/loan/auto-review-sim`) 복구.
   - 검증: `docker compose up -d elasticsearch` 후 `curl http://localhost:8089/actuator/health`.

6. **시드 데이터** (사용자: "전체적인 데이터 필요"). loan_db/관련 DB에 더미 삽입:
   - 신용정보보고서(`credit_info_report`), 알림 발송함(`notification_outbox`), 계약 추가 샘플,
     advisory rules(`review_advisory_rule`), advisory 리포트/감사의견, RAG 문서/임베딩(pgvector).
   - 방식: `services/loan-service/src/main/resources/db/migration` 또는 별도 seed 스크립트
     (`services/loan-service/.../rag/seed/` 패턴 기존재 — `SyntheticCaseSeedLoader` 참고).
   - 날짜 격리 규칙(메모리) 준수.

7. **doc-agent 검토큐 500 디버깅**: `admin/ai/doc-review` → doc-agent(8087) 내부 500.
   - `GET /api/documents?status=PENDING_REVIEW` 500 원인(로그 확인). doc-agent는 가동 중.

### Phase D — 잔여

8. **심사 확정 실패**: `admin/loan/review/[applId]` "심사 확정"(`POST /review/confirm`) 실패.
   - review 9003은 PENDING_APPROVAL. confirm 자체 로직은 정상. 403(권한) 또는
     bias-check(Kafka outbox) 경로 확인. Phase A-3 권한 해결과 연동 가능.

---

## 손대지 않는 것
- **ai-service 부활 금지**(폐기 확정). RAG는 advisory(8083)로만 연결.
- advisory를 독립 서비스로 분리/빌드하지 않음(이미 loan-service 소스셋 흡수 상태 유지).

## 검증 (end-to-end)
- 각 단계 후 해당 admin 화면을 `http://localhost:3001/admin/...`에서 직접 확인.
- 백엔드 응답: `curl`로 8083 advisory/계약 엔드포인트 상태코드 확인(403→200 전환).
- 빌드: `./gradlew :services:loan-service:compileJava`, 프론트 `npm run build`(신규 페이지 한정 에러 확인).
- 검증용 임시 서버는 종료 시 정리(메모리 규칙).

## 권장 진행 순서
A1(재시작·계약) → A2/A3(advisory 연결·권한) → C5(ES) → D8(심사확정) → B4(RAG 경로) → C6(시드) → C7(doc-agent).
설정·연결로 즉시 복구되는 A·C5부터, 손 많이 가는 B4·C6는 뒤로.

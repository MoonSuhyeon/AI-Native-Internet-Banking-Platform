# 관리자 콘솔 워크플로우 점검 — 타 도메인 이슈 공유

점검 일자: 2026-06-07 · 점검 환경: 로컬(문수현 PC)
요약: **고객/관리자/컴플라이언스 화면은 정상**. **대출·상담 화면은 현재 데이터 로드 실패** (각 도메인 서비스 미기동 + 라우팅/포트 불일치).

---

## 1) 대출(loan) — 담당: 양혜민

### 증상
대출 어드민 화면(본심사 목록·EOD·대출 감사로그·긴급접근 등) 데이터 로드 실패.

### 원인
1. **loan-service(8083) 미기동** — 8083 리스닝 없음(연결 거부).
2. **게이트웨이 라우트 불일치** — 프론트 `web/lib/loan-api.ts`는 아래 경로를 호출하는데,
   게이트웨이엔 `Path=/api/v1/loans/**` 단일 라우트뿐이라 매칭되지 않음 → loan-service가 떠 있어도 404.
   - 프론트 호출 경로(예): `/api/loan-products`, `/api/loan-applications`, `/api/loan-contracts`,
     `/api/loan-reviews`, `/api/collaterals`, `/api/credit-score`, `/api/business-calendar`,
     `/api/status-history`, `/api/notifications`, `/api/audit`, `/api/break-glass`,
     `/api/internal/loan-reviews/*`, `/api/internal/eod/*`

### 수정 포인트
- [ ] loan-service(8083) 기동
- [ ] 게이트웨이에 loan-service 실제 경로 → 8083 라우트 추가 (또는 `/api/v1/loans/**` 라우트를 실제 경로로 교체)

### 게이트웨이 라우트 패치 초안 (services/api-gateway/src/main/resources/application.yml)
> ⚠️ loan-service 실제 컨트롤러 매핑 기준으로 경로 목록 확정 필요(아래는 loan-api.ts·loan SecurityConfig 기반 추정).
```yaml
        # 대출 도메인 (JWT 필요) — loan-service 실제 경로로 라우팅
        - id: loan
          uri: http://${LOAN_SERVICE_HOST:localhost}:${LOAN_SERVICE_PORT:8083}
          predicates:
            - Path=/api/loan-products/**,/api/loan-applications/**,/api/loan-contracts/**,/api/loan-reviews/**,/api/collaterals/**,/api/credit-score/**,/api/business-calendar/**,/api/status-history/**,/api/notifications/**,/api/audit/**,/api/break-glass/**,/api/internal/loan-reviews/**,/api/internal/eod/**
```
- 참고: 기존 `/api/v1/loans/**` 가 실제 사용처가 있으면 함께 유지. 없으면 위로 대체.
- 공유 인프라(게이트웨이)라 머지 전 협의 필요.

---

## 2) 상담(consultation) — 담당: 상담/챗봇 팀

### 증상
상담 고객조회 화면(`/admin/consultation/customer`) 데이터 로드 실패.

### 구조 (대출과 다름: 게이트웨이 미경유, 직접 호출)
- 프론트 `web/lib/consultation-api.ts` 베이스 = `NEXT_PUBLIC_CONSULTATION_API_URL` → `web/.env.local` 에 `http://localhost:8090`
- consultation-service = Python(FastAPI), `services/consultation-service/start-8087.bat` → uvicorn **8087**

### 원인
1. **프론트 포트(8090) ↔ 서비스 포트(8087) 불일치** — 프론트는 8090으로 `POST /chatbot/features/{code}/execute` 호출하나 8090은 해당 경로에 **404**.
2. **consultation-service(8087) 미기동**.

### 수정 포인트 (택1로 포트 정합)
- [ ] consultation-service 기동
- [ ] 포트 통일: 아래 중 하나
  - (a) 서비스를 8090으로 기동하고 프론트 8090 유지, 또는
  - (b) `web/.env.local` 의 `NEXT_PUBLIC_CONSULTATION_API_URL` 을 실제 서비스 포트(8087 등)로 수정
- [ ] 8090이 다른 컨테이너(도커 published)면 그게 무엇인지 확인 — 현재 chatbot feature 경로에 404 응답

---

## 정상 확인된 영역 (참고)
- 고객/관리자/컴플라이언스 internal API 전부 200 (로그인→대시보드→고객조회/상세→감사로그→제재/EDD/중복/대리인/미성년/FATCA→회원/가입통계), audit01(COMPLIANCE)·staff01(TELLER) 검증 완료.
- 본 점검 중 customer-service 측 수정/복구는 별도 커밋 반영됨(비번 해시·403 화이트리스트·게이트웨이 internal 라우트·cryptoService 빈 충돌·감사로그 null keyword).

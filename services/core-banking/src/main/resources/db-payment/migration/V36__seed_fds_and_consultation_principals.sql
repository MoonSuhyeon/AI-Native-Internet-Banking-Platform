-- =============================================================
-- V36__seed_fds_and_consultation_principals.sql
-- FDS·상담 서비스 신원 등록 — 내부 API 인증 전환 절차 2단계
--
-- 근거: docs/decisions/transaction-initiator-auth-model.md §7
--
--   1  발판    신원을 세우되 거절하지 않는다. 미인증 호출을 감사에 남긴다  ← 완료
--   2  배선    확인된 호출자에게 신원과 권한을 주고 자격증명을 보내게 한다  ← 여기
--   3  강제    미인증 호출이 0 이 된 것을 확인한 뒤 거절로 전환한다
--   4  방지    인가 없는 internal 컨트롤러를 잡는 테스트                  ← 완료
--
-- 호출자는 코드에서 확인했다. 짐작하지 않는다 — 승인 게이트를 켤 때 경로 목록을
-- 사람이 주석에 적었다가 여신 넷을 빠뜨린 일이 있었다.
--
--   fds-detector          PaymentDetailClient → GET /v1/internal/payments/{id}
--   agents/consultation   core_banking_client → GET /v1/internal/banking/**
--
-- 개발용 자격증명 원문에 dev-secret 표식을 넣는다. DevSecretGuard 가 운영 프로파일
-- 에서 이 표식을 보고 기동을 거부한다.
-- =============================================================


-- ── 신원 ─────────────────────────────────────────────────────────────────────

INSERT INTO service_principal (
    service_id, display_name, credential_hash, status, created_by, updated_by
) VALUES
    ('FDS_DETECTOR', '이상거래 탐지',
     '81aac0a1e38e749a19c905ea04f35dd73ccba5e2cb0f95ee495d66e4666b928e',
     'ACTIVE', 'MIGRATION_V36', 'MIGRATION_V36'),
    ('CONSULTATION_SERVICE', 'AI 상담',
     '2b32c80d9d70df0fd23ef790e023d832580f860b1ff75cab4cf1482256997492',
     'ACTIVE', 'MIGRATION_V36', 'MIGRATION_V36');


-- ── 작업 권한 ────────────────────────────────────────────────────────────────
--
-- 자금을 움직이지 않으므로 금액 한도와 계좌 정책은 두지 않는다. 걸 대상이 없다.
--
-- FDS 에 결제 상세 조회만 준다. 탐지기는 판정을 위해 읽을 뿐 거래를 만들지 않는다 —
-- 읽기 권한만 주면 탐지기가 뚫려도 자금이 움직이지 않는다.
--
-- 상담에는 신원만 세우고 작업 권한을 따로 두지 않는다. 그 경로의 인가는 A2 가 세운
-- ResourceAccessGuard 가 행위자 기준으로 이미 하고 있다 — 같은 판단을 두 곳에서
-- 하면 어느 쪽이 정본인지 알 수 없게 된다. 여기서 필요한 것은 "호출한 서비스가
-- 누구인가" 이지 "무엇을 할 수 있는가" 가 아니다.

INSERT INTO service_permission (
    service_id, operation, max_amount, status, granted_by
) VALUES
    ('FDS_DETECTOR', 'PAYMENT_DETAIL_READ', NULL, 'ACTIVE', 'MIGRATION_V36');


-- ── 부여 감사 [1] ────────────────────────────────────────────────────────────

INSERT INTO service_permission_history (
    service_id, operation, action, before_state, after_state, reason, actor
) VALUES
    ('FDS_DETECTOR', 'PAYMENT_DETAIL_READ', 'GRANT', NULL,
     'max_amount=NULL(자금이동 없음), accounts=[]',
     '내부 API 인증 전환 2단계 — 탐지기 결제상세 조회', 'MIGRATION_V36');

-- 영업점과 지점 상담 예약.
--
-- "지점 상담 예약서비스" 화면은 통째로 흉내였다. 지점검색 버튼은 핸들러가 없었고,
-- "상담 예약" 버튼은 alert 만 띄우고 아무것도 저장하지 않았다. 고객은 예약됐다고
-- 믿고 지점에 갔을 것이다 — 화면이 "카카오톡 또는 문자로 안내드리겠습니다" 라고
-- 약속까지 했다.
--
-- **왜 department 로는 안 되는가.** 코어뱅킹의 department 는 내부 조직 단위다
-- (PRODUCT·SALES·OPERATION·RISK·IT). 고객이 찾아가는 영업점은 주소·전화·영업시간을
-- 가진 다른 개념이라, 그 표에 얹으면 조직도와 지점망이 한 표에서 섞인다.

CREATE TABLE IF NOT EXISTS branch (
    branch_id     BIGSERIAL    PRIMARY KEY,
    branch_code   VARCHAR(20)  NOT NULL UNIQUE,
    branch_name   VARCHAR(100) NOT NULL,
    branch_type   VARCHAR(20)  NOT NULL,
    -- 지역. 검색이 "서울 강남" 처럼 지역으로 먼저 좁혀진다.
    region        VARCHAR(50)  NOT NULL,
    address       VARCHAR(255) NOT NULL,
    phone         VARCHAR(30),
    -- HH:MM 형식. 지점마다 다르다.
    open_time     VARCHAR(5)   NOT NULL DEFAULT '09:00',
    close_time    VARCHAR(5)   NOT NULL DEFAULT '16:00',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE branch IS '영업점. 고객이 찾아가는 곳이라 내부 조직(department)과 다르다.';
COMMENT ON COLUMN branch.branch_type IS 'BRANCH(영업점) · CORPORATE(기업금융센터) · PB(PB센터)';

ALTER TABLE branch DROP CONSTRAINT IF EXISTS ck_branch_type;
ALTER TABLE branch ADD CONSTRAINT ck_branch_type
    CHECK (branch_type IN ('BRANCH', 'CORPORATE', 'PB'));

CREATE INDEX IF NOT EXISTS ix_branch_search ON branch (region, branch_name) WHERE active;

-- 지점 상담 예약.
--
-- 예약 시각을 지점 영업시간 밖으로 잡으면 고객이 헛걸음한다. 그 검사는 화면이 아니라
-- 서버에서 한다 — 화면만 막으면 API 를 직접 부르는 경로가 뚫린다.
CREATE TABLE IF NOT EXISTS branch_consultation_reservation (
    reservation_id  BIGSERIAL    PRIMARY KEY,
    customer_id     BIGINT       NOT NULL,
    branch_id       BIGINT       NOT NULL REFERENCES branch(branch_id),
    reserved_at     TIMESTAMPTZ  NOT NULL,
    -- 무엇을 상담하러 오는가. 지점이 담당자를 배정하는 근거다.
    topic_cd        VARCHAR(30)  NOT NULL,
    memo            VARCHAR(500),
    contact_phone   VARCHAR(30)  NOT NULL,
    status_cd       VARCHAR(20)  NOT NULL DEFAULT 'RESERVED',
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN branch_consultation_reservation.contact_phone IS
    '안내를 보낼 번호. 화면이 "문자로 안내드리겠습니다" 라고 약속하므로 비워 둘 수 없다.';

ALTER TABLE branch_consultation_reservation DROP CONSTRAINT IF EXISTS ck_bcr_status;
ALTER TABLE branch_consultation_reservation ADD CONSTRAINT ck_bcr_status
    CHECK (status_cd IN ('RESERVED', 'CANCELLED', 'COMPLETED', 'NO_SHOW'));

-- 취소했다면 시각이 있어야 한다. 없으면 언제 취소됐는지 알 수 없어 지점이 자리를
-- 언제부터 비울 수 있었는지 따질 수 없다.
ALTER TABLE branch_consultation_reservation DROP CONSTRAINT IF EXISTS ck_bcr_cancelled;
ALTER TABLE branch_consultation_reservation ADD CONSTRAINT ck_bcr_cancelled
    CHECK ((status_cd = 'CANCELLED') = (cancelled_at IS NOT NULL));

CREATE INDEX IF NOT EXISTS ix_bcr_customer
    ON branch_consultation_reservation (customer_id, reserved_at DESC);

-- 같은 고객이 같은 지점·같은 시각에 두 번 예약하면 자리가 둘 잡힌다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_bcr_no_double_booking
    ON branch_consultation_reservation (customer_id, branch_id, reserved_at)
    WHERE status_cd = 'RESERVED';

-- AXful Bank 영업점. 실재하지 않는 데모 은행의 지점망이다.
INSERT INTO branch (branch_code, branch_name, branch_type, region, address, phone, open_time, close_time)
SELECT * FROM (VALUES
    ('B001', '강남중앙지점',   'BRANCH',    '서울 강남구',  '서울특별시 강남구 테헤란로 152',   '02-555-0101', '09:00', '16:00'),
    ('B002', '여의도지점',     'BRANCH',    '서울 영등포구','서울특별시 영등포구 여의대로 108', '02-555-0102', '09:00', '16:00'),
    ('B003', '종로지점',       'BRANCH',    '서울 종로구',  '서울특별시 종로구 종로 51',        '02-555-0103', '09:00', '16:00'),
    ('B004', '판교지점',       'BRANCH',    '경기 성남시',  '경기도 성남시 분당구 판교역로 235','031-555-0104','09:00', '16:00'),
    ('B005', '해운대지점',     'BRANCH',    '부산 해운대구','부산광역시 해운대구 센텀중앙로 79','051-555-0105','09:00', '16:00'),
    ('C001', '강남기업금융센터','CORPORATE','서울 강남구',  '서울특별시 강남구 영동대로 517',   '02-555-0201', '09:00', '17:00'),
    ('P001', '압구정PB센터',   'PB',        '서울 강남구',  '서울특별시 강남구 압구정로 407',   '02-555-0301', '09:30', '17:00')
) AS v
WHERE NOT EXISTS (SELECT 1 FROM branch);

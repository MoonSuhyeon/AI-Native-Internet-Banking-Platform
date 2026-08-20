BEGIN;

CREATE TABLE IF NOT EXISTS consultation (
    consultation_id              BIGSERIAL PRIMARY KEY,
    customer_no                  VARCHAR(30) NOT NULL,
    reception_method_code_id     BIGINT,
    inquiry_type_code_id         BIGINT,
    reception_channel_code_id    BIGINT,
    content_summary              VARCHAR(200),
    status_code_id               BIGINT,
    answer_summary               VARCHAR(200),
    consulted_at                 TIMESTAMPTZ DEFAULT NOW(),
    completed_at                 TIMESTAMPTZ,
    previous_consultation_id     BIGINT,
    active_yn                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

CREATE TABLE IF NOT EXISTS chatbot_scenario (
    scenario_id                  BIGSERIAL PRIMARY KEY,
    scenario_name                VARCHAR(100) NOT NULL,
    scenario_desc                VARCHAR(500),
    scenario_type_code_id        BIGINT,
    consultation_category_code_id BIGINT,
    reception_channel_code_id    BIGINT,
    test_yn                      BOOLEAN NOT NULL DEFAULT FALSE,
    active_yn                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

CREATE TABLE IF NOT EXISTS chatbot_intent (
    intent_id                    BIGSERIAL PRIMARY KEY,
    fallback_intent_id           BIGINT,
    scenario_id                  BIGINT,
    intent_name                  VARCHAR(100) NOT NULL,
    intent_desc                  VARCHAR(500),
    process_method_code_id       BIGINT,
    confidence_threshold         INT,
    priority                     INT,
    test_yn                      BOOLEAN NOT NULL DEFAULT FALSE,
    active_yn                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

CREATE TABLE IF NOT EXISTS chatbot_node (
    node_id                      BIGSERIAL PRIMARY KEY,
    next_node_id                 BIGINT,
    scenario_id                  BIGINT NOT NULL,
    node_type_code_id            BIGINT,
    node_name                    VARCHAR(100) NOT NULL,
    response_message             TEXT NOT NULL,
    condition_expression         TEXT,
    error_move_node_id           BIGINT,
    timeout_seconds              INT,
    sort_order                   INT NOT NULL DEFAULT 0,
    exposure_count               INT,
    active_yn                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

CREATE TABLE IF NOT EXISTS chatbot_node_button (
    id                           BIGSERIAL PRIMARY KEY,
    node_id                      BIGINT NOT NULL,
    button_text                  VARCHAR(50) NOT NULL,
    button_value                 VARCHAR(20) NOT NULL,
    sort_order                   INT NOT NULL,
    active_yn                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

CREATE TABLE IF NOT EXISTS chatbot_node_flow (
    current_node_id              BIGINT NOT NULL,
    next_node_id                 BIGINT NOT NULL,
    sort_order                   INT NOT NULL,
    chatbot_flow_type_cd         VARCHAR(20) NOT NULL,
    branch_criteria_cd           VARCHAR(20),
    branch_value                 VARCHAR(50),
    active_yn                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT,
    PRIMARY KEY (current_node_id, next_node_id)
);

CREATE TABLE IF NOT EXISTS chatbot_consultation (
    chatbot_consultation_id      BIGSERIAL PRIMARY KEY,
    consultation_id              BIGINT NOT NULL,
    scenario_id                  BIGINT,
    intent_id                    BIGINT,
    process_method_code_id       BIGINT,
    initial_intent               VARCHAR(100),
    entry_screen                 VARCHAR(50),
    app_version                  VARCHAR(20),
    session_started_at           TIMESTAMPTZ DEFAULT NOW(),
    session_ended_at             TIMESTAMPTZ,
    total_turn_count             INT NOT NULL DEFAULT 0,
    resolved_yn                  BOOLEAN NOT NULL DEFAULT FALSE,
    agent_connected_yn           BOOLEAN NOT NULL DEFAULT FALSE,
    end_type_code_id             BIGINT,
    error_occurred_yn            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

CREATE TABLE IF NOT EXISTS chat_consultation (
    chat_consultation_id         BIGSERIAL PRIMARY KEY,
    consultation_id              BIGINT NOT NULL,
    chatbot_consultation_id      BIGINT,
    employee_id                  BIGINT,
    agent_requested_at           TIMESTAMPTZ,
    agent_connected_at           TIMESTAMPTZ,
    waiting_seconds              INT,
    waiting_abandoned_yn         BOOLEAN NOT NULL DEFAULT FALSE,
    waiting_abandoned_at         TIMESTAMPTZ,
    chat_started_at              TIMESTAMPTZ,
    chat_ended_at                TIMESTAMPTZ,
    chat_seconds                 INT,
    concurrent_chat_count        INT,
    reassignment_count           INT,
    total_turn_count             INT NOT NULL DEFAULT 0,
    end_type_code_id             BIGINT,
    agent_talk_seconds           INT,
    satisfaction_score           INT,
    active_yn                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

CREATE TABLE IF NOT EXISTS chat_message_history (
    chat_message_history_id      BIGSERIAL PRIMARY KEY,
    chat_consultation_id         BIGINT,
    chatbot_consultation_id      BIGINT,
    node_id                      BIGINT,
    sequence_no                  INT NOT NULL,
    sender_type_code_id          BIGINT,
    message_type_code_id         BIGINT,
    message_content              TEXT NOT NULL,
    button_value                 VARCHAR(100),
    confidence_score             INT,
    process_method_code_id       BIGINT,
    response_time_ms             INT,
    sentiment_result_code_id     BIGINT,
    error_type_code_id           BIGINT,
    read_yn                      BOOLEAN NOT NULL DEFAULT FALSE,
    read_at                      TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   BIGINT,
    updated_at                   TIMESTAMPTZ DEFAULT NOW(),
    updated_by                   BIGINT
);

ALTER TABLE consultation ADD COLUMN IF NOT EXISTS consultation_id BIGSERIAL;
ALTER TABLE consultation ADD COLUMN IF NOT EXISTS customer_no VARCHAR(30);
ALTER TABLE consultation ADD COLUMN IF NOT EXISTS reception_method_code_id BIGINT;
ALTER TABLE consultation ADD COLUMN IF NOT EXISTS inquiry_type_code_id BIGINT;
ALTER TABLE consultation ADD COLUMN IF NOT EXISTS reception_channel_code_id BIGINT;
ALTER TABLE consultation ADD COLUMN IF NOT EXISTS status_code_id BIGINT;
ALTER TABLE consultation ADD COLUMN IF NOT EXISTS previous_consultation_id BIGINT;

ALTER TABLE chatbot_scenario ADD COLUMN IF NOT EXISTS scenario_id BIGSERIAL;
ALTER TABLE chatbot_scenario ADD COLUMN IF NOT EXISTS scenario_name VARCHAR(100);
ALTER TABLE chatbot_scenario ADD COLUMN IF NOT EXISTS scenario_desc VARCHAR(500);
ALTER TABLE chatbot_scenario ADD COLUMN IF NOT EXISTS scenario_type_code_id BIGINT;
ALTER TABLE chatbot_scenario ADD COLUMN IF NOT EXISTS consultation_category_code_id BIGINT;
ALTER TABLE chatbot_scenario ADD COLUMN IF NOT EXISTS reception_channel_code_id BIGINT;

ALTER TABLE chatbot_intent ADD COLUMN IF NOT EXISTS intent_id BIGSERIAL;
ALTER TABLE chatbot_intent ADD COLUMN IF NOT EXISTS scenario_id BIGINT;
ALTER TABLE chatbot_intent ADD COLUMN IF NOT EXISTS process_method_code_id BIGINT;

ALTER TABLE chatbot_node ADD COLUMN IF NOT EXISTS node_id BIGSERIAL;
ALTER TABLE chatbot_node ADD COLUMN IF NOT EXISTS next_node_id BIGINT;
ALTER TABLE chatbot_node ADD COLUMN IF NOT EXISTS scenario_id BIGINT;
ALTER TABLE chatbot_node ADD COLUMN IF NOT EXISTS node_type_code_id BIGINT;
ALTER TABLE chatbot_node ADD COLUMN IF NOT EXISTS condition_expression TEXT;
ALTER TABLE chatbot_node ADD COLUMN IF NOT EXISTS error_move_node_id BIGINT;

ALTER TABLE chatbot_consultation ADD COLUMN IF NOT EXISTS chatbot_consultation_id BIGSERIAL;
ALTER TABLE chatbot_consultation ADD COLUMN IF NOT EXISTS consultation_id BIGINT;
ALTER TABLE chatbot_consultation ADD COLUMN IF NOT EXISTS scenario_id BIGINT;
ALTER TABLE chatbot_consultation ADD COLUMN IF NOT EXISTS intent_id BIGINT;
ALTER TABLE chatbot_consultation ADD COLUMN IF NOT EXISTS process_method_code_id BIGINT;
ALTER TABLE chatbot_consultation ADD COLUMN IF NOT EXISTS end_type_code_id BIGINT;

ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS chat_message_history_id BIGSERIAL;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS chat_consultation_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS chatbot_consultation_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS node_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS sequence_no INT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS sender_type_code_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS message_type_code_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS message_content TEXT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS button_value VARCHAR(100);
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS confidence_score INT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS process_method_code_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS response_time_ms INT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS sentiment_result_code_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS error_type_code_id BIGINT;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS read_yn BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;
ALTER TABLE chat_message_history ALTER COLUMN chat_consultation_id DROP NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'consultation' AND column_name = 'id'
    ) THEN
        CREATE SEQUENCE IF NOT EXISTS consultation_id_legacy_seq;
        ALTER TABLE consultation ALTER COLUMN id SET DEFAULT nextval('consultation_id_legacy_seq');
        PERFORM setval('consultation_id_legacy_seq', COALESCE((SELECT MAX(id) FROM consultation), 0) + 1, false);
        ALTER TABLE consultation ALTER COLUMN customer_id2 DROP NOT NULL;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'chatbot_scenario' AND column_name = 'id'
    ) THEN
        CREATE SEQUENCE IF NOT EXISTS chatbot_scenario_id_legacy_seq;
        ALTER TABLE chatbot_scenario ALTER COLUMN id SET DEFAULT nextval('chatbot_scenario_id_legacy_seq');
        PERFORM setval('chatbot_scenario_id_legacy_seq', COALESCE((SELECT MAX(id) FROM chatbot_scenario), 0) + 1, false);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'chatbot_intent' AND column_name = 'id'
    ) THEN
        CREATE SEQUENCE IF NOT EXISTS chatbot_intent_id_legacy_seq;
        ALTER TABLE chatbot_intent ALTER COLUMN id SET DEFAULT nextval('chatbot_intent_id_legacy_seq');
        PERFORM setval('chatbot_intent_id_legacy_seq', COALESCE((SELECT MAX(id) FROM chatbot_intent), 0) + 1, false);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'chatbot_node' AND column_name = 'id'
    ) THEN
        CREATE SEQUENCE IF NOT EXISTS chatbot_node_id_legacy_seq;
        ALTER TABLE chatbot_node ALTER COLUMN id SET DEFAULT nextval('chatbot_node_id_legacy_seq');
        PERFORM setval('chatbot_node_id_legacy_seq', COALESCE((SELECT MAX(id) FROM chatbot_node), 0) + 1, false);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'chatbot_consultation' AND column_name = 'id'
    ) THEN
        CREATE SEQUENCE IF NOT EXISTS chatbot_consultation_id_legacy_seq;
        ALTER TABLE chatbot_consultation ALTER COLUMN id SET DEFAULT nextval('chatbot_consultation_id_legacy_seq');
        PERFORM setval('chatbot_consultation_id_legacy_seq', COALESCE((SELECT MAX(id) FROM chatbot_consultation), 0) + 1, false);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS chatbot_document (
    document_id       BIGSERIAL PRIMARY KEY,
    customer_no       VARCHAR(30)  NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_path       VARCHAR(500) NOT NULL,
    doc_type          VARCHAR(50)  NOT NULL,
    file_size_bytes   BIGINT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        BIGINT,
    updated_at        TIMESTAMPTZ  DEFAULT NOW(),
    updated_by        BIGINT
);

-- 이메일상담 접수.
--
-- 상담 모달과 FAQ 화면에 "이메일상담하기" 버튼이 있었지만 핸들러가 없었다. 눌러도
-- 아무 일이 없었으니 24시간 접수한다고 써 놓고 접수할 곳이 없었던 셈이다.
--
-- **왜 consultation 만으로는 부족한가.** consultation 은 상담 한 건의 요약을 담는
-- 표라 content_summary 가 200자다. 이메일 문의는 본문이 그보다 길고, 답변을 보낼
-- 주소가 있어야 하는데 그 자리가 없다. 요약 칸에 이메일을 밀어 넣으면 나중에 읽는
-- 사람이 그 값을 요약으로 읽는다.
CREATE TABLE IF NOT EXISTS email_inquiry (
    inquiry_id      BIGSERIAL PRIMARY KEY,
    consultation_id BIGINT       NOT NULL REFERENCES consultation(consultation_id),
    customer_no     VARCHAR(30)  NOT NULL,
    -- 답변을 보낼 곳. 가입 이메일과 다를 수 있어 따로 받는다.
    reply_email     VARCHAR(255) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         TEXT         NOT NULL,
    answered_at     TIMESTAMPTZ,
    answer_content  TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_email_inquiry_customer
    ON email_inquiry (customer_no, created_at DESC);

-- 답변했다면 내용이 있어야 한다. 시각만 남으면 "답변함" 인데 무엇을 보냈는지 모른다.
ALTER TABLE email_inquiry DROP CONSTRAINT IF EXISTS ck_email_inquiry_answer;
ALTER TABLE email_inquiry ADD CONSTRAINT ck_email_inquiry_answer
    CHECK (answered_at IS NULL
           OR (answer_content IS NOT NULL AND length(btrim(answer_content)) > 0));

COMMIT;

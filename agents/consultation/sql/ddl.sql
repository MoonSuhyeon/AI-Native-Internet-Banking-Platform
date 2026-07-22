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
    active_yn                    CHAR(1) NOT NULL DEFAULT 'Y',
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
    test_yn                      CHAR(1) NOT NULL DEFAULT 'N',
    active_yn                    CHAR(1) NOT NULL DEFAULT 'Y',
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
    test_yn                      CHAR(1) NOT NULL DEFAULT 'N',
    active_yn                    CHAR(1) NOT NULL DEFAULT 'Y',
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
    active_yn                    CHAR(1) NOT NULL DEFAULT 'Y',
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
    active_yn                    CHAR(1) NOT NULL DEFAULT 'Y',
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
    active_yn                    CHAR(1) NOT NULL DEFAULT 'Y',
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
    resolved_yn                  CHAR(1) NOT NULL DEFAULT 'N',
    agent_connected_yn           CHAR(1) NOT NULL DEFAULT 'N',
    end_type_code_id             BIGINT,
    error_occurred_yn            CHAR(1) NOT NULL DEFAULT 'N',
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
    waiting_abandoned_yn         CHAR(1) NOT NULL DEFAULT 'N',
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
    active_yn                    CHAR(1) NOT NULL DEFAULT 'Y',
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
    read_yn                      CHAR(1) NOT NULL DEFAULT 'N',
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
ALTER TABLE chat_message_history ADD COLUMN IF NOT EXISTS read_yn CHAR(1) NOT NULL DEFAULT 'N';
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

COMMIT;

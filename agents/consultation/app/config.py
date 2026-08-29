from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "consultation-service"
    app_version: str = "0.1.0"
    # DB 접속 정보는 반드시 환경변수(CONSULTATION_DATABASE_URL)로 주입하세요.
    # 예: CONSULTATION_DATABASE_URL=postgresql+psycopg://user:pass@host:5432/db
    # 기본값 없음 — 미설정 시 시작 즉시 ValidationError 발생 (의도적)
    database_url: str
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_enabled: bool = False
    kafka_topic_chatbot_events: str = "consultation.chatbot.events"
    kafka_topic_chat_events: str = "consultation.chat.events"
    kafka_topic_deposit_events: str = "deposit.contract.events"   # deposit-api 발행 토픽
    kafka_topic_chatbot_message: str = "consultation.chatbot.message"  # 챗봇 메시지 수신 토픽
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"
    llm_confidence_threshold: int = 70
    # 추적은 harness_core.tracing 이 PHOENIX_ENABLED·PHOENIX_GRPC_ENDPOINT 를
    # 직접 읽는다. 여기 두면 설정 지점이 둘이 되어 한쪽만 켜지는 일이 생긴다.
    # customer-service 연동 (나이/생년월일 조회용)
    customer_service_url: str = "http://localhost:8081"
    # core-banking 연동 — 자금 이동은 여기를 거친다.
    # 챗봇이 직접 SQL 로 옮기면 락·멱등키·한도 검증이 없는 두 번째 원장 구현이 된다.
    core_banking_url: str = "http://localhost:8082"
    # 서비스 자격증명. core-banking 의 service_principal.credential_hash(V36)와
    # 맞아야 신원이 선다. 서비스마다 자기 값을 갖는다 — 공유 토큰을 쓰면 신원이
    # 하나가 되어, 그 토큰을 가진 무엇이든 상담을 사칭할 수 있다.
    #
    # 기본값의 dev-secret 은 의도된 표식이다.
    core_banking_credential: str = "dev-secret-consultation-service-credential"
    # 하네스 감사 기록 — 챗봇이 고객에게 무엇을 답했는지 남긴다.
    # 기본값을 켜 둔 것은 의도적이다. 감사는 켜는 것이 기본이고 끄는 것이 예외다.
    # 끄더라도 NoOp 이 들어가므로 의존하는 쪽은 깨지지 않는다.
    harness_audit_enabled: bool = True
    # 감사 저장이 실패했을 때 기록을 모아 두는 파일. DB 가 돌아온 뒤
    # `python -m app.audit_replay` 로 다시 넣는다.
    # 빈 값이면 모아 두지 않는다 — 그때 실패는 예전처럼 영구 유실이다.
    # 컨테이너라면 볼륨이 붙은 경로여야 한다. 볼륨 없이 재시작하면 스풀도 사라진다.
    harness_audit_spool_path: str = "/var/lib/consultation/audit-spool.jsonl"

    model_config = SettingsConfigDict(
        env_prefix="CONSULTATION_",
        env_file=".env",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "consultation-service"
    app_version: str = "0.1.0"
    # DB 접속 정보는 반드시 환경변수(CONSULTATION_DATABASE_URL)로 주입하세요.
    # 예: CONSULTATION_DATABASE_URL=postgresql+psycopg://user:pass@host:5432/db
    database_url: str = ""
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_enabled: bool = False
    kafka_topic_chatbot_events: str = "consultation.chatbot.events"
    kafka_topic_chat_events: str = "consultation.chat.events"
    kafka_topic_deposit_events: str = "deposit.contract.events"   # deposit-api 발행 토픽
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"
    llm_confidence_threshold: int = 70

    model_config = SettingsConfigDict(
        env_prefix="CONSULTATION_",
        env_file=".env",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()

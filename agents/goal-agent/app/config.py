from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "goal-agent"
    # 기본값은 로컬 개발용 deposit DB. 운영 환경에서는 DATABASE_URL 환경변수로 주입하세요.
    database_url: str = "postgresql+psycopg://deposit:deposit@localhost:5433/deposit_db"
    cors_origins: list[str] = ["http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:3001", "http://127.0.0.1:3001"]
    anthropic_api_key: str = ""
    # 내부 서비스 인증 키. 설정 시 X-Internal-Token 헤더로 검증. 빈 값이면 인증 비활성화(개발 전용).
    api_key: str = ""
    # 만기 알림 및 재투자 추천 에이전트 활성화 여부 (기본 비활성화)
    maturity_agent_enabled: bool = False
    # 지출 패턴 관리 에이전트 활성화 여부 (기본 비활성화)
    spending_agent_enabled: bool = False
    # Claude API 호출에 사용할 모델 ID
    llm_model: str = "claude-opus-4-8"
    # Tool Calling 에이전트 최대 반복 횟수 (도구 수 × 2)
    max_agent_iterations: int = 14
    langfuse_enabled: bool = False
    langfuse_secret_key: str = ""
    langfuse_public_key: str = ""
    langfuse_host: str = "http://localhost:3001"

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")


settings = Settings()

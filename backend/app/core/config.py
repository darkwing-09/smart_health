"""Application configuration loaded via Pydantic BaseSettings."""

from typing import List
from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

    # Core Application & Runtime
    APP_ENV: str = "development"
    DEBUG: bool = True
    PORT: int = 8000
    HOST: str = "0.0.0.0"
    API_V1_STR: str = "/v1"
    CORS_ORIGINS: List[str] = ["*"]
    ANDROID_APP_ID: str = "com.healthos.android"

    # Cryptographic Keys & Authentication
    SECRET_KEY: str = Field(
        default="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        description="HMAC secret key for JWT signing"
    )
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    REFRESH_TOKEN_EXPIRE_DAYS: int = 30
    ENCRYPTION_KEY_AES256: str = Field(
        default="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        description="Base64-encoded 32-byte key for AES-GCM field encryption"
    )


    # Database & Storage
    DATABASE_URL: str = Field(
        default="postgresql+asyncpg://healthos_user:healthos_dev_password@localhost:5432/healthos_db",
        description="PostgreSQL async connection string"
    )
    DB_POOL_SIZE: int = 20
    DB_MAX_OVERFLOW: int = 10
    REDIS_URL: str = "redis://localhost:6379/0"
    STORAGE_PROVIDER: str = "local"
    STORAGE_BUCKET: str = "healthos-daily-reports"
    STORAGE_LOCAL_PATH: str = "./data/reports"

    # Physiological Thresholds & Baselines
    BASELINE_MIN_DAYS_ESTABLISHED: int = 14
    BASELINE_ROLLING_WINDOW_DAYS: int = 30
    ANOMALY_ZSCORE_UNUSUAL: float = 2.0
    ANOMALY_ZSCORE_MONITORING: float = 2.8
    ANOMALY_ZSCORE_CONCERNING: float = 3.8
    ANOMALY_ZSCORE_URGENT: float = 5.0
    HARD_PHYSIO_HR_MAX: float = 150.0
    HARD_PHYSIO_HR_MIN: float = 38.0
    NOTIFICATION_DEDUP_WINDOW_HOURS: int = 12

    # LLM Settings
    LLM_PROVIDER: str = "openai"
    LLM_MODEL: str = "gpt-4o"
    LLM_API_KEY: str = ""
    ANTHROPIC_API_KEY: str = ""
    LLM_MODEL_HEALTH_INTEL: str = "gpt-4o"
    LLM_MODEL_DAILY_REPORT: str = "gpt-4o"
    LLM_TEMPERATURE_HEALTH_INTEL: float = 0.1
    LLM_MAX_TOKENS_HEALTH_INTEL: int = 1000

    # Gemini API Settings (Google AI)
    GEMINI_API_KEY: str = ""
    GEMINI_MODEL: str = "gemini-2.5-flash"
    GEMINI_TEMPERATURE: float = 0.3
    GEMINI_MAX_TOKENS: int = 500

    # LangSmith Observability
    LANGCHAIN_TRACING_V2: bool = True
    LANGCHAIN_API_KEY: str = ""
    LANGCHAIN_PROJECT: str = "personal-health-os"
    LANGCHAIN_ENDPOINT: str = "https://api.smith.langchain.com"

    # Push Notification & Messaging
    FCM_PROJECT_ID: str = ""
    FCM_CREDENTIALS_JSON: str = ""
    WHATSAPP_PROVIDER: str = "meta_cloud_api"
    WHATSAPP_ENABLED: bool = False
    WHATSAPP_ACCESS_TOKEN: str = ""
    WHATSAPP_PHONE_NUMBER_ID: str = ""

    # Third-Party Directory
    PLACES_PROVIDER: str = "mock"
    GOOGLE_PLACES_API_KEY: str = ""
    CARE_SEARCH_RADIUS_KM: int = 10

    # Observability
    LOG_LEVEL: str = "INFO"
    SENTRY_DSN: str = ""
    OTEL_EXPORTER_OTLP_ENDPOINT: str = "http://localhost:4317"

    # Key Rotation & Envelope Encryption
    ENCRYPTION_KEY_ID: str = "v1"
    ENCRYPTION_OLD_KEYS_JSON: str = "{}"  # JSON mapping of version_id -> base64_key

    # Rate Limiting (Redis-backed)
    RATE_LIMIT_ENABLED: bool = True
    RATE_LIMIT_LOGIN_PER_MIN: int = 5
    RATE_LIMIT_SYNC_PER_MIN: int = 60
    RATE_LIMIT_SUMMARY_PER_MIN: int = 10
    RATE_LIMIT_EXPORT_PER_MIN: int = 10

    @model_validator(mode="after")
    def validate_production_security(self) -> "Settings":
        """Enforces zero-default-secrets and strict cryptography when in production."""
        is_prod = self.APP_ENV.lower() in ("production", "prod")
        default_secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        default_enc_key = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="


        if is_prod:
            if self.SECRET_KEY == default_secret or len(self.SECRET_KEY) < 32:
                raise ValueError("CRITICAL: Production deployment requires a secure, non-default SECRET_KEY >= 32 chars.")
            if self.ENCRYPTION_KEY_AES256 == default_enc_key or not self.ENCRYPTION_KEY_AES256:
                raise ValueError("CRITICAL: Production deployment requires a secure, non-default ENCRYPTION_KEY_AES256.")
            if "healthos_dev_password" in self.DATABASE_URL:
                raise ValueError("CRITICAL: Production DATABASE_URL cannot contain the default development password.")
            if self.DEBUG:
                raise ValueError("CRITICAL: DEBUG mode cannot be enabled in production.")
            if "*" in self.CORS_ORIGINS:
                raise ValueError("CRITICAL: Wildcard CORS_ORIGINS ('*') cannot be enabled in production.")
        return self


settings = Settings()


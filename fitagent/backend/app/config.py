from __future__ import annotations

import logging
import secrets
from functools import lru_cache

from fitagent_agents.env import load_env
from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

log = logging.getLogger(__name__)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file_encoding="utf-8", extra="ignore")

    env: str = Field("development", alias="FITAGENT_ENV")
    database_url: str = Field("sqlite+aiosqlite:///./fitagent.db", alias="DATABASE_URL")
    db_auto_create: bool = Field(True, alias="DB_AUTO_CREATE")
    secret_key: str = Field("", alias="SECRET_KEY")
    access_token_minutes: int = Field(30, alias="ACCESS_TOKEN_MINUTES")
    refresh_token_days: int = Field(30, alias="REFRESH_TOKEN_DAYS")
    cors_origins: list[str] = Field(default_factory=list, alias="CORS_ORIGINS")
    rate_limit_auth_per_min: int = Field(10, alias="RATE_LIMIT_AUTH_PER_MIN")
    rate_limit_coach_per_min: int = Field(15, alias="RATE_LIMIT_COACH_PER_MIN")
    rate_limit_plan_per_hour: int = Field(10, alias="RATE_LIMIT_PLAN_PER_HOUR")
    android_package: str = Field("com.fitagent.app", alias="ANDROID_PACKAGE")
    # Release signing certificate fingerprint(s) for App Links (keytool -list -v), comma-separated.
    android_cert_sha256: str = Field("", alias="ANDROID_CERT_SHA256")
    # WhatsApp Cloud API (Meta). Empty token → reports are saved to REPORT_DIR instead of sent ("dry run").
    whatsapp_token: str = Field("", alias="WHATSAPP_TOKEN")
    whatsapp_phone_number_id: str = Field("", alias="WHATSAPP_PHONE_NUMBER_ID")
    # Approved template with a DOCUMENT header and one body variable {{1}}; empty → plain document message
    # (only delivered inside the 24-hour window after the user last messaged the business number).
    whatsapp_template: str = Field("", alias="WHATSAPP_TEMPLATE")
    whatsapp_template_lang: str = Field("en", alias="WHATSAPP_TEMPLATE_LANG")
    whatsapp_api_version: str = Field("v21.0", alias="WHATSAPP_API_VERSION")
    report_dir: str = Field("./reports", alias="REPORT_DIR")
    # Seconds between runs of the daily report job (--loop).
    report_job_interval_s: int = Field(600, alias="REPORT_JOB_INTERVAL_S")

    @property
    def whatsapp_configured(self) -> bool:
        return bool(self.whatsapp_token and self.whatsapp_phone_number_id)

    @property
    def is_production(self) -> bool:
        return self.env == "production"

    @model_validator(mode="after")
    def _secret(self) -> Settings:
        if not self.secret_key:
            if self.is_production:
                raise ValueError("SECRET_KEY must be set in production")
            self.secret_key = secrets.token_urlsafe(48)
            log.warning("SECRET_KEY not set — using a random key; tokens will not survive a restart.")
        elif self.is_production and len(self.secret_key) < 32:
            raise ValueError("SECRET_KEY must be at least 32 characters in production")
        return self


@lru_cache
def get_settings() -> Settings:
    # Export fitagent/.env so LangChain/LangSmith (which read os.environ) see the API keys too.
    load_env()
    return Settings()

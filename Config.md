# Config.md — Application Configuration Specification

This document details the configuration architecture for Personal Health OS. All settings are loaded via environment variables and validated at service startup using Pydantic `BaseSettings`.

---

## 1. Environment Profiles

Personal Health OS supports four operational tiers configured via `APP_ENV`:
- `development`: Verbose logging, auto-reloading, mocked SMS/push gateways, local Postgres with TimescaleDB.
- `test`: Ephemeral SQLite/Postgres in-memory, deterministic mock clocks, stubbed LLM clients, isolated LangSmith project.
- `staging`: Production-identical containerized services, sanitized test users, real FCM sandbox, LangSmith evaluation runs.
- `production`: Strict SSL enforcement, KMS-backed secrets, real push & WhatsApp gateways, Sentry error telemetry.

---

## 2. Configuration Settings Reference

### 2.1 Core Application & Security
| Parameter | Type | Default | Environments | Description |
| :--- | :--- | :--- | :--- | :--- |
| `APP_ENV` | string | `development` | All | Deployment profile (`development`, `test`, `staging`, `production`). |
| `API_V1_STR` | string | `/v1` | All | Base route prefix for API version 1. |
| `SECRET_KEY` | string | *Required* | All | HMAC cryptographic secret for JWT token signature. |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | int | `60` | All | JWT access token validity duration. |
| `REFRESH_TOKEN_EXPIRE_DAYS` | int | `30` | All | JWT refresh token validity duration. |
| `ENCRYPTION_KEY_AES256` | string | *Required* | All | 32-byte Base64-encoded key for database field-level AES-GCM encryption. |
| `CORS_ORIGINS` | list[str] | `["*"]` | All | Permitted CORS origins (restricted to app scheme in prod). |
| `ANDROID_APP_ID` | string | `com.healthos.android` | All | Target Android package identifier. |

### 2.2 Database, Storage & Cache Infrastructure
| Parameter | Type | Default | Environments | Description |
| :--- | :--- | :--- | :--- | :--- |
| `DATABASE_URL` | string | *Required* | All | PostgreSQL connection URI (`postgresql+asyncpg://user:pass@host:5432/healthos_db`). |
| `DB_POOL_SIZE` | int | `20` | All | Maximum persistent async database connections in pool. |
| `DB_MAX_OVERFLOW` | int | `10` | All | Max surge connections above pool size. |
| `REDIS_URL` | string | `redis://localhost:6379/0`| All | Redis URI for ARQ worker queue and rate limiting. |
| `STORAGE_PROVIDER` | string | `local` | All | Object storage provider for daily PDF reports (`local`, `s3`, `gcs`). |
| `STORAGE_BUCKET` | string | `healthos-daily-reports`| All | Destination bucket identifier. |

### 2.3 Analytics Engine & Physiological Thresholds
| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `BASELINE_MIN_DAYS_ESTABLISHED`| int | `14` | Minimum days of nominal data required to consider a baseline established. |
| `BASELINE_ROLLING_WINDOW_DAYS` | int | `30` | Rolling historical window size for mean and variance calculations. |
| `ANOMALY_ZSCORE_UNUSUAL` | float | `2.0` | Z-score cutoff for Level 1 Insight tier. |
| `ANOMALY_ZSCORE_MONITORING` | float | `2.8` | Z-score cutoff for Level 2 Attention tier. |
| `ANOMALY_ZSCORE_CONCERNING` | float | `3.8` | Z-score cutoff for Level 3 Important tier. |
| `ANOMALY_ZSCORE_URGENT` | float | `5.0` | Z-score cutoff for Level 4 Urgent tier. |
| `HARD_PHYSIO_HR_MAX` | float | `150.0` | Absolute resting heart rate ceiling (bpm) triggering urgent evaluation. |
| `HARD_PHYSIO_HR_MIN` | float | `38.0` | Absolute resting heart rate floor (bpm) triggering urgent evaluation. |

### 2.4 Agent System & LangSmith Observability
| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `LLM_PROVIDER` | string | `openai` | Primary LLM provider (`openai` or `anthropic`). |
| `LLM_MODEL` | string | `gpt-4o` | Default model identifier. |
| `LLM_API_KEY` | string | *Required* | API key for LLM execution. |
| `LANGCHAIN_TRACING_V2` | bool | `true` | Enables LangSmith execution tracing. |
| `LANGCHAIN_API_KEY` | string | Optional | API key for LangSmith project authentication. |
| `LANGCHAIN_PROJECT` | string | `personal-health-os` | LangSmith project workspace name. |
| `LANGCHAIN_ENDPOINT` | string | `https://api.smith.langchain.com` | LangSmith telemetry ingestion endpoint. |
| `LLM_MODEL_HEALTH_INTEL` | string | `gpt-4o` | Model ID for Health Intelligence explanation generator. |
| `LLM_MODEL_DAILY_REPORT` | string | `gpt-4o` | Model ID for Daily Report synthesis and quote generator. |
| `LLM_TEMPERATURE_HEALTH_INTEL`| float | `0.1` | Low temperature to prevent hallucinations in clinical telemetry explanation. |

### 2.5 Notification & Delivery Channels
| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `FCM_PROJECT_ID` | string | *Required (Prod)*| Google Firebase project ID for push notification dispatch. |
| `FCM_CREDENTIALS_JSON` | string | *Required (Prod)*| Base64-encoded Google service account credentials for FCM v1 API. |
| `WHATSAPP_PROVIDER` | string | `meta_cloud_api` | Messaging platform provider. |
| `WHATSAPP_ENABLED` | bool | `false` | Feature flag gating WhatsApp Business API alerts. |
| `WHATSAPP_ACCESS_TOKEN` | string | Optional | Permanent system user token for Meta Business Platform. |
| `WHATSAPP_PHONE_NUMBER_ID` | string | Optional | Phone Number ID registered with WhatsApp Business Platform. |
| `NOTIFICATION_DEDUP_WINDOW_HOURS`| int | `12` | Hours before an un-escalated anomaly can re-alert the user. |

### 2.6 Cryptographic Envelope & Rate Limiting Controls (Phase 6)
| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `CURRENT_KEY_ID` | string | `v1` | Identifier of active Master Key (KEK) for new envelope encryptions. |
| `OLD_ENCRYPTION_KEYS_JSON` | string | `{}` | JSON dictionary mapping historical key IDs to 32-byte Base64 keys for zero-downtime rotation. |
| `RATE_LIMIT_ENABLED` | bool | `true` | Enables distributed sliding-window rate limiting via Redis ZSET. |
| `RATE_LIMIT_LOGIN_PER_MIN` | int | `5` | Maximum login attempts per minute per client IP. |
| `RATE_LIMIT_SYNC_PER_MIN` | int | `60` | Maximum wearable sync batch submissions per minute per user. |
| `RATE_LIMIT_SUMMARY_PER_MIN`| int | `10` | Maximum clinical brief drafts / PDF exports per minute per user. |
| **Production Validator Rule** | Enforced | *Active* | `@model_validator(mode="after")` rejects startup in `APP_ENV=production` if dev secrets are detected. |

---


## 3. Pydantic Settings Implementation

```python
# backend/app/core/config.py
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field
from typing import List

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    APP_ENV: str = "development"
    DEBUG: bool = True
    PORT: int = 8000
    HOST: str = "0.0.0.0"
    API_V1_STR: str = "/v1"
    CORS_ORIGINS: List[str] = ["*"]
    ANDROID_APP_ID: str = "com.healthos.android"

    # Cryptography & Security
    SECRET_KEY: str = Field(..., description="JWT secret key")
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    REFRESH_TOKEN_EXPIRE_DAYS: int = 30
    ENCRYPTION_KEY_AES256: str = Field(..., description="Field-level AES-256-GCM encryption key")

    # Databases & Storage
    DATABASE_URL: str = Field(..., description="PostgreSQL async connection string")
    DB_POOL_SIZE: int = 20
    DB_MAX_OVERFLOW: int = 10
    REDIS_URL: str = "redis://localhost:6379/0"
    STORAGE_PROVIDER: str = "local"
    STORAGE_BUCKET: str = "healthos-daily-reports"

    # Baseline & Anomaly
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

settings = Settings()
```

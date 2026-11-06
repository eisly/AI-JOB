from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

AGENT_DIR = Path(__file__).resolve().parents[1]
PROJECT_ROOT = Path(__file__).resolve().parents[3]

DEFAULT_JWT_SECRET = "dev-only-internal-jwt-secret-change-me"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="AGENT_",
        env_file=(str(PROJECT_ROOT / ".env"), str(AGENT_DIR / ".env")),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        populate_by_name=True,
    )

    app_env: Literal["dev", "test", "prod"] = "dev"
    log_level: str = "INFO"
    host: str = "127.0.0.1"
    port: int = 8100
    trace_header: str = "X-Trace-Id"

    auth_mode: Literal["enabled", "disabled"] = "enabled"
    jwt_secret: str = DEFAULT_JWT_SECRET
    jwt_issuer: str = "career-business"
    jwt_audience: str = "career-ai-agent"
    jwt_leeway_seconds: int = 5
    run_scope: str = "ai:run"
    event_scope: str = "ai:run"

    storage_backend: Literal["sqlite", "memory"] = "sqlite"
    sqlite_path: str = str(AGENT_DIR / "data" / "agent_runs.db")
    data_encryption_key: str | None = Field(
        default=None,
        validation_alias=AliasChoices("AGENT_DATA_ENCRYPTION_KEY", "DATA_ENCRYPTION_KEY"),
    )

    worker_count: int = 2
    queue_max_size: int = 100
    event_poll_seconds: float = 0.25
    heartbeat_seconds: float = 15.0
    event_retention_hours: int = 24
    run_retention_days: int = 7
    deadline_max_seconds: int = 300
    deadline_clock_skew_seconds: int = 5

    llm_provider: Literal["auto", "deepseek", "fake"] = "auto"
    deepseek_api_key: str | None = Field(
        default=None,
        validation_alias=AliasChoices("DEEPSEEK_API_KEY"),
    )
    deepseek_base_url: str = Field(
        default="https://api.deepseek.com",
        validation_alias=AliasChoices("DEEPSEEK_BASE_URL", "AGENT_DEEPSEEK_BASE_URL"),
    )
    deepseek_model: str = Field(
        default="deepseek-chat",
        validation_alias=AliasChoices("DEEPSEEK_MODEL", "AGENT_DEEPSEEK_MODEL"),
    )
    llm_timeout_seconds: float = 45.0
    llm_max_output_tokens: int = 4000
    llm_temperature: float = 0.2
    llm_trust_env: bool = False
    llm_provider_max_retries: int = 1

    callback_enabled: bool = True
    callback_base_url: str = "http://127.0.0.1:8080"
    callback_path_template: str = "/internal/business/v1/ai-tasks/{task_id}/result"
    callback_secret: str | None = Field(
        default=None,
        validation_alias=AliasChoices("AGENT_CALLBACK_JWT_SECRET", "CALLBACK_JWT_SECRET"),
    )
    callback_issuer: str = "career-ai-agent"
    callback_audience: str = "career-business"
    callback_scope: str = "ai:callback"
    callback_timeout_seconds: float = 5.0
    callback_max_attempts: int = 10
    callback_backoff_base_seconds: float = 1.0
    callback_backoff_max_seconds: float = 60.0
    callback_scan_seconds: float = 1.0

    max_document_bytes: int = 10 * 1024 * 1024
    max_extracted_chars: int = 60000
    allowed_file_hosts: str = "files.example.invalid"
    file_allowed_schemes: str = "https"
    file_download_timeout_seconds: float = 10.0

    tavily_api_key: str | None = Field(default=None, repr=False, validation_alias="TAVILY_API_KEY")
    job_search_sources: str = "web,tencent,greenhouse"
    job_search_timeout_seconds: float = 12.0
    job_search_concurrency: int = 4
    job_search_per_source: int = 20
    job_search_max_total: int = 60
    job_search_max_jd_chars: int = 30000
    job_search_greenhouse_boards: str = "stripe:Stripe"
    job_search_user_agent: str = "AI-JOB-Agent/1.0 (+job-search; contact: local-agent)"
    job_search_trust_env: bool = False
    job_search_allow_http: bool = False

    @field_validator("log_level")
    @classmethod
    def _upper_level(cls, value: str) -> str:
        return value.upper()

    @field_validator("worker_count")
    @classmethod
    def _worker_count(cls, value: int) -> int:
        if value < 1 or value > 16:
            raise ValueError("worker_count must be between 1 and 16")
        return value

    @property
    def file_hosts(self) -> set[str]:
        return {item.strip().lower() for item in self.allowed_file_hosts.split(",") if item.strip()}

    @property
    def file_schemes(self) -> set[str]:
        return {item.strip().lower() for item in self.file_allowed_schemes.split(",") if item.strip()}

    @property
    def effective_llm_provider(self) -> str:
        if self.llm_provider != "auto":
            return self.llm_provider
        return "deepseek" if self.deepseek_api_key else "fake"

    @property
    def effective_model_name(self) -> str:
        if self.effective_llm_provider == "deepseek":
            return self.deepseek_model
        return "fake-llm"

    def validate_runtime_security(self) -> None:
        if self.app_env != "prod":
            return
        if self.auth_mode == "disabled":
            raise RuntimeError("AGENT_AUTH_MODE=disabled is forbidden in prod")
        if self.jwt_secret == DEFAULT_JWT_SECRET or len(self.jwt_secret) < 32:
            raise RuntimeError("AGENT_JWT_SECRET must be a strong secret in prod")
        if self.effective_llm_provider == "deepseek" and not self.deepseek_api_key:
            raise RuntimeError("DEEPSEEK_API_KEY is required when the deepseek provider is used")
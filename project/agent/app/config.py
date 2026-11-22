from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from .speech_config import FIELDS, resolve_speech_config

AGENT_DIR = Path(__file__).resolve().parents[1]
PROJECT_ROOT = Path(__file__).resolve().parents[3]

DEFAULT_JWT_SECRET = "dev-only-internal-jwt-secret-change-me"


class Settings(BaseSettings):
    speech_config_origins: dict[str, str] = Field(default_factory=dict, exclude=True, repr=False)

    @classmethod
    def settings_customise_sources(cls, settings_cls, init_settings, env_settings, dotenv_settings, file_secret_settings):
        def speech_source():
            explicit = init_settings.init_kwargs
            paths = dotenv_settings.env_file
            if isinstance(paths, (str, Path)):
                paths = (paths,)
            values, origins = resolve_speech_config(
                paths, overrides=explicit,
                app_env=explicit.get("app_env"),
            )
            values["speech_config_origins"] = origins
            return values
        speech_keys = set(FIELDS) | {key for aliases in FIELDS.values() for key in aliases}
        def other_settings(source):
            return lambda: {key: value for key, value in source().items() if key not in speech_keys}
        return (init_settings, speech_source, other_settings(env_settings),
                other_settings(dotenv_settings), other_settings(file_secret_settings))
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
        default="deepseek-v4-pro",
        validation_alias=AliasChoices("DEEPSEEK_MODEL", "AGENT_DEEPSEEK_MODEL"),
    )
    deepseek_thinking: bool = Field(
        default=True,
        validation_alias=AliasChoices("DEEPSEEK_THINKING", "AGENT_DEEPSEEK_THINKING"),
    )
    deepseek_reasoning_effort: Literal["low", "high", "max"] = Field(
        default="high",
        validation_alias=AliasChoices("DEEPSEEK_REASONING_EFFORT", "AGENT_DEEPSEEK_REASONING_EFFORT"),
    )
    llm_timeout_seconds: float = 45.0
    llm_thinking_read_timeout_seconds: float = Field(default=120.0, gt=0)
    llm_max_output_tokens: int = 16384
    llm_resume_thinking_max_output_tokens: int = Field(default=8192, gt=0)
    llm_temperature: float = 0.2
    llm_trust_env: bool = False
    llm_provider_max_retries: int = 1

    # 语音能力（首版逐题整段录音）：密钥只允许存在于 Agent 进程环境变量。
    # speech_provider 为兼容旧配置的总开关；asr/tts 可按能力独立选择，具体项优先于总开关。
    speech_provider: Literal["auto", "fake", "qwen"] = "auto"
    speech_asr_provider: Literal["auto", "fake", "qwen"] = "auto"
    speech_tts_provider: Literal["auto", "fake", "qwen"] = "auto"
    speech_api_key: str | None = Field(
        default=None,
        repr=False,
        validation_alias=AliasChoices("AI_BAILIAN_API_KEY", "SPEECH_API_KEY"),
    )
    speech_base_url: str = Field(
        default="https://dashscope.aliyuncs.com",
        validation_alias=AliasChoices("AI_BAILIAN_BASE_URL", "SPEECH_BASE_URL"),
    )
    speech_asr_model: str = Field(
        default="qwen-audio-3.1-asr-flash",
        validation_alias=AliasChoices("SPEECH_ASR_MODEL", "AGENT_SPEECH_ASR_MODEL"),
    )
    speech_tts_model: str = Field(
        default="cosyvoice-v1",
        validation_alias=AliasChoices("SPEECH_TTS_MODEL", "AGENT_SPEECH_TTS_MODEL"),
    )
    speech_tts_voice: str = Field(
        default="longxiaochun",
        validation_alias=AliasChoices("SPEECH_TTS_VOICE", "AGENT_SPEECH_TTS_VOICE"),
    )
    speech_timeout_seconds: float = Field(default=8.0, gt=0)
    speech_tts_timeout_seconds: float = Field(default=6.0, gt=0)
    speech_trust_env: bool = False
    speech_max_bytes: int = Field(default=8 * 1024 * 1024, gt=0)
    speech_min_audio_ms: int = Field(default=2000, gt=0)
    speech_max_audio_ms: int = Field(default=180_000, gt=0)
    speech_max_tts_chars: int = Field(default=500, gt=0)
    speech_concurrency: int = Field(default=4, gt=0)
    speech_scope: str = "ai:speech"
    speech_vad_silence_ms: int = 400

    reasoning_display_enabled: bool = True
    reasoning_max_chars: int = 4000

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
    brave_search_api_key: str | None = Field(default=None, repr=False, validation_alias="BRAVE_SEARCH_API_KEY")
    job_search_company_sites: str = "{}"
    job_search_sources: str = "company_official,web,baidu,tencent,greenhouse"
    job_search_timeout_seconds: float = 12.0
    job_search_concurrency: int = 4
    job_search_per_source: int = 20
    job_search_max_total: int = 60
    job_search_max_jd_chars: int = 30000
    job_search_greenhouse_boards: str = "stripe:Stripe"
    job_search_lever_boards: str = ""
    job_search_user_agent: str = "AI-JOB-Agent/1.0 (+job-search; contact: local-agent)"
    job_search_trust_env: bool = False
    job_search_allow_http: bool = False
    # 动态搜索预算：集中配置，避免散落在来源实现中。
    job_search_max_queries_per_round: int = 12
    job_search_max_pages_per_round: int = 20
    job_search_max_detail_pages: int = 20
    job_search_max_page_fetch: int = 20
    job_search_max_api_calls: int = 60
    job_search_max_elapsed_seconds: float = 150.0
    job_search_max_rounds: int = 3
    job_search_target_results: int = 10
    job_search_min_results: int = 5
    job_search_min_new_urls: int = 3
    job_search_cache_ttl_seconds: int = 900
    job_search_provider_retries: int = 1
    job_search_provider_pages: int = 2
    job_search_provider_results: int = 20
    job_search_freshness_days: int = 30
    job_search_detail_concurrency: int = 4
    job_search_detail_verification: bool = True
    job_search_web_batch_pages: int = 3
    job_search_web_batch_chars: int = 9000
    job_search_web_page_chars: int = 2800
    job_search_extraction_timeout_seconds: float = 18.0
    job_search_extraction_retries: int = 1
    job_search_extraction_reasoning_effort: str = "low"
    job_search_extraction_max_output_tokens: int = 2400
    job_search_synonym_timeout_seconds: float = 5.0
    job_search_understanding_timeout_seconds: float = 20.0
    job_search_tencent_pages: int = 2
    job_search_company_official_queries: int = 4
    job_search_company_official_pages: int = 6
    job_search_labels_path: str | None = None

    github_search_enabled: bool = True
    github_token: str | None = Field(default=None, repr=False, validation_alias="GITHUB_TOKEN")
    github_search_timeout_seconds: float = 8.0
    github_search_per_page: int = 8
    github_search_cache_ttl_seconds: int = 3600
    github_search_trust_env: bool = False
    github_search_user_agent: str = "AI-JOB-Agent/1.0 (+learning-plan; contact: local-agent)"

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

    def _resolve_speech_provider(self, specific: str) -> str:
        if specific != "auto":
            return specific
        if self.speech_provider != "auto":
            return self.speech_provider
        return "qwen" if self.speech_api_key else "fake"

    @property
    def effective_asr_provider(self) -> str:
        return self._resolve_speech_provider(self.speech_asr_provider)

    @property
    def effective_tts_provider(self) -> str:
        return self._resolve_speech_provider(self.speech_tts_provider)

    @property
    def effective_speech_provider(self) -> str:
        """兼容旧字段：历史上该值同时代表 ASR/TTS，现在返回 ASR 的选择结果。"""
        return self.effective_asr_provider

    def validate_runtime_security(self) -> None:
        if self.app_env != "prod":
            return
        if self.auth_mode == "disabled":
            raise RuntimeError("AGENT_AUTH_MODE=disabled is forbidden in prod")
        if self.jwt_secret == DEFAULT_JWT_SECRET or len(self.jwt_secret) < 32:
            raise RuntimeError("AGENT_JWT_SECRET must be a strong secret in prod")
        if self.effective_llm_provider == "deepseek" and not self.deepseek_api_key:
            raise RuntimeError("DEEPSEEK_API_KEY is required when the deepseek provider is used")
        if self.effective_asr_provider == "fake":
            raise RuntimeError(
                "AGENT_SPEECH_ASR_PROVIDER=fake is forbidden in prod; configure qwen and AI_BAILIAN_API_KEY"
            )

        if self.effective_asr_provider == "qwen" and not self.speech_api_key:
            raise RuntimeError("SPEECH_API_KEY is required when the qwen ASR provider is used")
        if self.effective_tts_provider == "qwen" and not self.speech_api_key:
            raise RuntimeError("SPEECH_API_KEY is required when the qwen TTS provider is used")

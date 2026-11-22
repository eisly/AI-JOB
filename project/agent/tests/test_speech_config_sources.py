import pytest

from app.config import Settings
from app.speech_config import FIELDS, resolve_speech_config


@pytest.fixture(autouse=True)
def clean_speech_environment(monkeypatch):
    for aliases in FIELDS.values():
        for key in aliases:
            monkeypatch.delenv(key, raising=False)
    monkeypatch.delenv("AGENT_APP_ENV", raising=False)
    monkeypatch.delenv("AGENT_SPEECH_CONFIG_SOURCE", raising=False)


def test_old_environment_key_is_rejected_without_exposing_values(tmp_path, monkeypatch):
    path = tmp_path / ".env"
    path.write_text("SPEECH_API_KEY=local-test-value\n", encoding="utf-8")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "stale-test-value")
    with pytest.raises(RuntimeError) as error:
        Settings(_env_file=path)
    assert "DASHSCOPE_API_KEY" in str(error.value)
    assert "local-test-value" not in str(error.value)
    assert "stale-test-value" not in str(error.value)


def test_conflicting_aliases_are_rejected():
    with pytest.raises(RuntimeError, match="Conflicting"):
        resolve_speech_config((), {"SPEECH_API_KEY": "one", "AI_BAILIAN_API_KEY": "two"})


def test_matching_aliases_and_local_origin(tmp_path, monkeypatch):
    path = tmp_path / ".env"
    path.write_text('SPEECH_API_KEY="same-test-value" # comment\n', encoding="utf-8")
    monkeypatch.setenv("SPEECH_API_KEY", "same-test-value")
    settings = Settings(_env_file=path)
    assert settings.speech_api_key == "same-test-value"
    assert settings.speech_config_origins["speech_api_key"].startswith(str(path))
    assert "same-test-value" not in repr(settings)


def test_production_uses_only_environment(tmp_path, monkeypatch):
    path = tmp_path / ".env"
    path.write_text("SPEECH_API_KEY=unused-local-value\n", encoding="utf-8")
    monkeypatch.setenv("SPEECH_API_KEY", "deployed-test-value")
    settings = Settings(_env_file=path, app_env="prod")
    assert settings.speech_api_key == "deployed-test-value"
    assert settings.speech_config_origins["speech_api_key"] == "environment:SPEECH_API_KEY"


def test_explicit_test_override_can_disable_real_credentials(monkeypatch):
    monkeypatch.setenv("AI_BAILIAN_API_KEY", "unused-test-key")
    settings = Settings(_env_file=None, speech_api_key=None)
    assert settings.speech_api_key is None


def test_prod_does_not_fall_back_to_local_credentials(tmp_path):
    path = tmp_path / ".env"
    path.write_text("SPEECH_API_KEY=unused-local-value\n", encoding="utf-8")
    settings = Settings(_env_file=path, app_env="prod")
    assert settings.speech_api_key is None

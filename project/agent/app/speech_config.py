"""Shared, secret-safe speech configuration for the Agent and verification CLI."""
from pathlib import Path
import os
import shlex

FIELDS = {
    "speech_api_key": ("SPEECH_API_KEY", "AI_BAILIAN_API_KEY", "DASHSCOPE_API_KEY"),
    "speech_base_url": ("SPEECH_BASE_URL", "AI_BAILIAN_BASE_URL"),
    "speech_asr_model": ("SPEECH_ASR_MODEL", "AGENT_SPEECH_ASR_MODEL"),
    "speech_tts_model": ("SPEECH_TTS_MODEL", "AGENT_SPEECH_TTS_MODEL"),
    "speech_tts_voice": ("SPEECH_TTS_VOICE", "AGENT_SPEECH_TTS_VOICE"),
}


def read_env(path):
    values = {}
    path = Path(path)
    if path.is_file():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line.startswith("export "):
                line = line[7:].strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            # Preserve '#' inside quoted values, while accepting trailing comments.
            try:
                tokens = shlex.split(value, comments=True, posix=True)
            except ValueError:
                raise RuntimeError("Invalid dotenv syntax for " + name.strip()) from None
            values[name.strip().upper()] = " ".join(tokens)
    return values


def resolve_speech_config(env_files, environ=None, overrides=None, source=None, app_env=None):
    env = {k.upper(): v for k, v in (os.environ if environ is None else environ).items()}
    overrides = overrides or {}
    files = [(str(Path(p)), read_env(p)) for p in (env_files or ())]
    file_values = {}
    for _, values in files:
        file_values.update(values)
    mode = source or env.get("AGENT_SPEECH_CONFIG_SOURCE") or file_values.get("AGENT_SPEECH_CONFIG_SOURCE") or "auto"
    stage = app_env or env.get("AGENT_APP_ENV") or file_values.get("AGENT_APP_ENV") or "dev"
    if mode == "auto":
        mode = "env" if stage == "prod" else "dotenv"
    if mode not in ("env", "dotenv"):
        raise RuntimeError("AGENT_SPEECH_CONFIG_SOURCE must be auto, env or dotenv")
    result, origins = {}, {}
    for field, aliases in FIELDS.items():
        explicit = next((k for k in (field,) + aliases if k in overrides), None)
        if explicit is not None:
            result[field] = overrides[explicit]
            origins[field] = "explicit argument"
            continue
        candidates = []
        if mode == "dotenv":
            for path, values in files:
                candidates.extend((path + ":" + k, values[k]) for k in aliases if values.get(k))
        candidates.extend(("environment:" + k, env[k]) for k in aliases if env.get(k))
        if len({v for _, v in candidates}) > 1:
            raise RuntimeError("Conflicting speech configuration for %s; check %s" % (
                field, ", ".join(label for label, _ in candidates)))
        if candidates:
            origins[field], result[field] = candidates[0]
        else:
            result[field] = None if field == "speech_api_key" else {
                "speech_base_url": "https://dashscope.aliyuncs.com",
                "speech_asr_model": "qwen-audio-3.1-asr-flash",
                "speech_tts_model": "cosyvoice-v1", "speech_tts_voice": "longxiaochun",
            }[field]
            origins[field] = "default (unset)" if field == "speech_api_key" else "default"
    return result, origins

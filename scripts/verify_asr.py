#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""独立验证 DashScope / 百炼 qwen-audio-3.1-asr-flash 的语音识别链路。

只使用 Python 标准库，便于在任意受控环境中直接运行。
读取项目根目录 .env 中的 SPEECH_API_KEY / SPEECH_BASE_URL / SPEECH_ASR_MODEL。

用法：
  python scripts/verify_asr.py --demo
  python scripts/verify_asr.py --audio "D:\\recordings\\answer.webm"
  python scripts/verify_asr.py --url "https://example.com/a.wav"

注意：脚本不会打印 API Key，也不会回显音频 Base64 内容。
"""

import argparse
import base64
import json
import mimetypes
import os
import socket
import sys
import time
from pathlib import Path

try:
    import http.client
    import ssl
    from urllib.parse import urlsplit
except ImportError:  # pragma: no cover
    raise SystemExit("需要 Python 3.6+")

DEMO_AUDIO_URL = (
    "https://g-adoc.alcasset.com/media/maas_docs/sfm-cn/zh/audios/"
    "6a4b3c2d1e0f9093.wav"
)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "project" / "agent"))
from app.speech_config import resolve_speech_config

GENERATION_PATH = "/services/aigc/multimodal-generation/generation"

CONTAINER_MIME = {
    "webm": "audio/webm",
    "ogg": "audio/ogg",
    "opus": "audio/opus",
    "mp3": "audio/mpeg",
    "wav": "audio/wav",
    "m4a": "audio/mp4",
    "mp4": "audio/mp4",
    "amr": "audio/amr",
    "flac": "audio/flac",
    "aac": "audio/aac",
}


def detect_container(data):
    head = data[:16]
    if head.startswith(b"\x1aE\xdf\xa3"):
        return "webm"
    if head.startswith(b"OggS"):
        return "ogg"
    if head.startswith(b"RIFF") and data[8:12] == b"WAVE":
        return "wav"
    if head.startswith(b"ID3") or (len(data) > 1 and head[0] == 0xFF and (head[1] & 0xE0) == 0xE0):
        return "mp3"
    if data[4:8] == b"ftyp":
        brand = data[8:12]
        if brand in (b"M4A ", b"M4B ", b"M4P "):
            return "m4a"
        return "mp4"
    if head.startswith(b"#!AMR"):
        return "amr"
    if head.startswith(b"fLaC"):
        return "flac"
    return ""


def build_data_uri(data, container):
    mime = CONTAINER_MIME.get(container) or mimetypes.guess_type("x." + container)[0] or "application/octet-stream"
    encoded = base64.b64encode(data).decode("ascii")
    return "data:%s;base64,%s" % (mime, encoded), mime


def api_endpoint(base_url):
    base = base_url.rstrip("/")
    if base.endswith("/api/v1"):
        return base + GENERATION_PATH
    return base + "/api/v1" + GENERATION_PATH


def post_json(url, api_key, payload, timeout):
    parts = urlsplit(url)
    if parts.scheme != "https":
        raise SystemExit("仅支持 https 地址: %s" % url)
    host = parts.hostname
    port = parts.port or 443
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Authorization": "Bearer " + api_key,
        "Content-Type": "application/json",
        "User-Agent": "AI-JOB-verify-asr/1.0",
        "X-DashScope-SSE": "disable",
    }

    started = time.monotonic()
    conn = http.client.HTTPSConnection(host, port, timeout=timeout, context=ssl.create_default_context())
    connect_started = time.monotonic()
    conn.connect()
    connect_ms = (time.monotonic() - connect_started) * 1000
    send_started = time.monotonic()
    conn.request("POST", path, body=body, headers=headers)
    response = conn.getresponse()
    first_byte_ms = (time.monotonic() - send_started) * 1000
    raw = response.read()
    total_ms = (time.monotonic() - started) * 1000
    conn.close()
    return {
        "status": response.status,
        "reason": response.reason,
        "headers": dict(response.getheaders()),
        "body": raw,
        "connect_ms": connect_ms,
        "wait_ms": first_byte_ms,
        "total_ms": total_ms,
    }


def parse_text(payload):
    output = payload.get("output") or {}
    if isinstance(output.get("text"), str):
        return output["text"]
    choices = output.get("choices") or []
    if choices:
        message = choices[0].get("message") or {}
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            for item in content:
                if isinstance(item, dict) and isinstance(item.get("text"), str):
                    return item["text"]
    return ""


def run_once(args, env):
    project_root = Path(__file__).resolve().parents[1]
    values, origins = resolve_speech_config(
        (project_root / ".env", project_root / "project/agent/.env"),
        source=args.config_source,
    )
    api_key, key_source = values["speech_api_key"], origins["speech_api_key"]
    base_url, base_source = values["speech_base_url"], origins["speech_base_url"]
    model, model_source = values["speech_asr_model"], origins["speech_asr_model"]

    if not api_key:
        raise SystemExit("未找到 SPEECH_API_KEY，请先写入项目根目录 .env")

    if args.audio:
        audio_path = Path(args.audio).expanduser()
        if not audio_path.is_file():
            raise SystemExit("音频文件不存在: %s" % audio_path)
        data = audio_path.read_bytes()
        container = args.format or detect_container(data) or audio_path.suffix.lstrip(".").lower()
        data_uri, mime = build_data_uri(data, container)
        source_desc = "本地文件 %s (%d bytes, 容器=%s, MIME=%s)" % (audio_path, len(data), container, mime)
    else:
        url = args.url or DEMO_AUDIO_URL
        data_uri = url
        container = args.format or "wav"
        source_desc = "音频 URL %s" % url

    parameters = {"format": container}
    if args.sample_rate:
        parameters["sample_rate"] = str(args.sample_rate)

    payload = {
        "model": model,
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "input_audio", "input_audio": {"data": data_uri}}
                    ],
                }
            ]
        },
        "parameters": parameters,
    }

    endpoint = api_endpoint(base_url)
    print("模型      : %s（%s）" % (model, model_source))
    print("接入地址  : %s（%s）" % (endpoint, base_source))
    print("鉴权来源  : %s（不显示密钥）" % key_source)
    print("音频来源  : %s" % source_desc)
    print("请求参数  : %s" % json.dumps(parameters, ensure_ascii=False))
    print("-" * 60)

    result = post_json(endpoint, api_key, payload, args.timeout)
    print("HTTP      : %d %s" % (result["status"], result["reason"]))
    print("连接耗时  : %.0f ms" % result["connect_ms"])
    print("首字节耗时: %.0f ms" % result["wait_ms"])
    print("总耗时    : %.0f ms" % result["total_ms"])

    try:
        body = json.loads(result["body"].decode("utf-8"))
    except Exception:
        body = None

    if result["status"] != 200:
        print("错误响应  :")
        text = result["body"].decode("utf-8", "replace")
        print(text[:2000])
        if body:
            print("code      : %s" % body.get("code"))
            print("message   : %s" % body.get("message"))
        return 2

    if body is None:
        print("响应非 JSON，原文前 2000 字符：")
        print(result["body"].decode("utf-8", "replace")[:2000])
        return 3

    text = parse_text(body)
    print("request_id: %s" % (body.get("request_id") or result["headers"].get("x-dashscope-request-id") or ""))
    usage = body.get("usage") or {}
    if usage:
        print("usage     : %s" % json.dumps(usage, ensure_ascii=False))
    print("识别文本  : %s" % (text if text else "<空>"))
    print("文本长度  : %d" % len(text))
    return 0 if text else 4


def main():
    parser = argparse.ArgumentParser(description="验证 qwen-audio-3.1-asr-flash 语音识别")
    parser.add_argument("--audio", help="本地录音文件路径（推荐：直接验证浏览器录音格式）")
    parser.add_argument("--url", help="公网可访问的音频 URL（默认使用官方示例音频）")
    parser.add_argument("--demo", action="store_true", help="使用官方示例音频验证鉴权与模型权限")
    parser.add_argument("--format", help="覆盖容器格式，如 webm/mp3/wav/m4a/ogg")
    parser.add_argument("--sample-rate", type=int, help="音频采样率，默认不传由服务端处理")
    parser.add_argument("--timeout", type=float, default=60.0, help="HTTP 超时秒数，默认 60")
    parser.add_argument("--runs", type=int, default=1, help="重复次数，用于观察冷/热启动耗时")
    parser.add_argument("--config-source", choices=("auto", "dotenv", "env"), default=None, help="与 Agent 共用配置规则；冲突时拒绝调用")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[1]
    env = None

    exit_code = 0
    for index in range(max(1, args.runs)):
        if args.runs > 1:
            print("=== 第 %d/%d 次 ===" % (index + 1, args.runs))
        try:
            code = run_once(args, env)
        except Exception as exc:
            print("执行失败: %s: %s" % (type(exc).__name__, exc))
            code = 1
        exit_code = code
        if index + 1 < args.runs:
            time.sleep(0.5)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
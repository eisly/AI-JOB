"""图片 PDF 的 OCR 文本提取。

流程：
1. pypdf 逐页提取文字层；有文字的页保留原生文本。
2. 无文字的页用 PyMuPDF 渲染为 PNG，再调用本机 Tesseract 5（chi_sim+eng）识别。
3. 输出统一的带页码标记文本（``[page:N]``），供后续解析与诊断复用。

约束：
- 页数与总时长有上限（``ocr_max_pages`` / ``ocr_max_seconds``）。
- Tesseract 未安装或语言包缺失时返回明确的不可用原因，由调用方决定失败或降级。
"""

from __future__ import annotations

import logging
import os
import re
import subprocess
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

from .config import Settings
from .errors import HandlerError

logger = logging.getLogger("agent.ocr")

_PAGE_MARKER = re.compile(r"^\[page:(\d+)\]$", re.MULTILINE)

_COMMON_TESSERACT_PATHS = (
    r"C:\Program Files\Tesseract-OCR\tesseract.exe",
    r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
    "/usr/bin/tesseract",
    "/usr/local/bin/tesseract",
    "/opt/homebrew/bin/tesseract",
)


@dataclass
class OcrOutcome:
    text: str
    native_pages: list[int] = field(default_factory=list)
    ocr_pages: list[int] = field(default_factory=list)
    empty_pages: list[int] = field(default_factory=list)
    duration_ms: int = 0


def find_tesseract(settings: Settings) -> str | None:
    candidates: list[str] = []
    if settings.tesseract_cmd:
        candidates.append(settings.tesseract_cmd)
    import shutil

    which = shutil.which("tesseract")
    if which:
        candidates.append(which)
    candidates.extend(_COMMON_TESSERACT_PATHS)
    for candidate in candidates:
        try:
            if Path(candidate).is_file():
                return candidate
        except OSError:
            continue
    return None


def _tesseract_base_args(tesseract_path: str, settings: Settings) -> list[str]:
    args = [tesseract_path]
    if settings.tessdata_dir:
        args.extend(["--tessdata-dir", settings.tessdata_dir])
    return args


def language_pack_error(tesseract_path: str, langs: str, settings: Settings | None = None) -> str | None:
    """返回语言包缺失说明；None 表示所需语言包齐全。"""
    required = [item.strip() for item in langs.split("+") if item.strip()]
    base = _tesseract_base_args(tesseract_path, settings) if settings else [tesseract_path]
    try:
        proc = subprocess.run(
            [*base, "--list-langs"],
            capture_output=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return f"无法运行 Tesseract（{type(exc).__name__}）"
    output = (proc.stdout + proc.stderr).decode("utf-8", "replace").lower()
    missing = [item for item in required if item.lower() not in output]
    if missing:
        return f"Tesseract 缺少语言包: {', '.join(missing)}"
    return None


def tesseract_unavailable_reason(settings: Settings) -> str | None:
    """Tesseract 可用性检查；None 表示可用。结果做进程级缓存。"""
    cached = getattr(tesseract_unavailable_reason, "_cache", None)
    if cached is not None and cached[0] == (settings.tesseract_cmd, settings.tessdata_dir, settings.ocr_langs):
        return cached[1]
    path = find_tesseract(settings)
    if path is None:
        reason = "未安装 Tesseract OCR 或未通过 TESSERACT_CMD 配置可执行文件路径"
    else:
        reason = language_pack_error(path, settings.ocr_langs, settings)
    tesseract_unavailable_reason._cache = (
        settings.tesseract_cmd, settings.tessdata_dir, settings.ocr_langs), reason
    return reason


def recognize_png(image_bytes: bytes, tesseract_path: str, settings: Settings) -> str:
    fd, tmp_path = tempfile.mkstemp(suffix=".png", prefix="aijob-ocr-")
    try:
        with os.fdopen(fd, "wb") as tmp:
            tmp.write(image_bytes)
        args = _tesseract_base_args(tesseract_path, settings)
        args.extend([tmp_path, "stdout", "-l", settings.ocr_langs, "--psm", "4"])
        proc = subprocess.run(
            args,
            capture_output=True,
            timeout=settings.ocr_page_timeout_seconds,
            check=False,
        )
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace").strip()[:200]
        raise HandlerError("OCR_ENGINE_FAILED", f"OCR 识别失败: {detail or proc.returncode}", retryable=True)
    return proc.stdout.decode("utf-8", "replace")


def clean_ocr_text(value: str) -> str:
    """清理 OCR 输出：统一换行、去行尾空白、压缩连续空行。"""
    text = value.replace("\r\n", "\n").replace("\r", "\n")
    lines: list[str] = []
    previous_blank = False
    for raw_line in text.split("\n"):
        line = raw_line.strip()
        if not line:
            if previous_blank:
                continue
            previous_blank = True
        else:
            previous_blank = False
        lines.append(line)
    return "\n".join(lines).strip()


def strip_page_markers(text: str) -> str:
    return _PAGE_MARKER.sub("", text).strip()


def _render_page_png(page, settings: Settings) -> bytes:
    pixmap = page.get_pixmap(dpi=settings.ocr_dpi)
    return pixmap.tobytes("png")


def merge_pdf_text(data: bytes, settings: Settings, progress=None) -> OcrOutcome:
    """合并 pypdf 原生文本与 OCR 识别结果，返回统一带页码标记的文本。

    ``progress`` 为可选回调 ``(done_pages, total_pages)``，用于上报任务进度。
    """
    from pypdf import PdfReader

    reader = PdfReader(__import__("io").BytesIO(data))
    native_texts: list[str] = [clean_ocr_text(page.extract_text() or "") for page in reader.pages]
    total = len(native_texts)
    if total == 0:
        raise HandlerError("FILE_UNREADABLE", "PDF 没有可处理的页面", retryable=False)
    if total > settings.ocr_max_pages:
        raise HandlerError(
            "OCR_PAGES_EXCEEDED",
            f"PDF 共 {total} 页，超过 OCR 处理上限（{settings.ocr_max_pages} 页）",
            retryable=False,
        )
    if not any(native_texts):
        # 纯图片 PDF：必须依赖 OCR，不可用时给出具体原因。
        reason = tesseract_unavailable_reason(settings)
        if reason:
            raise HandlerError("OCR_NOT_CONFIGURED", f"扫描版 PDF 需要 OCR，但 {reason}", retryable=False)

    outcome = OcrOutcome(text="")
    deadline = time.monotonic() + settings.ocr_max_seconds
    parts: list[str] = []
    need_ocr = [index for index, value in enumerate(native_texts, start=1) if not value]
    if need_ocr:
        import fitz

        document = fitz.open(stream=data, filetype="pdf")
        try:
            for index in need_ocr:
                if time.monotonic() > deadline:
                    raise HandlerError(
                        "OCR_TIMEOUT",
                        f"OCR 处理超过 {int(settings.ocr_max_seconds)} 秒限制",
                        retryable=True,
                    )
                page = document[index - 1]
                try:
                    recognized = clean_ocr_text(
                        recognize_png(_render_page_png(page, settings), _require_tesseract(settings), settings)
                    )
                except HandlerError:
                    if any(native_texts):
                        # 混合 PDF 的 OCR 页失败时保留其余原生页，避免整份文件不可用。
                        logger.warning("ocr page failed page=%s, keep native pages", index)
                        outcome.empty_pages.append(index)
                        continue
                    raise
                native_texts[index - 1] = recognized
                if recognized:
                    outcome.ocr_pages.append(index)
                else:
                    outcome.empty_pages.append(index)
                if progress:
                    progress(index, total)
        finally:
            document.close()

    for index, value in enumerate(native_texts, start=1):
        if value:
            if index not in outcome.ocr_pages:
                outcome.native_pages.append(index)
            parts.append(f"[page:{index}]\n{value}")
        else:
            parts.append(f"[page:{index}]")
    outcome.text = "\n".join(parts)
    return outcome


def _require_tesseract(settings: Settings) -> str:
    path = find_tesseract(settings)
    if path is None:
        raise HandlerError("OCR_NOT_CONFIGURED", "未安装 Tesseract OCR 或未配置可执行文件路径", retryable=False)
    return path

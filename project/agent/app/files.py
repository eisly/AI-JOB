from __future__ import annotations

import hashlib
import io
from dataclasses import dataclass
from urllib.parse import urlsplit

import httpx

from .config import Settings
from .errors import HandlerError, too_large, validation_error
from .schemas import DocumentRef

ALLOWED_MIME_TYPES = {
    "application/pdf": "pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
    "text/plain": "txt",
    "text/markdown": "md",
    "text/x-markdown": "md",
}

EXTENSION_TYPES = {
    ".pdf": "pdf",
    ".docx": "docx",
    ".txt": "txt",
    ".md": "md",
}

DISALLOWED_EXTENSIONS = {
    ".doc",
    ".docm",
    ".xls",
    ".xlsx",
    ".ppt",
    ".pptx",
    ".exe",
    ".zip",
    ".rar",
    ".7z",
    ".rtf",
    ".html",
    ".htm",
}


@dataclass
class ExtractedDocument:
    text: str
    text_hash: str
    source: str


def _normalize_text(value: str) -> str:
    return value.replace("\r\n", "\n").replace("\r", "\n").strip()


class FileFetcher:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def validate(self, document: DocumentRef) -> None:
        """Reject invalid document references before a run is accepted."""
        self._check_size(document.size_bytes)
        if document.text is not None:
            text = _normalize_text(document.text)
            self._check_text(text)
            return
        if not document.download_url:
            raise validation_error("document 必须提供 downloadUrl 或 text")
        self._validate_url(document.download_url)
        self._kind(document)

    async def extract(self, document: DocumentRef) -> ExtractedDocument:
        self.validate(document)
        if document.text is not None:
            text = _normalize_text(document.text)
            self._check_text(text)
            return ExtractedDocument(text=text, text_hash=self._hash(text), source="inline-text")

        url = self._validate_url(document.download_url or "")
        data = await self._download(url)
        self._check_size(len(data))
        kind = self._kind(document)
        text = self._decode(data, kind)
        text = _normalize_text(text)
        if not text:
            raise HandlerError("FILE_UNREADABLE", "未能从文件中提取到文本", retryable=False)
        self._check_text(text)
        return ExtractedDocument(text=text, text_hash=self._hash(text), source="download")

    def _check_size(self, size: int) -> None:
        if size > self._settings.max_document_bytes:
            raise too_large(f"简历文件不得超过 {self._settings.max_document_bytes} 字节")

    def _check_text(self, text: str) -> None:
        if len(text) > self._settings.max_extracted_chars:
            raise HandlerError(
                "FILE_TEXT_TOO_LONG",
                f"提取文本超过 {self._settings.max_extracted_chars} 字符限制",
                retryable=False,
            )

    @staticmethod
    def _hash(text: str) -> str:
        return hashlib.sha256(text.encode("utf-8")).hexdigest()

    def _validate_url(self, url: str) -> str:
        parsed = urlsplit(url)
        if parsed.scheme.lower() not in self._settings.file_schemes:
            raise validation_error("文件 URL 协议不在白名单内")
        if parsed.username or parsed.password:
            raise validation_error("文件 URL 不得包含用户凭据")
        if not parsed.hostname:
            raise validation_error("文件 URL 缺少主机名")
        netloc = parsed.netloc.lower()
        hostname = parsed.hostname.lower()
        allowed = self._settings.file_hosts
        if netloc not in allowed and hostname not in allowed:
            raise validation_error("文件 URL 主机不在白名单内")
        return url

    async def _download(self, url: str) -> bytes:
        timeout = httpx.Timeout(self._settings.file_download_timeout_seconds)
        limit = self._settings.max_document_bytes
        chunks: list[bytes] = []
        total = 0
        try:
            async with httpx.AsyncClient(
                follow_redirects=False,
                timeout=timeout,
                trust_env=False,
                headers={"Accept": "application/pdf, application/octet-stream, text/plain"},
            ) as client:
                async with client.stream("GET", url) as response:
                    if response.status_code >= 400:
                        raise HandlerError(
                            "FILE_DOWNLOAD_FAILED",
                            f"文件下载失败，状态码 {response.status_code}",
                            retryable=response.status_code >= 500,
                        )
                    declared = response.headers.get("content-length")
                    if declared and int(declared) > limit:
                        raise too_large(f"简历文件不得超过 {limit} 字节")
                    async for chunk in response.aiter_bytes():
                        total += len(chunk)
                        if total > limit:
                            raise too_large(f"简历文件不得超过 {limit} 字节")
                        chunks.append(chunk)
        except httpx.HTTPError as exc:
            raise HandlerError("FILE_DOWNLOAD_FAILED", f"文件下载失败: {type(exc).__name__}", retryable=True) from exc
        return b"".join(chunks)

    @staticmethod
    def _kind(document: DocumentRef) -> str:
        mime = document.mime_type.split(";")[0].strip().lower()
        if mime in ALLOWED_MIME_TYPES:
            return ALLOWED_MIME_TYPES[mime]
        name = document.file_name.lower()
        for extension in DISALLOWED_EXTENSIONS:
            if name.endswith(extension):
                raise validation_error(f"不支持的文件类型: {extension}")
        for extension, kind in EXTENSION_TYPES.items():
            if name.endswith(extension):
                return kind
        raise validation_error("不支持的文件类型，仅支持 PDF、DOCX、TXT、Markdown")

    @staticmethod
    def _decode(data: bytes, kind: str) -> str:
        if kind == "pdf":
            from pypdf import PdfReader

            try:
                reader = PdfReader(io.BytesIO(data))
                pages = []
                for index, page in enumerate(reader.pages, start=1):
                    pages.append(f"[page:{index}]\n{page.extract_text() or ''}")
                return "\n".join(pages)
            except Exception as exc:
                raise HandlerError("FILE_UNREADABLE", f"PDF 解析失败: {type(exc).__name__}", retryable=False) from exc
        if kind == "docx":
            from docx import Document as DocxDocument

            try:
                document = DocxDocument(io.BytesIO(data))
                return "\n".join(paragraph.text for paragraph in document.paragraphs if paragraph.text)
            except Exception as exc:
                raise HandlerError("FILE_UNREADABLE", f"DOCX 解析失败: {type(exc).__name__}", retryable=False) from exc
        for encoding in ("utf-8", "gb18030"):
            try:
                return data.decode(encoding)
            except UnicodeDecodeError:
                continue
        return data.decode("utf-8", errors="replace")
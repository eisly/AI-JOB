from __future__ import annotations

import hashlib
import io

import httpx
import pytest

from app.config import Settings
from app.errors import ApiError, HandlerError
from app.files import FileFetcher
from app.schemas import DocumentRef

from tests.factories import RESUME_VERSION_ID


def _settings(**overrides) -> Settings:
    # 显式固定文件白名单与 OCR 路径，避免本机 .env 的运行配置影响测试环境
    base = dict(
        app_env="test",
        callback_enabled=False,
        llm_provider="fake",
        allowed_file_hosts="files.example.invalid",
        file_allowed_schemes="https",
        tesseract_cmd=None,
        tessdata_dir=None,
    )
    base.update(overrides)
    return Settings(**base)


def _document(**overrides) -> DocumentRef:
    data = dict(
        versionId=RESUME_VERSION_ID,
        fileName="cv.txt",
        mimeType="text/plain",
        sizeBytes=10,
        text="你好，世界",
    )
    data.update(overrides)
    return DocumentRef.model_validate(data)


def test_validate_and_extract_inline_text():
    fetcher = FileFetcher(_settings())
    document = _document(text="  你好\r\n世界  ")
    fetcher.validate(document)
    extracted = __import__("asyncio").run(_extract(fetcher, document))
    assert extracted.text == "你好\n世界"
    assert extracted.source == "inline-text"
    assert extracted.text_hash == hashlib.sha256("你好\n世界".encode("utf-8")).hexdigest()


async def _extract(fetcher: FileFetcher, document: DocumentRef):
    return await fetcher.extract(document)


def test_validate_rejects_missing_source():
    fetcher = FileFetcher(_settings())
    document = DocumentRef.model_construct(
        version_id=RESUME_VERSION_ID,
        file_name="cv.txt",
        mime_type="text/plain",
        size_bytes=1,
        download_url=None,
        text=None,
    )
    with pytest.raises(ApiError, match="downloadUrl 或 text"):
        fetcher.validate(document)


def test_validate_rejects_oversize_document():
    fetcher = FileFetcher(_settings())
    document = _document(sizeBytes=11 * 1024 * 1024)
    with pytest.raises(ApiError) as caught:
        fetcher.validate(document)
    assert caught.value.status_code == 413


def test_extract_rejects_overlong_text():
    fetcher = FileFetcher(_settings(max_extracted_chars=5))
    with pytest.raises(HandlerError) as caught:
        __import__("asyncio").run(_extract(fetcher, _document(text="超过五个字符的文本")))
    assert caught.value.code == "FILE_TEXT_TOO_LONG"
    assert caught.value.retryable is False


@pytest.mark.parametrize(
    "url,message",
    [
        ("http://files.example.invalid/cv.txt", "协议不在白名单"),
        ("https://user:pass@files.example.invalid/cv.txt", "不得包含用户凭据"),
        ("https:///cv.txt", "缺少主机名"),
        ("https://evil.example.com/cv.txt", "主机不在白名单"),
    ],
)
def test_validate_url_whitelist(url, message):
    fetcher = FileFetcher(_settings())
    document = _document(text=None, downloadUrl=url)
    with pytest.raises(ApiError, match=message):
        fetcher.validate(document)


def test_validate_url_accepts_whitelisted_host():
    fetcher = FileFetcher(_settings())
    document = _document(text=None, downloadUrl="https://files.example.invalid/cv.txt")
    fetcher.validate(document)


def test_kind_rejects_disallowed_and_unknown_extensions():
    fetcher = FileFetcher(_settings())
    with pytest.raises(ApiError, match="不支持的文件类型: .doc"):
        fetcher.validate(_document(fileName="cv.doc", mimeType="application/msword", text=None,
                                   downloadUrl="https://files.example.invalid/cv.doc"))
    with pytest.raises(ApiError, match="仅支持 PDF、DOCX、TXT、Markdown"):
        fetcher.validate(_document(fileName="cv.xyz", mimeType="application/octet-stream", text=None,
                                   downloadUrl="https://files.example.invalid/cv.xyz"))


def test_kind_accepts_docx_mime_and_markdown_extension():
    fetcher = FileFetcher(_settings())
    fetcher.validate(_document(
        fileName="cv.bin",
        mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        text=None,
        downloadUrl="https://files.example.invalid/cv.bin",
    ))
    fetcher.validate(_document(fileName="cv.md", mimeType="application/octet-stream", text=None,
                               downloadUrl="https://files.example.invalid/cv.md"))


def test_decode_text_supports_utf8_gb18030_and_replacement():
    assert FileFetcher._decode("中文".encode("utf-8"), "txt") == "中文"
    assert FileFetcher._decode("中文".encode("gb18030"), "txt") == "中文"
    assert isinstance(FileFetcher._decode(b"\xff\xff\xff\xff\xff", "txt"), str)


def test_decode_docx_extracts_paragraphs():
    from docx import Document

    buffer = io.BytesIO()
    document = Document()
    document.add_paragraph("第一段")
    document.add_paragraph("第二段")
    document.save(buffer)
    text = FileFetcher._decode(buffer.getvalue(), "docx")
    assert "第一段" in text
    assert "第二段" in text


def test_decode_pdf_marks_pages_and_wraps_errors():
    from pypdf import PdfWriter

    buffer = io.BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=72, height=72)
    writer.write(buffer)
    assert "[page:1]" in FileFetcher._decode(buffer.getvalue(), "pdf")
    with pytest.raises(HandlerError, match="PDF 解析失败"):
        FileFetcher._decode(b"not-a-pdf", "pdf")
    with pytest.raises(HandlerError, match="DOCX 解析失败"):
        FileFetcher._decode(b"not-a-docx", "docx")


class _FakeStream:
    def __init__(self, chunks, status_code=200, headers=None, error=None):
        self._chunks = chunks
        self.status_code = status_code
        self.headers = headers or {}
        self._error = error

    async def __aenter__(self):
        if self._error is not None:
            raise self._error
        return self

    async def __aexit__(self, *args):
        return False

    async def aiter_bytes(self):
        for chunk in self._chunks:
            yield chunk


class _FakeClient:
    def __init__(self, stream):
        self._stream = stream

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    def stream(self, method, url):
        return self._stream


def _patch_download(monkeypatch, stream):
    monkeypatch.setattr("app.files.httpx.AsyncClient", lambda **kwargs: _FakeClient(stream))


def _remote_document(**overrides):
    return _document(text=None, downloadUrl="https://files.example.invalid/cv.txt", **overrides)


async def test_download_and_extract_remote_text(monkeypatch):
    _patch_download(monkeypatch, _FakeStream([b"hello ", "世界".encode("utf-8")]))
    fetcher = FileFetcher(_settings())
    extracted = await fetcher.extract(_remote_document())
    assert extracted.text == "hello 世界"
    assert extracted.source == "download"


async def test_download_http_status_controls_retryable(monkeypatch):
    fetcher = FileFetcher(_settings())
    _patch_download(monkeypatch, _FakeStream([], status_code=500))
    with pytest.raises(HandlerError) as caught:
        await fetcher.extract(_remote_document())
    assert caught.value.code == "FILE_DOWNLOAD_FAILED"
    assert caught.value.retryable is True

    _patch_download(monkeypatch, _FakeStream([], status_code=404))
    with pytest.raises(HandlerError) as caught:
        await fetcher.extract(_remote_document())
    assert caught.value.retryable is False


async def test_download_rejects_declared_and_streamed_oversize(monkeypatch):
    fetcher = FileFetcher(_settings(max_document_bytes=4))
    _patch_download(monkeypatch, _FakeStream([b"12"], headers={"content-length": "100"}))
    with pytest.raises(ApiError) as caught:
        await fetcher.extract(_remote_document(sizeBytes=1))
    assert caught.value.status_code == 413

    _patch_download(monkeypatch, _FakeStream([b"123", b"456"]))
    with pytest.raises(ApiError):
        await fetcher.extract(_remote_document(sizeBytes=1))


async def test_download_wraps_network_error(monkeypatch):
    fetcher = FileFetcher(_settings())
    _patch_download(monkeypatch, _FakeStream([], error=httpx.ConnectError("down")))
    with pytest.raises(HandlerError) as caught:
        await fetcher.extract(_remote_document())
    assert caught.value.code == "FILE_DOWNLOAD_FAILED"
    assert caught.value.retryable is True


async def test_download_rejects_unreadable_empty_document(monkeypatch):
    _patch_download(monkeypatch, _FakeStream([b"   "]))
    fetcher = FileFetcher(_settings())
    with pytest.raises(HandlerError) as caught:
        await fetcher.extract(_remote_document())
    assert caught.value.code == "FILE_UNREADABLE"
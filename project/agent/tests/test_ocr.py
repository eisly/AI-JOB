"""OCR 模块测试：文本清理、页数限制、混合保留、纯图片 PDF 的 Tesseract 不可用拒绝。"""
from __future__ import annotations

import pytest

from app.config import Settings
from app.errors import HandlerError
from app import ocr


def _settings(**overrides):
    values = {
        "tesseract_cmd": None,
        "tessdata_dir": None,
        "ocr_langs": "chi_sim+eng",
        "ocr_dpi": 150,
        "ocr_max_pages": 30,
        "ocr_max_seconds": 60.0,
        "ocr_page_timeout_seconds": 10.0,
    }
    values.update(overrides)
    return Settings(**values)


def _pdf_with_pages(texts: list[str | None]) -> bytes:
    """构造合成 PDF：texts[i] 为 None 表示图片页（无文字层）。"""
    import fitz

    document = fitz.open()
    for text in texts:
        page = document.new_page(width=595, height=842)
        if text:
            page.insert_text((72, 96), text, fontname="helv", fontsize=12)
    data = document.tobytes()
    document.close()
    return data


def test_clean_ocr_text_normalizes_lines():
    assert ocr.clean_ocr_text("a \r\n\r\n\r\n b \r c ") == "a\n\nb\nc"
    assert ocr.clean_ocr_text("  \n\n  ") == ""


def test_strip_page_markers():
    assert ocr.strip_page_markers("[page:1]\n内容\n[page:2]") == "内容"
    assert ocr.strip_page_markers("") == ""


def test_merge_pdf_text_keeps_native_pages(monkeypatch):
    data = _pdf_with_pages(["native text page", None, "another native page"])
    monkeypatch.setattr(ocr, "recognize_png", lambda *args, **kwargs: "ocr 识别结果")
    monkeypatch.setattr(ocr, "_require_tesseract", lambda settings: "tesseract")
    outcome = ocr.merge_pdf_text(data, _settings())
    assert outcome.native_pages == [1, 3]
    assert outcome.ocr_pages == [2]
    assert "[page:1]\nnative text page" in outcome.text
    assert "[page:2]\nocr 识别结果" in outcome.text


def test_merge_pdf_text_rejects_too_many_pages():
    data = _pdf_with_pages([None] * 4)
    with pytest.raises(HandlerError, match="超过 OCR 处理上限"):
        ocr.merge_pdf_text(data, _settings(ocr_max_pages=3))


def test_merge_pdf_text_pure_image_without_tesseract(monkeypatch):
    data = _pdf_with_pages([None, None])
    monkeypatch.setattr(ocr, "tesseract_unavailable_reason", lambda settings: "未安装 Tesseract OCR")
    with pytest.raises(HandlerError, match="扫描版 PDF 需要 OCR"):
        ocr.merge_pdf_text(data, _settings())


def test_merge_pdf_text_mixed_page_ocr_failure_keeps_native(monkeypatch):
    """混合 PDF 的单页 OCR 失败时保留其余原生页，不整体失败。"""
    data = _pdf_with_pages(["native page", None, "native again"])

    def fail(*args, **kwargs):
        raise HandlerError("OCR_ENGINE_FAILED", "boom", retryable=True)

    monkeypatch.setattr(ocr, "recognize_png", fail)
    monkeypatch.setattr(ocr, "_require_tesseract", lambda settings: "tesseract")
    outcome = ocr.merge_pdf_text(data, _settings())
    assert outcome.native_pages == [1, 3]
    assert outcome.empty_pages == [2]


def test_language_pack_error_reports_missing():
    # 直接检查函数对空语言包列表的处理逻辑
    assert ocr.language_pack_error("nonexistent.exe", "chi_sim") is not None


def test_tesseract_unavailable_reason_cached(monkeypatch):
    calls = {"n": 0}

    def fake_find(settings):
        calls["n"] += 1
        return None

    monkeypatch.setattr(ocr, "find_tesseract", fake_find)
    settings = _settings()
    assert ocr.tesseract_unavailable_reason(settings) is not None
    assert ocr.tesseract_unavailable_reason(settings) is not None
    assert calls["n"] == 1

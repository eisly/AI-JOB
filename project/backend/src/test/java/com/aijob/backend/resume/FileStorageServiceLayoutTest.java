package com.aijob.backend.resume;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.aijob.backend.common.JsonService;

import tools.jackson.databind.json.JsonMapper;

class FileStorageServiceLayoutTest {

    @TempDir
    Path storage;

    private FileStorageService service;

    @BeforeEach
    void setUp() {
        service = new FileStorageService(storage.toString(), "test-secret",
                new JsonService(JsonMapper.builder().build()));
    }

    @Test
    void pdfUsesPagePositionOrderInsteadOfContentStreamOrder() throws IOException {
        FileStorageService.StoredFile stored = service.store("user", "version",
                new MockMultipartFile("file", "cv.pdf", "application/pdf", pdfWithOutOfOrderLines()));

        String text = stored.text();
        assertTrue(text.indexOf("FIRST SECTION") < text.indexOf("SECOND SECTION"),
                "应按页面位置恢复阅读顺序，实际文本=" + text);
        assertTrue(text.contains("2024-06-30"));
        assertTrue(text.contains("30%"));
        assertEquals(FileStorageService.PDF_LAYOUT_VERSION, stored.layoutVersion());
    }

    @Test
    void pdfKeepsPageBoundariesInOrder() throws IOException {
        FileStorageService.StoredFile stored = service.store("user", "version",
                new MockMultipartFile("file", "cv.pdf", "application/pdf", twoPagePdf()));

        String text = stored.text();
        int first = text.indexOf("[page:1]");
        int second = text.indexOf("[page:2]");
        assertTrue(first >= 0 && second > first, "页标记应按页序出现，实际文本=" + text);
        assertTrue(text.indexOf("PAGE ONE HEADER") < text.indexOf("PAGE TWO HEADER"));
    }

    @Test
    void docxPreservesParagraphOrderAndCleansDecoration() throws IOException {
        FileStorageService.StoredFile stored = service.store("user", "version",
                new MockMultipartFile("file", "cv.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx()));

        String text = stored.text();
        assertTrue(text.indexOf("教育背景") < text.indexOf("2024-06-30 完成 30% 优化"));
        assertTrue(text.indexOf("2024-06-30 完成 30% 优化") < text.indexOf("项目经历"));
        assertTrue(text.contains("2024-06-30"));
        assertTrue(text.contains("30%"));
        assertFalse(text.contains("\u200B"), "零宽字符应被清理");
        assertFalse(text.contains("\uFFFD"), "替换字符应被清理");
        assertEquals(FileStorageService.DOCX_LAYOUT_VERSION, stored.layoutVersion());
    }

    @Test
    void reExtractUsesOriginalFileAndFallsBackWhenMissing() throws IOException {
        FileStorageService.StoredFile stored = service.store("user", "version",
                new MockMultipartFile("file", "cv.pdf", "application/pdf", pdfWithOutOfOrderLines()));

        FileStorageService.ExtractedText extracted = service.reExtract("user", stored.key(), stored.mimeType());
        assertNotNull(extracted);
        assertTrue(extracted.text().indexOf("FIRST SECTION") < extracted.text().indexOf("SECOND SECTION"));

        Files.delete(storage.resolve(stored.key()));
        assertNull(service.reExtract("user", stored.key(), stored.mimeType()));
    }

    @Test
    void cleanLayoutTextDropsZeroWidthAndControlCharactersButKeepsDatesAndNumbers() {
        String cleaned = FileStorageService.cleanLayoutText(
                "教育背景\u200B\n2024-06-30\uFFFD 完成 30% 优化\u0000\n\n\n项目经历");

        assertEquals("教育背景\n2024-06-30 完成 30% 优化\n\n项目经历", cleaned);
    }

    private byte[] pdfWithOutOfOrderLines() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                text(stream, font, 600, "SECOND SECTION 2024-06-30");
                text(stream, font, 700, "FIRST SECTION 30%");
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] twoPagePdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage first = new PDPage(PDRectangle.A4);
            PDPage second = new PDPage(PDRectangle.A4);
            document.addPage(first);
            document.addPage(second);
            try (PDPageContentStream stream = new PDPageContentStream(document, first)) {
                text(stream, font, 700, "PAGE ONE HEADER");
            }
            try (PDPageContentStream stream = new PDPageContentStream(document, second)) {
                text(stream, font, 700, "PAGE TWO HEADER");
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private void text(PDPageContentStream stream, PDType1Font font, float y, String value) throws IOException {
        stream.beginText();
        stream.setFont(font, 12);
        stream.newLineAtOffset(72, y);
        stream.showText(value);
        stream.endText();
    }

    private byte[] docx() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("教育背景");
            document.createParagraph().createRun().setText("2024-06-30\uFFFD 完成 30% 优化\u200B");
            document.createParagraph().createRun().setText("项目经历");
            document.write(output);
            return output.toByteArray();
        }
    }
}
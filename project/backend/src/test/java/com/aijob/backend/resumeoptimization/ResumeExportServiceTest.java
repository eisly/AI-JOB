package com.aijob.backend.resumeoptimization;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ResumeExportServiceTest {

    private final ResumeExportService service = new ResumeExportService("C:\\Windows\\Fonts\\simhei.ttf");
    private final List<Map<String, Object>> sections = List.of(
            new java.util.LinkedHashMap<>(Map.of("sectionKey", "experience", "title", "项目经历", "content", "使用 Java 与 Redis 完成缓存设计。")),
            new java.util.LinkedHashMap<>(Map.of("sectionKey", "education", "title", "教育经历", "content", "计算机科学与技术，本科。")));

    @Test
    void generatesReadablePdfWithChineseAcceptedContent() throws Exception {
        byte[] bytes = service.pdf("优化简历", sections);

        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("优化简历"));
            assertTrue(text.contains("项目经历"));
            assertTrue(text.contains("使用 Java 与 Redis 完成缓存设计"));
        }
    }

    @Test
    void generatesRealDocxWithAcceptedContent() throws Exception {
        byte[] bytes = service.docx("优化简历", sections);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(text.contains("项目经历"));
            assertTrue(text.contains("计算机科学与技术"));
            assertTrue(bytes.length > 1000);
        }
    }
}
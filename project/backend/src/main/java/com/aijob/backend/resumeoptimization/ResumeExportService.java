package com.aijob.backend.resumeoptimization;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aijob.backend.common.BusinessException;

@Service
public class ResumeExportService {

    private final String configuredFontPath;

    public ResumeExportService(@Value("${app.files.pdf-font-path:}") String configuredFontPath) {
        this.configuredFontPath = configuredFontPath;
    }

    public byte[] pdf(String title, List<Map<String, Object>> sections) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = loadFont(document);
            List<String> lines = new ArrayList<>();
            lines.addAll(wrap(title, font, 18, 495));
            lines.add("");
            for (Map<String, Object> section : sections) {
                lines.addAll(wrap(String.valueOf(section.get("title")), font, 14, 495));
                lines.addAll(wrap(String.valueOf(section.get("content")), font, 11, 495));
                lines.add("");
            }
            int from = 0;
            while (from < lines.size()) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 11);
                    stream.setLeading(17);
                    stream.newLineAtOffset(50, 792);
                    int end = Math.min(lines.size(), from + 43);
                    for (int index = from; index < end; index++) {
                        stream.showText(lines.get(index));
                        stream.newLine();
                    }
                    stream.endText();
                }
                from += 43;
            }
            if (lines.isEmpty()) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw BusinessException.dependencyUnavailable("PDF 导出失败");
        }
    }

    public byte[] docx(String title, List<Map<String, Object>> sections) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            paragraph(document, title, 18, true);
            for (Map<String, Object> section : sections) {
                paragraph(document, String.valueOf(section.get("title")), 14, true);
                String content = String.valueOf(section.get("content"));
                for (String line : content.split("\\R", -1)) {
                    paragraph(document, line, 11, false);
                }
            }
            document.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw BusinessException.dependencyUnavailable("DOCX 导出失败");
        }
    }

    private PDType0Font loadFont(PDDocument document) throws IOException {
        List<String> paths = new ArrayList<>();
        if (configuredFontPath != null && !configuredFontPath.isBlank()) {
            paths.add(configuredFontPath);
        }
        String windows = System.getenv("WINDIR");
        if (windows != null) {
            paths.add(windows + "\\Fonts\\msyh.ttc");
            paths.add(windows + "\\Fonts\\simhei.ttf");
            paths.add(windows + "\\Fonts\\simsun.ttc");
        }
        paths.add("C:\\Windows\\Fonts\\msyh.ttc");
        paths.add("C:\\Windows\\Fonts\\simhei.ttf");
        paths.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
        paths.add("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc");
        IOException last = null;
        for (String path : paths) {
            if (!Files.isRegularFile(Path.of(path))) {
                continue;
            }
            try {
                return PDType0Font.load(document, new File(path));
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("No Chinese PDF font available");
    }

    private List<String> wrap(String text, PDType0Font font, int size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        String sanitized = text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        for (String paragraph : sanitized.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                String next = new String(Character.toChars(codePoint));
                if (font.getStringWidth(line + next) / 1000 * size > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(next);
                offset += Character.charCount(codePoint);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private void paragraph(XWPFDocument document, String text, int size, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        paragraph.setSpacingAfter(160);
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(size);
        run.setBold(bold);
    }
}
package com.aijob.backend.resume;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;

@Service
public class FileStorageService {

    /** 按页、按页面位置排序、清理装饰字符后的文本布局版本。 */
    public static final String PDF_LAYOUT_VERSION = "pdf-layout-v2";
    public static final String DOCX_LAYOUT_VERSION = "docx-layout-v1";
    public static final String LEGACY_LAYOUT_VERSION = "legacy-v1";
    /** 图片 PDF 经 OCR 合成的统一文本布局版本（与 Agent 侧 pdf-ocr-v1 对齐）。 */
    public static final String PDF_OCR_LAYOUT_VERSION = "pdf-ocr-v1";

    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final Path storageDir;
    private final String signingSecret;
    private final JsonService json;

    public FileStorageService(@Value("${app.files.storage-dir}") String storageDir,
                              @Value("${app.files.download-signing-secret}") String signingSecret,
                              JsonService json) {
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.signingSecret = signingSecret;
        this.json = json;
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public StoredFile store(String userId, String versionId, MultipartFile file) {
        try {
            if (file.getSize() <= 0 || file.getSize() > 10 * 1024 * 1024) {
                throw BusinessException.payloadTooLarge();
            }
            byte[] bytes = file.getBytes();
            String original = file.getOriginalFilename() == null ? "resume" : Path.of(file.getOriginalFilename()).getFileName().toString();
            String mimeType;
            ExtractedText extracted;
            if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
                mimeType = PDF_MIME;
                extracted = pdfText(bytes);
            } else if (isDocx(bytes)) {
                mimeType = DOCX_MIME;
                extracted = docxText(bytes);
            } else {
                throw BusinessException.unsupportedFileType();
            }
            String text = extracted.text();
            if (text == null || text.isBlank()) {
                if (PDF_MIME.equals(mimeType) && needsOcr(bytes)) {
                    // 图片 PDF：允许无文字层上传，由后续 RESUME_OCR 任务识别文本。
                    text = pageMarkersOnly(bytes);
                } else {
                    throw BusinessException.validation("file", "文件中没有可提取的文本");
                }
            }
            text = text.strip();
            if (text.length() > 60000) {
                text = text.substring(0, 60000);
            }
            Path directory = storageDir.resolve(userId).normalize();
            if (!directory.startsWith(storageDir)) {
                throw BusinessException.forbidden();
            }
            Files.createDirectories(directory);
            String key = userId + "/" + versionId;
            Files.write(storageDir.resolve(key), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new StoredFile(original, mimeType, key, bytes, text, json.sha256(bytes), json.sha256(text),
                    extracted.layoutVersion());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.validation("file", "文件无法读取或已损坏");
        }
    }

    /**
     * 使用已保存的原始文件按当前布局规则重新提取文本。
     * 原文件缺失或无法读取时返回 {@code null}，调用方应回退到已保存文本并标记阅读顺序未复核。
     */
    public ExtractedText reExtract(String userId, String storageKey, String mimeType) {
        if (storageKey == null || storageKey.isBlank() || mimeType == null) {
            return null;
        }
        try {
            Path base = storageDir.resolve(userId).normalize();
            Path path = storageDir.resolve(storageKey).normalize();
            if (!path.startsWith(base) || !Files.isRegularFile(path)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            if (PDF_MIME.equals(mimeType)) {
                return pdfText(bytes);
            }
            if (DOCX_MIME.equals(mimeType)) {
                return docxText(bytes);
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    public Resource read(String userId, String key) {
        try {
            Path path = storageDir.resolve(key).normalize();
            if (!path.startsWith(storageDir.resolve(userId).normalize())) {
                throw BusinessException.forbidden();
            }
            return new ByteArrayResource(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw BusinessException.notFound();
        }
    }

    public String signature(String userId, String versionId, Instant expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String value = userId + ":" + versionId + ":" + expiresAt.getEpochSecond();
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 判断 PDF 是否包含图片内容（可能为扫描版/图片版，需要 OCR）。
     * 只要任一页存在图片资源即认为需要 OCR；文字层是否为空由调用方判断。
     */
    boolean needsOcr(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                if (document.getPage(page - 1).getResources().getXObjectNames().iterator().hasNext()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /**
     * 生成仅含页码标记的占位文本（如 "[page:1]\n[page:2]"），
     * 供图片 PDF 在 OCR 完成前占位；解析前必须先完成 OCR。
     */
    String pageMarkersOnly(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append("[page:").append(page).append("]");
            }
            return text.toString();
        } catch (Exception ex) {
            throw BusinessException.validation("file", "文件无法读取或已损坏");
        }
    }

    private ExtractedText pdfText(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String content = cleanLayoutText(stripper.getText(document));
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append("[page:").append(page).append("]\n").append(content);
            }
            return new ExtractedText(cleanLayoutText(text.toString()), PDF_LAYOUT_VERSION);
        }
    }

    private ExtractedText docxText(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return new ExtractedText(cleanLayoutText(extractor.getText()), DOCX_LAYOUT_VERSION);
        }
    }

    /**
     * 清除装饰性控制字符与零宽字符，保留章节、段落、日期、数值和原有空白缩进。
     */
    static String cleanLayoutText(String value) {
        StringBuilder filtered = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\t' || current == '\n') {
                filtered.append(current);
                continue;
            }
            if (current < 0x20 || current == 0x7F || current == '\u00AD' || current == '\uFFFD') {
                continue;
            }
            if ((current >= '\u200B' && current <= '\u200F')
                    || (current >= '\u202A' && current <= '\u202E')
                    || (current >= '\u2060' && current <= '\u2064')
                    || current == '\uFEFF') {
                continue;
            }
            filtered.append(current);
        }
        StringBuilder result = new StringBuilder(filtered.length());
        boolean previousBlank = false;
        for (String rawLine : filtered.toString().split("\n", -1)) {
            String line = rawLine.stripTrailing();
            boolean blank = line.isBlank();
            if (blank && previousBlank) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
            previousBlank = blank;
        }
        return result.toString().strip();
    }

    private boolean isDocx(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            return false;
        }
        boolean contentTypes = false;
        boolean document = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                contentTypes |= "[Content_Types].xml".equals(entry.getName());
                document |= "word/document.xml".equals(entry.getName());
            }
        }
        return contentTypes && document;
    }

    public record ExtractedText(String text, String layoutVersion) {
    }

    public record StoredFile(String fileName, String mimeType, String key, byte[] bytes, String text,
                             String sha256, String textSha256, String layoutVersion) {
    }
}
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
            String text;
            if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
                mimeType = "application/pdf";
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    text = new PDFTextStripper().getText(document);
                }
            } else if (isDocx(bytes)) {
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    text = extractor.getText();
                }
            } else {
                throw BusinessException.unsupportedFileType();
            }
            if (text == null || text.isBlank()) {
                throw BusinessException.validation("file", "文件中没有可提取的文本");
            }
            text = text.replace("\r\n", "\n").replace('\r', '\n').strip();
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
            return new StoredFile(original, mimeType, key, bytes, text.strip(), json.sha256(bytes), json.sha256(text.strip()));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.validation("file", "文件无法读取或已损坏");
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

    public record StoredFile(String fileName, String mimeType, String key, byte[] bytes, String text,
                             String sha256, String textSha256) {
    }
}

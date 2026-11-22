package com.aijob.backend.resume;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskResultHandler;

import tools.jackson.databind.JsonNode;

/**
 * RESUME_OCR 任务结果处理：保存统一 OCR 文本与哈希，并自动接续解析任务。
 * 失败语义：OCR 失败时版本回到 FAILED（OCR 仅在无文字层时发起，不存在旧草稿需要保留）。
 */
@Component
public class ResumeOcrResultHandler implements TaskResultHandler {

    private static final Logger log = LoggerFactory.getLogger(ResumeOcrResultHandler.class);

    private final ResumeMapper mapper;
    private final ResumeService resumes;
    private final JsonService json;

    public ResumeOcrResultHandler(ResumeMapper mapper, ResumeService resumes, JsonService json) {
        this.mapper = mapper;
        this.resumes = resumes;
        this.json = json;
    }

    @Override
    public Set<String> operations() {
        return Set.of("RESUME_OCR");
    }

    @Override
    @SuppressWarnings("unchecked")
    public int success(AiTask task, JsonNode result) {
        Map<String, Object> row = mapper.lockVersion(task.getResourceId(), task.getUserId());
        if (row == null || !Set.of("UPLOADED", "PARSING").contains(String.valueOf(row.get("status")))) {
            throw BusinessException.conflict("简历版本状态已变化", "INVALID_STATE");
        }
        Map<String, Object> parsed = json.map(result);
        String text = String.valueOf(parsed.get("text")).replace("\r\n", "\n").replace('\r', '\n').strip();
        String textHash = String.valueOf(parsed.get("textHash"));
        if (text.isBlank() || text.replaceAll("\\[page:\\d+]", "").isBlank()) {
            throw BusinessException.aiSchema("OCR 结果没有任何有效文本");
        }
        if (!json.sha256(text).equalsIgnoreCase(textHash)) {
            throw BusinessException.aiSchema("OCR 结果 textHash 与文本内容不一致");
        }
        if (text.length() > 60000) {
            text = text.substring(0, 60000);
        }
        List<Integer> ocrPages = ((List<Object>) parsed.getOrDefault("ocrPages", List.of()))
                .stream().map(item -> ((Number) item).intValue()).toList();
        if (mapper.ocrTextSaved(task.getResourceId(), text, json.sha256(text),
                json.stringify(ocrPages)) == 0) {
            throw BusinessException.conflict("简历版本状态已变化", "INVALID_STATE");
        }
        log.info("ocr text saved task={} version={} ocrPages={} chars={}",
                task.getId(), task.getResourceId(), ocrPages.size(), text.length());
        // 自动接续解析：OCR 保存成功后立即发起 RESUME_PARSE；接续失败时保留 UPLOADED 状态，用户可手动解析。
        try {
            resumes.startParseAfterOcr(task.getUserId(), task.getResourceId(), task.getId());
        } catch (Exception ex) {
            log.warn("ocr follow-up parse failed task={} version={} error={}",
                    task.getId(), task.getResourceId(), ex.getMessage());
        }
        return ((Number) row.get("version_no")).intValue();
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        Map<String, Object> row = mapper.version(task.getResourceId(), task.getUserId());
        if (row != null && Set.of("UPLOADED", "PARSING").contains(String.valueOf(row.get("status")))) {
            mapper.updateStatus(task.getResourceId(), "FAILED");
        }
    }

    @Override
    public Map<String, Object> resultSummary(AiTask task) {
        return Map.of("outcome", "OCR_COMPLETED");
    }
}

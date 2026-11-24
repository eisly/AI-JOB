package com.aijob.backend.resumeoptimization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 定向优化 Agent→Java 回写严格校验回归（真实 ResumeOptimizationResultHandler.optimize 代码路径）。
 *
 * <p>背景：Agent 侧改为行号引用 + 服务端回填（resume-optimize-v2），对外结果结构不变。
 * 本测试固化 Java 端红线：requireSource 为严格 contains（不做归一化/模糊匹配），
 * 回填内容必须能在任务快照原文中逐字定位；伪造引用/跨段拼接必须被拒绝。
 *
 * <p>真实产物用例（localOptimizeFixturesAvailable）：读取 .local-fixtures/ 下
 * 真实联调产物（快照 + Agent 回填结果，含真实简历内容，已 gitignore 不入库），
 * 文件缺失时跳过——数据由本地联调生成，测试代码本身不含任何简历内容。
 */
class ResumeOptimizationResultHandlerValidationTest {

    private static final String SNAPSHOT_TEMPLATE = """
            {"document":{"text":"%s","textLayoutVersion":"pdf-layout-v2","readOrderVerified":true}}
            """;

    // 合成授权文本：覆盖跨行中文、全角空格、中文引号（真实换行为 \n，非字面反斜杠 n）
    private static final String SOURCE_TEXT = "教育背景：本科，计算机科学与技术专业。\n"
            + "实习经历：使用 Redis 实现缓存，参与 Java 接口开发。\n"
            + "项目经历：“AI 监控平台”开发，负责后端模块。";

    private final ResumeOptimizationMapper mapper = mock(ResumeOptimizationMapper.class);
    private final ObjectMapper jackson = JsonMapper.builder().build();
    private final JsonService json = new JsonService(jackson);
    private final ResumeOptimizationResultHandler handler = new ResumeOptimizationResultHandler(mapper, json);

    private AiTask taskWithText(String text) {
        AiTask task = new AiTask();
        task.setId("t1");
        task.setUserId("u1");
        task.setOperation("RESUME_OPTIMIZE");
        task.setResourceType("RESUME_OPTIMIZATION");
        task.setResourceId("r1");
        task.setInputSnapshotJson(SNAPSHOT_TEMPLATE.formatted(escape(text)));
        return task;
    }

    private String resultJson(String original, String suggested, String quote) {
        return """
                {"title":"定向优化候选","sections":[{"sectionKey":"experience","title":"经历表述",\
                "originalText":"%s","suggestedText":"%s","sourceQuotes":["%s"]}]}
                """.formatted(escape(original), escape(suggested), escape(quote));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void stubMapperSuccess() {
        when(mapper.lockOptimization(anyString(), anyString())).thenReturn(Map.of("id", "r1"));
        when(mapper.completeOptimization(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
    }

    static boolean localOptimizeFixturesAvailable() {
        return Files.exists(Path.of(".local-fixtures", "optimize-result.json"))
                && Files.exists(Path.of(".local-fixtures", "optimize-snapshot.json"));
    }

    @Test
    void acceptsSingleLineRefillWithFullwidthAndChineseQuotes() {
        stubMapperSuccess();
        String line = "项目经历：“AI 监控平台”开发，负责后端模块。";
        AiTask task = taskWithText(SOURCE_TEXT);
        String result = resultJson(line, "候选改写：在保留事实基础上补充可核实成果。", line);
        assertDoesNotThrow(() -> handler.success(task, json.parse(result)));
    }

    @Test
    void acceptsCrossLineRefillWithRealNewline() {
        stubMapperSuccess();
        // 跨行回填：两行连续原文（中间为真实换行符，非字面 "\n"）
        String crossLine = "实习经历：使用 Redis 实现缓存，参与 Java 接口开发。\n项目经历：“AI 监控平台”开发，负责后端模块。";
        AiTask task = taskWithText(SOURCE_TEXT);
        String result = resultJson(crossLine, "候选改写。", "实习经历：使用 Redis 实现缓存，参与 Java 接口开发。");
        assertDoesNotThrow(() -> handler.success(task, json.parse(result)));
    }

    @Test
    void rejectsForgedOriginalText() {
        stubMapperSuccess();
        AiTask task = taskWithText(SOURCE_TEXT);
        String result = resultJson("实习经历：使用 Redis 实现缓存，虚构了不存在的工作。", "候选。", "实习经历：使用 Redis 实现缓存，参与 Java 接口开发。");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(result)));
        assertTrue(ex.getMessage().contains("originalText 不属于授权简历原文"), ex.getMessage());
    }

    @Test
    void rejectsForgedSourceQuote() {
        stubMapperSuccess();
        AiTask task = taskWithText(SOURCE_TEXT);
        String result = resultJson("实习经历：使用 Redis 实现缓存，参与 Java 接口开发。", "候选。", "伪造的原文依据");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(result)));
        assertTrue(ex.getMessage().contains("sourceQuotes 不属于授权简历原文"), ex.getMessage());
    }

    @Test
    void rejectsCrossSegmentSplice() {
        stubMapperSuccess();
        // 跨段拼接负例：两行内容顺序颠倒拼接（非原文连续子串，模糊匹配禁止兜底）
        String spliced = "项目经历：“AI 监控平台”开发，负责后端模块。实习经历：使用 Redis 实现缓存，参与 Java 接口开发。";
        AiTask task = taskWithText(SOURCE_TEXT);
        String result = resultJson(spliced, "候选。", "实习经历：使用 Redis 实现缓存，参与 Java 接口开发。");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(result)));
        assertTrue(ex.getMessage().contains("originalText 不属于授权简历原文"), ex.getMessage());
    }

    /** 真实联调产物回写（数据在 .local-fixtures/，含真实简历，不入库；缺失时跳过） */
    @Test
    @EnabledIf(value = "localOptimizeFixturesAvailable",
            disabledReason = "本地真实联调产物（.local-fixtures）不存在，跳过真实数据回写用例")
    void acceptsRealIntegrationRefillOutput() throws Exception {
        stubMapperSuccess();
        String snapshot = Files.readString(Path.of(".local-fixtures", "optimize-snapshot.json"));
        String result = Files.readString(Path.of(".local-fixtures", "optimize-result.json"));
        AiTask task = new AiTask();
        task.setId("real-1");
        task.setUserId("real-user");
        task.setOperation("RESUME_OPTIMIZE");
        task.setResourceType("RESUME_OPTIMIZATION");
        task.setResourceId("real-resource");
        task.setInputSnapshotJson(snapshot);
        // 真实证据：Agent 回填输出经 Java optimize() 全流程（requireSource 严格 contains）零失败
        assertDoesNotThrow(() -> handler.success(task, json.parse(result)));
    }
}
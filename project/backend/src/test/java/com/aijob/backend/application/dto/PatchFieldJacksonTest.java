package com.aijob.backend.application.dto;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.aijob.backend.application.ApplicationStatus;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

/**
 * PatchField 三态反序列化专项验证（纯 Jackson 3 / tools.jackson，无 Spring/数据库依赖）。
 * Spring Boot 4 webmvc 默认 MVC JSON 即 Jackson 3，此处验证与运行时同一体系：
 * ① 字段缺失 → UNDEFINED（保持原值）；② 显式 null → NULL（清空）；
 * ③ 正常值 → VALUE（更新）；④ 枚举/日期非法值 → InvalidFormatException（由全局异常处理器转 400）。
 * java.time 支持为 Jackson 3 内置（无需 jsr310 模块）。
 */
class PatchFieldJacksonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void absentFieldStaysUndefined() throws Exception {
        ApplicationUpdateDTO dto = mapper.readValue("{\"expectedVersion\":1}", ApplicationUpdateDTO.class);

        assertTrue(dto.getCompanyName().isUndefined());
        assertTrue(dto.getJobTitle().isUndefined());
        assertTrue(dto.getStatus().isUndefined());
        assertTrue(dto.getInterviewDate().isUndefined());
        assertTrue(dto.getNote().isUndefined());
        assertTrue(dto.getJobId().isUndefined());
    }

    @Test
    void explicitNullBecomesNullState() throws Exception {
        ApplicationUpdateDTO dto = mapper.readValue(
                "{\"expectedVersion\":1,\"note\":null,\"interviewDate\":null,"
                        + "\"companyName\":null,\"status\":null,\"jobId\":null}",
                ApplicationUpdateDTO.class);

        assertTrue(dto.getNote().isNull());
        assertTrue(dto.getInterviewDate().isNull());
        assertTrue(dto.getCompanyName().isNull());
        assertTrue(dto.getStatus().isNull());
        assertTrue(dto.getJobId().isNull());
    }

    @Test
    void valueBecomesValueState() throws Exception {
        ApplicationUpdateDTO dto = mapper.readValue("""
                {"expectedVersion":2,"companyName":"新公司","status":"INTERVIEWING",
                 "interviewDate":"2026-10-28","note":"备注","cityCode":"330100"}
                """, ApplicationUpdateDTO.class);

        assertTrue(dto.getCompanyName().isValue());
        assertEquals("新公司", dto.getCompanyName().getValue());
        assertEquals(ApplicationStatus.INTERVIEWING, dto.getStatus().getValue());
        assertEquals(LocalDate.of(2026, 10, 28), dto.getInterviewDate().getValue());
        assertEquals("备注", dto.getNote().getValue());
        assertEquals("330100", dto.getCityCode().getValue());
    }

    @Test
    void mixedFieldsKeepIndependentStates() throws Exception {
        ApplicationUpdateDTO dto = mapper.readValue(
                "{\"expectedVersion\":1,\"companyName\":\"新公司\",\"note\":null}",
                ApplicationUpdateDTO.class);

        assertTrue(dto.getCompanyName().isValue());
        assertTrue(dto.getNote().isNull());
        assertTrue(dto.getJobTitle().isUndefined());
        assertTrue(dto.getStatus().isUndefined());
    }

    @Test
    void illegalEnumValueThrowsInvalidFormat() {
        assertThrows(InvalidFormatException.class, () ->
                mapper.readValue("{\"expectedVersion\":1,\"status\":\"XX\"}", ApplicationUpdateDTO.class));
    }

    @Test
    void illegalDateValueThrowsInvalidFormat() {
        assertThrows(InvalidFormatException.class, () ->
                mapper.readValue("{\"expectedVersion\":1,\"interviewDate\":\"2026-13-45\"}",
                        ApplicationUpdateDTO.class));
    }
}
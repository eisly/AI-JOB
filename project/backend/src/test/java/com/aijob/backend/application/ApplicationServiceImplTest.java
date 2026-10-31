package com.aijob.backend.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aijob.backend.application.dto.ApplicationCreateDTO;
import com.aijob.backend.application.dto.ApplicationUpdateDTO;
import com.aijob.backend.application.dto.PatchField;
import com.aijob.backend.application.entity.Application;
import com.aijob.backend.application.mapper.ApplicationMapper;
import com.aijob.backend.application.service.impl.ApplicationServiceImpl;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.PageResult;

/**
 * Service 层单元测试（Mockito，不依赖数据库）。
 */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceImplTest {

    private static final String USER_A = "10000000-0000-4000-8000-00000000000a";
    private static final String USER_B = "10000000-0000-4000-8000-00000000000b";
    private static final String JOB_PUBLIC = "30000000-0000-4000-8000-000000000001";

    @Mock
    private ApplicationMapper applicationMapper;

    @InjectMocks
    private ApplicationServiceImpl service;

    private ApplicationCreateDTO createDto() {
        ApplicationCreateDTO dto = new ApplicationCreateDTO();
        dto.setCompanyName("云启智能（虚构）");
        dto.setJobTitle("Java 后端开发工程师");
        return dto;
    }

    private Application savedRecord(String id, String userId, int version) {
        Application record = new Application();
        record.setId(id);
        record.setUserId(userId);
        record.setCompanyName("云启智能（虚构）");
        record.setJobTitle("Java 后端开发工程师");
        record.setStatus("PLANNED");
        record.setNote("初始备注");
        record.setCityCode("330100");
        record.setVersion(version);
        record.setCreatedAt(Instant.parse("2026-10-26T02:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-10-26T02:00:00Z"));
        return record;
    }

    // ---------- create ----------

    @Test
    void create_defaultsStatusToPlannedWhenOmitted() {
        when(applicationMapper.insert(any())).thenReturn(1);
        when(applicationMapper.selectByIdAndUserId(anyString(), eq(USER_A)))
                .thenAnswer(inv -> savedRecord(inv.getArgument(0), USER_A, 1));

        ApplicationVO vo = service.create(USER_A, createDto());

        assertEquals(ApplicationStatus.PLANNED, vo.getStatus());
        assertEquals(1, vo.getVersion());
        assertNotNull(vo.getId());
    }

    @Test
    void create_returns404WhenJobNotVisible() {
        ApplicationCreateDTO dto = createDto();
        dto.setJobId(UUID.randomUUID().toString());
        when(applicationMapper.countVisibleJob(dto.getJobId(), USER_A)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(USER_A, dto));
        assertEquals(404, ex.getHttpStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getErrorKey());
    }

    @Test
    void create_rejectsOverlongCompanyName() {
        ApplicationCreateDTO dto = createDto();
        dto.setCompanyName("超".repeat(101));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(USER_A, dto));
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getDetails().toString().contains("companyName"));
    }

    @Test
    void create_trimsBlankToValidation() {
        ApplicationCreateDTO dto = createDto();
        dto.setJobTitle("   ");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(USER_A, dto));
        assertEquals(400, ex.getHttpStatus());
    }

    // ---------- list ----------

    @Test
    void list_rejectsIllegalStatus() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.list(USER_A, "XX", null, null));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void list_rejectsIllegalPaging() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.list(USER_A, null, 0, null)).getHttpStatus());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.list(USER_A, null, null, 0)).getHttpStatus());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.list(USER_A, null, null, 101)).getHttpStatus());
    }

    @Test
    void list_rejectsOversizedPageOffset() {
        // (current-1)*size 溢出 int 范围：按 400 拒绝而非溢出后 500
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.list(USER_A, null, Integer.MAX_VALUE, 100)).getHttpStatus());
        // 边界内的大页码合法：返回空结果（offset=(100000-1)*20=1999980）
        when(applicationMapper.selectPage(eq(USER_A), isNull(), eq(1999980L), eq(20))).thenReturn(List.of());
        when(applicationMapper.countByUserIdAndStatus(USER_A, null)).thenReturn(0L);
        PageResult<ApplicationVO> page = service.list(USER_A, null, 100000, 20);
        assertEquals(0, page.getTotal());
        assertTrue(page.getRecords().isEmpty());
    }

    @Test
    void list_rejectsBlankStatusButAllowsMissing() {
        // 空串/空白是"传了非法值"，一律 400；null（不传）查全部
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.list(USER_A, "", null, null)).getHttpStatus());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.list(USER_A, " ", null, null)).getHttpStatus());
        when(applicationMapper.selectPage(eq(USER_A), isNull(), eq(0L), eq(20))).thenReturn(List.of());
        when(applicationMapper.countByUserIdAndStatus(USER_A, null)).thenReturn(0L);
        assertEquals(0, service.list(USER_A, null, null, null).getTotal());
    }

    @Test
    void list_usesDefaultsAndReturnsEmptyRecords() {
        when(applicationMapper.selectPage(USER_A, null, 0, 20)).thenReturn(List.of());
        when(applicationMapper.countByUserIdAndStatus(USER_A, null)).thenReturn(0L);

        PageResult<ApplicationVO> page = service.list(USER_A, null, null, null);

        assertEquals(0, page.getTotal());
        assertNotNull(page.getRecords());
        assertTrue(page.getRecords().isEmpty());
        assertEquals(1, page.getCurrent());
        assertEquals(20, page.getSize());
    }

    // ---------- detail ----------

    @Test
    void detail_returns404WhenMissingOrOthers() {
        when(applicationMapper.selectByIdAndUserId(anyString(), eq(USER_A))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.detail(USER_A, UUID.randomUUID().toString()));
        assertEquals(404, ex.getHttpStatus());
    }

    // ---------- update（三态） ----------

    private ApplicationUpdateDTO updateDto(int expectedVersion) {
        ApplicationUpdateDTO dto = new ApplicationUpdateDTO();
        dto.setExpectedVersion(expectedVersion);
        return dto;
    }

    @Test
    void update_undefinedFieldsKeepOriginalValues() {
        String id = UUID.randomUUID().toString();
        Application current = savedRecord(id, USER_A, 1);
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(current);
        ApplicationUpdateDTO dto = updateDto(1);
        dto.setStatus(PatchField.of(ApplicationStatus.APPLIED));

        Application updated = savedRecord(id, USER_A, 2);
        updated.setStatus("APPLIED");
        updated.setNote("初始备注");
        when(applicationMapper.updateByIdUserIdVersion(any(Application.class), eq(1))).thenReturn(1);
        when(applicationMapper.selectByIdAndUserId(id, USER_A)).thenReturn(updated);

        ApplicationVO vo = service.update(USER_A, id, dto);

        assertEquals(ApplicationStatus.APPLIED, vo.getStatus());
        // 只改 status，note（未传）保持原值
        assertEquals("初始备注", vo.getNote());
        assertEquals("330100", vo.getCityCode());
    }

    @Test
    void update_nullClearsNullableField() {
        String id = UUID.randomUUID().toString();
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(savedRecord(id, USER_A, 1));
        ApplicationUpdateDTO dto = updateDto(1);
        dto.setNote(PatchField.ofNull());

        Application updated = savedRecord(id, USER_A, 2);
        updated.setNote(null);
        when(applicationMapper.updateByIdUserIdVersion(any(Application.class), eq(1))).thenReturn(1);
        when(applicationMapper.selectByIdAndUserId(id, USER_A)).thenReturn(updated);

        ApplicationVO vo = service.update(USER_A, id, dto);

        assertNull(vo.getNote());
    }

    @Test
    void update_requiredFieldNullReturns400() {
        String id = UUID.randomUUID().toString();
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(savedRecord(id, USER_A, 1));

        ApplicationUpdateDTO dto = updateDto(1);
        dto.setCompanyName(PatchField.ofNull());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(USER_A, id, dto));
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getDetails().toString().contains("companyName"));

        ApplicationUpdateDTO dto2 = updateDto(1);
        dto2.setStatus(PatchField.ofNull());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.update(USER_A, id, dto2)).getHttpStatus());

        ApplicationUpdateDTO dto3 = updateDto(1);
        dto3.setJobTitle(PatchField.ofNull());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.update(USER_A, id, dto3)).getHttpStatus());
    }

    @Test
    void update_emptyBodyReturns400() {
        String id = UUID.randomUUID().toString();
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(savedRecord(id, USER_A, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(USER_A, id, updateDto(1)));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void update_jobIdPresentReturns400() {
        String id = UUID.randomUUID().toString();


        ApplicationUpdateDTO dto = updateDto(1);
        dto.setJobId(PatchField.ofNull());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(USER_A, id, dto));
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getDetails().toString().contains("jobId"));
    }

    @Test
    void update_staleVersionReturns409() {
        String id = UUID.randomUUID().toString();
        Application current = savedRecord(id, USER_A, 3);
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(current);

        ApplicationUpdateDTO dto = updateDto(2);
        dto.setStatus(PatchField.of(ApplicationStatus.APPLIED));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(USER_A, id, dto));
        assertEquals(409, ex.getHttpStatus());
        assertEquals("VERSION_CONFLICT", ex.getErrorKey());
        assertEquals(3L, ex.getDetails().get("currentVersion"));
    }

    @Test
    void update_zeroAffectedRowsReturns409() {
        String id = UUID.randomUUID().toString();
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(savedRecord(id, USER_A, 1));
        ApplicationUpdateDTO dto = updateDto(1);
        dto.setStatus(PatchField.of(ApplicationStatus.APPLIED));
        when(applicationMapper.updateByIdUserIdVersion(any(Application.class), eq(1))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(USER_A, id, dto));
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void update_closedToAppliedAllowedAndDatesUntouched() {
        String id = UUID.randomUUID().toString();
        Application current = savedRecord(id, USER_A, 2);
        current.setStatus("CLOSED");
        current.setInterviewDate(LocalDate.of(2026, 10, 28));
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(current);

        ApplicationUpdateDTO dto = updateDto(2);
        dto.setStatus(PatchField.of(ApplicationStatus.APPLIED));

        Application updated = savedRecord(id, USER_A, 3);
        updated.setStatus("APPLIED");
        updated.setInterviewDate(LocalDate.of(2026, 10, 28));
        when(applicationMapper.updateByIdUserIdVersion(any(Application.class), eq(2))).thenReturn(1);
        when(applicationMapper.selectByIdAndUserId(id, USER_A)).thenReturn(updated);

        ApplicationVO vo = service.update(USER_A, id, dto);

        assertEquals(ApplicationStatus.APPLIED, vo.getStatus());
        assertEquals(LocalDate.of(2026, 10, 28), vo.getInterviewDate());
    }

    // ---------- delete ----------

    @Test
    void delete_returns404WhenMissing() {
        when(applicationMapper.selectByIdAndUserIdForUpdate(anyString(), eq(USER_A))).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.delete(USER_A, UUID.randomUUID().toString(), 1)).getHttpStatus());
    }

    @Test
    void delete_returns409OnVersionMismatch() {
        String id = UUID.randomUUID().toString();
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(savedRecord(id, USER_A, 5));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(USER_A, id, 4));
        assertEquals(409, ex.getHttpStatus());
        assertEquals(5L, ex.getDetails().get("currentVersion"));
    }

    @Test
    void delete_succeedsAndZeroAffectedFallsBackTo409() {
        String id = UUID.randomUUID().toString();
        when(applicationMapper.selectByIdAndUserIdForUpdate(id, USER_A)).thenReturn(savedRecord(id, USER_A, 1));
        when(applicationMapper.deleteByIdUserIdVersion(id, USER_A, 1)).thenReturn(1);
        assertTrue(service.delete(USER_A, id, 1));

        String id2 = UUID.randomUUID().toString();
        when(applicationMapper.selectByIdAndUserIdForUpdate(id2, USER_A)).thenReturn(savedRecord(id2, USER_A, 1));
        when(applicationMapper.deleteByIdUserIdVersion(id2, USER_A, 1)).thenReturn(0);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.delete(USER_A, id2, 1)).getHttpStatus());
    }

    // ---------- summary ----------

    @Test
    void summary_fillsZeroForMissingStatuses() {
        when(applicationMapper.countGroupByStatus(USER_A)).thenReturn(List.of(
                Map.of("status", "APPLIED", "cnt", 2L)));

        ApplicationSummaryVO vo = service.summary(USER_A);

        assertEquals(2, vo.getTotal());
        assertEquals(0L, vo.getCounts().get("PLANNED"));
        assertEquals(2L, vo.getCounts().get("APPLIED"));
        assertEquals(0L, vo.getCounts().get("INTERVIEWING"));
        assertEquals(0L, vo.getCounts().get("CLOSED"));
    }
}
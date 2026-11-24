package com.aijob.backend.application.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.application.ApplicationStatus;
import com.aijob.backend.application.ApplicationSummaryVO;
import com.aijob.backend.application.ApplicationVO;
import com.aijob.backend.application.dto.ApplicationCreateDTO;
import com.aijob.backend.application.dto.ApplicationUpdateDTO;
import com.aijob.backend.application.dto.PatchField;
import com.aijob.backend.application.entity.Application;
import com.aijob.backend.application.mapper.ApplicationMapper;
import com.aijob.backend.application.service.ApplicationService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.PageResult;

/**
 * 投递进度服务实现。
 * <p>并发写入（修改/删除）遵循统一事务边界：事务内 SELECT ... FOR UPDATE 锁定读取 →
 * 校验归属与版本 → 带 id+user_id+version 条件的条件写 → 检查影响行数（0 行按 409 兜底）。
 * <p>PATCH 三态语义：UNDEFINED 保持原值、NULL 清空、VALUE 更新；
 * 必填字段（companyName/jobTitle/status）显式传 null 返回 400。
 */
@Service
public class ApplicationServiceImpl implements ApplicationService {

    private static final int MAX_COMPANY_NAME = 100;
    private static final int MAX_JOB_TITLE = 100;
    private static final int MAX_CITY_CODE = 12;
    private static final int MAX_EDUCATION = 50;
    private static final int MAX_SALARY_TEXT = 100;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_NOTE = 2000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** 分页偏移量上限：超过即视为非法页码（400），避免大页码溢出与深度分页拖垮服务 */
    private static final long MAX_OFFSET = Integer.MAX_VALUE;

    private final ApplicationMapper applicationMapper;

    public ApplicationServiceImpl(ApplicationMapper applicationMapper) {
        this.applicationMapper = applicationMapper;
    }

    @Override
    @Transactional
    public ApplicationVO create(String userId, ApplicationCreateDTO dto) {
        // P01 并发防重：锁定用户行使同一用户的并发创建串行化，
        // 保证"重复检查 → 插入"的原子性；后到的并发请求读到已插入记录并返回 409。
        applicationMapper.lockUser(userId);
        if (dto.getJobId() != null) {
            int visible = applicationMapper.countVisibleJob(dto.getJobId(), userId);
            if (visible == 0) {
                throw BusinessException.notFound();
            }
            // 同一岗位重复添加：先提示已有记录，用户明确确认后才创建独立新记录（业务允许重复投递，但不允许无提示静默重复）。
            int existing = applicationMapper.countByUserIdAndJobId(userId, dto.getJobId());
            if (existing > 0 && !Boolean.TRUE.equals(dto.getConfirmDuplicate())) {
                Map<String, Object> details = new HashMap<>();
                details.put("jobId", dto.getJobId());
                details.put("existingCount", existing);
                throw BusinessException.conflict("该岗位已有 " + existing + " 条投递记录，请确认是否再次添加",
                        "DUPLICATE_APPLICATION", details);
            }
        }
        String companyName = requireText("companyName", dto.getCompanyName(), MAX_COMPANY_NAME);
        String jobTitle = requireText("jobTitle", dto.getJobTitle(), MAX_JOB_TITLE);
        String status = dto.getStatus() == null ? ApplicationStatus.PLANNED.name() : dto.getStatus().name();
        // 状态-日期一致性：待投递状态不得携带实际投递日期，避免"待投递却已投出"的矛盾记录。
        if (ApplicationStatus.PLANNED.name().equals(status) && dto.getAppliedDate() != null) {
            throw BusinessException.validation("appliedDate", "待投递状态不能填写实际投递日期，请清除日期或选择已投递");
        }

        Application entity = new Application();
        entity.setId(UUID.randomUUID().toString().toLowerCase());
        entity.setUserId(userId);
        entity.setJobId(dto.getJobId());
        entity.setCompanyName(companyName);
        entity.setJobTitle(jobTitle);
        entity.setCityCode(optionalText("cityCode", dto.getCityCode(), MAX_CITY_CODE));
        entity.setEducation(optionalText("education", dto.getEducation(), MAX_EDUCATION));
        entity.setSalaryText(optionalText("salaryText", dto.getSalaryText(), MAX_SALARY_TEXT));
        entity.setDescription(optionalText("description", dto.getDescription(), MAX_DESCRIPTION));
        entity.setStatus(status);
        entity.setPlannedDate(dto.getPlannedDate());
        entity.setAppliedDate(dto.getAppliedDate());
        entity.setInterviewDate(dto.getInterviewDate());
        entity.setClosedDate(dto.getClosedDate());
        entity.setNote(optionalText("note", dto.getNote(), MAX_NOTE));

        applicationMapper.insert(entity);
        Application saved = applicationMapper.selectByIdAndUserId(entity.getId(), userId);
        if (saved == null) {
            throw BusinessException.notFound();
        }
        return toVO(saved);
    }

    @Override
    public PageResult<ApplicationVO> list(String userId, String status, Integer current, Integer size) {
        // 区分"不传"（null，查全部）与"传了非法值"（空串/空白/非枚举，一律 400）
        String statusValue = null;
        if (status != null) {
            ApplicationStatus parsed = ApplicationStatus.fromValue(status);
            if (parsed == null) {
                throw BusinessException.validation("status", "非法投递状态: " + status);
            }
            statusValue = parsed.name();
        }
        long currentVal = current == null ? 1 : current;
        long sizeVal = size == null ? DEFAULT_PAGE_SIZE : size;
        if (currentVal < 1) {
            throw BusinessException.validation("current", "页码必须从 1 开始");
        }
        if (sizeVal < 1 || sizeVal > MAX_PAGE_SIZE) {
            throw BusinessException.validation("size", "每页数量必须在 1..100 之间");
        }
        // 用 long 计算避免 (current-1)*size 溢出；超界按 400 拒绝而非溢出后 500
        long offset = (currentVal - 1) * sizeVal;
        if (offset > MAX_OFFSET) {
            throw BusinessException.validation("current", "页码过大：偏移量超出可查询范围");
        }
        List<Application> records = applicationMapper.selectPage(userId, statusValue, offset, (int) sizeVal);
        long total = applicationMapper.countByUserIdAndStatus(userId, statusValue);
        List<ApplicationVO> vos = new ArrayList<>(records.size());
        for (Application record : records) {
            vos.add(toVO(record));
        }
        return new PageResult<>(total, vos, currentVal, sizeVal);
    }

    @Override
    public ApplicationVO detail(String userId, String id) {
        Application record = applicationMapper.selectByIdAndUserId(id, userId);
        if (record == null) {
            throw BusinessException.notFound();
        }
        return toVO(record);
    }

    @Override
    @Transactional
    public ApplicationVO update(String userId, String id, ApplicationUpdateDTO dto) {
        if (isPresent(dto.getJobId())) {
            throw BusinessException.validation("jobId", "jobId 不可修改");
        }
        Application current = applicationMapper.selectByIdAndUserIdForUpdate(id, userId);
        if (current == null) {
            throw BusinessException.notFound();
        }
        int expectedVersion = dto.getExpectedVersion();
        if (current.getVersion() != expectedVersion) {
            throw BusinessException.versionConflict(current.getVersion());
        }

        List<Map<String, String>> errors = new ArrayList<>();
        Application target = new Application();
        target.setId(current.getId());
        target.setUserId(current.getUserId());
        target.setJobId(current.getJobId());

        target.setCompanyName(applyRequired(errors, "companyName", dto.getCompanyName(),
                current.getCompanyName(), MAX_COMPANY_NAME));
        target.setJobTitle(applyRequired(errors, "jobTitle", dto.getJobTitle(),
                current.getJobTitle(), MAX_JOB_TITLE));

        if (dto.getStatus().isNull()) {
            addError(errors, "status", "status 为必填字段，不能传 null");
            target.setStatus(current.getStatus());
        } else if (dto.getStatus().isValue()) {
            target.setStatus(dto.getStatus().getValue().name());
        } else {
            target.setStatus(current.getStatus());
        }

        target.setCityCode(applyOptional(errors, "cityCode", dto.getCityCode(), current.getCityCode(), MAX_CITY_CODE));
        target.setEducation(applyOptional(errors, "education", dto.getEducation(), current.getEducation(), MAX_EDUCATION));
        target.setSalaryText(applyOptional(errors, "salaryText", dto.getSalaryText(), current.getSalaryText(), MAX_SALARY_TEXT));
        target.setDescription(applyOptional(errors, "description", dto.getDescription(), current.getDescription(), MAX_DESCRIPTION));
        target.setNote(applyOptional(errors, "note", dto.getNote(), current.getNote(), MAX_NOTE));

        target.setPlannedDate(applyDate(dto.getPlannedDate(), current.getPlannedDate()));
        target.setAppliedDate(applyDate(dto.getAppliedDate(), current.getAppliedDate()));
        target.setInterviewDate(applyDate(dto.getInterviewDate(), current.getInterviewDate()));
        target.setClosedDate(applyDate(dto.getClosedDate(), current.getClosedDate()));

        if (!errors.isEmpty()) {
            throw BusinessException.validation(errors);
        }
        // 状态-日期一致性：回退到待投递时不得保留实际投递日期。
        // 用户需在本次提交中同时清除日期（appliedDate 传 null），否则视为矛盾修改并拒绝。
        if (ApplicationStatus.PLANNED.name().equals(target.getStatus()) && target.getAppliedDate() != null) {
            throw BusinessException.validation("appliedDate",
                    "待投递状态不能保留实际投递日期，请同时清空实际投递日期或选择已投递");
        }
        if (!hasAnyChange(dto)) {
            throw BusinessException.validation("body", "至少提交一个修改字段");
        }

        int affected = applicationMapper.updateByIdUserIdVersion(target, expectedVersion);
        if (affected == 0) {
            throw BusinessException.versionConflict(current.getVersion());
        }
        Application updated = applicationMapper.selectByIdAndUserId(id, userId);
        if (updated == null) {
            throw BusinessException.notFound();
        }
        return toVO(updated);
    }

    @Override
    @Transactional
    public boolean delete(String userId, String id, int expectedVersion) {
        Application current = applicationMapper.selectByIdAndUserIdForUpdate(id, userId);
        if (current == null) {
            throw BusinessException.notFound();
        }
        if (current.getVersion() != expectedVersion) {
            throw BusinessException.versionConflict(current.getVersion());
        }
        int affected = applicationMapper.deleteByIdUserIdVersion(id, userId, expectedVersion);
        if (affected == 0) {
            throw BusinessException.versionConflict(current.getVersion());
        }
        return true;
    }

    @Override
    public ApplicationSummaryVO summary(String userId) {
        List<Map<String, Object>> rows = applicationMapper.countGroupByStatus(userId);
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (ApplicationStatus status : ApplicationStatus.values()) {
            counts.put(status.name(), 0L);
        }
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.get("status"));
            Number cnt = (Number) row.get("cnt");
            long value = cnt == null ? 0L : cnt.longValue();
            counts.put(status, value);
            total += value;
        }
        return new ApplicationSummaryVO(total, counts);
    }

    private boolean hasAnyChange(ApplicationUpdateDTO dto) {
        return isPresent(dto.getCompanyName())
                || isPresent(dto.getJobTitle())
                || isPresent(dto.getStatus())
                || isPresent(dto.getCityCode())
                || isPresent(dto.getEducation())
                || isPresent(dto.getSalaryText())
                || isPresent(dto.getDescription())
                || isPresent(dto.getPlannedDate())
                || isPresent(dto.getAppliedDate())
                || isPresent(dto.getInterviewDate())
                || isPresent(dto.getClosedDate())
                || isPresent(dto.getNote());
    }

    private static <T> boolean isPresent(PatchField<T> field) {
        return field != null && !field.isUndefined();
    }

    private String requireText(String field, String raw, int maxLength) {
        String trimmed = raw == null ? null : raw.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw BusinessException.validation(field, field + " 去除首尾空白后不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw BusinessException.validation(field, field + " 长度不能超过 " + maxLength + " 字符");
        }
        return trimmed;
    }

    private String optionalText(String field, String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw BusinessException.validation(field, field + " 长度不能超过 " + maxLength + " 字符");
        }
        return trimmed;
    }

    /** 必填字段：仅接受 VALUE 态；NULL 态记录错误并保持原值，UNDEFINED 保持原值。 */
    private String applyRequired(List<Map<String, String>> errors, String field, PatchField<String> patch,
                                 String oldValue, int maxLength) {
        if (patch == null) {
            return oldValue;
        }
        if (patch.isNull()) {
            addError(errors, field, field + " 为必填字段，不能传 null");
            return oldValue;
        }
        if (patch.isValue()) {
            try {
                return requireText(field, patch.getValue(), maxLength);
            } catch (BusinessException ex) {
                addError(errors, field, ex.getMessage());
                return oldValue;
            }
        }
        return oldValue;
    }

    /** 可空字段：VALUE 更新（含长度校验）、NULL 清空、UNDEFINED 保持原值。 */
    private String applyOptional(List<Map<String, String>> errors, String field, PatchField<String> patch,
                                 String oldValue, int maxLength) {
        if (patch == null) {
            return oldValue;
        }
        if (patch.isNull()) {
            return null;
        }
        if (patch.isValue()) {
            try {
                return optionalText(field, patch.getValue(), maxLength);
            } catch (BusinessException ex) {
                addError(errors, field, ex.getMessage());
                return oldValue;
            }
        }
        return oldValue;
    }

    /** 日期字段：VALUE 更新、NULL 清空、UNDEFINED 保持原值。 */
    private LocalDate applyDate(PatchField<LocalDate> patch, LocalDate oldValue) {
        if (patch == null) {
            return oldValue;
        }
        if (patch.isNull()) {
            return null;
        }
        if (patch.isValue()) {
            return patch.getValue();
        }
        return oldValue;
    }

    private void addError(List<Map<String, String>> errors, String field, String message) {
        Map<String, String> item = new HashMap<>();
        item.put("field", field);
        item.put("message", message);
        errors.add(item);
    }

    private ApplicationVO toVO(Application record) {
        ApplicationVO vo = new ApplicationVO();
        vo.setId(record.getId());
        vo.setJobId(record.getJobId());
        vo.setCompanyName(record.getCompanyName());
        vo.setJobTitle(record.getJobTitle());
        vo.setSourceType(record.getSourceType());
        vo.setSourceName(record.getSourceName());
        vo.setCityCode(record.getCityCode());
        vo.setEducation(record.getEducation());
        vo.setSalaryText(record.getSalaryText());
        vo.setDescription(record.getDescription());
        vo.setStatus(ApplicationStatus.fromValue(record.getStatus()));
        vo.setPlannedDate(record.getPlannedDate());
        vo.setAppliedDate(record.getAppliedDate());
        vo.setInterviewDate(record.getInterviewDate());
        vo.setClosedDate(record.getClosedDate());
        vo.setNote(record.getNote());
        vo.setVersion(record.getVersion());
        vo.setCreatedAt(record.getCreatedAt());
        vo.setUpdatedAt(record.getUpdatedAt());
        return vo;
    }
}
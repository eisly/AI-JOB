package com.aijob.backend.account;

import com.aijob.backend.common.AuditLogMapper;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 账号操作审计：独立事务写入，失败降级为应用日志，不阻断已完成的业务操作。
 * details_json 仅包含 result / reason / sourceDigest / targetMasked，禁止写入密码、验证码与完整手机号。
 */
@Service
public class AccountAuditService {

    private static final Logger log = LoggerFactory.getLogger(AccountAuditService.class);
    private static final String RESOURCE_TYPE = "ACCOUNT";

    private final AuditLogMapper auditLogMapper;
    private final JsonService jsonService;
    private final TransactionTemplate requiresNewTransaction;

    public AccountAuditService(AuditLogMapper auditLogMapper, JsonService jsonService,
                               PlatformTransactionManager transactionManager) {
        this.auditLogMapper = auditLogMapper;
        this.jsonService = jsonService;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String userId, AccountAction action, AccountAction.Result result, String reason,
                       String targetMasked) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("result", result.name());
            if (reason != null && !reason.isBlank()) {
                details.put("reason", reason);
            }
            details.put("sourceDigest", sourceDigest());
            if (targetMasked != null && !targetMasked.isBlank()) {
                details.put("targetMasked", targetMasked);
            }
            String traceId = currentTraceId();
            String detailsJson = jsonService.stringify(details);
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                // The audit foreign key also locks the user row. Wait for the business transaction to release it.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED || result == AccountAction.Result.FAILED) {
                            write(userId, action, traceId, detailsJson);
                        }
                    }
                });
            } else {
                write(userId, action, traceId, detailsJson);
            }
        } catch (Exception ex) {
            log.error("account audit write failed action={} traceId={} reason={}",
                    action, currentTraceId(), ex.getMessage(), ex);
        }
    }

    private void write(String userId, AccountAction action, String traceId, String detailsJson) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> auditLogMapper.insert(
                    UUID.randomUUID().toString(), userId, action.name(), RESOURCE_TYPE, userId, traceId, detailsJson));
        } catch (Exception ex) {
            log.error("account audit write failed action={} traceId={} reason={}",
                    action, traceId, ex.getMessage(), ex);
        }
    }

    private static String sourceDigest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return SourceDigest.of(null, null);
        }
        HttpServletRequest request = attributes.getRequest();
        return SourceDigest.of(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }
}

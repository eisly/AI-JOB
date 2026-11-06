package com.aijob.backend.growth;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.CurrentUserResolver;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.common.Responses;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/growth")
public class GrowthController {

    private final CurrentUserResolver users;
    private final GrowthService growth;

    public GrowthController(CurrentUserResolver users, GrowthService growth) {
        this.users = users;
        this.growth = growth;
    }

    @GetMapping
    public CommonResult<Map<String, Object>> summary() {
        return Responses.ok(growth.summary(users.resolveCurrentUserId()));
    }

    @GetMapping("/evidence")
    public CommonResult<PageResult<Map<String, Object>>> evidence(@RequestParam(required = false) String skillCode,
                                                                   @RequestParam(required = false) String sourceType,
                                                                   @RequestParam(required = false) Integer current,
                                                                   @RequestParam(required = false) Integer size) {
        return Responses.ok(growth.evidence(users.resolveCurrentUserId(), skillCode, sourceType, current, size));
    }

    @PostMapping("/suggestions")
    public ResponseEntity<CommonResult<Object>> suggestions(@RequestHeader("Idempotency-Key") String key,
                                                             @RequestBody(required = false) Map<String, Object> body,
                                                             HttpServletRequest request) {
        return growth.suggestions(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key),
                body == null ? Map.of() : body);
    }
}
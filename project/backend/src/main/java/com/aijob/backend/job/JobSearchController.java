package com.aijob.backend.job;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@RequestMapping("/api/v1/job-searches")
public class JobSearchController {

    private final CurrentUserResolver users;
    private final JobSearchService searches;

    public JobSearchController(CurrentUserResolver users, JobSearchService searches) {
        this.users = users;
        this.searches = searches;
    }

    @PostMapping
    public ResponseEntity<CommonResult<Object>> create(@RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        return searches.create(users.resolveCurrentUserId(), request.getRequestURI(),
                Data.idempotencyKey(key), body);
    }

    @GetMapping
    public CommonResult<PageResult<Map<String, Object>>> list(@RequestParam(required = false) Integer current,
                                                              @RequestParam(required = false) Integer size) {
        return Responses.ok(searches.list(users.resolveCurrentUserId(), current, size));
    }

    @GetMapping("/{searchId}")
    public CommonResult<Map<String, Object>> detail(@PathVariable String searchId) {
        return Responses.ok(searches.detail(users.resolveCurrentUserId(), Data.uuid(searchId, "searchId")));
    }

    @GetMapping("/{searchId}/items")
    public CommonResult<PageResult<Map<String, Object>>> items(
            @PathVariable String searchId,
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String candidateType,
            @RequestParam(required = false) String companySizes,
            @RequestParam(required = false) Boolean includeUnknownSize) {
        return Responses.ok(searches.items(users.resolveCurrentUserId(), Data.uuid(searchId, "searchId"),
                current, size, keyword, cityCode, candidateType, companySizes, includeUnknownSize));
    }

    @GetMapping("/match-item-options")
    public CommonResult<List<Map<String, Object>>> matchItemOptions() {
        return Responses.ok(searches.matchItemOptions(users.resolveCurrentUserId()));
    }
}
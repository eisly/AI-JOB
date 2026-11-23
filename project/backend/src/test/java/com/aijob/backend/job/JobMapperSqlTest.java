package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class JobMapperSqlTest {
    @Test
    void publicPageProducesValidSelectWithAndWithoutFilters() {
        Configuration config = new Configuration();
        config.addMapper(JobMapper.class);
        Map<String, Object> params = new HashMap<>(Map.of("size", 6, "offset", 0, "userId", "user"));
        for (boolean filtered : new boolean[]{false, true}) {
            if (filtered) params.putAll(Map.of("q", "Java", "cityCode", "110100", "candidateType", "EXPERIENCED"));
            String sql = config.getMappedStatement(JobMapper.class.getName() + ".publicPage")
                    .getBoundSql(params).getSql();
            assertTrue(sql.matches("(?s)^SELECT\\s+j\\.id,.*"), sql);
        }
    }

    @Test
    void jobSearchPageAndTotalUseTheSameAvailabilityFilter() {
        Configuration config = new Configuration();
        config.addMapper(JobSearchMapper.class);
        Map<String, Object> params = Map.of("searchId", "search", "includeUnknown", true, "sizes", List.of(),
                "size", 10, "offset", 0);
        for (String method : List.of("searchItems", "searchItemCount")) {
            String sql = config.getMappedStatement(JobSearchMapper.class.getName() + "." + method)
                    .getBoundSql(params).getSql();
            assertTrue(sql.contains(JobSearchMapper.AVAILABLE_SEARCH_ITEM.strip()), sql);
        }
    }
}

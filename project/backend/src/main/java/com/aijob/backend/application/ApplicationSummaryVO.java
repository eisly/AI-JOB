package com.aijob.backend.application;

import java.util.Map;

/**
 * 投递看板统计：total 为总记录数，counts 为四种状态的数量（无记录的状态返回 0）。
 */
public class ApplicationSummaryVO {

    private long total;

    private Map<String, Long> counts;

    public ApplicationSummaryVO() {
    }

    public ApplicationSummaryVO(long total, Map<String, Long> counts) {
        this.total = total;
        this.counts = counts;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public Map<String, Long> getCounts() {
        return counts;
    }

    public void setCounts(Map<String, Long> counts) {
        this.counts = counts;
    }
}
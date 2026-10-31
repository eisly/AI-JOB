package com.aijob.backend.common;

import java.util.List;

/**
 * 统一分页结构 {total, records, current, size}，current 从 1 开始。
 * 空结果返回 records=[]（空数组而非 null）。
 */
public class PageResult<T> {

    private long total;
    private List<T> records;
    private long current;
    private long size;

    public PageResult() {
    }

    public PageResult(long total, List<T> records, long current, long size) {
        this.total = total;
        this.records = records;
        this.current = current;
        this.size = size;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }

    public long getCurrent() {
        return current;
    }

    public void setCurrent(long current) {
        this.current = current;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
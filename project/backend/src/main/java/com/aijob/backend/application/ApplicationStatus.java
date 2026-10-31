package com.aijob.backend.application;

/**
 * 投递状态（接口文档 11-投递进度）：
 * PLANNED 待投递 / APPLIED 已投递 / INTERVIEWING 面试中 / CLOSED 已结束。
 * 状态允许自由流转（前进、回退或重新开启），修改状态不自动变更任何日期。
 */
public enum ApplicationStatus {
    PLANNED,
    APPLIED,
    INTERVIEWING,
    CLOSED;

    /**
     * 解析状态字符串；非法值（含 null）返回 null 由调用方按 400 处理。
     */
    public static ApplicationStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ApplicationStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }
}
package com.aijob.backend.application.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * PATCH 三态字段包装：区分"请求体未出现该字段"与"显式传 null"。
 * <ul>
 *   <li>{@link State#UNDEFINED}：请求体未出现该字段，保持原值（字段默认态，反序列化不会被调用）；</li>
 *   <li>{@link State#NULL}：显式传 null，清空该字段；</li>
 *   <li>{@link State#VALUE}：携带值，更新该字段。</li>
 * </ul>
 * 必填字段（companyName/jobTitle/status）仅接受 VALUE 态，显式传 null 由 Service 返回 400。
 * 使用 Jackson 3（tools.jackson，Spring Boot 4 webmvc 默认）的 @JsonDeserialize 指定反序列化器。
 */
@JsonDeserialize(using = PatchFieldDeserializer.class)
public final class PatchField<T> {

    public enum State {
        UNDEFINED, NULL, VALUE
    }

    private final State state;
    private final T value;

    private PatchField(State state, T value) {
        this.state = state;
        this.value = value;
    }

    public static <T> PatchField<T> undefined() {
        return new PatchField<>(State.UNDEFINED, null);
    }

    public static <T> PatchField<T> ofNull() {
        return new PatchField<>(State.NULL, null);
    }

    public static <T> PatchField<T> of(T value) {
        return new PatchField<>(State.VALUE, value);
    }

    public State getState() {
        return state;
    }

    public T getValue() {
        return value;
    }

    public boolean isUndefined() {
        return state == State.UNDEFINED;
    }

    public boolean isNull() {
        return state == State.NULL;
    }

    public boolean isValue() {
        return state == State.VALUE;
    }
}
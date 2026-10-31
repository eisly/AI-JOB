package com.aijob.backend.application.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;

/**
 * PatchField 反序列化器（Jackson 3 / tools.jackson，自研无第三方依赖）：
 * 字段未出现时 Jackson 不会调用本反序列化器（DTO 字段默认 UNDEFINED）；
 * 出现 null 时由 {@link #getNullValue} 生成 NULL 态（Jackson 对 null token 走 getNullValue
 * 而非 deserialize，默认实现会把字段置 null，必须覆盖才能保留三态语义）；
 * 出现具体值时委托内部类型的反序列化器生成 VALUE 态。
 * 内部类型非法（错误日期格式、非法枚举）时抛 InvalidFormatException，由全局异常处理器转 400。
 */
public class PatchFieldDeserializer extends ValueDeserializer<Object> {

    private final ValueDeserializer<?> inner;

    public PatchFieldDeserializer() {
        this(null);
    }

    private PatchFieldDeserializer(ValueDeserializer<?> inner) {
        this.inner = inner;
    }

    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        JavaType wrapperType = property.getType();
        JavaType innerType = wrapperType.containedType(0);
        ValueDeserializer<?> innerDeserializer = ctxt.findContextualValueDeserializer(innerType, property);
        return new PatchFieldDeserializer(innerDeserializer);
    }

    /**
     * 显式 null 的正确入口：JSON null token 不会进入 deserialize()，
     * 必须覆盖 getNullValue 返回 NULL 态，否则字段会被置为 Java null 丢失三态。
     */
    @Override
    public Object getNullValue(DeserializationContext ctxt) {
        return PatchField.ofNull();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object deserialize(JsonParser parser, DeserializationContext ctxt) {
        if (inner == null) {
            return PatchField.of(parser.readValueAs(Object.class));
        }
        return PatchField.of(((ValueDeserializer<Object>) inner).deserialize(parser, ctxt));
    }
}
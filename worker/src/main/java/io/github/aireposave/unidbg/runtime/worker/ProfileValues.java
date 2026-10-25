package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.JsonNode;

final class ProfileValues {
    static String text(JsonNode value, String field) {
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a JSON string");
        return value.textValue();
    }
    static String text(JsonNode object, String field, String fallback) {
        return object.has(field) ? text(object.get(field), field) : fallback;
    }
    static int integer(JsonNode object, String field, int fallback) {
        if (!object.has(field)) return fallback;
        JsonNode value = object.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException(field + " must be a 32-bit integer");
        return value.intValue();
    }
    static long longInteger(JsonNode object, String field, long fallback) {
        if (!object.has(field)) return fallback;
        JsonNode value = object.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException(field + " must be a 64-bit integer");
        return value.longValue();
    }
    private ProfileValues() {}
}

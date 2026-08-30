package com.offcanon.agent.domain;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank()) throw new IllegalArgumentException("Tool name must not be blank");
        if (description.isBlank()) throw new IllegalArgumentException("Tool description must not be blank");
        parameters = freezeMap(Objects.requireNonNull(parameters, "parameters"));
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "parameter name"), freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(Objects.requireNonNull(key, "parameter key")), freeze(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list.stream().map(ToolDefinition::freeze).toList());
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(freeze(item)));
            return List.copyOf(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            ArrayList<Object> copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(freeze(java.lang.reflect.Array.get(value, index)));
            }
            return List.copyOf(copy);
        }
        return value;
    }
}

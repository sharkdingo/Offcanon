package com.offcanon.agent.domain;

import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

public record ToolCall(String id, String name, Map<String, Object> arguments) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (id.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("Tool call id and name must not be blank");
        }
        if (arguments == null) {
            arguments = Map.of();
        } else {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("Tool call argument names must not be blank");
                }
                copy.put(entry.getKey(), freeze(entry.getValue()));
            }
            arguments = Collections.unmodifiableMap(copy);
        }
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(Objects.requireNonNull(key, "argument key")), freeze(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(freeze(item));
            // JSON arrays may legally contain null.  List.copyOf would turn a
            // valid model argument into an incidental NPE before the tool
            // boundary can report a useful validation error.
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(freeze(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            ArrayList<Object> copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) copy.add(freeze(java.lang.reflect.Array.get(value, index)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}

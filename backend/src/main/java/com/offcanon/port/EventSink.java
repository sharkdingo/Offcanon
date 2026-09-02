package com.offcanon.port;

import com.offcanon.agent.domain.RunEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EventSink {
    RunEvent publish(UUID experimentId, String type, Map<String, Object> payload);
    List<RunEvent> after(UUID experimentId, long sequence);

    /** Latest durable sequence, used to open a bounded tail window. */
    default long latestSequence(UUID experimentId) {
        long cursor = 0;
        while (true) {
            List<RunEvent> page = after(experimentId, cursor);
            if (page.isEmpty()) return cursor;
            long next = page.getLast().sequence();
            if (next <= cursor) return cursor;
            cursor = next;
        }
    }
}

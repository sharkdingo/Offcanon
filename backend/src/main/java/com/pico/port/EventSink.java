package com.pico.port;

import com.pico.agent.domain.RunEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EventSink {
    RunEvent publish(UUID experimentId, String type, Map<String, Object> payload);
    List<RunEvent> after(UUID experimentId, long sequence);
}

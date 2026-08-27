package com.pico.web;

import com.pico.application.ExperimentApplicationService;
import com.pico.agent.domain.RunEvent;
import com.pico.port.EventSink;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentEventController {
    private final ExperimentApplicationService experimentService;
    private final EventSink events;
    private final ScheduledExecutorService executor;
    private final long pollIntervalMillis;

    public ExperimentEventController(ExperimentApplicationService experimentService,
                                     EventSink events,
                                     ScheduledExecutorService eventStreamExecutor,
                                     @Value("${pico.events.poll-interval-ms:500}") long pollIntervalMillis) {
        this.experimentService = experimentService;
        this.events = events;
        this.executor = eventStreamExecutor;
        this.pollIntervalMillis = Math.max(250, pollIntervalMillis);
    }

    @GetMapping(value = "/{experimentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID experimentId,
                             @RequestParam(defaultValue = "0") long after,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        experimentService.get(experimentId);
        SseEmitter emitter = new SseEmitter(30_000L);
        long reconnectCursor = parseCursor(lastEventId);
        AtomicLong cursor = new AtomicLong(Math.max(0, Math.max(after, reconnectCursor)));
        ScheduledFuture<?> poller = executor.scheduleAtFixedRate(() -> {
            try {
                List<RunEvent> pending = events.after(experimentId, cursor.get());
                for (RunEvent event : pending) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).data(event));
                    cursor.set(event.sequence());
                }
            } catch (IOException | RuntimeException error) {
                emitter.completeWithError(error);
            }
        }, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> poller.cancel(false));
        emitter.onTimeout(() -> poller.cancel(false));
        emitter.onError(error -> poller.cancel(false));
        return emitter;
    }

    private long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}

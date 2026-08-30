package com.offcanon.web;

import com.offcanon.application.ExperimentApplicationService;
import com.offcanon.agent.domain.RunEvent;
import com.offcanon.port.EventSink;
import com.offcanon.identity.web.IdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentEventController {
    private static final Logger log = LoggerFactory.getLogger(ExperimentEventController.class);
    private final ExperimentApplicationService experimentService;
    private final EventSink events;
    private final ScheduledExecutorService executor;
    private final long pollIntervalMillis;
    private final long heartbeatIntervalNanos;
    private final IdentityContext identity;

    @Autowired
    public ExperimentEventController(ExperimentApplicationService experimentService,
                                     EventSink events,
                                     ScheduledExecutorService eventStreamExecutor,
                                     @Value("${offcanon.events.poll-interval-ms:500}") long pollIntervalMillis,
                                     @Value("${offcanon.events.heartbeat-interval-ms:15000}") long heartbeatIntervalMillis,
                                     IdentityContext identity) {
        this.experimentService = experimentService;
        this.events = events;
        this.executor = eventStreamExecutor;
        this.pollIntervalMillis = Math.max(250, pollIntervalMillis);
        this.heartbeatIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1_000, heartbeatIntervalMillis));
        this.identity = identity;
    }

    @GetMapping(value = "/{experimentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID experimentId,
                             @RequestParam(defaultValue = "0") long after,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request) {
        experimentService.get(experimentId, identity.ownerId(request));
        SseEmitter emitter = new SseEmitter(0L);
        long reconnectCursor = parseCursor(lastEventId);
        AtomicLong cursor = new AtomicLong(Math.max(0, Math.max(after, reconnectCursor)));
        AtomicLong lastWrite = new AtomicLong(System.nanoTime());
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> pollerRef = new AtomicReference<>();
        Runnable stopPolling = () -> {
            terminal.set(true);
            ScheduledFuture<?> poller = pollerRef.get();
            if (poller != null) poller.cancel(false);
        };
        ScheduledFuture<?> poller = executor.scheduleAtFixedRate(() -> {
            if (terminal.get()) return;
            try {
                List<RunEvent> pending = events.after(experimentId, cursor.get());
                for (RunEvent event : pending) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).data(event));
                    cursor.set(event.sequence());
                    lastWrite.set(System.nanoTime());
                }
                long now = System.nanoTime();
                if (pending.isEmpty() && now - lastWrite.get() >= heartbeatIntervalNanos) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastWrite.set(now);
                }
            } catch (IOException error) {
                // Spring dispatches the async error; only stop our polling task here.
                stopPolling.run();
            } catch (RuntimeException error) {
                log.warn("Closing event stream for experiment {} after event read failure: {}",
                        experimentId, error.toString());
                stopPolling.run();
                emitter.complete();
            }
        }, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
        pollerRef.set(poller);
        if (terminal.get()) poller.cancel(false);
        emitter.onCompletion(stopPolling);
        emitter.onTimeout(stopPolling);
        emitter.onError(error -> stopPolling.run());
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

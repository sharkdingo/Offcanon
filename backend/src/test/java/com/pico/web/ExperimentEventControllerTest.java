package com.pico.web;

import com.pico.application.ExperimentApplicationService;
import com.pico.port.EventSink;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

class ExperimentEventControllerTest {
    @Test
    void usesAnIndefiniteAsyncRequestAndSchedulesPollingForHeartbeatDelivery() {
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        EventSink events = mock(EventSink.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        doReturn(scheduled).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
        UUID experimentId = UUID.randomUUID();
        ExperimentEventController controller = new ExperimentEventController(
                experiments, events, executor, 250, 1_000);

        var emitter = controller.stream(experimentId, 0, null);

        assertEquals(0L, emitter.getTimeout());
        verify(experiments).get(experimentId);
        verify(executor).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
    }
}

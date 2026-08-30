package com.offcanon.web;

import com.offcanon.application.ExperimentApplicationService;
import com.offcanon.port.EventSink;
import com.offcanon.identity.web.IdentityContext;
import jakarta.servlet.http.HttpServletRequest;
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
import static org.mockito.Mockito.when;

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
        UUID ownerId = UUID.randomUUID();
        IdentityContext identity = mock(IdentityContext.class);
        when(identity.ownerId(any())).thenReturn(ownerId);
        ExperimentEventController controller = new ExperimentEventController(
                experiments, events, executor, 250, 1_000, identity);

        var emitter = controller.stream(experimentId, 0, null, mock(HttpServletRequest.class));

        assertEquals(0L, emitter.getTimeout());
        verify(experiments).get(experimentId, ownerId);
        verify(executor).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
    }
}

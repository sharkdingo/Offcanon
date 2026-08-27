package com.pico.infrastructure.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryEventSinkTest {
    @Test
    void concurrentPublishKeepsCursorSequenceContiguous() throws Exception {
        InMemoryEventSink sink = new InMemoryEventSink();
        UUID experimentId = UUID.randomUUID();
        int publishers = 12;
        int perPublisher = 50;
        CountDownLatch ready = new CountDownLatch(publishers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int publisher = 0; publisher < publishers; publisher++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int index = 0; index < perPublisher; index++) {
                        sink.publish(experimentId, "TEST", Map.of("index", index));
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
        }

        List<Long> sequences = sink.after(experimentId, 0).stream().map(event -> event.sequence()).toList();
        assertEquals(publishers * perPublisher, sequences.size());
        assertEquals(java.util.stream.LongStream.rangeClosed(1, publishers * perPublisher).boxed().toList(), sequences);
    }
}

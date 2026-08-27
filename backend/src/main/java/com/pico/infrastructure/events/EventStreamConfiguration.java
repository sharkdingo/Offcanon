package com.pico.infrastructure.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class EventStreamConfiguration {
    @Bean(name = "eventStreamExecutor", destroyMethod = "shutdownNow")
    public ScheduledExecutorService eventStreamExecutor() {
        return Executors.newScheduledThreadPool(2);
    }
}

package com.pico.infrastructure.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AgentExecutorConfiguration {
    @Bean(name = "agentExecutor", destroyMethod = "close")
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

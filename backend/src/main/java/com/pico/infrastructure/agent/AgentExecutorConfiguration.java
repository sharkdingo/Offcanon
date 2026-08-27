package com.pico.infrastructure.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AgentExecutorConfiguration {
    @Bean(name = "agentExecutor", destroyMethod = "close")
    public Executor agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

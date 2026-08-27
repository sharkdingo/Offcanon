package com.offcanon.infrastructure.process;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessRunnerConfiguration {
    @Bean
    public ProcessRunner processRunner() {
        return new ProcessRunner();
    }
}

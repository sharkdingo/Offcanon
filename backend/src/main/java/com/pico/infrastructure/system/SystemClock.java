package com.pico.infrastructure.system;

import com.pico.port.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SystemClock implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }
}

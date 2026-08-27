package com.offcanon.infrastructure.system;

import com.offcanon.port.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SystemClock implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }
}

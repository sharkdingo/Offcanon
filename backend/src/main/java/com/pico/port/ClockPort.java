package com.pico.port;

import java.time.Instant;

public interface ClockPort {
    Instant now();
}
